package edu.teamc.courseaiassistant.rag;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Implementations preserve input order and propagate cancellation to their owned transport. */
public interface EmbeddingClient extends AutoCloseable {
    enum InputType { DOCUMENT, QUERY }
    CompletableFuture<List<float[]>> embed(List<String> texts, InputType type);
    @Override default void close() {}
}
