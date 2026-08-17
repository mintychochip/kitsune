package dev.jlo.kitsune.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Fabric entrypoint, adapter, and configuration contracts. */
class FabricAdapterContractTest {
    @Test
    void exposesFabricEntrypointAndRuntimeAdapters() {
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.fabric.FabricMod"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.fabric.FabricRuntime"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.fabric.FabricItemAccess"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.fabric.FabricWorldAccess"));
    }

    @Test
    void writesAndLoadsNeutralConfiguration(@TempDir Path temporaryDirectory) {
        var config = assertDoesNotThrow(() ->
            FabricConfigLoader.load(temporaryDirectory.resolve("kitsune.properties"))
        );

        assertEquals(32, config.radius());
        assertEquals("builtin:sparse-v1", config.embeddingProvider());
    }

    @Test
    void emptySuccessfulScanIsReady() {
        assertTrue(FabricRuntime.scanIsReady(List.of(), Map.of()));
    }
}
