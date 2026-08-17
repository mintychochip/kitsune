package dev.jlo.kitsune.search;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

/**
 * Bridges asynchronous work to the server thread.
 */
public interface ServerThreadBridge {
    /**
     * Schedules a value-producing operation on the server thread.
     *
     * @param operation operation to execute
     * @param <T> produced value type
     * @return a future completed with the operation result
     */
    <T> CompletableFuture<T> supply(Callable<T> operation);

    /**
     * Schedules an action on the server thread.
     *
     * @param operation action to execute
     * @return a future completed when the action finishes
     */
    CompletableFuture<Void> run(Runnable operation);
}
