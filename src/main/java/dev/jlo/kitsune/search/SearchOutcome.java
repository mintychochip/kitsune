package dev.jlo.kitsune.search;

import java.util.List;
import java.util.Objects;

public record SearchOutcome(
    Status status,
    List<RootMatch> roots,
    int totalAccessibleMatchingRoots,
    int totalAccessibleMatchingStacks
) {
    public enum Status {
        SUCCESS,
        NO_MATCHES,
        CANCELED,
        UNSUPPORTED_QUERY,
        INDEX_WARMING,
        FAILURE
    }

    public SearchOutcome {
        Objects.requireNonNull(status, "Status must not be null");
        roots = List.copyOf(Objects.requireNonNull(roots, "Roots must not be null"));
        if (totalAccessibleMatchingRoots < 0) {
            throw new IllegalArgumentException("Total accessible matching roots must not be negative");
        }
        if (totalAccessibleMatchingStacks < 0) {
            throw new IllegalArgumentException("Total accessible matching stacks must not be negative");
        }
        for (RootMatch root : roots) {
            Objects.requireNonNull(root, "Root must not be null");
        }

        if (status == Status.SUCCESS) {
            if (roots.isEmpty()) {
                throw new IllegalArgumentException("A successful search must contain visible roots");
            }
            if (totalAccessibleMatchingRoots < roots.size()) {
                throw new IllegalArgumentException("Root total must include every visible root");
            }
            int visibleStacks = roots.stream().mapToInt(RootMatch::totalMatchingStacks).sum();
            if (totalAccessibleMatchingStacks < visibleStacks) {
                throw new IllegalArgumentException("Stack total must include every visible root");
            }
        } else if (!roots.isEmpty()
            || totalAccessibleMatchingRoots != 0
            || totalAccessibleMatchingStacks != 0) {
            throw new IllegalArgumentException("Non-success outcomes must not contain result data");
        }
    }

    public static SearchOutcome success(
        List<RootMatch> roots,
        int totalAccessibleMatchingRoots,
        int totalAccessibleMatchingStacks
    ) {
        return new SearchOutcome(
            Status.SUCCESS,
            roots,
            totalAccessibleMatchingRoots,
            totalAccessibleMatchingStacks
        );
    }

    public static SearchOutcome noMatches() {
        return empty(Status.NO_MATCHES);
    }

    public static SearchOutcome canceled() {
        return empty(Status.CANCELED);
    }

    public static SearchOutcome unsupportedQuery() {
        return empty(Status.UNSUPPORTED_QUERY);
    }

    public static SearchOutcome indexWarming() {
        return empty(Status.INDEX_WARMING);
    }

    public static SearchOutcome failure() {
        return empty(Status.FAILURE);
    }

    private static SearchOutcome empty(Status status) {
        return new SearchOutcome(status, List.of(), 0, 0);
    }
}
