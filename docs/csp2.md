# CSP 2.x in Yamcs

Yamcs 5.13.7 includes CSP support, but its packet classes use CSP 1.x layout: 4-byte header and 5-bit addresses. Feeding a CSP 2.x frame into that code would decode wrong fields before CRC is even checked.

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

## Decoding frames in the MDB

The preprocessor does not change accepted bytes, so the Mission Database decodes the header and the payload. A complete example is [`csp2.xml`](../examples/csp2-live/src/main/yamcs/mdb/csp2.xml). It uses these techniques:

- **Header.** Six fields of 2, 14, 14, 6, 6 and 6 bits, always big-endian, in one container named `CspPacket`.
- **Layout by port.** CSP frames carry no type or length, so child containers are chosen by `destination_port` with `RestrictionCriteria`. The example has port 10 (three fixed bytes), 11 (housekeeping) and 12 (any length). A frame for any other port still produces the header parameters.
- **Flags.** The flags field is read once as a number and again as single-bit parameters (`flag_crc32`, `flag_rdp`, `flag_hmac`, `flag_frag`). The flags are frame bits 42 to 47, counted from the most significant bit of the first byte, so CRC32 is bit 47. Single-bit parameters can be used in `RestrictionCriteria`, which cannot apply a mask to `flags`.
- **CRC trailer.** The trailer exists only when the CRC32 flag is set. For a fixed layout, a container below `Housekeeping` is chosen by `flag_crc32` and adds the `hk_crc32` parameter after the payload. The plugin has already verified the value; the parameter only makes it visible.
- **Payload of any length.** CSP has no length field, so the payload runs to the end of the frame. Yamcs provides `org.yamcs.algo.RemainingBinaryDecoder` for this. The decoded value includes the CRC trailer when the CRC32 flag is set, because Yamcs 5.13.7 doesn't support the XTCE position "from container end" (`XtceStaxReader` rejects it). Code that needs payload alone must drop the last four bytes when `flag_crc32` is 1.
- **Payload byte order.** CSP does not define it; each application decides. The example payloads are little-endian (`byteOrder="leastSignificantByteFirst"`), as a C struct copied from a little-endian CPU would be. Without `byteOrder`, Yamcs reads big-endian.

The example sets `invalidPackets: DIVERT` on the TM link, so a frame with a wrong CRC goes to a separate stream and the MDB never decodes it.

[`verify_live.py`](../examples/csp2-live/tools/verify_live.py) checks this MDB against frames built by libcsp. For every `mdb_*` vector it sends the frame to Yamcs and compares the decoded parameters with values taken from the vector bytes. It also checks that parameters not carried by the frame stay untouched, that a frame for another node is dropped, and that a frame with a wrong CRC is not decoded.

## Command configuration

```yaml
commandPostprocessorClassName: io.github.glocker.yamcscsp.csp2.CspCommandPostprocessor
commandPostprocessorArgs:
  crcMode: data
  maximumTcPacketLength: 262
```

| Option                  | Type    | Default | Meaning |
|-------------------------|---------|---------|---------|
| `crcMode`               | string  | `data`  | Generated CRC coverage: `data` or `header_data`. |
| `maximumTcPacketLength` | integer | `-1`    | Maximum final length including CRC. `-1` disables limit. |

MDB must provide complete 6-byte CSP header followed by command payload. Postprocessor does not fill addresses or ports. It appends CRC only when header has CRC32 flag.

This follows existing Yamcs CSP 1.x behavior and avoids two owners for same header fields. Details are in [ADR 0002](adr/0002-command-header-owner.md).

Commands shorter than header or longer than configured final limit are not sent. Command history receives `Acknowledge_Sent = NOK` and `CommandComplete = NOK`.

A libcsp frame with the default packet buffer is at most 262 bytes: 6 header bytes and 256 bytes of payload, CRC trailer included. The example uses that value as the limit.

## Building commands in the MDB

Because the MDB owns the header, a command is the header fields plus the payload. The commands in [`csp2.xml`](../examples/csp2-live/src/main/yamcs/mdb/csp2.xml) use these techniques:

- **Shared header.** An abstract command `CspCommand` has one argument for each header field, with the bit widths of the CSP 2.x header. Every other command inherits it with `BaseMetaCommand`, so the six header arguments are written once.
- **Defaults.** `priority` (2), `source` (1), `source_port` (7) and `flags` (1, CRC32 only) have initial values, so an operator gives only what changes. With `flags` 1 the postprocessor appends the CRC trailer.
- **Fixed port.** `Ping` and `AppCommand` set `destination_port` with `ArgumentAssignment`, so the operator cannot send them to another port by mistake. Port 1 is `CSP_PING` in libcsp, a node answers it with the same payload. `SendRaw` leaves the port free.
- **Payload of any length.** A binary argument type with no `SizeInBits` takes every byte the operator gives, including none. In the HTTP API the value is a hex string.
- **Payload fields.** `AppCommand` has two arguments, `opcode` and `value`, encoded after the header (the 16-bit value is little-endian, as in the housekeeping telemetry).
- **Range checks.** `ValidRange` on each header argument type makes Yamcs refuse an address above 16383, a port above 63 and so on with HTTP 400 before the postprocessor sees the command. The postprocessor only checks the final length.

CRC coverage belongs to the postprocessor, not to the command. A libcsp node checks the mode it was built with, so each mode needs its own TC link and postprocessor. The example has two processors, `data` and `header_data`, and releases the same commands through whichever matches the node.

[`verify_live.py`](../examples/csp2-live/tools/verify_live.py) checks these commands against libcsp. For each valid frame in both vector files it issues `SendRaw` with the fields of the vector and compares the datagram on the TC link byte for byte with the libcsp frame. `AppCommand` and `Ping` are compared with their own vectors (`mdb_cmd_app`, `mdb_cmd_ping`). It also checks that out-of-range arguments are refused, and that a frame one byte over the length limit is not sent and is marked `NOK` in command history.

## How compatibility is checked

Java tests do not calculate their own expected frames. A C generator links against pinned libcsp and produces complete UDP frames from two builds: default and `CSP_21`.

Vectors cover:

- minimum and maximum values of every header field;
- all priorities and flag combinations;
- empty and maximum payloads;
- both CRC modes;
- corrupted CRC, missing trailer and short frame;
- frames for the MDB example (`mdb_*`): fixed-layout housekeeping with and without CRC, payloads of any length from empty to maximum, an unknown port, and two commands from Yamcs to a node (`mdb_cmd_app`, `mdb_cmd_ping`).

Scheduled CI rebuilds libcsp in Docker and compares generated files byte-for-byte. [ADR 0004](adr/0004-reference-vectors.md) explains why this is separate from Java implementation.

Stored vectors are not only evidence. Live example starts Yamcs and real libcsp nodes, then checks both directions:

- libcsp telemetry is accepted by Yamcs;
- Yamcs command is accepted by libcsp;
- a Yamcs `Ping` is echoed by the libcsp service handler and decoded by the MDB;
- exchange passes in both CRC modes.

## Supported scope

Verified combination:

- Yamcs 5.13.7 (other versions haven't been tested);
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
