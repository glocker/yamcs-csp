# vector-generator

A small C program that links against libcsp and writes reference test vectors
for `csp2` module, plus script that builds everything.

Reference: libcsp v2.1, commit `48f7fb0b57f610bf65bab1aa2d1357c3b9722782`
(script refuses to run on any other commit or on modified checkout).

## Why two builds

The CRC mode libcsp uses when sending is chosen when the library is built (
`CSP_21` macro, see [../../docs/csp2.md](../../docs/csp2.md)):

| Variant       | Build                                   | CRC covers              |
|---------------|-----------------------------------------|-------------------------|
| `data`        | default                                 | payload only            |
| `header_data` | `CMAKE_C_FLAGS=-DCSP_21=1`              | 6 byte header + payload |

`build.sh` builds libcsp statically in both variants, links the generator
against each one and writes one vector file per variant.

## Usage

Requires Linux with `git`, `cmake`, `ninja` and a C compiler.

libcsp does not build natively on macOS, because its sources include
`<endian.h>`, which macOS does not provide. Its CMake build handles Linux,
Cygwin (experimental) and embedded targets (FreeRTOS, Zephyr) only, so native
Windows is not supported either. On macOS or Windows run the script in a Linux
virtual machine, a container, or WSL (Windows Subsystem for Linux).

```
bash build.sh
```

The libcsp checkout and build directories live in `~/vector-generator-work`
(override with `WORK_DIR`); results go to its `out/` subdirectory (override with
`OUT_DIR`). Copy generated files to `csp2/src/test/resources/vectors`.

## Output

For every variant: `vectors-<variant>.csv` and `vectors-<variant>.meta.toml`
(libcsp repository, tag and commit, build method, CMake flags, meaning).

The CSV starts with `#` comment lines (`# crc_mode=<variant>` and the column
list); every other line is one case:

```
name,crc_mode,priority,destination,source,destination_port,source_port,flags,payload_hex,frame_hex,expected
```

- `crc_mode` is detected by generator from behaviour of linked
  libcsp, so the label can't disagree with library that produced file;
  `build.sh` also checks it against the variant it asked for.
- `payload_hex` is application payload before the CRC is added (`-` if
  empty). The payload bytes are `01 02 03 ...`, wrapping after `FF`.
- `frame_hex` is the exact frame: 6 byte header, payload, and a 4 byte CRC if the
  CRC32 flag is set. Field numbers are decimal, bytes are upper case hex.
- `expected` is the verdict of libcsp's `csp_crc32_verify` on the printed bytes
  (`valid` or `crc_error`), or `too_short` for a frame below the header size.
- Frames without the CRC32 flag are identical in both files.

## Cases

Boundary values of every header field (address 0 and 16383, port 0 and 63, all
priorities, flag bits alone and combined, unknown flag bits), empty and
maximum payload, CRC in both modes, and deliberately broken frames: a flipped
CRC byte, the CRC32 flag without a trailer, and a frame shorter than the
header.

The RDP, HMAC and fragmentation flags are only set in header: generator
doesn't add RDP, HMAC or fragmentation data they would require on a real
link, because the csp2 module passes such packets through untouched.

The maximum payload is 256 bytes without CRC and 252 bytes with CRC, which is
the default libcsp packet buffer size (`CSP_BUFFER_SIZE`).
