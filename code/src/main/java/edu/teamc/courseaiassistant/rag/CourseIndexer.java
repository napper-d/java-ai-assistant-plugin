package edu.teamc.courseaiassistant.rag;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owns its background workers; borrows the EmbeddingClient, which the composition owner closes. */
public final class CourseIndexer implements AutoCloseable {
    public record IndexResult(int documents, int chunks, int dimensions) {}
    private final RagConfig config;
    private final EmbeddingClient embeddings;
    private final RagTaskRunner tasks = new RagTaskRunner();

    public CourseIndexer(RagConfig config, EmbeddingClient embeddings) {
        this.config = Objects.requireNonNull(config); this.embeddings = Objects.requireNonNull(embeddings);
    }

    /** Registers original paths without copying files or invoking the API; callers choose when to rebuild. */
    public CompletableFuture<List<DocumentImporter.ImportedDocument>> importFiles(List<Path> paths) {
        if (paths == null || paths.isEmpty() || paths.stream().anyMatch(Objects::isNull)) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.INVALID_INPUT, "Select at least one course file.", false));
        }
        List<Path> captured = List.copyOf(paths);
        return tasks.submit(context -> {
            DocumentImporter importer = new DocumentImporter(config);
            List<DocumentImporter.ImportedDocument> result = new ArrayList<>();
            for (Path path : captured) { context.check(); result.add(importer.importFile(path)); }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<IndexResult> rebuild() {
        return tasks.submit(context -> {
            try {
                Files.createDirectories(config.indexDirectory());
                try (FileChannel channel = FileChannel.open(config.indexDirectory().resolve(".rebuild.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    try (FileLock lock = channel.tryLock()) {
                        if (lock == null) throw busy();
                        return build(context);
                    }
                }
            } catch (OverlappingFileLockException error) { throw busy(); }
            catch (IOException error) {
                throw new RagException(RagException.Reason.STORAGE_ERROR, "Unable to lock or update the course index.", true, error);
            }
        });
    }

    private IndexResult build(RagTaskRunner.Context context) {
        var documents = new DocumentImporter(config).listDocuments();
        if (documents.isEmpty()) throw new RagException(RagException.Reason.INVALID_INPUT, "Import PDF/PPTX materials before building an index.", false);
        DocumentLoader loader = new DocumentLoader(config.includeSlideNotes());
        ChunkSplitter splitter = new ChunkSplitter(config.chunkCharacters(), config.overlapCharacters());
        List<LocalIndexStore.Entry> entries = new ArrayList<>();
        List<IndexManifest.SourceFile> sources = new ArrayList<>();
        Set<String> documentIds = new HashSet<>();
        int dimensions = config.embedding().dimensions();
        for (var document : documents) {
            context.check();
            if (!documentIds.add(document.documentId())) continue;
            List<DocumentChunk> chunks = splitter.split(loader.load(document.path(), document.documentId()));
            for (int start = 0; start < chunks.size(); start += config.batchSize()) {
                context.check();
                var batch = chunks.subList(start, Math.min(chunks.size(), start + config.batchSize()));
                List<float[]> vectors = context.await(embeddings.embed(batch.stream().map(DocumentChunk::text).toList(), EmbeddingClient.InputType.DOCUMENT));
                if (vectors == null || vectors.size() != batch.size()) throw invalidVectors();
                for (int i = 0; i < batch.size(); i++) {
                    try { RagFiles.validateVector(vectors.get(i), dimensions); }
                    catch (IllegalArgumentException error) { throw invalidVectors(); }
                    dimensions = vectors.get(i).length;
                    entries.add(new LocalIndexStore.Entry(batch.get(i), vectors.get(i)));
                }
            }
            try {
                if (!document.sha256().equals(RagFiles.sha256(document.path()))) {
                    throw new RagException(RagException.Reason.DOCUMENT_ERROR, "A course file changed during indexing. Rebuild it.", true);
                }
            } catch (IOException error) {
                throw new RagException(RagException.Reason.DOCUMENT_ERROR, "A course file became unavailable during indexing.", true);
            }
            sources.add(new IndexManifest.SourceFile(document.documentId(), document.documentName(), document.sha256()));
        }
        context.check();
        new LocalIndexStore(config).save(entries, dimensions, sources);
        return new IndexResult(sources.size(), entries.size(), dimensions);
    }

    private static RagException invalidVectors() { return new RagException(RagException.Reason.EMBEDDING_ERROR, "Embedding vectors have invalid counts or dimensions.", false); }
    private static RagException busy() { return new RagException(RagException.Reason.STORAGE_ERROR, "This course index is already being rebuilt. Try again later.", true); }
    @Override public void close() { tasks.close(); }
}
