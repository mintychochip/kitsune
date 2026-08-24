package org.aincraft.kitsune.embedding;

/**
 * Callback interface for download progress reporting.
 */
public interface DownloadProgressListener {
    void onProgress(String filename, long bytesDownloaded, long totalBytes, int percentComplete);
    default void onStart(String filename, long totalBytes) {}
    default void onComplete(String filename) {}
    default void onError(String filename, Exception error) {}
}
