package dev.jlo.kitsune.model;

import java.util.*;

public record ItemPath(List<ItemPathStep> steps) {
    public ItemPath {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("Steps must not be empty");
        steps = List.copyOf(steps);
        for (var step : steps) {
            Objects.requireNonNull(step, "Step must not be null");
        }
    }

    public List<ItemPathStep> steps() {
        return steps;
    }
}
