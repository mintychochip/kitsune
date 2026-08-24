package org.aincraft.kitsune.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.aincraft.kitsune.Platform;

public final class GoogleEmbeddingService implements EmbeddingService {
    private final Platform platform;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:embedContent";

    public GoogleEmbeddingService(Platform platform, String apiKey, String model) {
        this.platform = platform;
        this.apiKey = apiKey;
        // Use the stable model by default
        this.model = model != null && !model.isEmpty() ? model : "gemini-embedding-001";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("your-api-key")) {
                platform.getLogger().warning("Google API key not configured!");
                platform.getLogger().warning("Get an API key from https://aistudio.google.com/app/apikey");
                throw new IllegalStateException("Google API key not configured");
            }
            platform.getLogger().info("Google embedding service initialized with model: " + model);
        });
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        return embed(text, "RETRIEVAL_DOCUMENT");
    }

    @Override
    public CompletableFuture<float[]> embed(String text, String taskType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Log task type and text being embedded
                int previewLen = Math.min(150, text.length());
                platform.getLogger().info("Embedding with taskType=" + taskType + ", text=" +
                    text.substring(0, previewLen) + (previewLen < text.length() ? "..." : ""));

                // Build request body per Gemini API spec
                JsonObject parts = new JsonObject();
                parts.addProperty("text", text);

                JsonArray partsArray = new JsonArray();
                partsArray.add(parts);

                JsonObject content = new JsonObject();
                content.add("parts", partsArray);

                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "models/" + model);
                requestBody.add("content", content);
                requestBody.addProperty("taskType", taskType);

                String url = String.format(GEMINI_API_URL, model);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = response.body() != null ? response.body() : "No error body";
                    platform.getLogger().warning("Google API error: " + response.statusCode() + " - " + errorBody);
                    throw new RuntimeException("Google API error: " + response.statusCode());
                }

                String responseBody = response.body();
                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

                JsonObject embedding = jsonResponse.getAsJsonObject("embedding");
                JsonArray values = embedding.getAsJsonArray("values");

                float[] result = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    result[i] = values.get(i).getAsFloat();
                }

                // Log response vector length
                platform.getLogger().info("Embedding response: vector size=" + result.length);

                // L2 normalize the embedding
                normalizeEmbedding(result);
                return result;
            } catch (IOException | InterruptedException e) {
                platform.getLogger().log(Level.WARNING, "Failed to call Google API", e);
                throw new RuntimeException("Embedding failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts, String taskType) {
        if (texts == null || texts.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int batchSize = texts.size();
                platform.getLogger().info("Starting batch embedding for " + batchSize + " texts with Google");

                // Process texts sequentially - Google API doesn't support true batching
                // but we optimize by doing multiple requests in series with proper logging
                List<float[]> results = new ArrayList<>();

                for (int idx = 0; idx < texts.size(); idx++) {
                    String text = texts.get(idx);
                    int previewLen = Math.min(150, text.length());
                    platform.getLogger().info("Embedding [" + (idx + 1) + "/" + batchSize + "] with taskType=" + taskType);

                    // Build request body per Gemini API spec
                    JsonObject parts = new JsonObject();
                    parts.addProperty("text", text);

                    JsonArray partsArray = new JsonArray();
                    partsArray.add(parts);

                    JsonObject content = new JsonObject();
                    content.add("parts", partsArray);

                    JsonObject requestBody = new JsonObject();
                    requestBody.addProperty("model", "models/" + model);
                    requestBody.add("content", content);
                    requestBody.addProperty("taskType", taskType);

                    String url = String.format(GEMINI_API_URL, model);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("x-goog-api-key", apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        String errorBody = response.body() != null ? response.body() : "No error body";
                        platform.getLogger().warning("Google API error: " + response.statusCode() + " - " + errorBody);
                        throw new RuntimeException("Google API error: " + response.statusCode());
                    }

                    String responseBody = response.body();
                    JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

                    JsonObject embedding = jsonResponse.getAsJsonObject("embedding");
                    JsonArray values = embedding.getAsJsonArray("values");

                    float[] result = new float[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        result[i] = values.get(i).getAsFloat();
                    }

                    // L2 normalize the embedding
                    normalizeEmbedding(result);
                    results.add(result);
                }

                platform.getLogger().info("Batch embedding completed for " + batchSize + " texts");
                return results;
            } catch (IOException | InterruptedException e) {
                platform.getLogger().log(Level.WARNING, "Batch embedding failed with Google API", e);
                throw new RuntimeException("Batch embedding failed", e);
            }
        });
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
        // Google Gemini embedding models use 3072 dimensions by default
        // Models: gemini-embedding-001 (3072), text-embedding-004 (768)
        return switch (model) {
            case "text-embedding-004" -> 768;
            case "gemini-embedding-001" -> 3072;
            default -> 3072; // Default to gemini-embedding-001 dimensions
        };
    }

    @Override
    public void shutdown() {
        // JDK HttpClient doesn't require explicit cleanup
    }
}
