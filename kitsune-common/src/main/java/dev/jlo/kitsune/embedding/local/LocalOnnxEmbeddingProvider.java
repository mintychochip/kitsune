package dev.jlo.kitsune.embedding.local;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.embedding.DenseEmbedding;
import dev.jlo.kitsune.embedding.remote.EmbeddingTextSerializer;
import dev.jlo.kitsune.model.ItemDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A synchronous {@link EmbeddingProvider} backed by a local ONNX transformer model.
 *
 * <p>Models are loaded eagerly during construction. Text is prefixed according to the
 * configured task strategy, tokenized with DJL, and converted to dense embeddings using
 * mean pooling and L2 normalization.
 */
public final class LocalOnnxEmbeddingProvider implements EmbeddingProvider {
    /** Provider id reported by {@link #id()}. */
    public static final String PROVIDER_ID = "local:onnx-v1";
    /** Version of the embedding wire format produced by this provider. */
    public static final int VERSION = 1;

    private static final String TASK_DOCUMENT = "RETRIEVAL_DOCUMENT";
    private static final String TASK_QUERY = "RETRIEVAL_QUERY";
    private static final long UNK_TOKEN_ID = 100L;
    private static final int MAX_SEQUENCE_LENGTH = 512;

    private final OnnxModelSpec spec;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    /**
     * Creates a provider, downloading model artifacts when needed and loading ONNX state eagerly.
     *
     * @param dataDir directory containing local ONNX model artifacts
     * @param spec model specification
     * @param autoDownload whether missing artifacts should be downloaded automatically
     * @return initialized provider
     */
    public static LocalOnnxEmbeddingProvider create(Path dataDir, OnnxModelSpec spec, boolean autoDownload) {
        Objects.requireNonNull(dataDir, "Data directory must not be null");
        Objects.requireNonNull(spec, "Model specification must not be null");
        ensureModelAvailable(dataDir, spec, autoDownload);
        return new LocalOnnxEmbeddingProvider(dataDir, spec);
    }

    LocalOnnxEmbeddingProvider(Path dataDir, OnnxModelSpec spec) {
        Objects.requireNonNull(dataDir, "Data directory must not be null");
        this.spec = Objects.requireNonNull(spec, "Model specification must not be null");
        try {
            this.environment = OrtEnvironment.getEnvironment();
            Path modelPath = dataDir.resolve(spec.modelFileName());
            if (!Files.exists(modelPath)) {
                throw new IllegalStateException("ONNX model not found at " + modelPath);
            }
            this.session = environment.createSession(modelPath.toString());
            this.tokenizer = loadTokenizer(dataDir);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize local ONNX provider", exception);
        }
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Embedding embed(ItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        return embedAll(List.of(descriptor)).getFirst();
    }

    @Override
    public List<Embedding> embedAll(List<ItemDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "Descriptors must not be null");
        if (descriptors.isEmpty()) {
            return List.of();
        }

        List<String> texts = new ArrayList<>(descriptors.size());
        for (ItemDescriptor descriptor : descriptors) {
            texts.add(spec.applyTaskPrefix(EmbeddingTextSerializer.document(descriptor), TASK_DOCUMENT));
        }
        return embedTexts(texts);
    }

    @Override
    public Embedding embedQuery(String query) {
        Objects.requireNonNull(query, "Query must not be null");
        String text = spec.applyTaskPrefix(EmbeddingTextSerializer.query(query), TASK_QUERY);
        return embedTexts(List.of(text)).getFirst();
    }

    @Override
    public Embedding decode(byte[] payload, double norm) {
        return DenseEmbedding.decode(PROVIDER_ID, VERSION, payload, norm);
    }

    /**
     * Applies mean pooling over token hidden states using the attention mask.
     *
     * @param lastHiddenState token hidden states
     * @param attentionMask attention mask values
     * @param dimension output dimension count
     * @return pooled embedding vector
     */
    static float[] meanPooling(float[][] lastHiddenState, long[] attentionMask, int dimension) {
        Objects.requireNonNull(lastHiddenState, "Hidden states must not be null");
        Objects.requireNonNull(attentionMask, "Attention mask must not be null");
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }

        float[] result = new float[dimension];
        float sumMask = 0.0f;
        for (int index = 0; index < lastHiddenState.length && index < attentionMask.length; index++) {
            if (attentionMask[index] == 1L) {
                float[] token = lastHiddenState[index];
                for (int component = 0; component < dimension && component < token.length; component++) {
                    result[component] += token[component];
                }
                sumMask += 1.0f;
            }
        }

