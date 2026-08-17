package dev.jlo.kitsune.forge;

import dev.jlo.kitsune.search.ServerThreadBridge;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Runs operations on the Minecraft server thread, dispatching to it when called off-thread.
 */
public final class ForgeServerThreadBridge implements ServerThreadBridge {
    private final MinecraftServer server;

    /**
     * Creates a bridge onto the supplied server's thread.
     *
     * @param server server whose thread executes dispatched operations
     */
    public ForgeServerThreadBridge(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

    /**
     * Runs a callable on the server thread and returns its result or failure asynchronously.
     *
     * @param operation callable to execute
     * @param <T> result type
     * @return a completed future carrying the result or failure once executed
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
     * Runs a runnable on the server thread and completes once it finishes.
     *
     * @param operation runnable to execute
     * @return a completed future carrying any failure
     */
    @Override
    public CompletableFuture<Void> run(Runnable operation) {
        return supply(() -> {
            operation.run();
            return null;
        });
    }
}
