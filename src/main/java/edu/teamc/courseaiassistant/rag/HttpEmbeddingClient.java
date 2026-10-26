package edu.teamc.courseaiassistant.rag;

import com.google.gson.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly selected JSON embeddings protocol: POST /embeddings, model/input -> data[index,embedding]. */
public final class HttpEmbeddingClient implements EmbeddingClient {
    private final RagConfig.EmbeddingSettings settings;
    private final HttpClient http;
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpEmbeddingClient(RagConfig.EmbeddingSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        if (settings.model().isBlank() || settings.apiKey().isBlank()) {
            throw new RagException(RagException.Reason.CONFIGURATION, "Set the embedding model and API key.", false);
        }
        settings.endpoint();
        http = HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CompletableFuture<List<float[]>> embed(List<String> texts, InputType type) {
        if (closed.get()) return CompletableFuture.failedFuture(new RagException(RagException.Reason.CLOSED, "Embedding client is closed.", false));
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(text -> text == null || text.isBlank()) || type == null) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.INVALID_INPUT, "Embedding inputs cannot be empty.", false));
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        JsonArray inputs = new JsonArray();
        String prefix = type == InputType.QUERY ? settings.queryPrefix() : settings.documentPrefix();
        texts.forEach(text -> inputs.add(prefix + text));
        body.add("input", inputs);
        body.addProperty("encoding_format", "float");
        if (settings.dimensions() > 0) body.addProperty("dimensions", settings.dimensions());
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(settings.endpoint()).timeout(settings.timeout())
                    .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.CONFIGURATION, "Invalid embedding API configuration.", false));
        }
        CompletableFuture<HttpResponse<String>> transport;
        try { transport = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(networkFailure(error)); }
        CompletableFuture<List<float[]>> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean cancelled = super.cancel(interrupt);
                if (cancelled) transport.cancel(interrupt);
                return cancelled;
            }
        };
        active.add(result);
        result.whenComplete((value, error) -> active.remove(result));
        if (closed.get()) result.cancel(true);
        int count = texts.size();
        transport.whenComplete((response, error) -> {
            if (result.isDone()) return;
            if (error != null) {
                if (unwrap(error) instanceof CancellationException) result.cancel(true);
                else result.completeExceptionally(networkFailure(error));
                return;
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                result.completeExceptionally(new RagException(RagException.Reason.EMBEDDING_ERROR,
                        "Embedding API rejected the request (HTTP " + status + ").", status == 429 || status >= 500));
                return;
            }
            try { result.complete(parse(response.body(), count)); }
            catch (RuntimeException invalid) {
                result.completeExceptionally(new RagException(RagException.Reason.EMBEDDING_ERROR,
                        "Embedding API returned invalid vectors or an unexpected response format.", false));
            }
        });
        return result;
    }

    private List<float[]> parse(String body, int count) {
        if (body.length() > 16_000_000) throw new IllegalArgumentException("Response is too large.");
        JsonArray data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
        if (data.size() != count) throw new IllegalArgumentException("Embedding count mismatch.");
        float[][] ordered = new float[count][];
        int dimension = settings.dimensions();
        for (JsonElement element : data) {
            JsonObject item = element.getAsJsonObject();
            int index = item.get("index").getAsBigDecimal().intValueExact();
            if (index < 0 || index >= count || ordered[index] != null) throw new IllegalArgumentException("Invalid embedding index.");
            JsonArray values = item.getAsJsonArray("embedding");
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = values.get(i).getAsFloat();
            RagFiles.validateVector(vector, dimension);
            dimension = vector.length;
            ordered[index] = vector;
        }
        return List.copyOf(Arrays.asList(ordered));
    }

    private static RagException networkFailure(Throwable error) {
        Throwable cause = unwrap(error);
        boolean timeout = cause instanceof HttpTimeoutException || cause instanceof TimeoutException;
        return new RagException(timeout ? RagException.Reason.TIMEOUT : RagException.Reason.NETWORK_ERROR,
                timeout ? "Embedding request timed out." : "Unable to contact the embedding API.", true);
    }
    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.forEach(future -> future.cancel(true));
        http.shutdownNow();
    }
}
