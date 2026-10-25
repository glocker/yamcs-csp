# Decisions made during development

These notes record problems that were not obvious from CSP header format alone. Each one came from implementing or testing plugin against Yamcs and libcsp.

- [0001: Accept both libcsp CRC modes](0001-crc-modes.md) - same libcsp source can produce two different CRC trailers.
- [0002: Keep command headers in MDB](0002-command-header-owner.md) - avoid splitting ownership between command definition and link configuration.
- [0003: Use AGPL-3.0-only](0003-license.md) - keep repository license explicit and aligned with Yamcs ecosystem.
- [0004: Generate vectors with pinned libcsp](0004-reference-vectors.md) - test Java codec against real implementation instead of itself.
