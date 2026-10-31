package io.github.glocker.yamcscsp.csp2;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.AbstractPacketPreprocessor;

/**
 * Yamcs packet preprocessor for CSP 2.x telemetry.
 * <p>
 * Validates minimum header length (and header plus trailer length when the CRC32 flag is set), optionally filters
 * destination addresses, verifies flagged CRC trailers, and supplies Yamcs generation time and sequence count values
 * absent from CSP header. Accepted frame bytes are not modified before
 * Mission Database decoding.
 * <p>
 * Configuration keys: {@code cspId} (one address or address list) and {@code crcMode} ({@code both}, {@code data}, or
 * {@code header_data}).
 */
public class CspPacketPreprocessor extends AbstractPacketPreprocessor {

    private static final String CONFIG_CSP_ID = "cspId";
    private static final String CONFIG_CRC_MODE = "crcMode";
    private static final int MAX_CSP_ID = 0x3FFF;
    private static final int MAX_SEQUENCE_COUNT = 0xFFFF;

    private final int[] cspIdFilter;
    private final CrcVerificationMode crcMode;
    private final AtomicInteger sequenceCount = new AtomicInteger();

    private enum CrcVerificationMode {
        BOTH,
        DATA_ONLY,
        HEADER_AND_DATA
    }

    /**
     * Creates preprocessor accepting every destination and both libcsp CRC coverage modes.
     *
     * @param yamcsInstance
     *            Yamcs instance name
     */
    public CspPacketPreprocessor(String yamcsInstance) {
        this(yamcsInstance, YConfiguration.emptyConfig());
    }

    /**
     * Creates preprocessor from Yamcs link configuration.
     *
     * @param yamcsInstance
     *            Yamcs instance name
     * @param config
     *            preprocessor configuration
     * @throws ConfigurationException
     *             if address or CRC mode is invalid
     */
    public CspPacketPreprocessor(String yamcsInstance, YConfiguration config) {
        super(yamcsInstance, config);
        cspIdFilter = readCspIdFilter(config);
        crcMode = readCrcMode(config);
    }

    /**
     * Processes one CSP 2.x frame.
     *
     * @return original packet with Yamcs metadata, or {@code null} when too short (shorter than header, or than header
     *         plus CRC trailer when the CRC32 flag is set) or filtered by destination; CRC failure marks returned packet
     *         invalid instead of dropping it
     */
    @Override
    public TmPacket process(TmPacket packet) {
        byte[] bytes = packet.getPacket();
        if (bytes.length < CspHeader.SIZE) {
            eventProducer.sendWarning("SHORT_PACKET",
                    "Short CSP 2.x packet received, length: " + bytes.length + "; minimum required length is "
                            + CspHeader.SIZE + " bytes");
            return null;
        }

        CspHeader header = CspHeader.parse(bytes);
        if (!isAcceptedDestination(header.getDestination())) {
            return null;
        }

        if (header.hasCrc()) {
            if (bytes.length < CspHeader.SIZE + CspCrc.SIZE) {
                eventProducer.sendWarning("SHORT_PACKET",
                        "Short CSP 2.x packet received, length: " + bytes.length + "; minimum required length is "
                                + (CspHeader.SIZE + CspCrc.SIZE) + " bytes when the CRC flag is set");
                return null;
            }
            if (!verifyCrc(bytes)) {
                String message = "CRC verification failed for CSP 2.x packet";
                log.warn(message);
                eventProducer.sendWarning(ETYPE_CORRUPTED_PACKET, message);
                packet.setInvalid();
            }
        }

        packet.setGenerationTime(getGenerationTime(packet));
        packet.setSequenceCount(getSequenceCount(packet));
        return packet;
    }

    /**
     * Returns reception time because CSP header carries no generation time.
     *
     * @param packet
     *            packet being processed
     * @return packet reception time
     */
    protected long getGenerationTime(TmPacket packet) {
        return packet.getReceptionTime();
    }

    /**
     * Returns local rotating sequence count because CSP header carries no sequence count.
     *
     * @param packet
     *            packet being processed
     * @return next local count in range {@code 0..65535}
     */
    protected int getSequenceCount(TmPacket packet) {
        return sequenceCount.accumulateAndGet(1, (value, increment) -> value >= MAX_SEQUENCE_COUNT ? 0 : value + increment);
    }

    private boolean verifyCrc(byte[] frame) {
        return switch (crcMode) {
        case BOTH -> CspCrc.verify(frame);
        case DATA_ONLY -> CspCrc.verify(frame, CspCrcMode.DATA_ONLY);
        case HEADER_AND_DATA -> CspCrc.verify(frame, CspCrcMode.HEADER_AND_DATA);
        };
    }

    private boolean isAcceptedDestination(int destination) {
        if (cspIdFilter.length == 0) {
            return true;
        }
        for (int cspId : cspIdFilter) {
            if (destination == cspId) {
                return true;
            }
        }
        return false;
    }

    private static int[] readCspIdFilter(YConfiguration config) {
        if (!config.containsKey(CONFIG_CSP_ID)) {
            return new int[0];
        }

        int[] ids;
        if (config.isList(CONFIG_CSP_ID)) {
            List<Integer> configuredIds = config.getList(CONFIG_CSP_ID);
            ids = configuredIds.stream().mapToInt(Integer::intValue).toArray();
        } else {
            ids = new int[] { config.getInt(CONFIG_CSP_ID) };
        }

        for (int id : ids) {
            if (id < 0 || id > MAX_CSP_ID) {
                throw new ConfigurationException(
                        CONFIG_CSP_ID + " must be between 0 and " + MAX_CSP_ID + ", got " + id);
            }
        }
        return ids;
    }

    private static CrcVerificationMode readCrcMode(YConfiguration config) {
        String configuredMode = config.getString(CONFIG_CRC_MODE, "both").toLowerCase(Locale.ROOT);
        return switch (configuredMode) {
        case "both" -> CrcVerificationMode.BOTH;
        case "data" -> CrcVerificationMode.DATA_ONLY;
        case "header_data" -> CrcVerificationMode.HEADER_AND_DATA;
        default -> throw new ConfigurationException(
                "Invalid " + CONFIG_CRC_MODE + " '" + configuredMode
                        + "'; expected one of: both, data, header_data");
        };
    }
}
