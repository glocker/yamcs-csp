#!/usr/bin/env python3
"""Run Yamcs and verify CSP 2.x telemetry and command paths over UDP"""

import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
LOG = ROOT / "target" / "live-verification.log"
BASE_URL = "http://localhost:8090"
VALID_FRAME = bytes.fromhex("BFFF0007F141010203F130F21E")
CORRUPTED_FRAME = bytes.fromhex("BFFF0007F141010203F130F21F")
EXPECTED_COMMAND = VALID_FRAME


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
            links = request_json("/api/links/csp2")
            if len(links.get("links", [])) == 2:
                return
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.25)
    raise RuntimeError("Yamcs didn't expose both CSP links within 30 seconds")


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


def wait_for_parameter(name, expected, timeout=5):
    deadline = time.monotonic() + timeout
    path = f"/api/processors/csp2/realtime/parameters/CSP2/{name}"
    last_value = None
    while time.monotonic() < deadline:
        try:
            response = request_json(path)
            last_value = find_integer(response)
            if last_value == expected:
                return
        except HTTPError as error:
            if error.code != 404:
                raise
        time.sleep(0.1)
    raise AssertionError(f"Parameter {name} is {last_value}, expected {expected}")


def wait_for_crc_event(timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = request_json("/api/archive/csp2/events?limit=100")
        events = response.get("events", [])
        if any("CRC verification failed for CSP 2.x packet" in event.get("message", "") for event in events):
            return
        time.sleep(0.1)
    raise AssertionError("CRC warning event wasn't recorded")


def verify_telemetry():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.sendto(VALID_FRAME, ("127.0.0.1", 10015))
        wait_for_parameter("priority", 2)
        wait_for_parameter("destination", 16383)
        wait_for_parameter("source", 1)
        wait_for_parameter("destination_port", 63)
        wait_for_parameter("source_port", 5)
        wait_for_parameter("flags", 1)
        wait_for_parameter("payload_0", 1)
        wait_for_parameter("payload_1", 2)
        wait_for_parameter("payload_2", 3)

        udp.sendto(CORRUPTED_FRAME, ("127.0.0.1", 10015))
        wait_for_crc_event()


def verify_command():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as listener:
        listener.bind(("127.0.0.1", 10025))
        listener.settimeout(5)
        request_json(
            "/api/processors/csp2/realtime/commands/CSP2/SendReferencePacket",
            method="POST",
            body={"origin": "csp2-live", "sequenceNumber": 1},
        )
        command, _ = listener.recvfrom(1024)
    if command != EXPECTED_COMMAND:
        raise AssertionError(f"Command {command.hex().upper()} != {EXPECTED_COMMAND.hex().upper()}")


def main():
    (ROOT / "target" / "yamcs" / "tmp").mkdir(parents=True, exist_ok=True)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            ["mvn", "org.yamcs:yamcs-maven-plugin:1.3.7:run"],
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            wait_for_server()
            verify_telemetry()
            verify_command()
            print("LIVE VERIFICATION PASSED")
            print(f"TM frame: {VALID_FRAME.hex().upper()}")
            print(f"TC frame: {EXPECTED_COMMAND.hex().upper()}")
            print(f"Log: {LOG}")
        finally:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"LIVE VERIFICATION FAILED: {error}", file=sys.stderr)
        print(f"Log: {LOG}", file=sys.stderr)
        raise
