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

    @Override
    public String toString() {
        return "priority " + priority + ", destination " + destination + ", source " + source
                + ", destination port " + destinationPort + ", source port " + sourcePort + ", flags " + flags;
    }
}
