package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

public final class SemanticDescriptorHash {
    private static final int SHA_256_BYTES = 32;

    private final byte[] bytes;

    private SemanticDescriptorHash(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    public static SemanticDescriptorHash of(ItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SemanticDescriptorHash(digest.digest(DescriptorCodec.encodeSemantic(descriptor)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    public static SemanticDescriptorHash ofBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "Hash bytes must not be null");
        if (bytes.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("Descriptor hash must be 32 bytes");
        }
        return new SemanticDescriptorHash(bytes);
    }

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
