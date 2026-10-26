package edu.teamc.courseaiassistant.rag;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Pluggable planning policy; implementations may use rules or an asynchronously cancellable model client. */
@FunctionalInterface
public interface FollowUpQueryPlanner {
    record Passage(DocumentChunk chunk, double score) {
        public Passage { Objects.requireNonNull(chunk); }
    }

    /** A reference triggers exact definition lookup; otherwise text triggers semantic retrieval. */
    record Query(String text, String documentId, String reference, String sourceChunkId) {
        public Query(String text, String documentId, String reference) { this(text, documentId, reference, null); }
        public Query {
            if (text == null || text.isBlank() || text.length() > 2048) throw new IllegalArgumentException("Invalid follow-up query.");
            text = text.strip();
            if (documentId != null && documentId.isBlank()) throw new IllegalArgumentException("Invalid document scope.");
            if (reference != null && (reference.isBlank() || reference.length() > 64)) throw new IllegalArgumentException("Invalid reference.");
            if (reference != null) reference = reference.strip();
            if (sourceChunkId != null && sourceChunkId.isBlank()) throw new IllegalArgumentException("Invalid evidence identity.");
        }
    }

    /** Initial evidence contains semantic candidates; later rounds contain new evidence. */
    CompletableFuture<List<Query>> plan(String question, List<Passage> evidence);
}
