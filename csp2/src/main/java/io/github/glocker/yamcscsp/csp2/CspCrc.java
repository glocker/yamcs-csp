package io.github.glocker.yamcscsp.csp2;

import java.util.Arrays;
import java.util.zip.CRC32C;

import org.yamcs.utils.ByteArrayUtils;

/** CRC-32C operations for CSP 2.x frames */
public final class CspCrc {

    /** Size of CRC-32C trailer in bytes */
    public static final int SIZE = 4;

    private CspCrc() {
    }

    /**
     * Computes CRC-32C over all supplied bytes
     *
     * @return unsigned 32-bit CRC value stored in a Java {@code int}
     */
    public static int compute(byte[] bytes) {
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    /**
     * Returns a copy of CSP 2.x header + payload frame with big-endian CRC-32C trailer appended
     *
     * @param frame
     *            6-byte CSP header followed by payload, without a CRC trailer
     * @param mode
     *            which bytes are covered by CRC
     * @throws IllegalArgumentException
     *             if {@code frame} is shorter than a CSP 2.x header
     */
    public static byte[] append(byte[] frame, CspCrcMode mode) {
        requireHeader(frame);

        byte[] result = Arrays.copyOf(frame, frame.length + SIZE);
        int crc = computeCoveredBytes(frame, frame.length, mode);
        ByteArrayUtils.encodeInt(crc, result, frame.length);
        return result;
    }

    /**
     * Verifies a CSP 2.x frame using one CRC coverage mode
     *
     * @param frame
     *            6-byte CSP header, payload and 4-byte CRC trailer
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
     * Verifies CSP 2.x frame using both CRC modes accepted by libcsp 2.x receivers
     *
     * @return {@code true} if either header + data or data only CRC matches
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
