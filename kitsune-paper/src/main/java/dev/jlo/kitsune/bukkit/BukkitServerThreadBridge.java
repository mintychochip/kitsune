package dev.jlo.kitsune.bukkit;

import dev.jlo.kitsune.search.ServerThreadBridge;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * Dispatches operations to the Bukkit primary server thread.
 */
public final class BukkitServerThreadBridge implements ServerThreadBridge {

    private final Plugin plugin;
    private final Server server;

    /**
     * Creates a bridge for the server owned by a plugin.
     *
     * @param plugin plugin whose scheduler executes dispatched operations
     */
    public BukkitServerThreadBridge(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin must not be null");
        this.server = plugin.getServer();
    }

    /**
     * Supplies a value on the primary thread, or immediately when already there.
     *
     * @param <T> supplied value type
     * @param operation operation to invoke
     * @return a future completed with the operation result or failure
     */
    @Override
    public <T> CompletableFuture<T> supply(Callable<T> operation) {
        Objects.requireNonNull(operation, "Operation must not be null");

        if (server.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(operation.call());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        schedule(() -> {
            try {
                result.complete(operation.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, result);
        return result;
    }

    /**
     * Runs an operation on the primary thread, or immediately when already there.
     *
     * @param operation operation to invoke
     * @return a future completed when the operation finishes
     */
    @Override
    public CompletableFuture<Void> run(Runnable operation) {
        Objects.requireNonNull(operation, "Operation must not be null");

        if (server.isPrimaryThread()) {
            try {
                operation.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        schedule(() -> {
            try {
                operation.run();
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, result);
        return result;
    }

    private void schedule(Runnable operation, CompletableFuture<?> result) {
        try {
            server.getScheduler().runTask(plugin, operation);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
