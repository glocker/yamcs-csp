# libcsp reference vector generator

Java codec needs expected bytes produced outside Java implementation. This tool links a small C program against real libcsp and records complete CSP 2.x UDP frames for tests.

Pinned reference:

```text
libcsp v2.1
48f7fb0b57f610bf65bab1aa2d1357c3b9722782
```

`build.sh` refuses another commit or modified libcsp checkout.

## Why libcsp is built twice

libcsp chooses transmit CRC coverage at compile time:

| Variant       | Build                      | CRC covers              |
|---------------|----------------------------|-------------------------|
| `data`        | default                    | payload only            |
| `header_data` | `CMAKE_C_FLAGS=-DCSP_21=1` | 6-byte header + payload |

Generator is linked separately against each static library. It also detects actual CRC behavior before labelling output, so file name cannot silently disagree with linked build.

More detail: [CSP 2.x CRC notes](../../docs/csp2.md#crc32).

## Run on Linux

Required tools: Git, CMake, Ninja and C compiler.

```sh
bash build.sh
```

libcsp source and builds go to `~/vector-generator-work`; override with `WORK_DIR`. Generated files go to its `out/` directory; override with `OUT_DIR`.

libcsp v2.1 does not build natively on macOS because its source expects `<endian.h>`. On macOS or Windows, use Docker, Linux VM, or WSL.

## Run in Docker

From repository root:

```sh
docker build --tag yamcs-csp-vector-generator tools/vector-generator
mkdir -p target/generated-vectors
docker run --rm \
  --volume "$PWD/target/generated-vectors:/out" \
  yamcs-csp-vector-generator
diff --recursive --unified \
  csp2/src/test/resources/vectors target/generated-vectors
```

No `diff` output means generated files match committed vectors byte-for-byte. `Golden vectors` workflow repeats this check every night on Linux.

## Output

Each build writes CSV and provenance file:

```text
vectors-data.csv
vectors-data.meta.toml
vectors-header_data.csv
vectors-header_data.meta.toml
```

CSV columns:

```text
name,crc_mode,priority,destination,source,destination_port,source_port,flags,payload_hex,frame_hex,expected
```

Important fields:

- `payload_hex`: application payload before CRC, or `-` when empty;
- `frame_hex`: exact UDP frame - 6-byte header, payload and optional 4-byte CRC;
- `expected`: result from libcsp `csp_crc32_verify`, or `too_short` below header size;
- `crc_mode`: behavior detected from linked libcsp build.

Cases cover field boundaries, all priorities, individual and combined flags, empty and maximum payloads, both CRC modes, corrupted CRC, missing trailer and short frame.

RDP, HMAC and fragmentation bits are included as header values only. Generator does not create protocol data required by those features because `csp2` does not process them.

Maximum payload follows default libcsp packet buffer: 256 bytes without CRC, 252 bytes with CRC.
