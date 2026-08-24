package dev.jlo.kitsune.index;

import java.time.Duration;

/** Signals that indexed data did not become ready before a deadline. */
public final class IndexWarmupTimeoutException extends RuntimeException {
    /** Creates an exception with the default message. */
    public IndexWarmupTimeoutException() {
        super("Index warmup did not complete in time");
    }

    /** Creates an exception with a detail message. */
    public IndexWarmupTimeoutException(String message) {
        super(message);
    }

    /** Creates an exception with a message and cause. */
    public IndexWarmupTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates an exception with a cause. */
    public IndexWarmupTimeoutException(Throwable cause) {
        super(cause);
    }

    /** Creates an exception describing the supplied timeout. */
    public static IndexWarmupTimeoutException of(Duration timeout) {
        return new IndexWarmupTimeoutException("Index warmup timed out after " + timeout);
    }
}
