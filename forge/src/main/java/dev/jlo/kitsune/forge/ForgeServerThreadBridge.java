package dev.jlo.kitsune.forge;

import dev.jlo.kitsune.search.ServerThreadBridge;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public final class ForgeServerThreadBridge implements ServerThreadBridge {
    private final MinecraftServer server;

    public ForgeServerThreadBridge(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
    }

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

    @Override
    public CompletableFuture<Void> run(Runnable operation) {
        return supply(() -> {
            operation.run();
            return null;
        });
    }
}
