package dev.jlo.kitsune.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

public interface ServerThreadBridge {
    <T> CompletableFuture<T> supply(Callable<T> operation);

    CompletableFuture<Void> run(Runnable operation);
}
