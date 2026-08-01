package dev.jlo.kitsune.index;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class IndexWorker implements AutoCloseable {
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().name("kitsune-index").unstarted(task));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final IndexRepository repository;
    private final Queue<CompletableFuture<?>> submitted = new ConcurrentLinkedQueue<>();
    private CompletableFuture<Void> closeDone;
    private boolean repositoryCloseStarted;

    public IndexWorker(IndexRepository repository) {
        this.repository = Objects.requireNonNull(repository, "Repository");
    }

    public <T> CompletableFuture<T> submit(CheckedSupplier<T> operation) {
        Objects.requireNonNull(operation, "Operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(operation.get());
            } catch (Exception exception) {
                future.completeExceptionally(new CompletionException(exception));
            } catch (Error error) {
                future.completeExceptionally(error);
                throw error;
            } finally {
                submitted.remove(future);
            }
        };

        synchronized (this) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Index worker is closed"));
            }
            submitted.add(future);
            try {
                executor.execute(task);
            } catch (RuntimeException rejection) {
                submitted.remove(future);
                future.completeExceptionally(rejection);
            }
        }
        return future;
    }

    @Override
    public void close() throws Exception {
        close(5, TimeUnit.SECONDS);
    }

    void close(long timeout, TimeUnit unit) throws Exception {
        Objects.requireNonNull(unit, "Time unit");
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }

        synchronized (this) {
            if (closed.compareAndSet(false, true)) {
                executor.shutdown();
            }
        }

        InterruptedException interruption = null;
        boolean terminated = false;
        try {
            terminated = executor.awaitTermination(timeout, unit);
        } catch (InterruptedException interrupted) {
            interruption = interrupted;
        }

        if (!terminated) {
            cancelAll();
            executor.shutdownNow();
            AwaitResult forced = awaitTermination(timeout, unit, interruption);
            terminated = forced.terminated();
            interruption = forced.interruption();
        }

        Throwable failure;
        if (terminated) {
            CloseResult closeResult = awaitRepositoryClose(
                startRepositoryClose(),
                timeout,
                unit,
                interruption
            );
            failure = closeResult.failure();
            interruption = closeResult.interruption();
        } else {
            failure = new IllegalStateException(
                "Index worker did not terminate"
            );
        }

        if (interruption != null) {
            if (failure != null) {
                interruption.addSuppressed(failure);
            }
            Thread.currentThread().interrupt();
            throw interruption;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException(
                "Repository close failed",
                failure
            );
        }
    }

    private synchronized CompletableFuture<Void> startRepositoryClose() {
        if (closeDone == null) {
            closeDone = new CompletableFuture<>();
        }
        if (repositoryCloseStarted) return closeDone;
        repositoryCloseStarted = true;

        Thread.ofPlatform().name("kitsune-index").start(() -> {
            try {
                repository.close();
                closeDone.complete(null);
            } catch (Throwable failure) {
                closeDone.completeExceptionally(failure);
            }
        });
        return closeDone;
    }

    private static CloseResult awaitRepositoryClose(
        CompletableFuture<Void> close,
        long timeout,
        TimeUnit unit,
        InterruptedException priorInterruption
    ) {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        InterruptedException interruption = priorInterruption;

        while (true) {
            if (remainingNanos <= 0L && !close.isDone()) {
                return new CloseResult(
                    new java.util.concurrent.TimeoutException(
                        "Repository close timed out"
                    ),
                    interruption
                );
            }
            try {
                close.get(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS);
                return new CloseResult(null, interruption);
            } catch (InterruptedException interrupted) {
                if (interruption == null) {
                    interruption = interrupted;
                } else {
                    interruption.addSuppressed(interrupted);
                }
            } catch (ExecutionException failed) {
                return new CloseResult(failed.getCause(), interruption);
            } catch (java.util.concurrent.TimeoutException timedOut) {
                return new CloseResult(timedOut, interruption);
            }
            remainingNanos = deadline - System.nanoTime();
        }
    }

    private AwaitResult awaitTermination(
            long timeout, TimeUnit unit, InterruptedException priorInterruption) {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        InterruptedException interruption = priorInterruption;

        while (!executor.isTerminated()) {
            if (remainingNanos <= 0) {
                break;
            }
            try {
                if (executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException interrupted) {
                if (interruption == null) {
                    interruption = interrupted;
                } else {
                    interruption.addSuppressed(interrupted);
                }
            }
            remainingNanos = deadline - System.nanoTime();
        }
        return new AwaitResult(executor.isTerminated(), interruption);
    }

    private void cancelAll() {
        for (CompletableFuture<?> future : new ArrayList<>(submitted)) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private record CloseResult(
        Throwable failure,
        InterruptedException interruption
    ) {}

    private record AwaitResult(
            boolean terminated, InterruptedException interruption) {}
}
