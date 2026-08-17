package dev.jlo.kitsune.index;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * A {@link ByteArrayOutputStream} with a hard byte-count limit.
 *
 * <p>Writes that would push the buffer beyond its configured maximum are rejected with an
 * {@link IllegalArgumentException} carrying the given overflow message. The initial buffer is
 * sized to the minimum of the limit and 1 KiB.
 */
final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private final int maximumBytes;
    private final String overflowMessage;

    /**
     * Creates a bounded stream.
     *
     * @param maximumBytes maximum number of bytes that may be written, non-negative
     * @param overflowMessage message for the exception raised on overflow, must not be null
     * @throws IllegalArgumentException if {@code maximumBytes} is negative
     */
    BoundedByteArrayOutputStream(int maximumBytes, String overflowMessage) {
        super(Math.min(maximumBytes, 1024));
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("Maximum bytes must not be negative");
        }
        this.maximumBytes = maximumBytes;
        this.overflowMessage = Objects.requireNonNull(overflowMessage, "Overflow message");
    }

    /** Writes a single byte, rejecting the write if it would exceed the limit. */
    @Override
    public synchronized void write(int value) {
        ensureCapacityFor(1);
        super.write(value);
    }

    /** Writes a byte range, validating bounds and rejecting the write if it would exceed the limit. */
    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureCapacityFor(length);
        super.write(bytes, offset, length);
    }

    /** Throws unless writing {@code additionalBytes} more stays at or below the limit. */
    private void ensureCapacityFor(int additionalBytes) {
        if ((long) count + additionalBytes > maximumBytes) {
            throw new IllegalArgumentException(overflowMessage);
        }
    }
}
