package dev.jlo.kitsune.command;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.search.ServerThreadBridge;
import dev.jlo.kitsune.session.SearchSessionManager;
import dev.jlo.kitsune.session.SearchToken;
import dev.jlo.kitsune.ui.RenderedMarker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies how the kitsune command reacts when a server-thread dispatch is
 * rejected before the search session completes.
 */
class KitsuneCommandLifecycleTest {
    /**
     * A rejected server-thread completion clears the associated search session
     * and removes its rendered marker.
     */
    @Test
    void rejectedServerThreadCompletionClearsTheSearchSession() {
        SearchSessionManager sessions = new SearchSessionManager(
            (delay, action) -> () -> {},
            Duration.ofSeconds(20)
        );
        UUID playerId = UUID.randomUUID();
        SearchToken token = sessions.begin(playerId);
        RecordingMarker marker = new RecordingMarker(playerId);
        assertTrue(sessions.attach(token, List.of(marker)));

        KitsuneCommand.dispatchCompletion(
            new RejectingServerThreadBridge(),
            sessions,
            token,
            () -> fail("Rejected dispatch must not run the completion")
        );

        assertFalse(sessions.isCurrent(token));
        assertTrue(marker.removed);
    }

    /** A {@link ServerThreadBridge} whose scheduling operations always fail. */
    private static final class RejectingServerThreadBridge
        implements ServerThreadBridge {
        @Override
        public <T> CompletableFuture<T> supply(Callable<T> operation) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("forced scheduling rejection")
            );
        }

        @Override
        public CompletableFuture<Void> run(Runnable operation) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("forced scheduling rejection")
            );
        }
    }

    /** A {@link RenderedMarker} that records whether it was removed. */
    private static final class RecordingMarker implements RenderedMarker {
        private final UUID id = UUID.randomUUID();
        private final BlockKey root;
        private boolean removed;

        private RecordingMarker(UUID worldId) {
            root = new BlockKey(worldId, 0, 64, 0);
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public BlockKey root() {
            return root;
        }

        @Override
        public void remove() {
            removed = true;
        }
    }
}
