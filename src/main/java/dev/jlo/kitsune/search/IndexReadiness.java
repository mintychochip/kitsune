package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.ChunkKey;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public interface IndexReadiness {
    CompletionStage<Void> awaitReady(Set<ChunkKey> chunks, Duration timeout);
}
