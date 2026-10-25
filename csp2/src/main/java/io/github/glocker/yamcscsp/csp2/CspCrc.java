package io.github.glocker.yamcscsp.csp2;

import java.util.Arrays;
import java.util.zip.CRC32C;

import org.yamcs.utils.ByteArrayUtils;

/**
 * CRC-32C operations for CSP 2.x frames.
 * <p>
 * Supports payload-only coverage used by default libcsp 2.1 build and header-plus-payload coverage selected by
 * libcsp's {@code CSP_21} compile-time macro. CRC trailers are encoded most significant byte first.
 */
public final class CspCrc {

    /** Encoded CRC-32C trailer size in bytes. */
    public static final int SIZE = 4;

    private CspCrc() {
    }

    /**
     * Computes CRC-32C over all supplied bytes.
     *
     * @param bytes
     *            bytes covered by calculation
     * @return unsigned 32-bit CRC value stored in Java {@code int}
     */
    public static int compute(byte[] bytes) {
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    /**
     * Copies CSP 2.x header + payload and appends big-endian CRC-32C trailer.
     *
     * @param frame
     *            6-byte CSP header followed by payload, without CRC trailer
     * @param mode
     *            which bytes are covered by CRC
     * @return new array containing {@code frame} and its four-byte trailer
     * @throws IllegalArgumentException
     *             if {@code frame} is shorter than CSP 2.x header
     */
    public static byte[] append(byte[] frame, CspCrcMode mode) {
        requireHeader(frame);

        byte[] result = Arrays.copyOf(frame, frame.length + SIZE);
        int crc = computeCoveredBytes(frame, frame.length, mode);
        ByteArrayUtils.encodeInt(crc, result, frame.length);
        return result;
    }

    /**
     * Verifies CSP 2.x frame using one CRC coverage mode.
     *
     * @param frame
     *            6-byte CSP header, payload and 4-byte CRC trailer
     * @param mode
     *            coverage mode to verify
     * @return {@code false} if frame is too short to contain header and trailer, or if CRC doesn't match
     */
    public static boolean verify(byte[] frame, CspCrcMode mode) {
        if (frame.length < CspHeader.SIZE + SIZE) {
            return false;
        }

        int trailerOffset = frame.length - SIZE;
        int computed = computeCoveredBytes(frame, trailerOffset, mode);
        int received = ByteArrayUtils.decodeInt(frame, trailerOffset);
        return computed == received;
    }

    /**
     * Verifies CSP 2.x frame using both CRC modes accepted by libcsp 2.x receivers.
     *
     * @param frame
     *            6-byte CSP header, payload and 4-byte CRC trailer
     * @return {@code true} if either header-plus-data or data-only CRC matches
     */
    public static boolean verify(byte[] frame) {
        return verify(frame, CspCrcMode.HEADER_AND_DATA) || verify(frame, CspCrcMode.DATA_ONLY);
    }

    private static int computeCoveredBytes(byte[] frame, int dataEnd, CspCrcMode mode) {
        int offset = mode == CspCrcMode.DATA_ONLY ? CspHeader.SIZE : 0;
        var crc = new CRC32C();
        crc.update(frame, offset, dataEnd - offset);
        return (int) crc.getValue();
    }

    private static void requireHeader(byte[] frame) {
        if (frame.length < CspHeader.SIZE) {
            throw new IllegalArgumentException(
                    "CSP 2.x frame needs at least " + CspHeader.SIZE + " header bytes, got " + frame.length);
        }
    }
}
