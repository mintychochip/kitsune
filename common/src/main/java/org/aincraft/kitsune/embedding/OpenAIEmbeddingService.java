package org.aincraft.kitsune.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.aincraft.kitsune.Platform;
import org.aincraft.kitsune.config.KitsuneConfig;

public final class OpenAIEmbeddingService implements EmbeddingService {
    private final Platform platform;
    private final KitsuneConfig config;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/embeddings";

    public OpenAIEmbeddingService(Platform platform, KitsuneConfig config) {
        this.platform = platform;
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            String apiKey = config.embeddingApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("OpenAI API key not configured");
            }
            platform.getLogger().info("OpenAI embedding service initialized with model: " + config.embeddingModel());
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
                String apiKey = config.embeddingApiKey();

                JsonObject body = new JsonObject();
                body.addProperty("input", text);
                body.addProperty("model", config.embeddingModel());

                String jsonBody = gson.toJson(body);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException("OpenAI API error: " + response.statusCode() + " " + response.body());
                }

                JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);

                JsonArray data = responseJson.getAsJsonArray("data");
                if (data == null || data.size() == 0) {
                    throw new RuntimeException("No embedding data in response");
                }

                JsonObject embedding = data.get(0).getAsJsonObject();
                JsonArray embeddingArray = embedding.getAsJsonArray("embedding");

                float[] result = new float[embeddingArray.size()];
                for (int i = 0; i < embeddingArray.size(); i++) {
                    result[i] = embeddingArray.get(i).getAsFloat();
                }

                return result;
            } catch (Exception e) {
                platform.getLogger().log(Level.WARNING, "Failed to embed text with OpenAI", e);
                throw new RuntimeException("OpenAI embedding failed", e);
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
                String apiKey = config.embeddingApiKey();
                int batchSize = texts.size();
                platform.getLogger().info("Starting batch embedding for " + batchSize + " texts with OpenAI");

                // Build batch request - OpenAI API accepts array of strings
                JsonObject body = new JsonObject();
                JsonArray inputArray = new JsonArray();
                for (String text : texts) {
                    inputArray.add(text);
                }
                body.add("input", inputArray);
                body.addProperty("model", config.embeddingModel());

                String jsonBody = gson.toJson(body);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException("OpenAI API error: " + response.statusCode() + " " + response.body());
                }

                JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);

                JsonArray data = responseJson.getAsJsonArray("data");
                if (data == null || data.size() == 0) {
                    throw new RuntimeException("No embedding data in response");
                }

                // Results come in the same order as input
                List<float[]> results = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    JsonObject embedding = data.get(i).getAsJsonObject();
                    JsonArray embeddingArray = embedding.getAsJsonArray("embedding");

                    float[] result = new float[embeddingArray.size()];
                    for (int j = 0; j < embeddingArray.size(); j++) {
                        result[j] = embeddingArray.get(j).getAsFloat();
                    }
                    results.add(result);
                }

                platform.getLogger().info("Batch embedding completed for " + batchSize + " texts");
                return results;
            } catch (Exception e) {
                platform.getLogger().log(Level.WARNING, "Batch embedding failed with OpenAI", e);
                throw new RuntimeException("Batch embedding failed", e);
            }
        });
    }

    @Override
    public int getDimension() {
        String model = config.embeddingModel();
        return switch (model) {
            case "text-embedding-3-large" -> 3072;
            case "text-embedding-3-small" -> 1536;
            case "text-embedding-ada-002" -> 1536;
            default -> 1536; // Default to text-embedding-3-small dimensions
        };
    }

    @Override
    public void shutdown() {
        // JDK HttpClient uses daemon threads by default - no explicit shutdown needed
    }
}
