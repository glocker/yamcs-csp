# 0004: Generate vectors with pinned libcsp

Status: Accepted

## What came up

Header codec and CRC code needed expected byte sequences for tests. Calculating those values with the same Java code would only prove that code agrees with itself. A wrong mask or CRC range could appear in both production code and expected values.

Handwritten vectors have the same weakness and become especially risky around 14-bit addresses, packed 6-bit ports and two CRC coverage modes.

The useful reference is libcsp itself: this plugin exists to exchange frames with libcsp nodes.

## What we chose

A small C generator links against official libcsp v2.1 pinned to commit `48f7fb0b57f610bf65bab1aa2d1357c3b9722782`. It produces complete UDP frames, not only decoded field values.

libcsp is built twice:

- default build for payload-only CRC;
- build with `CSP_21` for header-plus-payload CRC.

Generated CSV files and provenance metadata live in `src/test/resources/vectors`. Scheduled CI rebuilds generator in Linux Docker image and compares output byte-for-byte.

## Trade-off

Tests now have an implementation-independent oracle for Java code, but vector regeneration needs C toolchain and Linux environment. Docker keeps that work reproducible on macOS and in CI.

Changing libcsp commit is treated as a compatibility update: regenerate vectors, inspect differences, then update Java behavior if needed.
