package dev.jlo.kitsune.neoforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies that the NeoForge entry point and runtime adapters remain loadable.
 */
class NeoForgeAdapterContractTest {
    /**
     * Ensures the adapter classes exposed by the NeoForge module are present.
     */
    @Test
    void exposesNeoForgeEntrypointAndRuntimeAdapters() {
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.neoforge.NeoForgeMod"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.neoforge.NeoForgeRuntime"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.neoforge.NeoForgeItemAccess"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.neoforge.NeoForgeWorldAccess"));
    }
}
