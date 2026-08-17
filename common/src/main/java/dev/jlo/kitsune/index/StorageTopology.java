package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes the set of storage roots whose index entries depend on a changed
 * block, including its orthogonal neighbors.
 */
final class StorageTopology {
    private StorageTopology() {}

    /**
     * Returns the root and its horizontal neighbors as the affected root set.
     *
     * @param changed changed block key
     * @return immutable list of the changed root and its valid neighbors
     */
    static List<BlockKey> affectedRoots(BlockKey changed) {
        Objects.requireNonNull(changed, "Changed root must not be null");
        List<BlockKey> roots = new ArrayList<>(5);
        roots.add(changed);
        addOffset(roots, changed, -1, 0);
        addOffset(roots, changed, 1, 0);
        addOffset(roots, changed, 0, -1);
        addOffset(roots, changed, 0, 1);
        return List.copyOf(roots);
    }

    private static void addOffset(
        List<BlockKey> roots,
        BlockKey changed,
        int xOffset,
        int zOffset
    ) {
        try {
            roots.add(new BlockKey(
                changed.worldId(),
                Math.addExact(changed.x(), xOffset),
                changed.y(),
                Math.addExact(changed.z(), zOffset)
            ));
        } catch (ArithmeticException coordinateOverflow) {
            // The block coordinate has no representable neighbor in this direction.
        }
    }
}
