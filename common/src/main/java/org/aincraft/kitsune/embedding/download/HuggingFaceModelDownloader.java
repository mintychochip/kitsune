package org.aincraft.kitsune.embedding.download;

import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.aincraft.kitsune.Platform;
import org.aincraft.kitsune.embedding.DownloadProgressListener;

/**
 * Downloads ONNX models and tokenizers from Hugging Face using JDK HttpClient.
 * Implements retry logic with exponential backoff, streaming downloads,
 * and progress reporting.
 */
public final class HuggingFaceModelDownloader {
    private static final String HF_BASE_URL = "https://huggingface.co";
    private static final int BASE_DELAY_MS = 1000;
    private static final int MAX_DELAY_MS = 30000;
    private static final int BUFFER_SIZE = 8192;
    private static final String TEMP_SUFFIX = ".downloading";

    private final Platform platform;
    private final HttpClient httpClient;
    private final int maxRetries;
    private final ExecutorService executor;
    private final Path modelsDir;

    public HuggingFaceModelDownloader(Platform platform, HttpClient httpClient, int maxRetries, ExecutorService executor) {
        this.platform = platform;
        this.httpClient = httpClient;
        this.maxRetries = maxRetries;
        this.executor = executor;
        this.modelsDir = platform.getDataFolder().resolve("models");
    }

