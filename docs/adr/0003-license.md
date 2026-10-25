# 0003: Use AGPL-3.0-only

Status: Accepted

## What came up

This plugin depends on `yamcs-core`. Yamcs and its official plugins use AGPLv3, so choosing an unrelated permissive license would add legal questions without helping implementation or interoperability.

There was also a smaller choice between `AGPL-3.0-only` and `AGPL-3.0-or-later`. Repository already contained AGPL version 3 text, but no statement allowing future versions.

## What we chose

Project license is `AGPL-3.0-only`. Full text stays in root `LICENSE`; Maven metadata and public documentation use the same identifier.

## Trade-off

Choice is conservative and unambiguous. Moving to `-or-later` would need a separate relicensing decision. Exact obligations for a particular distribution or hosted service still depend on how software is delivered and should not be inferred from this ADR as legal advice.
