package dev.jlo.kitsune.embedding.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies batching, retries, ordering, and validation of the OpenAI-compatible provider.
 */
final class OpenAiCompatibleEmbeddingProviderTest {
    @Test
    void sendsBatchAndRestoresResponseOrder() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (ServerFixture server = ServerFixture.responding((exchange, attempt) -> {
            request.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200,
                "{\"data\":["
                    + "{\"index\":1,\"embedding\":[0.0,1.0]},"
                    + "{\"index\":0,\"embedding\":[1.0,0.0]}]}"
            );
        })) {
            OpenAiCompatibleEmbeddingProvider provider = provider(server.endpoint(), Map.of(
                "model", "embed-v1",
                "document-prefix", "document: "
            ));

            List<Embedding> embeddings = provider.embedAll(List.of(
                descriptor("minecraft:stone"),
                descriptor("minecraft:diamond")
            ));

            assertEquals(2, embeddings.size());
            assertEquals(1.0, embeddings.get(0).norm(), 1e-6);
            assertEquals(1.0, embeddings.get(1).norm(), 1e-6);
            assertTrue(request.get().contains("\"model\":\"embed-v1\""));
            assertTrue(request.get().contains("document: material: minecraft:stone"));
            assertEquals("Bearer secret-value", authorization.get());
        }
    }

    @Test
    void queryUsesQueryPrefix() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        try (ServerFixture server = ServerFixture.responding((exchange, attempt) -> {
            request.set(readBody(exchange));
            respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}");
        })) {
            OpenAiCompatibleEmbeddingProvider provider = provider(server.endpoint(), Map.of(
                "model", "embed-v1",
                "query-prefix", "query: "
            ));

            provider.embedQuery("  mining tool ");

            assertTrue(request.get().contains("query: mining tool"));
        }
    }

    @Test
    void retriesRateLimitOnceAndThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (ServerFixture server = ServerFixture.responding((exchange, attempt) -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "busy");
            } else {
                respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}");
            }
        })) {
            OpenAiCompatibleEmbeddingProvider provider = provider(server.endpoint(), Map.of(
                "model", "embed-v1",
                "max-retries", "1"
            ));

            provider.embedQuery("diamond");

            assertEquals(2, attempts.get());
        }
    }

    @Test
    void rejectsMalformedResponseAndOversizedBody() throws Exception {
        try (ServerFixture malformed = ServerFixture.responding((exchange, attempt) ->
            respond(exchange, 200, "{\"data\":[{\"index\":0}]}"))) {
            OpenAiCompatibleEmbeddingProvider provider = provider(malformed.endpoint(), Map.of(
                "model", "embed-v1"
            ));
            assertThrows(RuntimeException.class, () -> provider.embedQuery("diamond"));
        }

        try (ServerFixture oversized = ServerFixture.responding((exchange, attempt) ->
            respond(exchange, 200, "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))) {
            OpenAiCompatibleEmbeddingProvider provider = provider(oversized.endpoint(), Map.of(
                "model", "embed-v1",
                "max-response-bytes", "8"
            ));
            assertThrows(RuntimeException.class, () -> provider.embedQuery("diamond"));
        }
    }

    @Test
    void rejectsOversizedRequestBeforeSending() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (ServerFixture server = ServerFixture.responding((exchange, attempt) -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}");
        })) {
            OpenAiCompatibleEmbeddingProvider provider = provider(server.endpoint(), Map.of(
                "model", "embed-v1",
                "max-request-bytes", "64"
            ));
            ItemDescriptor oversized = ItemDescriptor.builder()
                .materialKey("minecraft:stone")
                .amount(1)
                .addLore("x".repeat(1000))
                .build();

            assertThrows(
                IllegalStateException.class,
                () -> provider.embedAll(List.of(oversized))
            );
            assertEquals(0, requests.get());
        }
    }

    @Test
    void providerIdentityChangesWithNonSecretModelSettings() throws Exception {
        try (ServerFixture server = ServerFixture.responding((exchange, attempt) ->
            respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}"))) {
            EmbeddingProvider first = provider(server.endpoint(), Map.of("model", "embed-v1"));
            EmbeddingProvider second = provider(server.endpoint(), Map.of("model", "embed-v2"));

            assertNotEquals(first.id(), second.id());
            assertTrue(!first.id().contains("secret-value"));
        }
    }

    private static OpenAiCompatibleEmbeddingProvider provider(
        String endpoint,
        Map<String, String> overrides
    ) {
        java.util.LinkedHashMap<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("endpoint", endpoint);
        settings.put("model", "embed-v1");
        settings.put("credential-reference", "env:API_KEY");
        settings.put("allow-insecure-http", "true");
        settings.putAll(overrides);
        return new OpenAiCompatibleEmbeddingProviderFactory().create(
            settings,
            reference -> Optional.of("secret-value")
        );
    }

    private static ItemDescriptor descriptor(String material) {
        return ItemDescriptor.builder().materialKey(material).amount(1).build();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ResponseHandler {
        void respond(HttpExchange exchange, AtomicInteger attempt) throws Exception;
    }

    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger attempts = new AtomicInteger();

        private ServerFixture(HttpServer server) {
            this.server = server;
        }

        static ServerFixture responding(ResponseHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ServerFixture fixture = new ServerFixture(server);
            server.createContext("/v1/embeddings", exchange -> {
                try {
                    handler.respond(exchange, fixture.attempts);
                } catch (Exception failure) {
                    exchange.close();
                    throw new RuntimeException(failure);
                }
            });
            server.start();
            return fixture;
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
