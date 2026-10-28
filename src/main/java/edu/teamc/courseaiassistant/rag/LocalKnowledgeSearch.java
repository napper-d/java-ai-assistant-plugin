package edu.teamc.courseaiassistant.rag;

import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import edu.teamc.courseaiassistant.service.KnowledgeSearch;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Async retrieval adapter. Owns workers, borrows the embedding client; reload() switches generations. */
public final class LocalKnowledgeSearch implements KnowledgeSearch, AutoCloseable {
    public record IndexSummary(int chunks, int dimensions, Set<String> documentIds) {
        public IndexSummary { documentIds = Set.copyOf(documentIds); }
    }
    private final RagConfig config;
    private final EmbeddingClient embeddings;
    private final LocalIndexStore storage;
    private final RagTaskRunner tasks = new RagTaskRunner();
    private volatile LocalIndexStore.Snapshot snapshot;
    private final Object loadLock = new Object();

    public LocalKnowledgeSearch(RagConfig config, EmbeddingClient embeddings) {
        this.config = Objects.requireNonNull(config); this.embeddings = Objects.requireNonNull(embeddings);
        storage = new LocalIndexStore(config);
    }

    @Override public CompletableFuture<List<KnowledgeChunk>> search(String query, int maxResults) {
        if (query == null || query.isBlank() || maxResults < 1 || maxResults > 100) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.INVALID_INPUT,
                    "Enter a question and request between 1 and 100 results.", false));
        }
        return tasks.submit(context -> {
            LocalIndexStore.Snapshot current = snapshot;
            if (current == null) {
                synchronized (loadLock) {
                    context.check();
                    current = snapshot;
                    if (current == null) { current = storage.load(); context.check(); snapshot = current; }
                }
            }
            context.check();
            var vectors = context.await(embeddings.embed(List.of(query.strip()), EmbeddingClient.InputType.QUERY));
            try {
                if (vectors == null || vectors.size() != 1) throw new IllegalArgumentException();
                RagFiles.validateVector(vectors.getFirst(), current.dimensions());
            } catch (IllegalArgumentException error) {
                throw new RagException(RagException.Reason.EMBEDDING_ERROR, "Query embedding does not match the course index.", false);
            }
            return current.search(vectors.getFirst(), config.minScore(), maxResults, context);
        });
    }

    /** Explicitly reload after a successful rebuild; in-flight queries retain their original snapshot. */
    public CompletableFuture<Integer> reload() {
        return reload(LocalIndexStore.Snapshot::chunkCount);
    }

    /** Metadata describes the very snapshot loaded, without reading original course files or calling embeddings. */
    public CompletableFuture<IndexSummary> reloadSummary() {
        return reload(current -> new IndexSummary(current.chunkCount(), current.dimensions(), current.documentIds()));
    }

    private <T> CompletableFuture<T> reload(Function<LocalIndexStore.Snapshot, T> describe) {
        return tasks.submit(context -> {
            synchronized (loadLock) {
                context.check();
                var replacement = storage.load();
                T result = describe.apply(replacement);
                context.check(); snapshot = replacement;
                return result;
            }
        });
    }

    @Override public void close() { tasks.close(); }
}