    /**
     * Downloads a complete model (both model file and tokenizer) asynchronously.
     *
     * @param spec Model specification with HuggingFace repo and paths
     * @param listener Progress listener for download events
     * @return CompletableFuture completing when both files are downloaded
     */
    public CompletableFuture<Void> downloadModel(
        ModelSpec spec,
        DownloadProgressListener listener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create models directory if needed
                Files.createDirectories(modelsDir);
                platform.getLogger().info("Starting download of model: " + spec.modelName());
                return modelsDir;
            } catch (IOException e) {
                platform.getLogger().log(Level.SEVERE, "Failed to create models directory", e);
                throw new CompletionException(e);
            }
        }, executor).thenCompose(dir -> {
            // Download model file
            String modelFileName = spec.modelName() + ".onnx";
            Path modelPath = dir.resolve(modelFileName);

            CompletableFuture<Path> modelDownload = downloadFile(
                spec.huggingFaceRepo(),
                spec.modelPath(),
                modelPath,
                listener
            );

            // Download tokenizer file
            String tokenizerFileName = "tokenizer.json";
            Path tokenizerPath = dir.resolve(tokenizerFileName);

            CompletableFuture<Path> tokenizerDownload = downloadFile(
                spec.huggingFaceRepo(),
                spec.tokenizerPath(),
                tokenizerPath,
                listener
            );

            // Wait for both downloads to complete
            return CompletableFuture.allOf(modelDownload, tokenizerDownload)
                .thenRun(() -> platform.getLogger().info("Successfully downloaded model: " + spec.modelName()));
        });
    }

    /**
     * Downloads a single file from Hugging Face with retry logic.
     *
     * @param repoId Hugging Face repository ID (e.g., "nomic-ai/nomic-embed-text-v1.5")
     * @param filePath Path within the repository (e.g., "onnx/model.onnx")
     * @param destination Destination file path
     * @param listener Progress listener
     * @return CompletableFuture with destination path on success
     */
    public CompletableFuture<Path> downloadFile(
        String repoId,
        String filePath,
        Path destination,
        DownloadProgressListener listener
    ) {
        String url = buildDownloadUrl(repoId, filePath);
        String filename = destination.getFileName().toString();
        platform.getLogger().info("Downloading " + filename + " from " + url);
        return downloadWithRetry(url, destination, filename, listener, 0);
    }

    /**
     * Implements retry logic with exponential backoff.
     * Delay = BASE_DELAY_MS * 2^attempt, capped at MAX_DELAY_MS.
     * Virtual threads efficiently handle Thread.sleep() during backoff delays.
     */
    private CompletableFuture<Path> downloadWithRetry(
        String url,
        Path destination,
        String filename,
        DownloadProgressListener listener,
        int attempt
    ) {
        if (attempt > maxRetries) {
            platform.getLogger().severe("Download failed after " + maxRetries + " retries: " + filename);
            return CompletableFuture.failedFuture(
                new IOException("Download failed after " + maxRetries + " retries")
            );
        }

        // First attempt, no delay
        if (attempt == 0) {
            return doDownload(url, destination, filename, listener)
                .exceptionally(e -> {
                    platform.getLogger().log(Level.WARNING, "Download attempt 1 failed for " + filename, e);
                    listener.onError(filename, (Exception) e);
                    // Chain to retry with backoff
                    throw new CompletionException(e);
                })
                .exceptionallyCompose(ex -> {
                    int nextAttempt = attempt + 1;
                    long delayMs = calculateBackoffDelay(nextAttempt);
                    platform.getLogger().info("Retrying download of " + filename + " in " + delayMs + "ms (attempt " + (nextAttempt + 1) + ")");

                    // Use virtual thread for delay-then-retry, avoiding scheduled executor
                    return CompletableFuture.supplyAsync(
                        () -> sleepAndRetry(url, destination, filename, listener, nextAttempt, delayMs),
                        executor
                    ).thenCompose(future -> future);
                });
        }

        return doDownload(url, destination, filename, listener)
            .exceptionallyCompose(ex -> {
                int nextAttempt = attempt + 1;
                if (nextAttempt > maxRetries) {
                    platform.getLogger().severe("Download failed after " + maxRetries + " retries: " + filename);
                    return CompletableFuture.failedFuture(
                        new IOException("Download failed after " + maxRetries + " retries", (Throwable) ex)
                    );
                }

                long delayMs = calculateBackoffDelay(nextAttempt);
                platform.getLogger().info("Retrying download of " + filename + " in " + delayMs + "ms (attempt " + (nextAttempt + 1) + ")");
                listener.onError(filename, (Exception) ex);

                // Use virtual thread for delay-then-retry, avoiding scheduled executor
                return CompletableFuture.supplyAsync(
                    () -> sleepAndRetry(url, destination, filename, listener, nextAttempt, delayMs),
                    executor
                ).thenCompose(future -> future);
            });
    }

    /**
     * Helper to sleep in a virtual thread, then retry download.
     * Virtual threads handle blocking sleep efficiently without consuming platform threads.
     */
    private CompletableFuture<Path> sleepAndRetry(
        String url,
        Path destination,
        String filename,
        DownloadProgressListener listener,
        int attempt,
        long delayMs
    ) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            platform.getLogger().log(Level.WARNING, "Sleep interrupted during backoff for " + filename, e);
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
        return downloadWithRetry(url, destination, filename, listener, attempt);
    }

    /**
     * Performs the actual download with streaming and progress reporting.
     * Uses temp file with atomic move on success.
     */
    private CompletableFuture<Path> doDownload(
        String url,
        Path destination,
        String filename,
        DownloadProgressListener listener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Path tempFile = destination.resolveSibling(destination.getFileName() + TEMP_SUFFIX);

            try {
                // Build HTTP request
                HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                    .GET()
                    .build();

                // Send request and get response with InputStream body
                HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
                );

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }

                InputStream body = response.body();
                if (body == null) {
                    throw new IOException("Empty response body");
                }

                // Get content-length from response headers
                long totalBytes = response.headers()
                    .firstValueAsLong("content-length")
                    .orElse(-1L);

                // Notify listener of start
                listener.onStart(filename, totalBytes);

                // Stream to temp file
                long downloaded = 0;
                try (InputStream in = body;
                     OutputStream out = new FileOutputStream(tempFile.toFile())) {

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloaded += bytesRead;

                        // Calculate progress
                        int percentComplete = totalBytes > 0
                            ? (int) ((downloaded * 100) / totalBytes)
                            : 0;

                        listener.onProgress(filename, downloaded, totalBytes, percentComplete);
                    }
                }

                // Atomic move from temp to destination
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);

                platform.getLogger().info("Successfully downloaded " + filename);
                listener.onComplete(filename);

                return destination;
            } catch (Exception e) {
                // Clean up temp file on failure
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanupError) {
                    platform.getLogger().log(Level.WARNING, "Failed to clean up temp file: " + tempFile, cleanupError);
                }

                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Calculates exponential backoff delay: BASE_DELAY_MS * 2^attempt, capped at MAX_DELAY_MS.
     */
    private long calculateBackoffDelay(int attempt) {
        // Prevent overflow: cap at reasonable maximum
        if (attempt >= 30) {
            return MAX_DELAY_MS;
        }

        long delayMs = BASE_DELAY_MS * (1L << attempt); // 2^attempt
        return Math.min(delayMs, MAX_DELAY_MS);
    }

    /**
     * Builds the complete Hugging Face download URL.
     * Format: https://huggingface.co/{repo}/resolve/main/{path}
     */
    public static String buildDownloadUrl(String repoId, String filename) {
        return String.format("%s/%s/resolve/main/%s", HF_BASE_URL, repoId, filename);
    }

}
