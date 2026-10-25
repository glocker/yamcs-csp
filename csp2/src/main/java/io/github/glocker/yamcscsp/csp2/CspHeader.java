package io.github.glocker.yamcscsp.csp2;

import org.yamcs.utils.ByteArrayUtils;

/**
 * Immutable representation of 48-bit CSP 2.x header.
 * <p>
 * Wire layout: priority (2 bits), destination (14 bits), source (14 bits), destination port (6 bits), source port
 * (6 bits), and flags (6 bits). Most significant byte is transmitted first.
 */
public final class CspHeader {

    /** Encoded CSP 2.x header size in bytes. */
    public static final int SIZE = 6;

    /*
     * Header is read as two windows: first 2 bytes hold priority and destination (16 bits); following 4 bytes hold
     * source, destination port, source port and flags (32 bits). Shifts count from least significant bit of each window
     */
    private static final int PRIORITY_SHIFT = 14;
    private static final int PRIORITY_MASK = 0x3;
    private static final int DESTINATION_MASK = 0x3FFF;
    private static final int SOURCE_SHIFT = 18;
    private static final int SOURCE_MASK = 0x3FFF;
    private static final int DESTINATION_PORT_SHIFT = 12;
    private static final int SOURCE_PORT_SHIFT = 6;
    private static final int PORT_MASK = 0x3F;
    private static final int FLAGS_MASK = 0x3F;
    private static final int CRC_FLAG = 0x01;

    private final int priority;
    private final int destination;
    private final int source;
    private final int destinationPort;
    private final int sourcePort;
    private final int flags;

    private CspHeader(int priority, int destination, int source, int destinationPort, int sourcePort, int flags) {
        this.priority = priority;
        this.destination = destination;
        this.source = source;
        this.destinationPort = destinationPort;
        this.sourcePort = sourcePort;
        this.flags = flags;
    }

    /**
     * Creates header from decoded field values.
     *
     * @param priority
     *            packet priority in range {@code 0..3}
     * @param destination
     *            destination address in range {@code 0..16383}
     * @param source
     *            source address in range {@code 0..16383}
     * @param destinationPort
     *            destination port in range {@code 0..63}
     * @param sourcePort
     *            source port in range {@code 0..63}
     * @param flags
     *            six-bit flags value in range {@code 0..63}
     * @return immutable validated header
     * @throws IllegalArgumentException
     *             if any field is outside its range
     */
    public static CspHeader of(int priority, int destination, int source, int destinationPort, int sourcePort,
            int flags) {
        checkRange("priority", priority, PRIORITY_MASK);
        checkRange("destination", destination, DESTINATION_MASK);
        checkRange("source", source, SOURCE_MASK);
        checkRange("destinationPort", destinationPort, PORT_MASK);
        checkRange("sourcePort", sourcePort, PORT_MASK);
        checkRange("flags", flags, FLAGS_MASK);
        return new CspHeader(priority, destination, source, destinationPort, sourcePort, flags);
    }

    /**
     * Decodes header from first {@link #SIZE} bytes of supplied array.
     * <p>
     * Unlike CSP 1.x helper in Yamcs, which fails with unexpected exception on short input, this method rejects short
     * input explicitly. Caller should check length first; packet preprocessor logs warning and drops short packets.
     *
     * @param bytes
     *            encoded header; bytes after {@link #SIZE} are ignored
     * @return decoded header
     * @throws IllegalArgumentException
     *             if array is shorter than {@link #SIZE} bytes
     */
    public static CspHeader parse(byte[] bytes) {
        if (bytes.length < SIZE) {
            throw new IllegalArgumentException("CspHeader needs " + SIZE + " bytes, got " + bytes.length);
        }
        int firstWindow = ByteArrayUtils.decodeUnsignedShort(bytes, 0);
        int secondWindow = ByteArrayUtils.decodeInt(bytes, 2);

        int priority = (firstWindow >>> PRIORITY_SHIFT) & PRIORITY_MASK;
        int destination = firstWindow & DESTINATION_MASK;
        // Signed second window becomes negative when top bit is set; use >>> before masking
        int source = (secondWindow >>> SOURCE_SHIFT) & SOURCE_MASK;
        int destinationPort = (secondWindow >>> DESTINATION_PORT_SHIFT) & PORT_MASK;
        int sourcePort = (secondWindow >>> SOURCE_PORT_SHIFT) & PORT_MASK;
        int flags = secondWindow & FLAGS_MASK;
        return new CspHeader(priority, destination, source, destinationPort, sourcePort, flags);
    }

    /**
     * Encodes header into new {@link #SIZE}-byte array in CSP 2.x wire order, most significant byte first.
     *
     * @return new encoded array on every call
     */
    public byte[] encode() {
        int firstWindow = (priority << PRIORITY_SHIFT) | destination;
        int secondWindow = (source << SOURCE_SHIFT) | (destinationPort << DESTINATION_PORT_SHIFT)
                | (sourcePort << SOURCE_PORT_SHIFT) | flags;

        byte[] bytes = new byte[SIZE];
        ByteArrayUtils.encodeUnsignedShort(firstWindow, bytes, 0);
        ByteArrayUtils.encodeInt(secondWindow, bytes, 2);
        return bytes;
    }

    private static void checkRange(String field, int value, int maximum) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 0 and " + maximum + ", got " + value);
        }
    }


    public int getPriority() {
        return priority;
    }


    public int getDestination() {
        return destination;
    }


    public int getSource() {
        return source;
    }


    public int getDestinationPort() {
        return destinationPort;
    }


    public int getSourcePort() {
        return sourcePort;
    }


    public int getFlags() {
        return flags;
    }


    public boolean hasCrc() {
        return (flags & CRC_FLAG) != 0;
    }

    @Override
    public String toString() {
        return "priority " + priority + ", destination " + destination + ", source " + source
                + ", destination port " + destinationPort + ", source port " + sourcePort + ", flags " + flags;
    }
}
