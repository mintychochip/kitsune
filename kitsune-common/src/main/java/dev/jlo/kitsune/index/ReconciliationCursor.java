package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Iterates through roots in stable world and coordinate order. */
public final class ReconciliationCursor {
    private static final Comparator<BlockKey> WORLD_X_Y_Z = (left, right) -> {
        int worldCompare = left.worldId().toString().compareTo(right.worldId().toString());
        if (worldCompare != 0) return worldCompare;
        int x = Integer.compare(left.x(), right.x());
        if (x != 0) return x;
        int y = Integer.compare(left.y(), right.y());
        if (y != 0) return y;
        return Integer.compare(left.z(), right.z());
    };

    private final List<BlockKey> roots;
    private int index;

    /** Creates a cursor over a defensive, sorted root collection. */
    public ReconciliationCursor(Collection<BlockKey> roots) {
        Objects.requireNonNull(roots, "Roots must not be null");
        List<BlockKey> copy = new ArrayList<>(roots.size());
        for (BlockKey root : roots) {
            copy.add(Objects.requireNonNull(root, "Root must not be null"));
        }
        copy.sort(WORLD_X_Y_Z);
        this.roots = List.copyOf(copy);
        this.index = 0;
    }

    /** Returns whether all roots have been returned. */
    public boolean complete() {
        return index >= roots.size();
    }

    /** Returns up to the requested number of remaining roots. */
    public List<BlockKey> next(int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("Budget must be positive");
        }
        if (complete()) {
            return List.of();
        }
        int end = index + Math.min(budget, roots.size() - index);
        List<BlockKey> slice = roots.subList(index, end);
        index = end;
        return Collections.unmodifiableList(new ArrayList<>(slice));
    }
}
