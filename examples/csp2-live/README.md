# CSP 2.x live Yamcs example

Unit tests prove header and CRC bytes, but they do not prove that Yamcs can load plugin JAR, read YAML and MDB files, receive telemetry, or release a command. This example covers that gap with a real Yamcs 5.13.6 process.

It has two checks:

- quick smoke test that sends committed libcsp reference frames and checks how the MDB decodes them;
- slower bidirectional test with real libcsp 2.1 nodes built in Docker.

Example stays outside root Maven reactor so normal `mvn verify` remains fast and does not open HTTP or UDP ports.

## Packet path

- TM: UDP `127.0.0.1:10015` -> `UdpTmDataLink` -> `CspPacketPreprocessor` -> XTCE MDB
- TC: XTCE command -> `CspCommandPostprocessor` -> `UdpTcDataLink` -> UDP `127.0.0.1:10025`
- Yamcs HTTP API: `http://localhost:8090`

Smoke test takes telemetry frames from the libcsp reference vectors in `csp2/src/test/resources/vectors`, the same files the Java tests use. For example, port 11 housekeeping with CRC (default libcsp build):

```text
80010390B1C1 020138FF40E20100 00006040 CAECF29F
```

That is a 6-byte CSP 2.x header, a 12-byte payload and a data-only CRC-32C trailer. How the MDB decodes frames like this one is described in [Decoding frames in the MDB](../../docs/csp2.md#decoding-frames-in-the-mdb).

## Quick smoke test

Install current plugin snapshot first:

```sh
mvn install
```

Then run example from its own directory:

```sh
cd examples/csp2-live
python3 tools/verify_live.py
```

Expected result:

```text
LIVE VERIFICATION PASSED
TM frames decoded as expected: 26
TC frame: BFFF0007F141010203F130F21E
```

Script starts Yamcs and sends the `mdb_*` vectors from both libcsp builds (data-only and header-plus-data CRC). For every frame it reads the decoded parameters through the HTTP API and compares them with values taken from the vector bytes: header fields, flags, three-byte sample, housekeeping with and without CRC, payloads from empty to maximum length, and a frame for an unknown port. Parameters that the frame does not carry must stay unchanged. Two more frames must not be decoded at all: one addressed to another node and one with a wrong CRC, which must also produce a warning event. Then script sends `/CSP2/SendReferencePacket` and compares the outgoing UDP datagram byte-for-byte with the reference frame. Yamcs is stopped on success and failure.

Log is written to `target/live-verification.log`. Startup wait defaults to 90 seconds and can be changed with `YAMCS_STARTUP_TIMEOUT`. If Yamcs exits or startup times out, script prints last 80 log lines before failing.

## Real libcsp interop

Smoke test still uses a stored frame. Interop test goes one step further: it builds pinned libcsp commit `48f7fb0b57f610bf65bab1aa2d1357c3b9722782`, starts a real node, and exchanges packets with Yamcs.

Docker Desktop must be running. After `mvn install` from repository root:

```sh
cd examples/csp2-live
python3 tools/verify_interop.py
```

Expected result:

```text
data: libcsp telemetry accepted by Yamcs; Yamcs command accepted by libcsp
header_data: libcsp telemetry accepted by Yamcs; Yamcs command accepted by libcsp
LIBCSP INTEROPERABILITY PASSED
```

Two images are needed because libcsp transmit CRC mode is selected at compile time. Default build covers payload; `CSP_21=1` build covers header plus payload. Each node sends telemetry to Yamcs, then receives command produced by matching Yamcs postprocessor.

Addresses and ports:

- libcsp node address: `228`
- Yamcs CSP address: `1`
- application port in both directions: `10`
- telemetry UDP port: `11015`
- command UDP ports: `11025` and `11026`

Container reaches Yamcs through `host.docker.internal`; Docker publishes command ports back to containers.

Logs are written to `target/interop-docker-build.log` and `target/interop-yamcs.log`.

## Run Yamcs manually

After installing plugin snapshot:

```sh
cd examples/csp2-live
python3 tools/yamcs_runtime.py
```

Launcher builds runtime classpath with Maven, copies Yamcs configuration under `target/yamcs`, and starts `org.yamcs.YamcsServer` directly. HTTP API is available at `http://localhost:8090`. Stop Yamcs with `Ctrl-C`.

Runtime databases, cache files and logs stay under `target/`.
