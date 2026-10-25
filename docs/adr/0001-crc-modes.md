# 0001: Accept both libcsp CRC modes

Status: Accepted

## What came up

CRC looked straightforward at first: CSP sets a CRC32 flag, so Yamcs checks the trailer when that flag is present.

libcsp 2.1 made this less simple. A default build calculates CRC-32C over payload only. Building the same source with `CSP_21` changes coverage to the 6-byte header plus payload. Nothing in a frame says which build produced it.

The receive side of libcsp tries both calculations. Matching only one of them in Yamcs would make the plugin incompatible with valid nodes built in the other mode.

## What we chose

`CspPacketPreprocessor` accepts both modes by default. `crcMode` can narrow this to `data` or `header_data` when a deployment wants strict validation.

Sending is different: one trailer must be produced, so `CspCommandPostprocessor` uses a configured mode. Default is `data`, matching a default libcsp 2.1 build.

## Trade-off

Telemetry works with both known libcsp 2.1 builds without extra link configuration. Commands still need the correct mode for their target node because CSP does not negotiate CRC coverage on wire.
