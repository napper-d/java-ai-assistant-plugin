package edu.teamc.courseaiassistant.llm;

import com.google.gson.*;
import edu.teamc.courseaiassistant.model.ServiceFailure;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Non-streaming chat/completions transport. Redirects disabled to avoid forwarding credentials. */
public final class HttpLlmClient implements LlmClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final LlmConfig config;
    private final HttpClient http;
    private final LlmTasks tasks = new LlmTasks();

    public HttpLlmClient(LlmConfig config) {
        this.config = config;
        http = HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CompletableFuture<String> complete(String id, String system, String user) {
        return tasks.submit(id, config.timeout(), context -> {
            if (system == null || user == null || user.isBlank()
                    || (long) system.length() + user.length() > config.maxInputCharacters()) throw LlmFailures.invalid(id);
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model()); body.addProperty("stream", false);
            body.addProperty("max_tokens", config.maxOutputTokens());
            JsonArray messages = new JsonArray();
            messages.add(message("system", system)); messages.add(message("user", user)); body.add("messages", messages);
            HttpRequest request = HttpRequest.newBuilder(config.completionEndpoint()).timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            // Discard error bodies: provider diagnostics may echo credentials or private prompts.
            HttpResponse<byte[]> response = context.await(http.sendAsync(request, info ->
                    new BoundedBody(id, info.statusCode() < 200 || info.statusCode() >= 300)));
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw new ServiceFailure(id, ServiceFailure.Category.MODEL_ERROR,
                    "Model service rejected the request (HTTP " + status + "). Check model settings or retry later.",
                    status == 408 || status == 429 || status >= 500);
            try {
                JsonObject root = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices.size() != 1) throw new IllegalArgumentException();
                JsonObject choice = choices.get(0).getAsJsonObject();
                if (!"stop".equals(choice.get("finish_reason").getAsString())) throw new IllegalArgumentException();
                String content = StructuredReplies.string(choice.getAsJsonObject("message"), "content");
                if (content.isBlank()) throw new IllegalArgumentException();
                return content;
            } catch (RuntimeException error) { throw LlmFailures.malformed(id); }
        });
    }

    private static JsonObject message(String role, String content) {
        JsonObject value = new JsonObject(); value.addProperty("role", role); value.addProperty("content", content); return value;
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final String id;
        private final boolean discard;
        private Flow.Subscription subscription;
        private long received;
        BoundedBody(String id, boolean discard) { this.id = id; this.discard = discard; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
                if (received > MAX_RESPONSE_BYTES) {
                    subscription.cancel(); result.completeExceptionally(LlmFailures.malformed(id)); return;
                }
                if (!discard) { byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    @Override public void close() { tasks.close(); http.shutdownNow(); }
}
