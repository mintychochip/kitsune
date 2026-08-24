package dev.jlo.kitsune.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable identity of a root container observed at a revision.
 *
 * @param key         block key of the root
 * @param blockType   type of the root block
 * @param fingerprint binary fingerprint of the observed contents
 * @param revision    monotonically increasing revision of the observation
 */
public record RootIdentity(BlockKey key, String blockType, byte[] fingerprint, long revision) {
    public RootIdentity {
        Objects.requireNonNull(key, "Key must not be null");
        if (blockType == null || blockType.isBlank()) throw new IllegalArgumentException("Block type must not be blank");
        if (fingerprint == null || fingerprint.length == 0) throw new IllegalArgumentException("Fingerprint must not be empty");
        if (revision < 0) throw new IllegalArgumentException("Revision must not be negative");
        fingerprint = fingerprint.clone();
    }

    /**
     * @return the block key of the root
     */
    public BlockKey key() {
        return key;
    }

    /**
     * @return a defensive copy of the fingerprint
     */
    public byte[] fingerprint() {
        return fingerprint.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RootIdentity that)) return false;
        return revision == that.revision &&
                Objects.equals(key, that.key) &&
                Objects.equals(blockType, that.blockType) &&
                Arrays.equals(fingerprint, that.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, blockType, revision, Arrays.hashCode(fingerprint));
    }
}
