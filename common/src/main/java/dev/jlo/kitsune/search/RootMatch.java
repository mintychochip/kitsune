package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.RootIdentity;

import java.util.List;
import java.util.Objects;

/** Aggregates matching items and ranking data for one root. */
public record RootMatch(
    RootIdentity identity,
    double distance,
    double bestScore,
    int totalMatchingStacks,
    List<ItemMatch> itemMatches
) {
    /** Validates ranking data and defensively copies item matches. */
    public RootMatch {
        Objects.requireNonNull(identity, "Identity must not be null");
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Distance must be a finite non-negative number");
        }
        if (!Double.isFinite(bestScore) || bestScore < 0 || bestScore > 1) {
            throw new IllegalArgumentException("Best score must be a finite number between 0 and 1");
        }
        if (totalMatchingStacks < 1) {
            throw new IllegalArgumentException("Total matching stacks must be positive");
        }
        itemMatches = List.copyOf(Objects.requireNonNull(itemMatches, "Item matches must not be null"));
        if (itemMatches.isEmpty()) {
            throw new IllegalArgumentException("A root match must expose at least one item match");
        }
        if (totalMatchingStacks < itemMatches.size()) {
            throw new IllegalArgumentException("Stack total must include every visible item match");
        }
        for (ItemMatch match : itemMatches) {
            Objects.requireNonNull(match, "Match must not be null");
        }
    }

    /** Returns the block key of this root. */
    public BlockKey key() {
        return identity.key();
    }
}
