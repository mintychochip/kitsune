package dev.jlo.kitsune.neoforge;

import dev.jlo.kitsune.search.ServerThreadBridge;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches operations onto the Minecraft server thread via completable futures.
 */
public final class NeoForgeServerThreadBridge implements ServerThreadBridge {
    private final MinecraftServer server;

    /**
     * Creates a bridge that schedules operations onto the given server's thread.
     *
     * @param server server whose thread executes operations
     */
    public NeoForgeServerThreadBridge(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

    /**
     * Runs a callable on the server thread, or immediately when already there.
     *
     * @param <T> supplied value type
     * @param operation operation to invoke
     * @return a future completed with the operation result or failure
     */
    @Override
    public <T> CompletableFuture<T> supply(Callable<T> operation) {
        Objects.requireNonNull(operation, "Operation must not be null");
        if (server.isSameThread()) {
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
     * Runs an operation on the server thread, or immediately when already there.
     *
     * @param operation operation to invoke
     * @return a future completed when the operation finishes
     */
    @Override
    public CompletableFuture<Void> run(Runnable operation) {
        return supply(() -> {
            operation.run();
            return null;
        });
    }
}
