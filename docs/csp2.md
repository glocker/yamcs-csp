# CSP 2.x support

Protocol notes and design of `csp2` module.

Reference implementation: [libcsp](https://github.com/libcsp/libcsp) v2.1,
commit `48f7fb0`. File names below refer to that commit.

## Scope

In scope:

- a packet preprocessor: header parsing, destination filtering, CRC32 verification;
- a command postprocessor: CRC32 generation; command header comes from
  Mission Database (see [Design decisions](#design-decisions));
- transport through existing Yamcs UDP and TCP links.

Out of scope: ZeroMQ transport, RDP, HMAC, fragmentation. Packets with those
flags are passed through untouched.

## CSP 2.x header

The header is 48 bits, transmitted most significant byte first: priority (2
bits), destination (14), source (14), destination port (6), source port (6),
flags (6). Source: `src/csp_id.c`, `CSP_ID2_*`.

CSP 1.x uses a 32-bit header with 5-bit addresses and 8 flag bits. The two
versions cannot be told apart from the bytes alone; the version is a property
of the network and is configured per link.

Plugin acts only on the CRC32 flag (`0x01`). The RDP (`0x02`), HMAC
(`0x08`) and fragmentation (`0x10`) flags are not interpreted
(`include/csp/csp_types.h`).

## CRC32

The algorithm is CRC-32C (Castagnoli), as in the CSP 1.x preprocessor of
Yamcs. The CRC is appended after the payload, big-endian, and is present only
if the CRC32 flag is set.

What is new in 2.x is range the CRC covers. libcsp knows two modes:

- **data only**: payload; this is what the 1.x preprocessor of Yamcs
  checks;
- **header and data**: 6-byte header followed by the payload.

libcsp treats two directions differently:

- Receiving side (`csp_crc32_verify`): tries header-and-data first, and
  accepts packet if data-only matches.
- Sending side (`csp_crc32_append`): the mode is chosen at compile time by the
  `CSP_21` macro. At commit `48f7fb0` macro is only referenced in
  `src/csp_crc32.c` and defined nowhere else in the tree, so a default build
  produces data-only CRCs. Deployment can build libcsp with `CSP_21`
  defined and get header-and-data CRCs.

Consequence: plugin must verify both modes, and its transmit mode must be
configurable to match the spacecraft's build.

## Wire format on UDP

`src/interfaces/csp_if_udp.c` sends one CSP packet per datagram:
header, payload, optional CRC. Yamcs UDP link hands whole datagram
to preprocessor, which leaves it unchanged (as 1.x preprocessor
does); header fields are decoded by Mission Database.

### Choosing Yamcs links

Libcsp UDP interface sends to a fixed remote port (`rport`) and listens on a
fixed local port (`lport`). `UdpTcTmDataLink` connects its socket to remote
host and port and takes the local port from the operating system, so it only
receives datagrams that come back from that same remote address. It will not
receive telemetry from a libcsp node configured this way.

Use two links instead: `UdpTmDataLink` (listens on `port`) with packet
preprocessor, and `UdpTcDataLink` (sends to `host:port`) with command
postprocessor.

## Planned components

Package `io.github.glocker.yamcscsp.csp2`.

| Component                | Responsibility                                                                |
|--------------------------|-------------------------------------------------------------------------------|
| CSP 2.x packet accessor  | Read and write header fields on a `byte[]`                                    |
| CRC helper               | CRC-32C in both modes, big-endian append and verify                           |
| Packet preprocessor      | Length check, destination filter, CRC verification, generation time, sequence |
| Command postprocessor    | CRC generation in the configured mode, maximum length check                   |

### Planned configuration

Subject to change while module is being implemented.

Preprocessor options:

- `cspId` (integer or list in the range 0–16383): drop packets whose destination is not listed.
  Same name as in 1.x preprocessor. Default: accept all.
- `crcMode`: CRC variants accepted for packets carrying the CRC flag. Values are `both`, `data`, and `header_data`.
  Default: `both`.

Postprocessor options:

- `crcMode`: CRC variant generated for commands carrying the CRC flag. Values are `data` and `header_data`.
  Default: `data`, matching a default libcsp 2.1 build.
- `maximumTcPacketLength`: maximum final command length, including the 4-byte CRC trailer when present.
  Default: unlimited.

## Testing strategy

Codec must match libcsp byte for byte. Reference vectors are produced by
libcsp itself (`tools/vector-generator`) and stored under
`csp2/src/test/resources/vectors`. Because transmit CRC mode is a build
option, the vectors are generated from two libcsp builds: default and with
`CSP_21` defined.

Vector coverage: boundary values of every header field (address 0 and 16383,
port 0 and 63, all priorities), each flag alone and combined, empty and
maximum-size payloads, CRC in both modes, and deliberately corrupted packets.

## Design decisions

- Header of an outgoing command is produced by Mission Database, as with
  1.x. If the header carries the CRC flag, the postprocessor appends the CRC in the configured transmit mode; otherwise
  it leaves the command bytes unchanged. An opt-in mode in which the postprocessor fills link-level fields (such as the
  source address) may be added later without changing this behavior.
- The header parser `CspHeader.parse(byte[])` rejects an array shorter than 6 bytes with an
  `IllegalArgumentException`. This differs from CSP 1.x helper `CspPacket` in Yamcs, whose static getters don't
  check length and fail with an `ArrayIndexOutOfBoundsException`. Callers check the length first: the packet
  preprocessor logs a warning for a packet shorter than header and drops it, as it does for CSP 1.x.
- Header construction uses `CspHeader.of(...)` followed by `encode()`. The header object remains immutable, and each
  encoding produces a new 6-byte array. Field values outside the CSP 2.x ranges are rejected with an
  `IllegalArgumentException` instead of being silently truncated by bit masks.
- CRC operations are kept separate from header encoding. `CspCrc.append(...)` generates either data-only or
  header + data CRC according to explicit `CspCrcMode`; `CspCrc.verify(...)` without mode accepts either variant,
  matching libcsp 2.x receive behavior. A frame too short to contain both 6-byte header and 4-byte trailer is invalid.
- The packet preprocessor leaves accepted frame bytes unchanged for Mission Database decoding. It drops frames shorter
  than 6-byte header and frames rejected by destination filter. Failed or missing CRC on a frame carrying
  CRC flag produces a warning and marks `TmPacket` invalid instead of dropping it.
- Сommand postprocessor rejects commands shorter than 6-byte header or longer than `maximumTcPacketLength`.
  Rejection publishes both `Acknowledge_Sent = NOK` and `CommandComplete = NOK` and returns no binary to link.
- Supported Yamcs versions: 5.13.x.
- License: AGPL-3.0, like the official Yamcs plugins that link against
  `yamcs-core`.

## Compatibility

Plugin targets libcsp 2.1 (commit `48f7fb0`) at level of UDP
frame format: header and CRC32 in both modes.

Not supported: RDP, HMAC, fragmentation, and transports other than UDP and TCP
links of Yamcs. libcsp is the only reference implementation used.
