package org.aincraft.kitsune.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.aincraft.kitsune.Platform;
import org.aincraft.kitsune.embedding.download.HuggingFaceModelDownloader;
import org.aincraft.kitsune.embedding.download.ModelSpec;

/**
 * Unified ONNX embedding service that supports multiple models via ModelSpec.
 * Handles model downloading, tokenizer loading, inference, and mean pooling.
 * Uses virtual threads for ONNX inference (JDK 21+).
 */
public final class OnnxEmbeddingService implements EmbeddingService {
    private final Platform platform;
    private final ModelSpec spec;
    // TODO: PERF - Virtual threads not ideal for CPU-bound ONNX inference
    // Virtual threads excel at I/O but ONNX inference is CPU-bound
    // Consider: Fixed thread pool sized to Runtime.getRuntime().availableProcessors()
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final boolean autoDownloadEnabled;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private HuggingFaceModelDownloader downloader;

    // Constants for token sanitization
    private static final long UNK_TOKEN_ID = 100;  // [UNK] token for BERT-based models
    private static final int MAX_SEQUENCE_LENGTH = 512;

    public OnnxEmbeddingService(Platform platform, ModelSpec spec) {
        this(platform, spec, true);
    }

    public OnnxEmbeddingService(Platform platform, ModelSpec spec, boolean autoDownloadEnabled) {
        this.platform = platform;
        this.spec = spec;
        this.autoDownloadEnabled = autoDownloadEnabled;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return ensureModelAvailable()
            .thenCompose(v -> doInitialize());
    }

