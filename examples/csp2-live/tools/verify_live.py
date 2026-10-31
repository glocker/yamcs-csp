
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
STARTUP_TIMEOUT = int(os.environ.get("YAMCS_STARTUP_TIMEOUT", "90"))

# Telemetry frames come from the libcsp reference vectors used by the Java tests
VECTORS = ROOT.parents[1] / "src" / "test" / "resources" / "vectors"
VECTOR_FILES = ("vectors-data.csv", "vectors-header_data.csv")
VECTOR_COLUMNS = ("name", "crc_mode", "priority", "destination", "source", "destination_port", "source_port",
                  "flags", "payload_hex", "frame_hex", "expected")
TM_ADDRESS = ("127.0.0.1", 10015)
# libcsp transmit CRC coverage is fixed at build time, so Yamcs has one processor and TC link per mode
COMMAND_VARIANTS = (
    ("vectors-data.csv", "data", 10025),
    ("vectors-header_data.csv", "header_data", 10026),
)
MAXIMUM_FRAME_PAYLOAD = 256  # libcsp packet buffer, CRC trailer included; the TC links allow 6 more bytes of header
CSP_HEADER_SIZE = 6
# Vector names. Everything named mdb_* is meant for this example (mdb_cmd_* are commands, the rest telemetry); the
# others are reused from the codec vectors.
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
            if len(links.get("links", [])) == 3:
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
            names = [name for name in vectors
                     if name.startswith("mdb_") and not name.startswith("mdb_cmd_") and name != CORRUPTED_VECTOR]
            for name in names + [HEADER_ONLY_VECTOR]:
                verify_decoded(udp, file_name, vectors[name])
                decoded += 1
            verify_not_decoded(udp, file_name, vectors[FOREIGN_ADDRESS_VECTOR], "is addressed to another node")
            verify_not_decoded(udp, file_name, vectors[CORRUPTED_VECTOR], "has a wrong CRC")
        wait_for_crc_event()
    return decoded


def issue_command(processor, name, args, sequence_number):
    return request_json(
        f"/api/processors/csp2/{processor}/commands/CSP2/{name}",
        method="POST",
        body={"args": args, "sequenceNumber": sequence_number},
    )


def receive_frame(listener, timeout):
    """Next datagram released by a TC link, or None if nothing arrives in time."""
    listener.settimeout(timeout)
    try:
        return listener.recvfrom(2048)[0]
    except socket.timeout:
        return None


def raw_arguments(row):
    """SendRaw arguments that make Yamcs build the frame of a vector row."""
    return {
        "priority": int(row["priority"]),
        "destination": int(row["destination"]),
        "source": int(row["source"]),
        "destination_port": int(row["destination_port"]),
        "source_port": int(row["source_port"]),
        "flags": int(row["flags"]),
        "payload": "" if row["payload_hex"] == "-" else row["payload_hex"],
    }


def verify_sent(listener, processor, command, args, row, sequence_number):
    issue_command(processor, command, args, sequence_number)
    frame = receive_frame(listener, 5)
    expected = bytes.fromhex(row["frame_hex"])
    if frame != expected:
        got = "nothing" if frame is None else frame.hex().upper()
        raise AssertionError(f"{processor} {command} for {row['name']}: got {got}, expected {expected.hex().upper()}")


def verify_commands(listeners, sequence):
    """Every valid libcsp vector is rebuilt by Yamcs from command arguments, with the CRC mode of its processor."""
    sent = 0
    for file_name, processor, port in COMMAND_VARIANTS:
        for row in load_vectors(file_name).values():
            if row["expected"] != "valid":
                continue
            verify_sent(listeners[port], processor, "SendRaw", raw_arguments(row), row, next(sequence))
            sent += 1
            # Commands that fix the port, with the other header arguments left at their defaults where possible
            if row["name"] == "mdb_cmd_app":
                args = {"destination": 228, "source_port": 5, "opcode": 0x2A, "value": 0x1234}
                verify_sent(listeners[port], processor, "AppCommand", args, row, next(sequence))
                sent += 1
            elif row["name"] == "mdb_cmd_ping":
                args = {"destination": 228, "source_port": 5, "payload": row["payload_hex"]}
                verify_sent(listeners[port], processor, "Ping", args, row, next(sequence))
                sent += 1
    return sent


def verify_rejected_arguments(listener, sequence):
    """Yamcs refuses header values that do not fit their bits, so nothing reaches the TC link."""
    rejected = 0
    for argument, value in (("destination", 16384), ("source", 16384), ("destination_port", 64),
                            ("source_port", 64), ("priority", 4), ("flags", 64)):
        args = {"destination": 1, "destination_port": 1, "payload": "01", argument: value}
        try:
            issue_command("data", "SendRaw", args, next(sequence))
        except HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            if error.code != 400 or "not in the range" not in body:
                raise AssertionError(f"{argument}={value} rejected with {error.code}: {body}")
        else:
            raise AssertionError(f"{argument}={value} was accepted")
        if receive_frame(listener, 0.3) is not None:
            raise AssertionError(f"{argument}={value} was refused, but a frame was sent")
        rejected += 1
    return rejected


def command_history_status(sequence_number, key, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for entry in request_json("/api/archive/csp2/commands?limit=500").get("entry", []):
            if entry.get("sequenceNumber") == sequence_number:
                for attribute in entry.get("attr", []):
                    if attribute["name"] == key:
                        return attribute["value"].get("stringValue")
        time.sleep(0.1)
    return None


def verify_too_long(listener, sequence):
    """Postprocessor limit counts the whole frame: 6-byte header, payload and CRC trailer."""
    too_long = (
        ("without CRC", 0, MAXIMUM_FRAME_PAYLOAD + 1),
        ("with CRC", 1, MAXIMUM_FRAME_PAYLOAD - 3),
    )
    for label, flags, payload_length in too_long:
        sequence_number = next(sequence)
        args = {"destination": 1, "destination_port": 1, "flags": flags, "payload": "00" * payload_length}
        issue_command("data", "SendRaw", args, sequence_number)
        if receive_frame(listener, 1) is not None:
            raise AssertionError(f"Command {label} with {payload_length} payload bytes wasn't refused")
        for key in ("Acknowledge_Sent_Status", "CommandComplete_Status"):
            status = command_history_status(sequence_number, key)
            if status != "NOK":
                raise AssertionError(f"Command {label} is too long, but {key} in command history is {status}")
    return len(too_long)


def verify_command():
    sequence = iter(range(1, 100000))
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as data_listener, \
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as header_data_listener:
        listeners = {10025: data_listener, 10026: header_data_listener}
        for port, listener in listeners.items():
            listener.bind(("127.0.0.1", port))
        sent = verify_commands(listeners, sequence)
        rejected = verify_rejected_arguments(data_listener, sequence)
        rejected += verify_too_long(data_listener, sequence)
    return sent, rejected


def main():
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("w", encoding="utf-8") as log:
        process = start_yamcs(ROOT, log)
        try:
            wait_for_server(process)
            decoded = verify_telemetry()
            sent, rejected = verify_command()
            print("LIVE VERIFICATION PASSED")
            print(f"TM frames decoded as expected: {decoded}")
            print(f"TC frames equal to libcsp frames: {sent}")
            print(f"TC commands refused: {rejected}")
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
