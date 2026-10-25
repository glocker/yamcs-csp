# CSP 2.x in Yamcs

Yamcs 5.13 includes CSP support, but its packet classes use CSP 1.x layout: 4-byte header and 5-bit addresses. Feeding a CSP 2.x frame into that code would decode wrong fields before CRC is even checked.

`csp2` module adds separate packet processors for CSP 2.x. It does not replace Yamcs transport links or turn Yamcs into a CSP router.

Implementation was checked against [libcsp](https://github.com/libcsp/libcsp) v2.1 at commit `48f7fb0b57f610bf65bab1aa2d1357c3b9722782`. Source paths below refer to that commit.

## Header

CSP 2.x packs these fields into 6 bytes:

| Field            | Bits |
|------------------|------|
| priority         | 2    |
| destination      | 14   |
| source           | 14   |
| destination port | 6    |
| source port      | 6    |
| flags            | 6    |

Bytes are transmitted most significant first. libcsp defines masks and shifts in `src/csp_id.c` as `CSP_ID2_*`.

CSP version cannot be detected from packet bytes. CSP 1.x and 2.x interpret same leading bits differently, so correct preprocessor must be chosen in link configuration.

Plugin reads CRC32 flag (`0x01`). RDP (`0x02`), HMAC (`0x08`) and fragmentation (`0x10`) bits are preserved but not processed.

## CRC32

CRC turned out to be main compatibility trap.

Both versions use CRC-32C (Castagnoli), stored after payload with most significant byte first. Trailer exists only when CRC32 flag is set. Difference is which bytes libcsp includes in calculation:

- `data`: payload only;
- `header_data`: 6-byte header followed by payload.

At pinned libcsp commit, sending behavior is selected at compile time in `src/csp_crc32.c`. Default build uses payload only. Building with `CSP_21` uses header plus payload.

Receive function `csp_crc32_verify` accepts both. Plugin follows that behavior by default, otherwise valid telemetry would depend on how remote libcsp was compiled. Sending cannot try both, so command postprocessor needs one configured mode.

See [ADR 0001](adr/0001-crc-modes.md) for decision history.

## UDP packets and Yamcs links

libcsp UDP interface (`src/interfaces/csp_if_udp.c`) sends one complete CSP packet per UDP datagram:

```text
6-byte header | payload | optional 4-byte CRC
```

Preprocessor receives whole datagram and leaves accepted bytes unchanged. XTCE Mission Database decodes header and payload fields.

One combined `UdpTcTmDataLink` looked attractive, but it does not fit usual libcsp UDP setup. libcsp listens on fixed `lport` and sends to fixed `rport`; combined Yamcs link uses an operating-system-selected local port and only accepts replies from connected peer.

Use two links instead:

- `UdpTmDataLink` listens for telemetry;
- `UdpTcDataLink` sends commands.

Working configuration is available in [`examples/csp2-live`](../examples/csp2-live).

## Java classes

Package: `io.github.glocker.yamcscsp.csp2`

| Class                     | Role |
|---------------------------|------|
| `CspHeader`               | Validate, decode and encode 6-byte header |
| `CspCrc`                  | Calculate, append and verify CRC-32C |
| `CspPacketPreprocessor`   | Validate telemetry, filter destination and set Yamcs metadata |
| `CspCommandPostprocessor` | Append command CRC and enforce final length limit |

## Telemetry configuration

```yaml
packetPreprocessorClassName: io.github.glocker.yamcscsp.csp2.CspPacketPreprocessor
packetPreprocessorArgs:
  cspId: 1
  crcMode: both
```

| Option    | Type                    | Default | Meaning |
|-----------|-------------------------|---------|---------|
| `cspId`   | integer or integer list | unset   | Accept listed 14-bit destination addresses (`0..16383`). Unset accepts all. |
| `crcMode` | string                  | `both`  | Accepted CRC coverage: `data`, `header_data`, or `both`. |

`crcMode` does not require every packet to carry CRC. Packet without CRC32 flag skips verification.

When flagged CRC fails, packet is marked invalid and warning event is emitted. Packet is still returned, so corrupted telemetry can be recorded and inspected. Frames shorter than 6-byte header and frames rejected by `cspId` are dropped.

CSP header has no timestamp or packet counter. Preprocessor uses Yamcs reception time as generation time and assigns local 16-bit sequence count. Those values describe ingestion into Yamcs, not data sent by spacecraft.

Multiple local addresses can share one TM link:

```yaml
packetPreprocessorArgs:
  cspId: [1, 2, 16383]
  crcMode: header_data
```

## Command configuration

```yaml
commandPostprocessorClassName: io.github.glocker.yamcscsp.csp2.CspCommandPostprocessor
commandPostprocessorArgs:
  crcMode: data
  maximumTcPacketLength: 256
```

| Option                  | Type    | Default | Meaning |
|-------------------------|---------|---------|---------|
| `crcMode`               | string  | `data`  | Generated CRC coverage: `data` or `header_data`. |
| `maximumTcPacketLength` | integer | `-1`    | Maximum final length including CRC. `-1` disables limit. |

MDB must provide complete 6-byte CSP header followed by command payload. Postprocessor does not fill addresses or ports. It appends CRC only when header has CRC32 flag.

This follows existing Yamcs CSP 1.x behavior and avoids two owners for same header fields. Details are in [ADR 0002](adr/0002-command-header-owner.md).

Commands shorter than header or longer than configured final limit are not sent. Command history receives `Acknowledge_Sent = NOK` and `CommandComplete = NOK`.

## How compatibility is checked

Java tests do not calculate their own expected frames. A C generator links against pinned libcsp and produces complete UDP frames from two builds: default and `CSP_21`.

Vectors cover:

- minimum and maximum values of every header field;
- all priorities and flag combinations;
- empty and maximum payloads;
- both CRC modes;
- corrupted CRC, missing trailer and short frame.

Scheduled CI rebuilds libcsp in Docker and compares generated files byte-for-byte. [ADR 0004](adr/0004-reference-vectors.md) explains why this is separate from Java implementation.

Stored vectors are not only evidence. Live example starts Yamcs and real libcsp nodes, then checks both directions:

- libcsp telemetry is accepted by Yamcs;
- Yamcs command is accepted by libcsp;
- exchange passes in both CRC modes.

## Supported scope

Verified combination:

- Yamcs 5.13.x;
- libcsp 2.1 at pinned commit above;
- CSP 2.x UDP frames;
- CRC-32C in both libcsp modes.

Not implemented:

- RDP state;
- HMAC verification;
- fragmentation and reassembly;
- ZeroMQ transport;
- CRC mode negotiation;
- CSP routing.

Flag bits for unsupported features are not rejected, but plugin does not validate data associated with them. This pass-through behavior is not support for those protocols.

Existing Yamcs TCP links may be used only when they deliver complete CSP frame boundaries to preprocessor. Live interoperability has been verified over UDP, not TCP.

Project license is `AGPL-3.0-only`; see [ADR 0003](adr/0003-license.md).
