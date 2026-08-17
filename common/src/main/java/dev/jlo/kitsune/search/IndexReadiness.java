package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.ChunkKey;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Provides asynchronous readiness checks for indexed chunks. */
public interface IndexReadiness {
    /** Waits until the requested chunks are ready or the timeout expires. */
    CompletionStage<Void> awaitReady(Set<ChunkKey> chunks, Duration timeout);
}
