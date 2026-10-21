package io.github.glocker.yamcscsp.csp2;

import org.yamcs.utils.ByteArrayUtils;

/**
 * 48 bit header of a CSP 2.x packet: priority, destination, source, destination port, source port and flags
 * <p>
 * The header is transmitted most significant byte first
 */
public final class CspHeader {

    /** Size of an encoded header in bytes */
    public static final int SIZE = 6;

    /*
     * The 48 header bits are read as two windows: the first 2 bytes hold the priority and the destination (16 bits),
     * the following 4 bytes hold the source, the destination port, the source port and the flags (32 bits). Every
     * shift below counts from the least significant bit of its own window
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
     * Creates header from its decoded field values
     *
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
     * Decodes a header from the first {@link #SIZE} bytes of the given array
     * <p>
     * Unlike the CSP 1.x helper in Yamcs, which fails with an arbitrary exception on a short array, this method
     * rejects a short array explicitly. The caller is expected to check the length in advance: the packet
     * preprocessor logs a warning for short packets and drops them.
     *
     * @param bytes
     *            the encoded header; may be longer than {@link #SIZE}, the rest is ignored
     * @return the decoded header
     * @throws IllegalArgumentException
     *             if the array is shorter than {@link #SIZE} bytes
     */
    public static CspHeader parse(byte[] bytes) {
        if (bytes.length < SIZE) {
            throw new IllegalArgumentException("CspHeader needs " + SIZE + " bytes, got " + bytes.length);
        }
        int firstWindow = ByteArrayUtils.decodeUnsignedShort(bytes, 0);
        int secondWindow = ByteArrayUtils.decodeInt(bytes, 2);

        int priority = (firstWindow >>> PRIORITY_SHIFT) & PRIORITY_MASK;
        int destination = firstWindow & DESTINATION_MASK;
        // The second window is a signed int and negative if its top bit is set: shift with >>> and mask
        int source = (secondWindow >>> SOURCE_SHIFT) & SOURCE_MASK;
        int destinationPort = (secondWindow >>> DESTINATION_PORT_SHIFT) & PORT_MASK;
        int sourcePort = (secondWindow >>> SOURCE_PORT_SHIFT) & PORT_MASK;
        int flags = secondWindow & FLAGS_MASK;
        return new CspHeader(priority, destination, source, destinationPort, sourcePort, flags);
    }

    /**
     * Encodes header into new {@link #SIZE}-byte array in CSP 2.x wire order,
     * most significant byte first
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
