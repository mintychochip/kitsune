package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** SHA-256 hash of an item descriptor's semantic content. */
public final class SemanticDescriptorHash {
    private static final int SHA_256_BYTES = 32;

    private final byte[] bytes;

    private SemanticDescriptorHash(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    /** Creates a hash from the descriptor fields that participate in semantic identity.
     *
     * @param descriptor descriptor to hash
     * @return the resulting SHA-256 hash
     * @throws NullPointerException if {@code descriptor} is {@code null}
     */
    public static SemanticDescriptorHash of(ItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SemanticDescriptorHash(digest.digest(DescriptorCodec.encodeSemantic(descriptor)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    /** Reconstructs a hash from its 32-byte representation.
     *
     * @param bytes SHA-256 bytes
     * @return the reconstructed hash
     * @throws NullPointerException if {@code bytes} is {@code null}
     * @throws IllegalArgumentException if {@code bytes} is not 32 bytes long
     */
    public static SemanticDescriptorHash ofBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "Hash bytes must not be null");
        if (bytes.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("Descriptor hash must be 32 bytes");
        }
        return new SemanticDescriptorHash(bytes);
    }

    /** Returns a defensive copy of the hash bytes.
     *
     * @return the SHA-256 bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SemanticDescriptorHash that)) return false;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
