package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;

import java.util.*;

public final class SparseEmbedding implements Embedding {
    private static final int MAX_ENTRIES = 16384;
    private static final int MAX_KEY_CHARS = 256;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private final String providerId;
    private final int providerVersion;
    private final Map<String, Double> values;
    private final double norm;

    public SparseEmbedding(String providerId, int providerVersion, Map<String, Double> values) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Provider ID must not be blank");
        if (providerVersion < 0) throw new IllegalArgumentException("Provider version must not be negative");
        Objects.requireNonNull(values, "Values must not be null");
        if (values.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many entries");
        this.providerId = providerId;
        this.providerVersion = providerVersion;
        for (String key : values.keySet()) {
            if (key == null) throw new IllegalArgumentException("Key must not be null");
            if (key.isBlank() || key.length() > MAX_KEY_CHARS) throw new IllegalArgumentException("Invalid key");
        }
        Map<String, Double> copy = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.naturalOrder());
        double sum = 0.0;
        for (String key : keys) {
            Double v = values.get(key);
            Objects.requireNonNull(v, "Value must not be null");
            if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException("Values must be finite and positive");
            copy.put(key, v);
            sum += v * v;
        }
        if (!Double.isFinite(sum) || Double.isInfinite(sum)) throw new IllegalArgumentException("Norm overflow");
        if (!copy.isEmpty() && sum == 0.0) throw new IllegalArgumentException("Norm underflow");
        this.norm = Math.sqrt(sum);
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    public static SparseEmbedding of(String providerId, int providerVersion, Map<String, Double> values) {
        return new SparseEmbedding(providerId, providerVersion, values);
    }

    @Override
    public String providerId() { return providerId; }

    @Override
    public int providerVersion() { return providerVersion; }

    @Override
    public double norm() {
        return norm;
    }

    @Override
    public byte[] encode() {
        try {
            var bos = new java.io.ByteArrayOutputStream();
            try (var dos = new java.io.DataOutputStream(bos)) {
                dos.writeInt(values.size());
                for (var entry : values.entrySet()) {
                    dos.writeUTF(entry.getKey());
                    dos.writeDouble(entry.getValue());
                }
            }
            return bos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static SparseEmbedding decode(String providerId, int providerVersion, byte[] payload, double expectedNorm) {
        if (payload == null) throw new IllegalArgumentException("Payload must not be null");
        if (!Double.isFinite(expectedNorm) || expectedNorm < 0) throw new IllegalArgumentException("Invalid norm");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Payload too large");
        try (var dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))) {
            int count = dis.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid count");
            Map<String, Double> map = new LinkedHashMap<>();
            double sum = 0.0;
            for (int i = 0; i < count; i++) {
                String key = dis.readUTF();
                if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate key");
                if (key == null || key.isBlank() || key.length() > MAX_KEY_CHARS) throw new IllegalArgumentException("Invalid key");
                double v = dis.readDouble();
                if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException("Non-finite or non-positive value");
                map.put(key, v);
                sum += v * v;
            }
            if (dis.available() != 0) throw new IllegalArgumentException("Trailing bytes");
            double norm = Math.sqrt(sum);
            if (count > 0 && norm == 0.0) throw new IllegalArgumentException("Norm underflow");
            if (Double.compare(norm, expectedNorm) != 0) throw new IllegalArgumentException("Norm mismatch");
            return new SparseEmbedding(providerId, providerVersion, map);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed payload", ex);
        }
    }

    @Override
    public double cosine(Embedding other) {
        Objects.requireNonNull(other, "Other must not be null");
        if (!Objects.equals(this.providerId, other.providerId())) throw new IllegalArgumentException("Provider mismatch");
        if (this.providerVersion != other.providerVersion()) throw new IllegalArgumentException("Version mismatch");
        if (!(other instanceof SparseEmbedding that)) throw new IllegalArgumentException("Unsupported embedding type");
        double n1 = this.norm;
        double n2 = that.norm;
        if (n1 == 0.0 || n2 == 0.0) return 0.0;
        Map<String, Double> smaller = this.values.size() <= that.values.size() ? this.values : that.values;
        Map<String, Double> larger = this.values.size() <= that.values.size() ? that.values : this.values;
        double dot = 0.0;
        for (var entry : smaller.entrySet()) {
            dot += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        double denom = n1 * n2;
        if (denom == 0.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, dot / denom));
    }

    public Map<String, Double> values() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SparseEmbedding that)) return false;
        return providerVersion == that.providerVersion &&
                Objects.equals(providerId, that.providerId) &&
                Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, providerVersion, values);
    }
}
