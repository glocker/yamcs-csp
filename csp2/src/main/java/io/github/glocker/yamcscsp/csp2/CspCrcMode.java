package io.github.glocker.yamcscsp.csp2;

/** Defines which part of CSP 2.x frame is covered by CRC-32C */
public enum CspCrcMode {

    /** Only payload is covered */
    DATA_ONLY,

    /** 6-byte header followed by payload is covered */
    HEADER_AND_DATA
}
