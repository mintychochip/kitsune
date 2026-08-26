package dev.jlo.kitsune.index;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Verifies root fingerprints carry an index-format marker that forces legacy roots to rebuild. */
class ContainerSnapshotterFingerprintTest {

    @Test
    void rootContentDigestIncludesHoverPayloadFormatMarker() throws Exception {
        byte[] content = "same-container-content".getBytes(StandardCharsets.UTF_8);
        MessageDigest legacy = MessageDigest.getInstance("SHA-256");
        legacy.update(content);

        MessageDigest current = ContainerSnapshotter.contentDigest();
        current.update(content);
        byte[] currentFingerprint = current.digest();

        assertFalse(Arrays.equals(legacy.digest(), currentFingerprint));

        MessageDigest repeated = ContainerSnapshotter.contentDigest();
        repeated.update(content);
        assertArrayEquals(currentFingerprint, repeated.digest());
    }
}
