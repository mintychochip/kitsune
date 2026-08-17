package dev.jlo.kitsune.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Contracts that the Bukkit bridge continues to expose the runtime and index
 * resolver types expected by the platform.
 */
class BukkitPlatformBoundaryTest {
    /**
     * Ensures the shared runtime and root resolver classes are loadable.
     */
    @Test
    void sharedRuntimeIsExposedByTheBukkitBridge() {
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.bukkit.BukkitRuntime"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.bukkit.index.BukkitRootResolver"));
    }
}
