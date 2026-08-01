package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.RootIdentity;

import java.util.Set;

public interface LiveRootAccess {
    Set<ChunkKey> loadedChunks(SearchContext context, int radius);

    AllowedRoot validate(SearchContext context, RootIdentity identity, int radius);
}
