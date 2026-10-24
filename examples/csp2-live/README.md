# CSP 2.x live Yamcs integration

This example verifies the `yamcs-csp2` module inside a live Yamcs 5.13.6 instance. It provides fast smoke test with libcsp generated reference frames and Docker based interoperability test with running libcsp 2.1 nodes.

## Data flow

- TM input: UDP `127.0.0.1:10015` → `UdpTmDataLink` → `CspPacketPreprocessor` → XTCE MDB.
- TC output: XTCE command → `CspCommandPostprocessor` → `UdpTcDataLink` → UDP `127.0.0.1:10025`.
- Yamcs HTTP API: `http://localhost:8090`.

The reference packet is:

```text
BFFF0007F141 010203 F130F21E
```

It contains a 6-byte CSP 2.x header, a 3-byte payload and a data-only CRC-32C trailer.

## Automated verification

From the repository root, first build and install the current plugin snapshot into the local Maven repository. The example is intentionally not part of the root Maven reactor, so this step makes `yamcs-csp2` available as its dependency:

```sh
mvn install
```

Then run the bounded live check:

```sh
cd examples/csp2-live
python3 tools/verify_live.py
```

Expected output:

```text
LIVE VERIFICATION PASSED
TM frame: BFFF0007F141010203F130F21E
TC frame: BFFF0007F141010203F130F21E
```

The script:

1. starts Yamcs through `yamcs-maven-plugin`;
2. waits until both CSP links are exposed by the HTTP API;
3. sends a valid libcsp reference frame to the TM UDP port;
4. reads all decoded CSP fields and payload bytes from the realtime processor API;
5. sends the same frame with a corrupted CRC and waits for the recorded warning event;
6. binds a UDP listener to the TC port;
7. issues `/CSP2/SendReferencePacket` through the Yamcs command API;
8. compares the transmitted datagram byte-for-byte with the libcsp reference frame;
9. terminates Yamcs gracefully.

The Yamcs log is written to `target/live-verification.log`.

## Live libcsp interoperability

Docker Desktop must be running. The test builds libcsp at commit
`48f7fb0b57f610bf65bab1aa2d1357c3b9722782` twice: default data only CRC build and a header + data build with
`CSP_21=1`. Each real libcsp process sends CRC protected telemetry to Yamcs and accepts CRC protected command from
matching Yamcs TC link.

After `mvn install` from repository root, run:

```sh
cd examples/csp2-live
python3 tools/verify_interop.py
```

Expected output:

```text
data: libcsp telemetry accepted by Yamcs; Yamcs command accepted by libcsp
header_data: libcsp telemetry accepted by Yamcs; Yamcs command accepted by libcsp
LIBCSP INTEROPERABILITY PASSED
```

The node uses CSP address `228`, Yamcs uses address `1`, and application port `10` is used in both directions. Docker
publishes UDP ports `11025` and `11026` for commands; libcsp sends telemetry to Yamcs on UDP port `11015` through
`host.docker.internal`.

Build and server logs are written to `target/interop-docker-build.log` and `target/interop-yamcs.log`.

## Manual run

After installing plugin snapshot from repository root, start Yamcs from example directory:

```sh
cd examples/csp2-live
mvn org.yamcs:yamcs-maven-plugin:1.3.7:run
```

HTTP API is available at `http://localhost:8090`. Stop Yamcs with `Ctrl-C`.

Runtime databases, cache files and logs are created under `target/yamcs` and `target/`; they're local build artifacts.
