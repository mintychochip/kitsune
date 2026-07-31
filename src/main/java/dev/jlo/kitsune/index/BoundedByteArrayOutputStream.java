package dev.jlo.kitsune.index;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private final int maximumBytes;
    private final String overflowMessage;

    BoundedByteArrayOutputStream(int maximumBytes, String overflowMessage) {
        super(Math.min(maximumBytes, 1024));
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("Maximum bytes must not be negative");
        }
        this.maximumBytes = maximumBytes;
        this.overflowMessage = Objects.requireNonNull(overflowMessage, "Overflow message");
    }

    @Override
    public synchronized void write(int value) {
        ensureCapacityFor(1);
        super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureCapacityFor(length);
        super.write(bytes, offset, length);
    }

    private void ensureCapacityFor(int additionalBytes) {
        if ((long) count + additionalBytes > maximumBytes) {
            throw new IllegalArgumentException(overflowMessage);
        }
    }
}
