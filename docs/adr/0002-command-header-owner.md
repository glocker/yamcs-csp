# 0002: Keep command headers in MDB

Status: Accepted

## What came up

A CSP 2.x command needs six header bytes before its payload. The plugin could build them from link settings, MDB could provide them, or responsibility could be split between both.

Splitting looked flexible, but it immediately raised more questions: which side owns source address, can destination change per command, and what happens when MDB and link configuration disagree?

Yamcs already has a clear answer for CSP 1.x: MDB builds the command header and postprocessor adds CRC. Keeping that model also makes complete command bytes visible in command definitions.

## What we chose

MDB provides complete CSP 2.x header and payload. `CspCommandPostprocessor` reads the CRC32 flag and appends CRC-32C when requested. Priority, addresses, ports and flags are never rewritten.

## Trade-off

Command definitions must contain a valid CSP header, which is some extra MDB work. In return, link behavior stays predictable and per-command destinations remain easy to express.

A source-address override can still be added later as an opt-in feature if a real deployment needs reusable command definitions. It is not part of the default path.
