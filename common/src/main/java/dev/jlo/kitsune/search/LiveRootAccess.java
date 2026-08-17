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
}
