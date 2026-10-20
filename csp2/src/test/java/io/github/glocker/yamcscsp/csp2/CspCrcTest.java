package io.github.glocker.yamcscsp.csp2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.yamcs.utils.StringConverter.hexStringToArray;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

public class CspCrcTest {

    private static final int CRC_FLAG = 0x01;

    @Test
    public void testStandardCheckValue() {
        int crc = CspCrc.compute("123456789".getBytes(StandardCharsets.US_ASCII));

        assertEquals(0xE3069283L, Integer.toUnsignedLong(crc));
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @CsvFileSource(resources = { "/vectors/vectors-data.csv", "/vectors/vectors-header_data.csv" })
    public void testReferenceVectors(String name, String crcMode, int priority, int destination, int source,
            int destinationPort, int sourcePort, int flags, String payloadHex, String frameHex, String expected) {
        if ((flags & CRC_FLAG) == 0) {
            return;
        }

        byte[] frame = hexStringToArray(frameHex);
        CspCrcMode mode = parseMode(crcMode);
        boolean valid = expected.equals("valid");

        assertEquals(valid, CspCrc.verify(frame, mode));
        assertEquals(valid, CspCrc.verify(frame));

        if (valid) {
            byte[] withoutTrailer = Arrays.copyOf(frame, frame.length - CspCrc.SIZE);
            assertArrayEquals(frame, CspCrc.append(withoutTrailer, mode));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 3, 4, 9 })
    public void testTooShortFrameDoesNotVerify(int length) {
        assertFalse(CspCrc.verify(new byte[length], CspCrcMode.DATA_ONLY));
        assertFalse(CspCrc.verify(new byte[length], CspCrcMode.HEADER_AND_DATA));
        assertFalse(CspCrc.verify(new byte[length]));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 5 })
    public void testCannotAppendToFrameWithoutCompleteHeader(int length) {
        assertThrows(IllegalArgumentException.class,
                () -> CspCrc.append(new byte[length], CspCrcMode.DATA_ONLY));
    }

    @Test
    public void testReceiverAcceptsBothModes() {
        byte[] dataOnly = hexStringToArray("BFFF0007F141010203F130F21E");
        byte[] headerAndData = hexStringToArray("BFFF0007F1410102037601894D");

        assertTrue(CspCrc.verify(dataOnly));
        assertTrue(CspCrc.verify(headerAndData));
        assertFalse(CspCrc.verify(dataOnly, CspCrcMode.HEADER_AND_DATA));
        assertFalse(CspCrc.verify(headerAndData, CspCrcMode.DATA_ONLY));
    }

    private static CspCrcMode parseMode(String crcMode) {
        return switch (crcMode) {
        case "data" -> CspCrcMode.DATA_ONLY;
        case "header_data" -> CspCrcMode.HEADER_AND_DATA;
        default -> throw new IllegalArgumentException("Unknown CRC mode: " + crcMode);
        };
    }
}
