package dev.jlo.kitsune.embedding.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jlo.kitsune.embedding.DenseEmbedding;
import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.ItemDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    public static final String FACTORY_ID = "remote:openai-compatible";
    public static final int VERSION = 1;

    private final OpenAiCompatibleEmbeddingSettings settings;
    private final EmbeddingCredentialResolver credentials;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String id;

    public OpenAiCompatibleEmbeddingProvider(
        OpenAiCompatibleEmbeddingSettings settings,
        EmbeddingCredentialResolver credentials
    ) {
        this(
            settings,
            credentials,
            HttpClient.newBuilder()
                .connectTimeout(settings.requestTimeout())
                .build(),
            new ObjectMapper()
        );
    }

    OpenAiCompatibleEmbeddingProvider(
        OpenAiCompatibleEmbeddingSettings settings,
        EmbeddingCredentialResolver credentials,
        HttpClient client,
        ObjectMapper mapper
    ) {
        this.settings = Objects.requireNonNull(settings, "Settings must not be null");
        this.credentials = Objects.requireNonNull(credentials, "Credentials must not be null");
        this.client = Objects.requireNonNull(client, "HTTP client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "JSON mapper must not be null");
        this.id = FACTORY_ID + ":" + fingerprint(settings);
    }

    @Override
    public String id() {
        return id;
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

    public List<Embedding> embedAll(List<ItemDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "Descriptors must not be null");
        if (descriptors.isEmpty()) return List.of();

        List<Embedding> embeddings = new ArrayList<>(descriptors.size());
        List<String> batch = new ArrayList<>(settings.maxBatchSize());
        for (ItemDescriptor descriptor : descriptors) {
            String text = settings.documentPrefix() + EmbeddingTextSerializer.document(descriptor);
            if (batch.size() == settings.maxBatchSize()) {
                embeddings.addAll(request(batch));
                batch.clear();
            }

            List<String> candidate = new ArrayList<>(batch.size() + 1);
            candidate.addAll(batch);
            candidate.add(text);
            if (!fitsRequest(candidate)) {
                if (batch.isEmpty()) {
                    throw new IllegalStateException("Embedding input exceeds configured request size");
                }
                embeddings.addAll(request(batch));
                batch.clear();
                if (!fitsRequest(List.of(text))) {
                    throw new IllegalStateException("Embedding input exceeds configured request size");
                }
            }
            batch.add(text);
        }
        if (!batch.isEmpty()) {
            embeddings.addAll(request(batch));
        }
        return List.copyOf(embeddings);
    }

    @Override
    public Embedding embedQuery(String query) {
        String text = settings.queryPrefix() + EmbeddingTextSerializer.query(query);
        return request(List.of(text)).getFirst();
    }

    @Override
    public Embedding decode(byte[] payload, double norm) {
        return DenseEmbedding.decode(id, VERSION, payload, norm);
    }

    private List<Embedding> request(List<String> inputs) {
        if (inputs.isEmpty() || inputs.size() > settings.maxBatchSize()) {
            throw new IllegalArgumentException("Invalid embedding request batch");
        }

        byte[] body = requestBody(inputs);
        HttpRequest.Builder builder = HttpRequest.newBuilder(settings.endpoint())
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        authorization().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        HttpRequest request = builder.build();

        for (int attempt = 0; ; attempt++) {
            HttpResponse<InputStream> response = send(request);
            byte[] responseBody = readBody(response.body());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return parseEmbeddings(responseBody, inputs.size());
            }
            if (!retryable(status) || attempt >= settings.maxRetries()) {
                throw new IllegalStateException("Embedding request failed with HTTP " + status);
            }
            sleepBeforeRetry(response, attempt);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding request interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Embedding request failed", failure);
        }
    }

    private boolean fitsRequest(List<String> inputs) {
        try {
            requestBody(inputs);
            return true;
        } catch (RequestTooLargeException tooLarge) {
            return false;
        }
    }

    private byte[] requestBody(List<String> inputs) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", settings.model());
            root.put("encoding_format", "float");
            ArrayNode values = root.putArray("input");
            for (String input : inputs) {
                values.add(input);
            }
            BoundedOutputStream output = new BoundedOutputStream(settings.maxRequestBytes());
            mapper.writeValue(output, root);
            return output.bytes();
        } catch (RequestTooLargeException tooLarge) {
            throw tooLarge;
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to encode embedding request", failure);
        }
    }

    private List<Embedding> parseEmbeddings(byte[] responseBody, int expectedCount) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.size() != expectedCount) {
                throw new IllegalStateException("Embedding response data count mismatch");
            }

            List<DenseEmbedding> ordered = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
            for (JsonNode entry : data) {
                JsonNode indexNode = entry.get("index");
                JsonNode vectorNode = entry.get("embedding");
                if (indexNode == null || !indexNode.canConvertToInt()
                    || vectorNode == null || !vectorNode.isArray()) {
                    throw new IllegalStateException("Malformed embedding response entry");
                }
                int index = indexNode.intValue();
                if (index < 0 || index >= expectedCount || ordered.get(index) != null) {
                    throw new IllegalStateException("Invalid embedding response index");
                }
                if (settings.dimensions() > 0 && vectorNode.size() != settings.dimensions()) {
                    throw new IllegalStateException("Embedding dimension mismatch");
                }
                float[] components = new float[vectorNode.size()];
                for (int component = 0; component < vectorNode.size(); component++) {
                    JsonNode value = vectorNode.get(component);
                    if (value == null || !value.isNumber()) {
                        throw new IllegalStateException("Embedding component is not numeric");
                    }
                    float number = value.floatValue();
                    if (!Float.isFinite(number)) {
                        throw new IllegalStateException("Embedding component is not finite");
                    }
                    components[component] = number;
                }
                DenseEmbedding embedding = new DenseEmbedding(id, VERSION, components);
                if (embedding.norm() == 0.0) {
                    throw new IllegalStateException("Embedding vector has zero norm");
                }
                ordered.set(index, embedding);
            }
            if (ordered.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("Embedding response omitted an index");
            }
            return List.copyOf(ordered);
        } catch (IOException failure) {
            throw new IllegalStateException("Malformed embedding response", failure);
        }
    }

    private Optional<String> authorization() {
        if (settings.credentialReference().isBlank()) return Optional.empty();
        String token = credentials.resolve(settings.credentialReference()).orElseThrow(
            () -> new IllegalStateException("Missing embedding credential: " + settings.credentialReference())
        );
        if (token.isBlank()) {
            throw new IllegalStateException("Embedding credential is blank");
        }
        return Optional.of(token);
    }

    private byte[] readBody(InputStream stream) {
        Objects.requireNonNull(stream, "Response body must not be null");
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total = Math.addExact(total, count);
                if (total > settings.maxResponseBytes()) {
                    throw new IllegalStateException("Embedding response exceeds configured size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read embedding response", failure);
        }
    }

    private static final class RequestTooLargeException extends IllegalStateException {
        private RequestTooLargeException(int maximumBytes) {
            super("Embedding request exceeds configured size limit: " + maximumBytes);
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int maximumBytes;

        private BoundedOutputStream(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, values.length);
            ensureCapacity(length);
            delegate.write(values, offset, length);
        }

        private void ensureCapacity(int additionalBytes) {
            if (additionalBytes > maximumBytes - delegate.size()) {
                throw new RequestTooLargeException(maximumBytes);
            }
        }

        private byte[] bytes() {
            return delegate.toByteArray();
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private void sleepBeforeRetry(HttpResponse<?> response, int attempt) {
        long delay = retryAfterMillis(response).orElseGet(() -> Math.min(1_000L, 50L << attempt));
        if (delay == 0L) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding retry interrupted", interrupted);
        }
    }

    private static Optional<Long> retryAfterMillis(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty()) return Optional.empty();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0L) return Optional.empty();
            return Optional.of(Math.min(1_000L, Math.multiplyExact(seconds, 1_000L)));
        } catch (ArithmeticException | NumberFormatException failure) {
            return Optional.empty();
        }
    }

    private static String fingerprint(OpenAiCompatibleEmbeddingSettings settings) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(settings.fingerprintMaterial().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
