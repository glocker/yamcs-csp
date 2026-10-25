package io.github.glocker.yamcscsp.csp2;

/** Defines CSP 2.x frame bytes covered by CRC-32C. */
public enum CspCrcMode {

    /** Covers payload after 6-byte CSP header. */
    DATA_ONLY,

    /** Covers 6-byte CSP header followed by payload. */
    HEADER_AND_DATA
}