        if (sumMask > 0.0f) {
            for (int index = 0; index < result.length; index++) {
                result[index] /= sumMask;
            }
        }
        return normalizeEmbedding(result);
    }

    /**
     * L2-normalizes the given embedding vector in place and returns it.
     *
     * @param embedding embedding vector to normalize
     * @return the normalized vector
     */
    static float[] normalizeEmbedding(float[] embedding) {
        Objects.requireNonNull(embedding, "Embedding must not be null");
        float norm = 0.0f;
        for (float value : embedding) {
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0.0f) {
            for (int index = 0; index < embedding.length; index++) {
                embedding[index] /= norm;
            }
        }
        return embedding;
    }

    /**
     * Replaces out-of-range token ids with the unknown-token id when sanitization is enabled.
     *
     * @param inputIds token ids from the tokenizer
     * @param vocabSize vocabulary size, or {@code 0} to disable sanitization
     * @return sanitized token ids
     */
    static long[] sanitizeTokenIds(long[] inputIds, int vocabSize) {
        Objects.requireNonNull(inputIds, "Input ids must not be null");
        if (vocabSize <= 0) {
            return inputIds;
        }

        long[] sanitized = new long[inputIds.length];
        for (int index = 0; index < inputIds.length; index++) {
            long tokenId = inputIds[index];
            if (tokenId >= 0L && tokenId < vocabSize) {
                sanitized[index] = tokenId;
            } else {
                sanitized[index] = UNK_TOKEN_ID;
            }
        }
        return sanitized;
    }

    private static void ensureModelAvailable(Path dataDir, OnnxModelSpec spec, boolean autoDownload) {
        Path modelPath = dataDir.resolve(spec.modelFileName());
        Path tokenizerPath = dataDir.resolve(spec.tokenizerFileName());
        Path externalDataPath = dataDir.resolve(spec.externalDataFileName());

        boolean modelExists = Files.exists(modelPath);
        boolean tokenizerExists = Files.exists(tokenizerPath);
        boolean externalDataExists = !spec.requiresExternalData() || Files.exists(externalDataPath);
        if (modelExists && tokenizerExists && externalDataExists) {
            return;
        }

        if (!autoDownload) {
            throw new IllegalStateException(
                spec.modelName() + " model not found and auto-download is disabled"
            );
        }

        new HuggingFaceModelDownloader(dataDir).downloadModel(spec, HuggingFaceModelDownloader.DownloadProgressListener.noop());
    }

    private List<Embedding> embedTexts(List<String> texts) {
        try {
            int batchSize = texts.size();
            List<Encoding> encodings = new ArrayList<>(batchSize);
            int maxLength = 0;
            for (String text : texts) {
                Encoding encoding = tokenizer.encode(text);
                encodings.add(encoding);
                maxLength = Math.max(maxLength, encoding.getIds().length);
            }

            long[][] inputIdsTensor = new long[batchSize][maxLength];
            long[][] attentionMaskTensor = new long[batchSize][maxLength];
            long[][] tokenTypeIdsTensor = new long[batchSize][maxLength];
            for (int index = 0; index < batchSize; index++) {
                Encoding encoding = encodings.get(index);
                long[] inputIds = sanitizeTokenIds(encoding.getIds(), spec.vocabSize());
                System.arraycopy(inputIds, 0, inputIdsTensor[index], 0, inputIds.length);
                System.arraycopy(
                    encoding.getAttentionMask(),
                    0,
                    attentionMaskTensor[index],
                    0,
                    encoding.getAttentionMask().length
                );
                System.arraycopy(
                    encoding.getTypeIds(),
                    0,
                    tokenTypeIdsTensor[index],
                    0,
                    encoding.getTypeIds().length
                );
            }

            List<float[]> vectors = runBatchInference(
                inputIdsTensor,
                attentionMaskTensor,
                tokenTypeIdsTensor,
                attentionMaskTensor
            );
            List<Embedding> embeddings = new ArrayList<>(vectors.size());
            for (float[] vector : vectors) {
                embeddings.add(new DenseEmbedding(PROVIDER_ID, VERSION, vector));
            }
            return List.copyOf(embeddings);
        } catch (Exception exception) {
            throw new IllegalStateException("Local ONNX embedding failed", exception);
        }
    }

    private List<float[]> runBatchInference(
        long[][] inputIds,
        long[][] attentionMask,
        long[][] tokenTypeIds,
        long[][] attentionMaskForPooling
    ) throws Exception {
        Map<String, OnnxTensor> inputs = createTensors(inputIds, attentionMask, tokenTypeIds);
        try (OrtSession.Result outputs = session.run(inputs)) {
            closeTensors(inputs);
            Object value = outputs.get("last_hidden_state")
                .orElseThrow(() -> new IllegalStateException("No last_hidden_state in output"))
                .getValue();
            float[][][] batchOutput = (float[][][]) value;

            List<float[]> results = new ArrayList<>(batchOutput.length);
            for (int index = 0; index < batchOutput.length; index++) {
                results.add(meanPooling(batchOutput[index], attentionMaskForPooling[index], spec.dimension()));
            }
            return results;
        }
    }

    private Map<String, OnnxTensor> createTensors(
        long[][] inputIds,
        long[][] attentionMask,
        long[][] tokenTypeIds
    ) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(environment, inputIds));
        inputs.put("attention_mask", OnnxTensor.createTensor(environment, attentionMask));
        inputs.put("token_type_ids", OnnxTensor.createTensor(environment, tokenTypeIds));
        return inputs;
    }

    private static void closeTensors(Map<String, OnnxTensor> tensors) {
        for (OnnxTensor tensor : tensors.values()) {
            tensor.close();
        }
    }

    private static HuggingFaceTokenizer loadTokenizer(Path dataDir) throws Exception {
        Path tokenizerJsonPath = dataDir.resolve("tokenizer.json");
        Path vocabPath = dataDir.resolve("vocab.txt");

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(LocalOnnxEmbeddingProvider.class.getClassLoader());
            if (Files.exists(tokenizerJsonPath)) {
                return HuggingFaceTokenizer.newInstance(tokenizerJsonPath);
            }
            if (Files.exists(vocabPath)) {
                Map<String, String> options = new HashMap<>();
                options.put("modelMaxLength", String.valueOf(MAX_SEQUENCE_LENGTH));
                options.put("addSpecialTokens", "true");
                options.put("padding", "false");
                options.put("truncation", "true");
                return HuggingFaceTokenizer.newInstance(vocabPath, options);
            }
            throw new IllegalStateException("No tokenizer found in " + dataDir);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}
