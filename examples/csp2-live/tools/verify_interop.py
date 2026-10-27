
"""Verify live UDP interoperability between Yamcs and pinned libcsp 2.1"""

import base64
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from yamcs_runtime import start_yamcs, stop_process

ROOT = Path(__file__).resolve().parents[1]
INTEROP = ROOT / "interop"
TARGET = ROOT / "target"
YAMCS_LOG = TARGET / "interop-yamcs.log"
BUILD_LOG = TARGET / "interop-docker-build.log"
BASE_URL = "http://localhost:8090"
LIBCSP_COMMIT = "48f7fb0b57f610bf65bab1aa2d1357c3b9722782"
INTEROP_NODE_ADDRESS = 228
CSP_PING_PORT = 1
PING_REPLY_PORT = 12  # the MDB decodes port 12 as raw bytes of any length
PING_PAYLOAD = bytes(range(1, 9))
STARTUP_TIMEOUT = int(os.environ.get("YAMCS_STARTUP_TIMEOUT", "90"))
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


def wait_for_server(process, timeout=STARTUP_TIMEOUT):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        returncode = process.poll()
        if returncode is not None:
            raise RuntimeError(f"Yamcs exited during startup with code {returncode}")
        try:
            links = request_json("/api/links/interop")
            if len(links.get("links", [])) == 3:
                return
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"Yamcs didn't expose all interop links within {timeout} seconds")


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


def get_binary_parameter(name):
    response = request_json(f"/api/processors/interop/realtime/parameters/CSP2/{name}")
    value = response.get("engValue")
    return None if value is None else base64.b64decode(value["binaryValue"])


def verify_ping(mode, processor, sequence_number):
    """Yamcs pings the libcsp node; the node echoes the packet and Yamcs decodes the echo with the MDB."""
    previous_count = get_tm_count()
    request_json(
        f"/api/processors/interop/{processor}/commands/CSP2/Ping",
        method="POST",
        body={
            "origin": "libcsp-interop",
            "sequenceNumber": sequence_number,
            "args": {"destination": INTEROP_NODE_ADDRESS, "source_port": PING_REPLY_PORT, "payload": PING_PAYLOAD.hex()},
        },
    )
    wait_for_tm_count(previous_count)
    # Reply to a ping is addressed to the source port of the request and comes from the ping port
    assert_parameter("destination", 1)
    assert_parameter("source", INTEROP_NODE_ADDRESS)
    assert_parameter("destination_port", PING_REPLY_PORT)
    assert_parameter("source_port", CSP_PING_PORT)
    assert_parameter("flag_crc32", 1)
    # Raw payload of the reply: echoed bytes, then the CRC trailer added by libcsp
    raw = get_binary_parameter("raw_data")
    if raw is None or len(raw) != len(PING_PAYLOAD) + 4 or raw[:len(PING_PAYLOAD)] != PING_PAYLOAD:
        raise AssertionError(f"Ping echo is {None if raw is None else raw.hex()}, expected {PING_PAYLOAD.hex()} and CRC")


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

        verify_ping(mode, processor, sequence_number + 100)
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
    if f"PING_ECHOED crc_mode={mode}" not in output:
        raise AssertionError(f"libcsp {mode} node didn't accept Yamcs ping:\n{output}")
    print(f"{mode}: libcsp telemetry accepted by Yamcs; Yamcs command accepted by libcsp; "
          "Yamcs ping echoed by libcsp and decoded")


def main():
    subprocess.run(["docker", "info"], check=True, stdout=subprocess.DEVNULL)
    build_images()

    with YAMCS_LOG.open("w", encoding="utf-8") as log:
        yamcs = start_yamcs(ROOT, log)
        try:
            wait_for_server(yamcs)
            for sequence_number, (mode, _, host_port, processor) in enumerate(VARIANTS, start=1):
                verify_variant(mode, host_port, processor, sequence_number)
            print("LIBCSP INTEROPERABILITY PASSED")
            print(f"libcsp commit: {LIBCSP_COMMIT}")
            print(f"Yamcs log: {YAMCS_LOG}")
            print(f"Docker build log: {BUILD_LOG}")
        finally:
            stop_process(yamcs)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"LIBCSP INTEROPERABILITY FAILED: {error}", file=sys.stderr)
        print(f"Yamcs log: {YAMCS_LOG}", file=sys.stderr)
        print(f"Docker build log: {BUILD_LOG}", file=sys.stderr)
        raise
