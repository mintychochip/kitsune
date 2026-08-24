package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;

import java.util.*;

/**
 * A sparse embedding stored as a map of feature keys to positive weights.
 *
 * <p>Keys are validated (non-null, non-blank, at most 256 characters) and capped at 16384
 * entries; values must be finite and positive. The L2 norm is computed eagerly. The backing
 * map and its iteration order are stable, and the map is exposed unmodifiable.
 */
public final class SparseEmbedding implements Embedding {
    private static final int MAX_ENTRIES = 16384;
    private static final int MAX_KEY_CHARS = 256;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private final String providerId;
    private final int providerVersion;
    private final Map<String, Double> values;
    private final double norm;

    /**
     * Creates a sparse embedding, validating and copying the given values.
     *
     * @param providerId non-blank identifier of the producing provider
     * @param providerVersion version of the producing provider, non-negative
     * @param values feature weights, must not be null, at most 16384 entries with non-null,
     *        non-blank keys no longer than 256 characters and finite positive values
     * @throws IllegalArgumentException if a provider identity is invalid, an entry is
     *         invalid, too many entries are given, or the norm computation overflows or
     *         underflows
     */
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

    /** Convenience factory delegating to the validated constructor. */
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

    /**
     * Serializes this embedding: an entry count followed by UTF keys and double weights.
     *
     * @return compact binary encoding of this embedding
     */
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

    /**
     * Restores a sparse embedding from the payload produced by {@link #encode()}.
     *
     * @param providerId non-blank identifier of the producing provider
     * @param providerVersion version of the producing provider
     * @param payload binary payload, at most 16 MiB, with no trailing bytes
     * @param expectedNorm the norm the restored embedding must match
     * @return the decoded embedding
     * @throws IllegalArgumentException if the payload is malformed or oversized, contains a
     *         duplicate or invalid key, an invalid value, the norm underflows, or the norm
     *         does not match {@code expectedNorm}
     */
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

    /**
     * Computes cosine similarity with another embedding.
     *
     * <p>Requires the other embedding to be a {@link SparseEmbedding} of the same provider and
     * version. Iterates the smaller map for efficiency. Returns {@code 0.0} if either
     * embedding has a zero norm; otherwise returns the similarity clamped to {@code [0.0, 1.0]}.
     *
     * @param other the embedding to compare against, must not be null
     * @return cosine similarity in {@code [0.0, 1.0]}
     * @throws IllegalArgumentException on provider, version, or type mismatch
     */
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

    /** Returns the unmodifiable map of feature weights. */
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
