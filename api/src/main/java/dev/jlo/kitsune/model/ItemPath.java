package dev.jlo.kitsune.model;

import java.util.*;

/**
 * An ordered path of steps locating an item within its root container.
 *
 * @param steps non-empty ordered steps from the container to the item
 */
public record ItemPath(List<ItemPathStep> steps) {
    public ItemPath {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("Steps must not be empty");
        steps = List.copyOf(steps);
        for (var step : steps) {
            Objects.requireNonNull(step, "Step must not be null");
        }
    }

    /**
     * @return an immutable copy of the path steps
     */
    public List<ItemPathStep> steps() {
        return steps;
    }
}
