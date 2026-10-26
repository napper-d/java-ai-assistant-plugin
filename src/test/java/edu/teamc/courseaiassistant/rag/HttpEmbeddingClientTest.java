package edu.teamc.courseaiassistant.rag;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpEmbeddingClientTest {
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach void stopServer() throws InterruptedException {
        if (server != null) server.stop(0);
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "The local HTTP server must stop its owned workers.");
        }
    }

    private RagConfig.EmbeddingSettings start(HttpHandler handler, Duration timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Virtual socket I/O lazily starts JVM-global pollers, which IDEA's
        // per-test leak tracker can mistake for leaked test-owned threads.
        executor = Executors.newCachedThreadPool(task -> new Thread(task, "RagTestHttpServer"));
        server.setExecutor(executor); server.createContext("/v1/embeddings", handler); server.start();
        return new RagConfig.EmbeddingSettings("openai-compatible", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", "local-test-key", 2, timeout, "document: ", "query: ");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Test void preservesOrderAndUsesConfiguredQueryPrefix() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        var settings = start(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}");
        }, Duration.ofSeconds(5));
        try (var client = new HttpEmbeddingClient(settings)) {
            var result = client.embed(List.of("first", "second"), EmbeddingClient.InputType.QUERY).get(10, TimeUnit.SECONDS);
            assertArrayEquals(new float[]{1, 0}, result.getFirst());
            assertArrayEquals(new float[]{0, 1}, result.getLast());
            var request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals("query: first", request.getAsJsonArray("input").get(0).getAsString());
            assertEquals("test-model", request.get("model").getAsString());
            assertEquals("Bearer local-test-key", authorization.get());
        }
    }

    @Test void rejectsDuplicateIndexesAndInvalidVectors() throws Exception {
        AtomicReference<String> response = new AtomicReference<>();
        var settings = start(exchange -> respond(exchange, 200, response.get()), Duration.ofSeconds(5));
        try (var client = new HttpEmbeddingClient(settings)) {
            for (String invalid : List.of(
                    "{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]}",
                    "{\"data\":[{\"index\":0,\"embedding\":[0,0]},{\"index\":1,\"embedding\":[1,0]}]}",
                    "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[1,0]}]}",
                    "not JSON")) {
                response.set(invalid);
                LocalRetrievalTest.assertReason(RagException.Reason.EMBEDDING_ERROR,
                        client.embed(List.of("one", "two"), EmbeddingClient.InputType.DOCUMENT));
            }
        }
    }

    @Test void apiErrorIsSafeAndRetryabilityReflectsStatus() throws Exception {
        var settings = start(exchange -> respond(exchange, 429, "local-test-key private source response"), Duration.ofSeconds(5));
        try (var client = new HttpEmbeddingClient(settings)) {
            var error = assertThrows(ExecutionException.class,
                    () -> client.embed(List.of("text"), EmbeddingClient.InputType.DOCUMENT).get(10, TimeUnit.SECONDS));
            RagException failure = assertInstanceOf(RagException.class, error.getCause());
            assertTrue(failure.isRetryable());
            assertFalse(failure.getMessage().contains("local-test-key"));
            assertFalse(failure.getMessage().contains("private"));
        }
    }

    @Test void requestDeadlineProducesTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        var settings = start(exchange -> {
            try { release.await(5, TimeUnit.SECONDS); respond(exchange, 200, "{}"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }, Duration.ofMillis(150));
        try (var client = new HttpEmbeddingClient(settings)) {
            LocalRetrievalTest.assertReason(RagException.Reason.TIMEOUT, client.embed(List.of("text"), EmbeddingClient.InputType.QUERY));
        } finally { release.countDown(); }
    }

    @Test void closeAndCancelTerminatePendingCalls() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1), release = new CountDownLatch(1);
        var settings = start(exchange -> {
            invoked.countDown();
            try { release.await(5, TimeUnit.SECONDS); respond(exchange, 200, "{}"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }, Duration.ofSeconds(10));
        var client = new HttpEmbeddingClient(settings);
        try {
            var future = client.embed(List.of("text"), EmbeddingClient.InputType.QUERY);
            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            client.close();
            assertTrue(future.isCancelled());
            LocalRetrievalTest.assertReason(RagException.Reason.CLOSED, client.embed(List.of("text"), EmbeddingClient.InputType.QUERY));
        } finally { release.countDown(); client.close(); }
    }
}
