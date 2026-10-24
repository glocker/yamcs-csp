#!/usr/bin/env python3
"""Verify live UDP interoperability between Yamcs and pinned libcsp 2.1"""

import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
INTEROP = ROOT / "interop"
TARGET = ROOT / "target"
YAMCS_LOG = TARGET / "interop-yamcs.log"
BUILD_LOG = TARGET / "interop-docker-build.log"
BASE_URL = "http://localhost:8090"
LIBCSP_COMMIT = "48f7fb0b57f610bf65bab1aa2d1357c3b9722782"
INTEROP_NODE_ADDRESS = 228
# libcsp selects transmit CRC coverage at compile time, so each mode needs its own image and Yamcs processor
VARIANTS = (
    ("data", 0, 11025, "data"),
    ("header_data", 1, 11026, "header_data"),
)


def request_json(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(BASE_URL + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urlopen(request, timeout=3) as response:
        payload = response.read()
        return json.loads(payload) if payload else {}


def wait_for_server(timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            links = request_json("/api/links/interop")
            if len(links.get("links", [])) == 3:
                return
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.25)
    raise RuntimeError("Yamcs didn't expose all interop links within 30 seconds")


def get_tm_count():
    link = request_json("/api/links/interop/csp2_tm")
    return int(link.get("dataInCount", 0))


def wait_for_tm_count(previous, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = get_tm_count()
        if current > previous:
            return
        time.sleep(0.1)
    raise AssertionError("Yamcs didn't receive telemetry from libcsp")


def get_tc_count(mode):
    link = request_json(f"/api/links/interop/csp2_tc_{mode}")
    return int(link.get("dataOutCount", 0))


def wait_for_tc_count(mode, previous, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = get_tc_count(mode)
        if current > previous:
            return
        time.sleep(0.1)
    raise AssertionError(f"Yamcs TC link for {mode} didn't transmit command")


def find_integer(value):
    if isinstance(value, dict):
        for key in ("uint32Value", "sint32Value", "uint64Value", "sint64Value"):
            if key in value:
                return int(value[key])
        for nested in value.values():
            result = find_integer(nested)
            if result is not None:
                return result
    elif isinstance(value, list):
        for nested in value:
            result = find_integer(nested)
            if result is not None:
                return result
    return None


def assert_parameter(name, expected):
    response = request_json(f"/api/processors/interop/realtime/parameters/CSP2/{name}")
    actual = find_integer(response)
    if actual != expected:
        raise AssertionError(f"Parameter {name} is {actual}, expected {expected}")


def image_name(mode):
    return f"yamcs-csp-libcsp-{mode}:48f7fb0"


def build_images():
    TARGET.mkdir(parents=True, exist_ok=True)
    with BUILD_LOG.open("w", encoding="utf-8") as log:
        for mode, csp_21, _, _ in VARIANTS:
            command = [
                "docker", "build",
                "--build-arg", f"LIBCSP_COMMIT={LIBCSP_COMMIT}",
                "--build-arg", f"CSP_21={csp_21}",
                "--tag", image_name(mode),
                str(INTEROP),
            ]
            subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)


def verify_variant(mode, host_port, processor, sequence_number):
    previous_count = get_tm_count()
    container = subprocess.Popen(
        [
            "docker", "run", "--rm",
            "--publish", f"{host_port}:{host_port}/udp",
            image_name(mode),
            "host.docker.internal", str(host_port),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        wait_for_tm_count(previous_count)
        assert_parameter("destination", 1)
        assert_parameter("source", INTEROP_NODE_ADDRESS)
        assert_parameter("flags", 1)
        assert_parameter("payload_0", 1)
        assert_parameter("payload_1", 2)
        assert_parameter("payload_2", 3)

        previous_tc_count = get_tc_count(mode)
        request_json(
            f"/api/processors/interop/{processor}/commands/CSP2/SendInteropPacket",
            method="POST",
            body={
                "origin": "libcsp-interop",
                "sequenceNumber": sequence_number,
            },
        )
        # Link counter proves Yamcs transmitted; node output below proves libcsp accepted CRC and payload
        wait_for_tc_count(mode, previous_tc_count)
        output, _ = container.communicate(timeout=20)
    except Exception:
        container.terminate()
        try:
            output, _ = container.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            container.kill()
            output, _ = container.communicate(timeout=5)
        print(output, file=sys.stderr)
        raise

    if container.returncode != 0:
        raise AssertionError(f"libcsp {mode} node failed:\n{output}")
    if f"TELEMETRY_SENT crc_mode={mode}" not in output:
        raise AssertionError(f"libcsp {mode} node didn't confirm telemetry:\n{output}")
    if f"COMMAND_RECEIVED crc_mode={mode}" not in output:
        raise AssertionError(f"libcsp {mode} node didn't accept Yamcs command:\n{output}")
    print(f"{mode}: libcsp telemetry accepted by Yamcs; Yamcs command accepted by libcsp")


def main():
    subprocess.run(["docker", "info"], check=True, stdout=subprocess.DEVNULL)
    build_images()

    (TARGET / "yamcs" / "tmp").mkdir(parents=True, exist_ok=True)
    with YAMCS_LOG.open("w", encoding="utf-8") as log:
        yamcs = subprocess.Popen(
            ["mvn", "org.yamcs:yamcs-maven-plugin:1.3.7:run"],
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            wait_for_server()
            for sequence_number, (mode, _, host_port, processor) in enumerate(VARIANTS, start=1):
                verify_variant(mode, host_port, processor, sequence_number)
            print("LIBCSP INTEROPERABILITY PASSED")
            print(f"libcsp commit: {LIBCSP_COMMIT}")
            print(f"Yamcs log: {YAMCS_LOG}")
            print(f"Docker build log: {BUILD_LOG}")
        finally:
            if yamcs.poll() is None:
                os.killpg(yamcs.pid, signal.SIGTERM)
                try:
                    yamcs.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(yamcs.pid, signal.SIGKILL)
                    yamcs.wait(timeout=5)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"LIBCSP INTEROPERABILITY FAILED: {error}", file=sys.stderr)
        print(f"Yamcs log: {YAMCS_LOG}", file=sys.stderr)
        print(f"Docker build log: {BUILD_LOG}", file=sys.stderr)
        raise
