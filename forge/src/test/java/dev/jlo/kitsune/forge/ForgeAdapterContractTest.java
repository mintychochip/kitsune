package dev.jlo.kitsune.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ForgeAdapterContractTest {
    @Test
    void exposesForgeEntrypointAndRuntimeAdapters() {
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.forge.ForgeMod"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.forge.ForgeRuntime"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.forge.ForgeItemAccess"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.forge.ForgeWorldAccess"));
    }
}
