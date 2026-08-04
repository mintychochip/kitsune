package dev.jlo.kitsune.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BukkitPlatformBoundaryTest {
    @Test
    void sharedRuntimeIsExposedByTheBukkitBridge() {
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.bukkit.BukkitRuntime"));
        assertDoesNotThrow(() -> Class.forName("dev.jlo.kitsune.bukkit.index.BukkitRootResolver"));
    }
}
