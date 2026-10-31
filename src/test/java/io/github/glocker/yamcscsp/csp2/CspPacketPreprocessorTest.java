package io.github.glocker.yamcscsp.csp2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.yamcs.utils.StringConverter.hexStringToArray;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.utils.TimeEncoding;

public class CspPacketPreprocessorTest {

    private static final String YAMCS_INSTANCE = "csp2-test";
    private static final int CRC_FLAG = 0x01;

    @BeforeEach
    public void setUpEvents() {
        TimeEncoding.setUp();
        EventProducerFactory.setMockup(true);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 5 })
    public void testPacketShorterThanHeaderIsDropped(int length) {
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);

        assertNull(preprocessor.process(new TmPacket(1000, new byte[length])));
        assertEquals(1, EventProducerFactory.getMockupQueue().size());
        assertEquals("SHORT_PACKET", EventProducerFactory.getMockupQueue().element().getType());
    }

    @Test
    public void testNoDestinationFilterAcceptsAnyDestination() {
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);

        assertSamePacket(preprocessor, frame(0));
        assertSamePacket(preprocessor, frame(16383));
    }

    @Test
    public void testSingleDestinationFilter() {
        var config = YConfiguration.wrap(Map.of("cspId", 42));
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE, config);

        assertSamePacket(preprocessor, frame(42));
        assertNull(preprocessor.process(new TmPacket(1000, frame(43))));
    }

    @Test
    public void testDestinationListFilter() {
        var config = YConfiguration.wrap(Map.of("cspId", List.of(1, 42, 16383)));
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE, config);

        assertSamePacket(preprocessor, frame(1));
        assertSamePacket(preprocessor, frame(42));
        assertSamePacket(preprocessor, frame(16383));
        assertNull(preprocessor.process(new TmPacket(1000, frame(2))));
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 16384 })
    public void testDestinationFilterOutsideCspRangeIsRejected(int cspId) {
        var config = YConfiguration.wrap(Map.of("cspId", cspId));

        assertThrows(ConfigurationException.class, () -> new CspPacketPreprocessor(YAMCS_INSTANCE, config));
    }

    @Test
    public void testInvalidCrcModeIsRejected() {
        var config = YConfiguration.wrap(Map.of("crcMode", "unknown"));

        assertThrows(ConfigurationException.class, () -> new CspPacketPreprocessor(YAMCS_INSTANCE, config));
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @CsvFileSource(resources = { "/vectors/vectors-data.csv", "/vectors/vectors-header_data.csv" })
    public void testReferenceVectors(String name, String crcMode, int priority, int destination, int source,
            int destinationPort, int sourcePort, int flags, String payloadHex, String frameHex, String expected) {
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);
        byte[] frame = hexStringToArray(frameHex);
        var packet = new TmPacket(123456, frame);

        TmPacket result = preprocessor.process(packet);

        boolean tooShortForCrc = (flags & CRC_FLAG) != 0 && frame.length < CspHeader.SIZE + CspCrc.SIZE;
        if (expected.equals("too_short") || tooShortForCrc) {
            assertNull(result);
            return;
        }
        assertSame(packet, result);
        assertEquals(expected.equals("crc_error"), result.isInvalid());
        assertEquals(123456, result.getGenerationTime());
        assertEquals(1, result.getSeqCount());
    }

    @Test
    public void testConfiguredCrcModeAcceptsOnlyThatMode() {
        byte[] dataOnly = hexStringToArray("BFFF0007F141010203F130F21E");
        byte[] headerAndData = hexStringToArray("BFFF0007F1410102037601894D");
        var dataPreprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE,
                YConfiguration.wrap(Map.of("crcMode", "data")));
        var headerDataPreprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE,
                YConfiguration.wrap(Map.of("crcMode", "header_data")));

        assertFalse(dataPreprocessor.process(new TmPacket(1000, dataOnly)).isInvalid());
        assertTrue(dataPreprocessor.process(new TmPacket(1000, headerAndData)).isInvalid());
        assertFalse(headerDataPreprocessor.process(new TmPacket(1000, headerAndData)).isInvalid());
        assertTrue(headerDataPreprocessor.process(new TmPacket(1000, dataOnly)).isInvalid());
    }

    @ParameterizedTest
    @ValueSource(ints = { 6, 7, 8, 9 })
    public void testCrcFlagWithFrameShorterThanTrailerIsDropped(int length) {
        byte[] frame = Arrays.copyOf(CspHeader.of(1, 5, 3, 10, 20, CRC_FLAG).encode(), length);
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);

        assertNull(preprocessor.process(new TmPacket(1000, frame)));
        assertEquals(1, EventProducerFactory.getMockupQueue().size());
        assertEquals("SHORT_PACKET", EventProducerFactory.getMockupQueue().element().getType());
    }

    @Test
    public void testCrcFlagWithEmptyPayloadAndWrongTrailerMarksPacketInvalid() {
        // Shortest frame that can hold a CRC trailer: header and four trailer bytes. CRC-32C of no data is zero, so
        // the last trailer byte is changed to make the trailer wrong
        byte[] frame = Arrays.copyOf(CspHeader.of(1, 5, 3, 10, 20, CRC_FLAG).encode(), CspHeader.SIZE + CspCrc.SIZE);
        frame[frame.length - 1] = 0x01;
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);

        TmPacket result = preprocessor.process(new TmPacket(1000, frame));

        assertTrue(result.isInvalid());
        assertEquals(1, EventProducerFactory.getMockupQueue().size());
        assertEquals("CORRUPTED_PACKET", EventProducerFactory.getMockupQueue().element().getType());
    }

    @Test
    public void testExistingInvalidStatusIsPreserved() {
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);
        var packet = new TmPacket(1000, frame(5));
        packet.setInvalid();

        TmPacket result = preprocessor.process(packet);

        assertTrue(result.isInvalid());
    }

    @Test
    public void testGenerationTimeAndSequenceCount() {
        var preprocessor = new CspPacketPreprocessor(YAMCS_INSTANCE);
        var first = new TmPacket(1234, frame(5));
        var second = new TmPacket(5678, frame(5));

        assertSame(first, preprocessor.process(first));
        assertSame(second, preprocessor.process(second));
        assertEquals(1234, first.getGenerationTime());
        assertEquals(5678, second.getGenerationTime());
        assertEquals(1, first.getSeqCount());
        assertEquals(2, second.getSeqCount());
    }

    private static void assertSamePacket(CspPacketPreprocessor preprocessor, byte[] bytes) {
        var packet = new TmPacket(1000, bytes);
        assertSame(packet, preprocessor.process(packet));
    }

    private static byte[] frame(int destination) {
        return CspHeader.of(1, destination, 3, 10, 20, 0).encode();
    }
}
