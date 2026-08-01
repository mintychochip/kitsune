package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageTopologyTest {
    private static final UUID WORLD_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void mutationRescansTheChangedBlockAndHorizontalNeighbors() {
        BlockKey changed = new BlockKey(WORLD_ID, 4, 64, -2);

        assertEquals(
            List.of(
                changed,
                new BlockKey(WORLD_ID, 3, 64, -2),
                new BlockKey(WORLD_ID, 5, 64, -2),
                new BlockKey(WORLD_ID, 4, 64, -3),
                new BlockKey(WORLD_ID, 4, 64, -1)
            ),
            StorageTopology.affectedRoots(changed)
        );
    }

    @Test
    void coordinateOverflowDoesNotWrapToAnUnrelatedRoot() {
        BlockKey changed = new BlockKey(
            WORLD_ID,
            Integer.MAX_VALUE,
            64,
            Integer.MIN_VALUE
        );

        assertEquals(
            List.of(
                changed,
                new BlockKey(WORLD_ID, Integer.MAX_VALUE - 1, 64, Integer.MIN_VALUE),
                new BlockKey(WORLD_ID, Integer.MAX_VALUE, 64, Integer.MIN_VALUE + 1)
            ),
            StorageTopology.affectedRoots(changed)
        );
    }
}
