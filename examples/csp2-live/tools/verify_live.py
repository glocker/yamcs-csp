
"""Run Yamcs and verify CSP 2.x telemetry and command paths over UDP"""

import base64
import csv
import json
import os
import socket
import struct
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from yamcs_runtime import start_yamcs, stop_process

ROOT = Path(__file__).resolve().parents[1]
LOG = ROOT / "target" / "live-verification.log"
BASE_URL = "http://localhost:8090"
EXPECTED_COMMAND = bytes.fromhex("BFFF0007F141010203F130F21E")
STARTUP_TIMEOUT = int(os.environ.get("YAMCS_STARTUP_TIMEOUT", "90"))

# Telemetry frames come from the libcsp reference vectors used by the Java tests
VECTORS = ROOT.parents[1] / "csp2" / "src" / "test" / "resources" / "vectors"
VECTOR_FILES = ("vectors-data.csv", "vectors-header_data.csv")
VECTOR_COLUMNS = ("name", "crc_mode", "priority", "destination", "source", "destination_port", "source_port",
                  "flags", "payload_hex", "frame_hex", "expected")
TM_ADDRESS = ("127.0.0.1", 10015)
CSP_HEADER_SIZE = 6
# Vector names. Everything named mdb_* is meant for this example; the others are reused from the codec vectors.
HEADER_ONLY_VECTOR = "crc_mixed_fields"  # destination 16383, port 63: no container for this port
FOREIGN_ADDRESS_VECTOR = "crc"  # destination 5, not in cspId of the TM link
CORRUPTED_VECTOR = "mdb_hk_crc_corrupted"
HEADER_PARAMETERS = ("priority", "destination", "source", "destination_port", "source_port", "flags", "flag_crc32",
                     "flag_rdp", "flag_hmac", "flag_frag")
ALL_PARAMETERS = HEADER_PARAMETERS + ("payload_0", "payload_1", "payload_2", "hk_counter", "hk_temperature",
                                      "hk_uptime", "hk_voltage", "hk_crc32", "raw_data")


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
            links = request_json("/api/links/csp2")
            if len(links.get("links", [])) == 2:
                return
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"Yamcs didn't expose both CSP links within {timeout} seconds")



def print_log_tail(lines=80):
    if not LOG.exists():
        return
    content = LOG.read_text(encoding="utf-8", errors="replace").splitlines()
    print(f"--- last {min(lines, len(content))} Yamcs log lines ---", file=sys.stderr)
    print("\n".join(content[-lines:]), file=sys.stderr)


