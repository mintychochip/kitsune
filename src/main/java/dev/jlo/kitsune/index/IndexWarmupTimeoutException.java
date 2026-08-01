package dev.jlo.kitsune.index;

import java.time.Duration;

public final class IndexWarmupTimeoutException extends RuntimeException {
    public IndexWarmupTimeoutException() {
        super("Index warmup did not complete in time");
    }

    public IndexWarmupTimeoutException(String message) {
        super(message);
    }

    public IndexWarmupTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public IndexWarmupTimeoutException(Throwable cause) {
        super(cause);
    }

    public static IndexWarmupTimeoutException of(Duration timeout) {
        return new IndexWarmupTimeoutException("Index warmup timed out after " + timeout);
    }
}
