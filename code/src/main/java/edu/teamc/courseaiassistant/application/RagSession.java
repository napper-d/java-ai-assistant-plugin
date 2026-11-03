package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.rag.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** One immutable configuration generation; all consumers share one lazily created transport. */
final class RagSession implements AutoCloseable {
    final CourseIndexer indexer;
    final LocalKnowledgeSearch search;
    final KnowledgeBaseManager library;
    private final LazyEmbedding embeddings;

    RagSession(RagConfig config, Supplier<String> credential) {
        embeddings = new LazyEmbedding(config, credential);
        indexer = new CourseIndexer(config, embeddings);
        search = new LocalKnowledgeSearch(config, embeddings);
        library = new KnowledgeBaseManager(config, indexer, search::reloadSummary);
    }

    @Override public void close() {
        library.close();
        search.close();
        indexer.close();
        embeddings.close();
    }

    private static final class LazyEmbedding implements EmbeddingClient {
        private final RagConfig config;
        private final Supplier<String> credential;
        private EmbeddingClient delegate;
        private boolean closed;

        LazyEmbedding(RagConfig config, Supplier<String> credential) {
            this.config = config; this.credential = credential;
        }

        private EmbeddingClient client() {
            synchronized (this) {
                if (closed) throw closed();
                if (delegate != null) return delegate;
            }
            // Do not hold the lifecycle lock while PasswordSafe waits for an OS store.
            String key;
            try { key = credential.get(); }
            catch (RuntimeException error) {
                throw new RagException(RagException.Reason.CONFIGURATION,
                        "Unable to read the embedding API key. Check IDE Password Safe settings.", true);
            }
            synchronized (this) {
                if (closed) throw closed();
                if (delegate == null) {
                    var original = config.embedding();
                    delegate = new HttpEmbeddingClient(new RagConfig.EmbeddingSettings(original.provider(),
                            original.baseUrl(), original.model(), key, original.dimensions(), original.timeout(),
                            original.documentPrefix(), original.queryPrefix()));
                }
                return delegate;
            }
        }

        @Override public CompletableFuture<List<float[]>> embed(List<String> texts, InputType type) {
            try { return client().embed(texts, type); }
            catch (RagException error) { return CompletableFuture.failedFuture(error); }
        }

        private static RagException closed() {
            return new RagException(RagException.Reason.CLOSED, "Course RAG settings changed or the project closed.", false);
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (delegate != null) delegate.close();
        }
    }
}
