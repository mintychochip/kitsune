package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.search.ServerThreadBridge;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ServerThreadBridge} implementation that executes operations on the
 * Minecraft server thread, completing futures asynchronously.
 *
 * <p>If the current thread is already the server thread the operation runs
 * inline; otherwise it is enqueued via the server's task executor. Failures
 * from the operation complete the returned future exceptionally.
 */
public final class FabricServerThreadBridge implements ServerThreadBridge {
    private final MinecraftServer server;

    /**
     * Creates a bridge bound to the given server.
     *
     * @param server server whose thread executes bridged operations
     */
    public FabricServerThreadBridge(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

    /**
     * Runs the operation on the server thread and returns its result.
     *
     * @param operation operation to run
     * @param <T> operation result type
     * @return future completing with the operation's value or failure
     */
    @Override
    public <T> CompletableFuture<T> supply(Callable<T> operation) {
        Objects.requireNonNull(operation, "Operation must not be null");
        if (server.isOnThread()) {
            try {
                return CompletableFuture.completedFuture(operation.call());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    result.complete(operation.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * Runs the operation on the server thread and completes when it finishes.
     *
     * @param operation operation to run
     * @return future completing when the operation completes
     */
    @Override
    public CompletableFuture<Void> run(Runnable operation) {
        return supply(() -> {
            operation.run();
            return null;
        });
    }
}