    private CompletableFuture<Void> ensureModelAvailable() {
        Path modelsDir = platform.getDataFolder().resolve("models");
        Path modelPath = modelsDir.resolve(spec.getModelFileName());
        Path tokenizerPath = modelsDir.resolve("tokenizer.json");

        // Check if all required files exist
        boolean modelExists = Files.exists(modelPath);
        boolean tokenizerExists = Files.exists(tokenizerPath);
        boolean dataExists = !spec.requiresExternalData() ||
                             Files.exists(modelsDir.resolve("model.onnx_data"));

        if (modelExists && tokenizerExists && dataExists) {
            platform.getLogger().info(spec.modelName() + " model files found locally");
            return CompletableFuture.completedFuture(null);
        }

        if (!autoDownloadEnabled) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(spec.modelName() + " model not found and auto-download is disabled"));
        }

        // Download from Hugging Face
        platform.getLogger().info("Downloading " + spec.modelName() + " from Hugging Face...");
        initializeDownloader();
        DownloadProgressListener listener = new ConsoleProgressReporter(platform.getLogger());

        CompletableFuture<Void> downloadFuture = downloader.downloadModel(spec, listener);

        // Also download external data file if needed
        if (spec.requiresExternalData()) {
            Path dataPath = modelsDir.resolve("model.onnx_data");
            if (!Files.exists(dataPath)) {
                downloadFuture = downloadFuture.thenCompose(v -> {
                    platform.getLogger().info("Downloading external data file for " + spec.modelName() + "...");
                    return downloader.downloadFile(spec.huggingFaceRepo(), "onnx/model.onnx_data", dataPath, listener);
                }).thenRun(() -> {});
            }
        }

        return downloadFuture;
    }

    private void initializeDownloader() {
        var httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        downloader = new HuggingFaceModelDownloader(platform, httpClient, 3, executor);
    }

    private CompletableFuture<Void> doInitialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                env = OrtEnvironment.getEnvironment();
                Path modelsDir = platform.getDataFolder().resolve("models");
                Path modelPath = modelsDir.resolve(spec.getModelFileName());

                if (!Files.exists(modelPath)) {
                    throw new IllegalStateException("ONNX model not found at " + modelPath);
                }

                session = env.createSession(modelPath.toString());
                loadTokenizer(modelsDir);

                platform.getLogger().info("ONNX embedding service initialized with " + spec.modelName() +
                           " (" + spec.dimension() + " dimensions, strategy: " + spec.taskPrefixStrategy() + ")");
                return null;
            } catch (Exception e) {
                platform.getLogger().log(Level.SEVERE, "Failed to initialize ONNX session", e);
                throw new RuntimeException("ONNX initialization failed", e);
            }
        }, executor);
    }

    private void loadTokenizer(Path modelsDir) throws Exception {
        Path tokenizerJsonPath = modelsDir.resolve("tokenizer.json");
        Path vocabPath = modelsDir.resolve("vocab.txt");

        // Fix classloader context for DJL native library loading in plugin environments
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

            if (Files.exists(tokenizerJsonPath)) {
                tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJsonPath);
                platform.getLogger().fine("Loaded tokenizer from tokenizer.json");
            } else if (Files.exists(vocabPath)) {
                Map<String, String> options = new HashMap<>();
                options.put("modelMaxLength", String.valueOf(MAX_SEQUENCE_LENGTH));
                options.put("addSpecialTokens", "true");
                options.put("padding", "false");
                options.put("truncation", "true");
                tokenizer = HuggingFaceTokenizer.newInstance(vocabPath, options);
                platform.getLogger().fine("Loaded tokenizer from vocab.txt");
            } else {
                throw new IllegalStateException("No tokenizer found");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        return embed(text, "RETRIEVAL_DOCUMENT");
    }

    @Override
    public CompletableFuture<float[]> embed(String text, String taskType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (session == null || tokenizer == null) {
                    throw new IllegalStateException("ONNX session or tokenizer not initialized");
                }

                String processedText = spec.applyTaskPrefix(text, taskType);
                Encoding encoding = tokenizer.encode(processedText);

                long[] inputIds = sanitizeTokenIds(encoding.getIds());
                long[] attentionMask = encoding.getAttentionMask();
                long[] tokenTypeIds = encoding.getTypeIds();

                float[] embedding = runInference(
                    new long[][]{inputIds},
                    new long[][]{attentionMask},
                    new long[][]{tokenTypeIds},
                    attentionMask
                );

                return embedding;
            } catch (Exception e) {
                platform.getLogger().log(Level.WARNING, "Failed to embed text", e);
                throw new RuntimeException("Embedding failed", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts, String taskType) {
        if (texts == null || texts.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (session == null || tokenizer == null) {
                    throw new IllegalStateException("ONNX session or tokenizer not initialized");
                }

                int batchSize = texts.size();
                platform.getLogger().fine("Batch embedding " + batchSize + " texts");

                // Tokenize all texts and find max length
                List<Encoding> encodings = new ArrayList<>();
                int maxLen = 0;
                for (String text : texts) {
                    String processed = spec.applyTaskPrefix(text, taskType);
                    Encoding enc = tokenizer.encode(processed);
                    encodings.add(enc);
                    maxLen = Math.max(maxLen, enc.getIds().length);
                }

                // Create padded tensors
                long[][] inputIdsTensor = new long[batchSize][maxLen];
                long[][] attentionMaskTensor = new long[batchSize][maxLen];
                long[][] tokenTypeIdsTensor = new long[batchSize][maxLen];

                for (int i = 0; i < batchSize; i++) {
                    Encoding enc = encodings.get(i);
                    long[] ids = sanitizeTokenIds(enc.getIds());
                    System.arraycopy(ids, 0, inputIdsTensor[i], 0, ids.length);
                    System.arraycopy(enc.getAttentionMask(), 0, attentionMaskTensor[i], 0, enc.getAttentionMask().length);
                    System.arraycopy(enc.getTypeIds(), 0, tokenTypeIdsTensor[i], 0, enc.getTypeIds().length);
                }

                // Run batch inference
                List<float[]> results = runBatchInference(inputIdsTensor, attentionMaskTensor, tokenTypeIdsTensor);

                platform.getLogger().fine("Batch embedding completed for " + batchSize + " texts");
                return results;
            } catch (Exception e) {
                platform.getLogger().log(Level.WARNING, "Batch embedding failed", e);
                throw new RuntimeException("Batch embedding failed", e);
            }
        }, executor);
    }

    private float[] runInference(long[][] inputIds, long[][] attentionMask,
                                  long[][] tokenTypeIds, long[] attentionMaskFlat) throws Exception {
        Map<String, OnnxTensor> inputs = createTensors(inputIds, attentionMask, tokenTypeIds);

        try (var outputs = session.run(inputs)) {
            closeTensors(inputs);

            var lastHiddenStateOpt = outputs.get("last_hidden_state");
            if (lastHiddenStateOpt.isEmpty()) {
                throw new RuntimeException("No last_hidden_state in output");
            }

            OnnxTensor lastHiddenState = (OnnxTensor) lastHiddenStateOpt.get();
            float[][][] output = (float[][][]) lastHiddenState.getValue();
            return meanPooling(output[0], attentionMaskFlat);
        }
    }

    private List<float[]> runBatchInference(long[][] inputIds, long[][] attentionMask,
                                             long[][] tokenTypeIds) throws Exception {
        Map<String, OnnxTensor> inputs = createTensors(inputIds, attentionMask, tokenTypeIds);

        try (var outputs = session.run(inputs)) {
            closeTensors(inputs);

            var lastHiddenStateOpt = outputs.get("last_hidden_state");
            if (lastHiddenStateOpt.isEmpty()) {
                throw new RuntimeException("No last_hidden_state in output");
            }

            OnnxTensor lastHiddenState = (OnnxTensor) lastHiddenStateOpt.get();
            float[][][] batchOutput = (float[][][]) lastHiddenState.getValue();

            List<float[]> results = new ArrayList<>();
            for (int i = 0; i < batchOutput.length; i++) {
                results.add(meanPooling(batchOutput[i], attentionMask[i]));
            }
            return results;
        }
    }

    private Map<String, OnnxTensor> createTensors(long[][] inputIds, long[][] attentionMask,
                                                   long[][] tokenTypeIds) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(env, inputIds));
        inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMask));
        inputs.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIds));
        return inputs;
    }

    private void closeTensors(Map<String, OnnxTensor> tensors) {
        for (var tensor : tensors.values()) {
            tensor.close();
        }
    }

    private long[] sanitizeTokenIds(long[] inputIds) {
        if (spec.vocabSize() <= 0) {
            return inputIds; // Sanitization disabled
        }

        long[] sanitized = new long[inputIds.length];
        for (int i = 0; i < inputIds.length; i++) {
            if (inputIds[i] >= 0 && inputIds[i] < spec.vocabSize()) {
                sanitized[i] = inputIds[i];
            } else {
                sanitized[i] = UNK_TOKEN_ID;
            }
        }
        return sanitized;
    }

    private float[] meanPooling(float[][] lastHiddenState, long[] attentionMask) {
        float[] result = new float[spec.dimension()];
        float sumMask = 0;

        for (int i = 0; i < lastHiddenState.length && i < attentionMask.length; i++) {
            if (attentionMask[i] == 1) {
                for (int j = 0; j < spec.dimension() && j < lastHiddenState[i].length; j++) {
                    result[j] += lastHiddenState[i][j];
                }
                sumMask += 1.0f;
            }
        }

        if (sumMask > 0) {
            for (int i = 0; i < result.length; i++) {
                result[i] /= sumMask;
            }
        }

        normalizeEmbedding(result);
        return result;
    }

    private void normalizeEmbedding(float[] embedding) {
        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }
    }

    @Override
    public int getDimension() {
        return spec.dimension();
    }

    @Override
    public void shutdown() {
        if (session != null) {
            try { session.close(); }
            catch (Exception e) { platform.getLogger().log(Level.WARNING, "Failed to close ONNX session", e); }
        }
        if (env != null) {
            env.close();
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
        executor.shutdown();
    }
}