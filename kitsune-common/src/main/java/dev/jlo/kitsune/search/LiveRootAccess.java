package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.RootIdentity;

import java.util.Set;

/** Reads live chunk and root visibility from the server. */
public interface LiveRootAccess {
    /** Returns loaded chunks within the search radius. */
    Set<ChunkKey> loadedChunks(SearchContext context, int radius);

    /** Validates a candidate root and returns its accessible distance, or null. */
    AllowedRoot validate(SearchContext context, RootIdentity identity, int radius);

    /**
     * Validates a candidate and exposes its current snapshot when supported.
     *
     * <p>Implementations without snapshot support retain the indexed hover
     * payload by returning an empty draft.
     */
    default LiveRootSnapshot validateSnapshot(
        SearchContext context,
        RootIdentity identity,
        int radius
    ) {
        AllowedRoot allowed = validate(context, identity, radius);
        return allowed == null
            ? null
            : new LiveRootSnapshot(allowed, java.util.Optional.empty());
    }
}
