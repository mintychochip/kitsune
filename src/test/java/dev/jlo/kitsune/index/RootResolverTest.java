package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootResolverTest {

    private static final UUID WORLD_ID = new UUID(0L, 1L);

    @Test
    void canonicalDoubleChestUsesLexicographicCoordinates() {
        BlockKey west = key(0, 64, 0);
        BlockKey east = key(1, 64, 0);
        BlockKey lowerY = key(0, 10, 100);
        BlockKey higherY = key(0, 20, 1);

        assertEquals(west, RootResolver.canonicalDoubleChest(east, west));
        assertEquals(west, RootResolver.canonicalDoubleChest(west, east));
        assertEquals(lowerY, RootResolver.canonicalDoubleChest(higherY, lowerY));
        assertEquals(lowerY, RootResolver.canonicalDoubleChest(lowerY, higherY));
    }

    @Test
    void canonicalDoubleChestRejectsDifferentWorldPairs() {
        BlockKey firstWorld = key(WORLD_ID, 0, 64, 0);
        BlockKey secondWorld = key(new UUID(0L, 2L), 0, 64, 0);

        assertThrows(IllegalArgumentException.class, () -> RootResolver.canonicalDoubleChest(firstWorld, secondWorld));
    }

    @Test
    void resolvePersistentSingleRootDirectly() {
        BlockKey root = key(0, 64, 0);
        RootResolver.RootProbe<String> probe = probe(root, "minecraft:chest", "single-inventory", true, false, null, false);
        FakeLiveAccess access = new FakeLiveAccess(probe);
        RootResolver<String> resolver = new RootResolver<>(access);

        RootResolver.Resolution<String> resolution = resolver.resolve(root);

        assertEquals(RootResolver.Status.RESOLVED, resolution.status());
        assertEquals(root, resolution.key());
        assertEquals("minecraft:chest", resolution.blockType());
        assertEquals("single-inventory", resolution.logicalInventory());
        assertEquals(List.of(root), access.inspected());
    }

    @Test
    void unresolvedLootIsExplicitlyUnresolved() {
        BlockKey root = key(1, 64, 0);
        RootResolver.RootProbe<String> probe = probe(root, "minecraft:chest", null, true, true, null, false);
        FakeLiveAccess access = new FakeLiveAccess(probe);
        RootResolver<String> resolver = new RootResolver<>(access);

        RootResolver.Resolution<String> resolution = resolver.resolve(root);

        assertEquals(RootResolver.Status.UNRESOLVED_LOOT, resolution.status());
        assertNull(resolution.key());
        assertNull(resolution.blockType());
        assertNull(resolution.logicalInventory());
    }

    @Test
    void unsupportedStateReturnsUnsupportedOutcome() {
        BlockKey root = key(2, 64, 0);
        RootResolver.RootProbe<String> probe = probe(root, "minecraft:ender_chest", null, false, false, null, false);
        FakeLiveAccess access = new FakeLiveAccess(probe);
        RootResolver<String> resolver = new RootResolver<>(access);

        RootResolver.Resolution<String> resolution = resolver.resolve(root);

        assertEquals(RootResolver.Status.UNSUPPORTED, resolution.status());
        assertNull(resolution.key());
        assertNull(resolution.blockType());
        assertNull(resolution.logicalInventory());
    }

    @Test
    void connectedDoubleChestWithUnloadedHalfIsUnavailable() {
        BlockKey west = key(3, 64, 0);
        BlockKey east = key(4, 64, 0);
        RootResolver.RootProbe<String> westProbe = probe(west, "minecraft:chest", "left-inventory", true, false, east, false);
        RootResolver.RootProbe<String> eastProbe = probe(east, "minecraft:chest", "right-inventory", true, false, west, true);
        FakeLiveAccess access = new FakeLiveAccess(westProbe, eastProbe);
        RootResolver<String> resolver = new RootResolver<>(access);

        RootResolver.Resolution<String> resolution = resolver.resolve(west);

        assertEquals(RootResolver.Status.UNAVAILABLE, resolution.status());
        assertNull(resolution.key());
        assertNull(resolution.blockType());
        assertNull(resolution.logicalInventory());
        assertTrue(access.inspected().size() >= 1, "resolver must drive the fake through inspect-only access");
        assertEquals(west, RootResolver.canonicalDoubleChest(west, east));
    }

    @Test
    void persistentRootWithoutLogicalInventoryIsUnavailable() {
        BlockKey root = key(5, 64, 0);
        RootResolver<String> resolver = new RootResolver<>(
            new FakeLiveAccess(
                probe(
                    root,
                    "minecraft:barrel",
                    null,
                    true,
                    false,
                    null,
                    false
                )
            )
        );

        RootResolver.Resolution<String> resolution = resolver.resolve(root);

        assertEquals(RootResolver.Status.UNAVAILABLE, resolution.status());
        assertNull(resolution.logicalInventory());
    }

    @Test
    void eitherDoubleChestHalfResolvesToCanonicalLogicalInventory() {
        BlockKey west = key(6, 64, 0);
        BlockKey east = key(7, 64, 0);
        RootResolver.RootProbe<String> westProbe = probe(
            west,
            "minecraft:chest",
            "whole-inventory",
            true,
            false,
            east,
            true
        );
        RootResolver.RootProbe<String> eastProbe = probe(
            east,
            "minecraft:chest",
            "whole-inventory",
            true,
            false,
            west,
            true
        );
        RootResolver<String> resolver = new RootResolver<>(
            new FakeLiveAccess(westProbe, eastProbe)
        );

        RootResolver.Resolution<String> fromWest = resolver.resolve(west);
        RootResolver.Resolution<String> fromEast = resolver.resolve(east);

        assertEquals(RootResolver.Status.RESOLVED, fromWest.status());
        assertEquals(west, fromWest.key());
        assertEquals("whole-inventory", fromWest.logicalInventory());
        assertEquals(fromWest, fromEast);
    }

    @Test
    void unresolvedConnectedHalfKeepsDoubleChestUnindexed() {
        BlockKey west = key(8, 64, 0);
        BlockKey east = key(9, 64, 0);
        RootResolver<String> resolver = new RootResolver<>(
            new FakeLiveAccess(
                probe(
                    west,
                    "minecraft:chest",
                    "whole-inventory",
                    true,
                    false,
                    east,
                    true
                ),
                probe(
                    east,
                    "minecraft:chest",
                    null,
                    true,
                    true,
                    west,
                    true
                )
            )
        );

        RootResolver.Resolution<String> resolution = resolver.resolve(west);

        assertEquals(
            RootResolver.Status.UNRESOLVED_LOOT,
            resolution.status()
        );
        assertNull(resolution.key());
    }

    private static RootResolver.RootProbe<String> probe(
            BlockKey key,
            String blockType,
            String logicalInventory,
            boolean persistentBlockInventory,
            boolean unresolvedLoot,
            BlockKey connectedHalf,
            boolean connectedHalfLoaded) {
        return new RootResolver.RootProbe<>(
                key,
                blockType,
                logicalInventory,
                persistentBlockInventory,
                unresolvedLoot,
                connectedHalf,
                connectedHalfLoaded);
    }

    private static BlockKey key(int x, int y, int z) {
        return key(WORLD_ID, x, y, z);
    }

    private static BlockKey key(UUID worldId, int x, int y, int z) {
        return new BlockKey(worldId, x, y, z);
    }

    private static final class FakeLiveAccess implements RootResolver.LiveAccess<String> {
        private final Map<BlockKey, RootResolver.RootProbe<String>> probes;
        private final List<BlockKey> inspected = new ArrayList<>();

        @SafeVarargs
        FakeLiveAccess(RootResolver.RootProbe<String>... probes) {
            this.probes = new HashMap<>();
            for (RootResolver.RootProbe<String> probe : probes) {
                this.probes.put(probe.key(), probe);
            }
        }

        @Override
        public RootResolver.RootProbe<String> inspect(BlockKey key) {
            inspected.add(key);
            return probes.get(key);
        }

        List<BlockKey> inspected() {
            return List.copyOf(inspected);
        }
    }
}
