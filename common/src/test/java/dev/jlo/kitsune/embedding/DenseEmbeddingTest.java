package dev.jlo.kitsune.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies {@link DenseEmbedding} encode/decode round-tripping, norms, cosine similarity, and validation. */
final class DenseEmbeddingTest {
    @Test
    void roundTripsFiniteComponentsAndNorm() {
        DenseEmbedding embedding = new DenseEmbedding("test", 1, new float[] {3.0f, 4.0f});

        DenseEmbedding decoded = DenseEmbedding.decode(
            "test",
            1,
            embedding.encode(),
            embedding.norm()
        );

        assertEquals(5.0, decoded.norm(), 1e-12);
        assertEquals(1.0, embedding.cosine(decoded), 1e-6);
        assertArrayEquals(embedding.encode(), decoded.encode());
    }

    @Test
    void returnsNegativeCosineForOppositeVectors() {
        DenseEmbedding first = new DenseEmbedding("test", 1, new float[] {1.0f, 0.0f});
        DenseEmbedding opposite = new DenseEmbedding("test", 1, new float[] {-1.0f, 0.0f});

        assertEquals(-1.0, first.cosine(opposite), 1e-6);
    }

    @Test
    void rejectsMismatchedDimensions() {
        DenseEmbedding first = new DenseEmbedding("test", 1, new float[] {1.0f, 0.0f});
        DenseEmbedding other = new DenseEmbedding("test", 1, new float[] {1.0f, 0.0f, 0.0f});

        assertThrows(IllegalArgumentException.class, () -> first.cosine(other));
    }

    @Test
    void rejectsCorruptPayloadAndNormMismatch() {
        DenseEmbedding embedding = new DenseEmbedding("test", 1, new float[] {1.0f, 2.0f});
        byte[] corrupt = embedding.encode();
        corrupt[0] ^= 1;

        assertThrows(
            IllegalArgumentException.class,
            () -> DenseEmbedding.decode("test", 1, corrupt, embedding.norm())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DenseEmbedding.decode("test", 1, embedding.encode(), embedding.norm() + 1.0)
        );
    }

    @Test
    void zeroNormCosineIsZero() {
        DenseEmbedding zero = new DenseEmbedding("test", 1, new float[] {0.0f, 0.0f});
        DenseEmbedding nonZero = new DenseEmbedding("test", 1, new float[] {1.0f, 0.0f});

        assertEquals(0.0, zero.cosine(nonZero));
    }
}
