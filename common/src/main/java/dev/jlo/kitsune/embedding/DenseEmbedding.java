package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

public final class DenseEmbedding implements Embedding {
    private static final int MAGIC = 0x4B445631;
    private static final int MAX_DIMENSIONS = 16_384;
    private static final int HEADER_BYTES = Integer.BYTES * 2;
    private static final int MAX_PAYLOAD_BYTES = HEADER_BYTES + MAX_DIMENSIONS * Float.BYTES;

    private final String providerId;
    private final int providerVersion;
    private final float[] components;
    private final double norm;

    public DenseEmbedding(String providerId, int providerVersion, float[] components) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID must not be blank");
        }
        if (providerVersion < 0) {
            throw new IllegalArgumentException("Provider version must not be negative");
        }
        Objects.requireNonNull(components, "Components must not be null");
        if (components.length == 0 || components.length > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Invalid component count");
        }

        this.providerId = providerId;
        this.providerVersion = providerVersion;
        this.components = components.clone();

        double sum = 0.0;
        for (float component : this.components) {
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException("Components must be finite");
            }
            sum += (double) component * component;
        }
        if (!Double.isFinite(sum)) {
            throw new IllegalArgumentException("Norm overflow");
        }
        this.norm = Math.sqrt(sum);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public int providerVersion() {
        return providerVersion;
    }

    @Override
    public double norm() {
        return norm;
    }

    @Override
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + components.length * Float.BYTES);
        buffer.putInt(MAGIC);
        buffer.putInt(components.length);
        for (float component : components) {
            buffer.putFloat(component);
        }
        return buffer.array();
    }

    public static DenseEmbedding decode(
        String providerId,
        int providerVersion,
        byte[] payload,
        double expectedNorm
    ) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (!Double.isFinite(expectedNorm) || expectedNorm < 0.0) {
            throw new IllegalArgumentException("Invalid norm");
        }
        if (payload.length < HEADER_BYTES || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid payload size");
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            if (buffer.getInt() != MAGIC) {
                throw new IllegalArgumentException("Invalid payload magic");
            }
            int dimensions = buffer.getInt();
            if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
                throw new IllegalArgumentException("Invalid component count");
            }
            int expectedLength = HEADER_BYTES + Math.multiplyExact(dimensions, Float.BYTES);
            if (payload.length != expectedLength) {
                throw new IllegalArgumentException("Invalid payload length");
            }

            float[] components = new float[dimensions];
            for (int index = 0; index < dimensions; index++) {
                components[index] = buffer.getFloat();
            }
            DenseEmbedding embedding = new DenseEmbedding(providerId, providerVersion, components);
            if (Double.compare(embedding.norm(), expectedNorm) != 0) {
                throw new IllegalArgumentException("Norm mismatch");
            }
            return embedding;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed payload", exception);
        }
    }

    @Override
    public double cosine(Embedding other) {
        Objects.requireNonNull(other, "Other must not be null");
        if (!Objects.equals(providerId, other.providerId())) {
            throw new IllegalArgumentException("Provider mismatch");
        }
        if (providerVersion != other.providerVersion()) {
            throw new IllegalArgumentException("Version mismatch");
        }
        if (!(other instanceof DenseEmbedding that)) {
            throw new IllegalArgumentException("Unsupported embedding type");
        }
        if (components.length != that.components.length) {
            throw new IllegalArgumentException("Dimension mismatch");
        }
        if (norm == 0.0 || that.norm == 0.0) {
            return 0.0;
        }

        double dot = 0.0;
        for (int index = 0; index < components.length; index++) {
            dot += (double) components[index] * that.components[index];
        }
        double cosine = dot / (norm * that.norm);
        return Math.max(-1.0, Math.min(1.0, cosine));
    }

    public float[] components() {
        return components.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DenseEmbedding that)) {
            return false;
        }
        return providerVersion == that.providerVersion
            && Objects.equals(providerId, that.providerId)
            && Arrays.equals(components, that.components);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(providerId, providerVersion);
        result = 31 * result + Arrays.hashCode(components);
        return result;
    }
}