def wait_for_crc_event(timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = request_json("/api/archive/csp2/events?limit=100")
        events = response.get("events", [])
        if any("CRC verification failed for CSP 2.x packet" in event.get("message", "") for event in events):
            return
        time.sleep(0.1)
    raise AssertionError("CRC warning event wasn't recorded")


def load_vectors(file_name):
    with (VECTORS / file_name).open(encoding="utf-8") as vectors:
        rows = csv.DictReader((line for line in vectors if not line.startswith("#")), fieldnames=VECTOR_COLUMNS)
        return {row["name"]: row for row in rows}


def expected_parameters(row):
    """Values Yamcs must show for a vector, derived from the libcsp frame bytes, not from the MDB."""
    flags = int(row["flags"])
    frame = bytes.fromhex(row["frame_hex"])
    payload = b"" if row["payload_hex"] == "-" else bytes.fromhex(row["payload_hex"])
    port = int(row["destination_port"])

    expected = {
        "priority": int(row["priority"]),
        "destination": int(row["destination"]),
        "source": int(row["source"]),
        "destination_port": port,
        "source_port": int(row["source_port"]),
        "flags": flags,
        "flag_crc32": flags & 0x01,
        "flag_rdp": (flags >> 1) & 0x01,
        "flag_hmac": (flags >> 3) & 0x01,
        "flag_frag": (flags >> 4) & 0x01,
    }
    if port == 10:
        expected.update(payload_0=payload[0], payload_1=payload[1], payload_2=payload[2])
    elif port == 11:
        counter, temperature, uptime, voltage = struct.unpack("<HhIf", payload)
        expected.update(hk_counter=counter, hk_temperature=temperature, hk_uptime=uptime, hk_voltage=voltage)
        if flags & 0x01:
            expected["hk_crc32"] = int.from_bytes(frame[-4:], "big")
    elif port == 12:
        # Everything after the header, so the CRC trailer is included when the CRC32 flag is set
        expected["raw_data"] = base64.b64encode(frame[CSP_HEADER_SIZE:]).decode("ascii")
    return expected


def read_parameters():
    """Latest (value, generation time) of every parameter; (None, None) if never received."""
    state = {}
    for name in ALL_PARAMETERS:
        try:
            response = request_json(f"/api/processors/csp2/realtime/parameters/CSP2/{name}")
        except HTTPError as error:
            if error.code != 404:
                raise
            state[name] = (None, None)
            continue
        value = response.get("engValue")
        if value is None:  # parameter exists in the MDB, but no frame carried it yet
            state[name] = (None, None)
            continue
        raw = next(value[key] for key in value if key != "type")
        if value["type"] in ("UINT32", "SINT32", "UINT64", "SINT64"):
            raw = int(raw)
        state[name] = (raw, response.get("generationTime"))
    return state


def describe_changes(before, after, expected):
    """Problems found when comparing parameters before and after one frame; empty list means all as expected."""
    problems = []
    if after["priority"][1] == before["priority"][1]:
        problems.append("frame hasn't been decoded yet")
    for name in ALL_PARAMETERS:
        changed = after[name][1] != before[name][1]
        if name in expected:
            if not changed:
                problems.append(f"{name} wasn't updated")
            elif after[name][0] != expected[name]:
                problems.append(f"{name} is {after[name][0]!r}, expected {expected[name]!r}")
        elif changed:
            problems.append(f"{name} was updated, but this frame doesn't carry it")
    return problems


def send_frame(udp, row):
    # Parameters are told apart by generation time, which has millisecond resolution
    time.sleep(0.05)
    before = read_parameters()
    udp.sendto(bytes.fromhex(row["frame_hex"]), TM_ADDRESS)
    return before


def verify_decoded(udp, file_name, row):
    expected = expected_parameters(row)
    before = send_frame(udp, row)
    deadline = time.monotonic() + 5
    while True:
        problems = describe_changes(before, read_parameters(), expected)
        if not problems:
            return
        if time.monotonic() > deadline:
            raise AssertionError(f"{file_name} {row['name']}: " + "; ".join(problems))
        time.sleep(0.1)


def verify_not_decoded(udp, file_name, row, reason):
    before = send_frame(udp, row)
    time.sleep(0.5)
    after = read_parameters()
    updated = [name for name in ALL_PARAMETERS if after[name][1] != before[name][1]]
    if updated:
        raise AssertionError(f"{file_name} {row['name']} {reason}, but Yamcs updated {', '.join(updated)}")


def verify_telemetry():
    decoded = 0
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        for file_name in VECTOR_FILES:
            vectors = load_vectors(file_name)
            names = [name for name in vectors if name.startswith("mdb_") and name != CORRUPTED_VECTOR]
            for name in names + [HEADER_ONLY_VECTOR]:
                verify_decoded(udp, file_name, vectors[name])
                decoded += 1
            verify_not_decoded(udp, file_name, vectors[FOREIGN_ADDRESS_VECTOR], "is addressed to another node")
            verify_not_decoded(udp, file_name, vectors[CORRUPTED_VECTOR], "has a wrong CRC")
        wait_for_crc_event()
    return decoded


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
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("w", encoding="utf-8") as log:
        process = start_yamcs(ROOT, log)
        try:
            wait_for_server(process)
            decoded = verify_telemetry()
            verify_command()
            print("LIVE VERIFICATION PASSED")
            print(f"TM frames decoded as expected: {decoded}")
            print(f"TC frame: {EXPECTED_COMMAND.hex().upper()}")
            print(f"Log: {LOG}")
        finally:
            stop_process(process)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"LIVE VERIFICATION FAILED: {error}", file=sys.stderr)
        print(f"Log: {LOG}", file=sys.stderr)
        print_log_tail()
        raise
