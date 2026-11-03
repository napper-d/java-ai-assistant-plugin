package edu.teamc.courseaiassistant.llm;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import edu.teamc.courseaiassistant.model.ServiceFailure;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpLlmClientTest {
    static String response(String content, String reason) {
        JsonObject message = new JsonObject(); message.addProperty("content", content);
        JsonObject choice = new JsonObject(); choice.add("message", message); choice.addProperty("finish_reason", reason);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject root = new JsonObject(); root.add("choices", choices); return root.toString();
    }
    static final class Server implements AutoCloseable {
        final HttpServer server;
        // Virtual socket I/O starts JVM-global pollers that IDEA's per-test leak tracker
        // mistakes for test-owned threads. Match the existing RAG HTTP fixture.
        final ExecutorService workers = Executors.newCachedThreadPool(task -> new Thread(task, "LlmTestHttpServer"));
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile int status = 200;
        volatile String reply = response("{\"answer\":\"hello\",\"citations\":[]}", "stop");
        volatile boolean wait;
        Server() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    arrived.countDown();
                    if (wait) release.await(5, TimeUnit.SECONDS);
                    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        LlmConfig config(Duration timeout) { return new LlmConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"), "test-model", "private-key", timeout, 1000, 60000); }
        @Override public void close() throws InterruptedException {
            release.countDown(); server.stop(0); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "The local HTTP server must release its workers.");
        }
    }
    @Test void sendsExplicitModelMessagesAndKey() throws Exception {
        try (var server = new Server(); var client = new HttpLlmClient(server.config(Duration.ofSeconds(3)))) {
            assertTrue(client.complete("id", "rules", "question").get(4, TimeUnit.SECONDS).contains("hello"));
            JsonObject body = JsonParser.parseString(server.body.get()).getAsJsonObject();
            assertEquals("test-model", body.get("model").getAsString());
            assertFalse(body.get("stream").getAsBoolean());
            assertEquals(1000, body.get("max_tokens").getAsInt());
            assertEquals("rules", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
            assertEquals("Bearer private-key", server.authorization.get());
        }
    }
    @Test void httpFailuresAreSanitizedAndClassified() throws Exception {
        try (var server = new Server(); var client = new HttpLlmClient(server.config(Duration.ofSeconds(3)))) {
            for (int status : new int[]{401, 403, 429, 503, 302}) {
                server.status = status; server.reply = "private-key and prompt contents";
                var failure = LlmServicesTest.failure(client.complete("request-1", "rules", "question"));
                assertEquals("request-1", failure.getRequestId());
                assertEquals(ServiceFailure.Category.MODEL_ERROR, failure.getCategory());
                assertEquals(status == 429 || status == 503, failure.isRetryable());
                assertFalse(failure.toString().contains("private-key"));
            }
        }
    }
    @Test void rejectsTruncationRefusalMalformedAndOversizedResponses() throws Exception {
        try (var server = new Server(); var client = new HttpLlmClient(server.config(Duration.ofSeconds(3)))) {
            for (String response : new String[]{response("partial", "length"), response("refused", "content_filter"),
                    "{\"choices\":[]}", "not json", response("", "stop"), "x".repeat(4 * 1024 * 1024 + 1)}) {
                server.reply = response;
                assertEquals(ServiceFailure.Category.MODEL_ERROR, LlmServicesTest.failure(client.complete("id", "rules", "question")).getCategory());
            }
        }
    }
    @Test void deadlineAndCloseTerminatePendingHttpCalls() throws Exception {
        try (var server = new Server(); var client = new HttpLlmClient(server.config(Duration.ofMillis(200)))) {
            server.wait = true;
            assertEquals(ServiceFailure.Category.TIMEOUT, LlmServicesTest.failure(client.complete("id", "rules", "question")).getCategory());
        }
        try (var server = new Server(); var client = new HttpLlmClient(server.config(Duration.ofSeconds(3)))) {
            server.wait = true;
            var pending = client.complete("id", "rules", "question");
            assertTrue(server.arrived.await(2, TimeUnit.SECONDS)); client.close();
            assertTrue(pending.isCancelled());
            assertEquals(ServiceFailure.Category.UNKNOWN, LlmServicesTest.failure(client.complete("id", "rules", "question")).getCategory());
        }
    }
    @Test void explicitCancellationStopsPendingResult() throws Exception {
        try (var server = new Server(); var client = new HttpLlmClient(server.config(Duration.ofSeconds(3)))) {
            server.wait = true;
            var pending = client.complete("id", "rules", "question");
            assertTrue(server.arrived.await(2, TimeUnit.SECONDS)); pending.cancel(true); assertTrue(pending.isCancelled());
        }
    }
}
