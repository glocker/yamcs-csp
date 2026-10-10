# vector-generator

A small C program that links against libcsp and writes reference test vectors
for the `csp2` module.

Reference: libcsp v2.1, commit `48f7fb0`.

Output goes to `csp2/src/test/resources/vectors`. The vector file format and the
build instructions will be documented here once the generator exists.

Two libcsp builds are needed, because the CRC mode used when sending is a
compile-time option (see [../../docs/csp2.md](../../docs/csp2.md)):

1. default build;
2. build with `CSP_21` defined.
