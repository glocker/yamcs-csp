package io.github.glocker.yamcscsp.csp2;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent_KEY;

import java.util.Locale;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.Link;
import org.yamcs.utils.TimeEncoding;

/** Command postprocessor for CSP 2.x commands */
public class CspCommandPostprocessor implements CommandPostprocessor {

    private static final String CONFIG_CRC_MODE = "crcMode";
    private static final String CONFIG_MAXIMUM_PACKET_LENGTH = "maximumTcPacketLength";

    private final Log log = new Log(getClass());
    private int maximumTcPacketLength = -1;
    private CspCrcMode crcMode = CspCrcMode.DATA_ONLY;
    private CommandHistoryPublisher commandHistory;

    @Override
    public void init(String yamcsInstance, YConfiguration config, Link link) {
        maximumTcPacketLength = config.getInt(CONFIG_MAXIMUM_PACKET_LENGTH, -1);
        if (maximumTcPacketLength < -1) {
            throw new ConfigurationException(CONFIG_MAXIMUM_PACKET_LENGTH + " must be -1 or greater");
        }
        crcMode = readCrcMode(config);
    }

    @Override
    public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistory) {
        this.commandHistory = commandHistory;
    }

    @Override
    public byte[] process(PreparedCommand preparedCommand) {
        byte[] binary = preparedCommand.getBinary();
        if (binary.length < CspHeader.SIZE) {
            return failCommand(preparedCommand,
                    "Command too short for a CSP 2.x header, length: " + binary.length + "; minimum required length: "
                            + CspHeader.SIZE);
        }

        int newLength = getBinaryLength(preparedCommand);
        if (maximumTcPacketLength != -1 && newLength > maximumTcPacketLength) {
            return failCommand(preparedCommand,
                    "Command too long, length: " + newLength + ", expected maximum length: " + maximumTcPacketLength);
        }

        byte[] processed = CspHeader.parse(binary).hasCrc() ? CspCrc.append(binary, crcMode) : binary;
        commandHistory.publish(preparedCommand.getCommandId(), PreparedCommand.CNAME_BINARY, processed);
        return processed;
    }

    @Override
    public int getBinaryLength(PreparedCommand preparedCommand) {
        byte[] binary = preparedCommand.getBinary();
        if (binary.length < CspHeader.SIZE) {
            return binary.length;
        }
        return binary.length + (CspHeader.parse(binary).hasCrc() ? CspCrc.SIZE : 0);
    }

    public int getMaximumTcPacketLength() {
        return maximumTcPacketLength;
    }

    private byte[] failCommand(PreparedCommand preparedCommand, String message) {
        log.warn(message);
        long time = TimeEncoding.getWallclockTime();
        commandHistory.publishAck(preparedCommand.getCommandId(), AcknowledgeSent_KEY, time, AckStatus.NOK, message);
        commandHistory.commandFailed(preparedCommand.getCommandId(), time, message);
        return null;
    }

    private static CspCrcMode readCrcMode(YConfiguration config) {
        String configuredMode = config.getString(CONFIG_CRC_MODE, "data").toLowerCase(Locale.ROOT);
        return switch (configuredMode) {
        case "data" -> CspCrcMode.DATA_ONLY;
        case "header_data" -> CspCrcMode.HEADER_AND_DATA;
        default -> throw new ConfigurationException(
                "Invalid " + CONFIG_CRC_MODE + " '" + configuredMode + "'; expected one of: data, header_data");
        };
    }
}
