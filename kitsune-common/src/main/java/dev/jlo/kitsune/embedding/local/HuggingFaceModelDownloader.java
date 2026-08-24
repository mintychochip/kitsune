package dev.jlo.kitsune.embedding.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;

/**
 * Downloads ONNX models and tokenizers from Hugging Face using {@link HttpClient}.
 *
 * <p>Implements retry logic with exponential backoff, streaming downloads, atomic
 * temp-file writes, and optional progress reporting.
 */
public final class HuggingFaceModelDownloader {
    /** Base URL for Hugging Face model downloads. */
    public static final String HF_BASE_URL = "https://huggingface.co";

    private static final int BASE_DELAY_MS = 1_000;
    private static final int MAX_DELAY_MS = 30_000;
    private static final int BUFFER_SIZE = 8_192;
    private static final String TEMP_SUFFIX = ".downloading";

    private final HttpClient httpClient;
    private final Path dataDir;
    private final int maxRetries;

    /**
     * Creates a downloader using a default {@link HttpClient}.
     *
     * @param dataDir directory where model artifacts are stored
     */
    public HuggingFaceModelDownloader(Path dataDir) {
        this(
            dataDir,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
            3
        );
    }

    /**
     * Creates a downloader with the given HTTP client and retry budget.
     *
     * @param dataDir directory where model artifacts are stored
     * @param httpClient HTTP client used for downloads
     * @param maxRetries maximum number of retries after the first attempt
     */
    public HuggingFaceModelDownloader(Path dataDir, HttpClient httpClient, int maxRetries) {
        this.dataDir = Objects.requireNonNull(dataDir, "Data directory must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP client must not be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries must not be negative");
        }
        this.maxRetries = maxRetries;
    }

    /**
     * Downloads the ONNX model, tokenizer, and optional external data file.
     *
     * @param spec model specification
     * @param listener optional progress listener
     */
    public void downloadModel(OnnxModelSpec spec, DownloadProgressListener listener) {
        Objects.requireNonNull(spec, "Model specification must not be null");
        DownloadProgressListener progress = listener == null ? DownloadProgressListener.noop() : listener;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create data directory: " + dataDir, exception);
        }

        downloadFile(
            spec.huggingFaceRepo(),
            spec.modelRepoPath(),
            dataDir.resolve(spec.modelFileName()),
            progress
        );
        downloadFile(
            spec.huggingFaceRepo(),
            spec.tokenizerRepoPath(),
            dataDir.resolve(spec.tokenizerFileName()),
            progress
        );
        if (spec.requiresExternalData()) {
            downloadFile(
                spec.huggingFaceRepo(),
                spec.externalDataRepoPath(),
                dataDir.resolve(spec.externalDataFileName()),
                progress
            );
        }
    }

    /**
     * Downloads a single file from Hugging Face with retry logic.
     *
     * @param repoId Hugging Face repository id
     * @param repoPath path within the repository
     * @param destination destination file path
     * @param listener progress listener
     */
    public void downloadFile(
        String repoId,
        String repoPath,
        Path destination,
        DownloadProgressListener listener
    ) {
        Objects.requireNonNull(repoId, "Repository id must not be null");
        Objects.requireNonNull(repoPath, "Repository path must not be null");
        Objects.requireNonNull(destination, "Destination must not be null");
        DownloadProgressListener progress = listener == null ? DownloadProgressListener.noop() : listener;
        String url = buildDownloadUrl(repoId, repoPath);
        String filename = destination.getFileName().toString();
        downloadWithRetry(url, destination, filename, progress, 0);
    }

    /**
     * Builds the Hugging Face download URL for a repository file.
     *
     * @param repoId repository id
     * @param filePath path within the repository
     * @return resolved download URL
     */
    public static String buildDownloadUrl(String repoId, String filePath) {
        return HF_BASE_URL + "/" + repoId + "/resolve/main/" + filePath;
    }

    private void downloadWithRetry(
        String url,
        Path destination,
        String filename,
        DownloadProgressListener listener,
        int attempt
    ) {
        try {
            doDownload(url, destination, filename, listener);
        } catch (RuntimeException | IOException exception) {
            if (attempt >= maxRetries) {
                throw new IllegalStateException(
                    "Download failed after " + maxRetries + " retries: " + filename,
                    exception
                );
            }
            listener.onError(
                filename,
                exception instanceof Exception cast ? cast : new IOException(exception)
            );
            long delayMs = calculateBackoffDelay(attempt + 1);
            sleep(delayMs);
            downloadWithRetry(url, destination, filename, listener, attempt + 1);
        }
    }

    private void doDownload(
        String url,
        Path destination,
        String filename,
        DownloadProgressListener listener
    ) throws IOException {
        Path tempFile = destination.resolveSibling(destination.getFileName() + TEMP_SUFFIX);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }

            try (InputStream body = response.body()) {
                if (body == null) {
                    throw new IOException("Empty response body");
                }
                long totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1L);
                listener.onStart(filename, totalBytes);
                long downloaded = 0;
                try (OutputStream out = Files.newOutputStream(tempFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = body.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloaded += bytesRead;
                        int percentComplete = totalBytes > 0
                            ? (int) ((downloaded * 100L) / totalBytes)
                            : 0;
                        listener.onProgress(filename, downloaded, totalBytes, percentComplete);
                    }
                }
            }

            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
            listener.onComplete(filename);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted for " + filename, exception);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best-effort cleanup for failed downloads.
            }
        }
    }

    private static long calculateBackoffDelay(int attempt) {
        if (attempt >= 30) {
            return MAX_DELAY_MS;
        }
        long delayMs = BASE_DELAY_MS * (1L << attempt);
        return Math.min(delayMs, MAX_DELAY_MS);
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during download backoff", exception);
        }
    }

    /**
     * Receives download lifecycle events for progress reporting.
     */
    public interface DownloadProgressListener {
        /** Called when a download starts. */
        void onStart(String filename, long totalBytes);

        /** Called as bytes are streamed to disk. */
        void onProgress(String filename, long downloaded, long totalBytes, int percentComplete);

        /** Called when a download completes successfully. */
        void onComplete(String filename);

        /** Called when a download attempt fails. */
        void onError(String filename, Exception error);

        /** Returns a listener that ignores all events. */
        static DownloadProgressListener noop() {
            return new DownloadProgressListener() {
                @Override
                public void onStart(String filename, long totalBytes) {}

                @Override
                public void onProgress(
                    String filename,
                    long downloaded,
                    long totalBytes,
                    int percentComplete
                ) {}

                @Override
                public void onComplete(String filename) {}

                @Override
                public void onError(String filename, Exception error) {}
            };
        }
    }
}
