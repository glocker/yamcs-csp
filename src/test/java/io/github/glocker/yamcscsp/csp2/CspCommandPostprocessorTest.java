package io.github.glocker.yamcscsp.csp2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent_KEY;
import static org.yamcs.cmdhistory.CommandHistoryPublisher.CommandComplete_KEY;
import static org.yamcs.cmdhistory.CommandHistoryPublisher.SUFFIX_STATUS;
import static org.yamcs.utils.StringConverter.hexStringToArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.utils.TimeEncoding;

public class CspCommandPostprocessorTest {

    private static final int CRC_FLAG = 0x01;

    @BeforeAll
    public static void setUpTimeEncoding() {
        TimeEncoding.setUp();
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @CsvFileSource(resources = { "/vectors/vectors-data.csv", "/vectors/vectors-header_data.csv" })
    public void testReferenceVectors(String name, String crcMode, int priority, int destination, int source,
            int destinationPort, int sourcePort, int flags, String payloadHex, String frameHex, String expected) {
        if (!expected.equals("valid")) {
            return;
        }

        byte[] expectedFrame = hexStringToArray(frameHex);
        byte[] command = (flags & CRC_FLAG) != 0
                ? Arrays.copyOf(expectedFrame, expectedFrame.length - CspCrc.SIZE)
                : expectedFrame;
        var postprocessor = newPostprocessor(Map.of("crcMode", crcMode), new RecordingCommandHistory());
        var preparedCommand = new PreparedCommand(command);

        assertEquals(expectedFrame.length, postprocessor.getBinaryLength(preparedCommand));
        assertArrayEquals(expectedFrame, postprocessor.process(preparedCommand));
    }

    @Test
    public void testDefaultModeIsDataOnly() {
        byte[] command = hexStringToArray("BFFF0007F141010203");
        var postprocessor = newPostprocessor(Map.of(), new RecordingCommandHistory());

        assertArrayEquals(hexStringToArray("BFFF0007F141010203F130F21E"),
                postprocessor.process(new PreparedCommand(command)));
    }

    @Test
    public void testCommandWithoutCrcFlagIsUnchanged() {
        byte[] command = CspHeader.of(1, 5, 3, 10, 20, 0).encode();
        var history = new RecordingCommandHistory();
        var postprocessor = newPostprocessor(Map.of(), history);
        var preparedCommand = new PreparedCommand(command);

        byte[] processed = postprocessor.process(preparedCommand);

        assertArrayEquals(command, processed);
        assertSame(processed, history.binary);
        assertEquals(command.length, postprocessor.getBinaryLength(preparedCommand));
    }

    @Test
    public void testCrcAppendDoesNotModifyMdbBinary() {
        byte[] command = hexStringToArray("BFFF0007F141010203");
        byte[] original = command.clone();
        var postprocessor = newPostprocessor(Map.of("crcMode", "header_data"), new RecordingCommandHistory());

        byte[] processed = postprocessor.process(new PreparedCommand(command));

        assertArrayEquals(original, command);
        assertArrayEquals(hexStringToArray("BFFF0007F1410102037601894D"), processed);
    }

    @Test
    public void testMaximumLengthIncludesCrcTrailer() {
        byte[] command = hexStringToArray("BFFF0007F141010203");
        var postprocessor = newPostprocessor(Map.of("maximumTcPacketLength", 13), new RecordingCommandHistory());
        var preparedCommand = new PreparedCommand(command);

        assertEquals(13, postprocessor.getBinaryLength(preparedCommand));
        assertEquals(13, postprocessor.process(preparedCommand).length);
    }

    @Test
    public void testTooLongCommandFailsCommandHistory() {
        byte[] command = hexStringToArray("BFFF0007F141010203");
        var history = new RecordingCommandHistory();
        var postprocessor = newPostprocessor(Map.of("maximumTcPacketLength", 12), history);

        assertNull(postprocessor.process(new PreparedCommand(command)));
        assertEquals("NOK", history.values.get(AcknowledgeSent_KEY + SUFFIX_STATUS));
        assertEquals("NOK", history.values.get(CommandComplete_KEY + SUFFIX_STATUS));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 5 })
    public void testCommandShorterThanHeaderFailsCommandHistory(int length) {
        var history = new RecordingCommandHistory();
        var postprocessor = newPostprocessor(Map.of(), history);

        assertNull(postprocessor.process(new PreparedCommand(new byte[length])));
        assertEquals("NOK", history.values.get(AcknowledgeSent_KEY + SUFFIX_STATUS));
        assertEquals("NOK", history.values.get(CommandComplete_KEY + SUFFIX_STATUS));
    }

    @Test
    public void testInvalidCrcModeIsRejected() {
        assertThrows(ConfigurationException.class,
                () -> newPostprocessor(Map.of("crcMode", "both"), new RecordingCommandHistory()));
    }

    @ParameterizedTest
    @ValueSource(ints = { -2, -100 })
    public void testInvalidMaximumLengthIsRejected(int maximumLength) {
        assertThrows(ConfigurationException.class,
                () -> newPostprocessor(Map.of("maximumTcPacketLength", maximumLength), new RecordingCommandHistory()));
    }

    private static CspCommandPostprocessor newPostprocessor(Map<String, Object> options,
            CommandHistoryPublisher commandHistory) {
        var postprocessor = new CspCommandPostprocessor();
        postprocessor.setCommandHistoryPublisher(commandHistory);
        postprocessor.init("csp2-test", YConfiguration.wrap(options), null);
        return postprocessor;
    }

    private static class RecordingCommandHistory implements CommandHistoryPublisher {
        private final Map<String, Object> values = new HashMap<>();
        private byte[] binary;

        @Override
        public void publish(CommandId commandId, String key, String value) {
            values.put(key, value);
        }

        @Override
        public void publish(CommandId commandId, String key, int value) {
            values.put(key, value);
        }

        @Override
        public void publish(CommandId commandId, String key, long value) {
            values.put(key, value);
        }

        @Override
        public void publish(CommandId commandId, String key, byte[] binary) {
            values.put(key, binary);
            this.binary = binary;
        }

        @Override
        public void addCommand(PreparedCommand preparedCommand) {
        }
    }
}
