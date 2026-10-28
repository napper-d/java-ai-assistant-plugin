package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.rag.*;
import edu.teamc.courseaiassistant.rag.DocumentImporter.RegisteredDocument;
import edu.teamc.courseaiassistant.rag.LocalKnowledgeSearch.IndexSummary;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static edu.teamc.courseaiassistant.application.KnowledgeBaseSnapshot.IndexState.*;

/** Serializes library work for one configuration generation; borrows indexer/search services. */
final class KnowledgeBaseManager implements AutoCloseable {
    private final RagConfig config;
    private final DocumentImporter importer;
    private final CourseIndexer indexer;
    private final Supplier<CompletableFuture<IndexSummary>> reload;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "CourseAI-library"); thread.setDaemon(true); return thread;
    });
    private CompletableFuture<?> active;
    private boolean closed;
    // Accessed only by the serial worker; file-content freshness is never asserted after reopening.
    private boolean dirty;
    private boolean reloadPending;
    private CourseIndexer.IndexResult published;

    KnowledgeBaseManager(RagConfig config, CourseIndexer indexer, Supplier<CompletableFuture<IndexSummary>> reload) {
        this.config = config; this.importer = new DocumentImporter(config); this.indexer = indexer; this.reload = reload;
    }

    public CompletableFuture<KnowledgeBaseSnapshot> refreshLibrary() {
        return submit(context -> inspect(context, importer.listRegisteredDocuments()));
    }

    public CompletableFuture<MaterialRegistrationResult> registerMaterials(List<Path> paths) {
        if (paths == null || paths.isEmpty() || paths.stream().anyMatch(Objects::isNull)) return invalidSelection();
        List<Path> captured = List.copyOf(paths);
        return submit(context -> {
            // Imports are intentionally not transactional: earlier successful registrations may remain on failure.
            boolean previouslyDirty = dirty, previouslyReloadPending = reloadPending;
            dirty = true;
            reloadPending = false;
            var imported = context.await(indexer.importFiles(captured));
            int added = (int) imported.stream().filter(document -> !document.reused()).count();
            if (added == 0) {
                dirty = previouslyDirty; reloadPending = previouslyReloadPending;
            }
            var duplicates = imported.stream().filter(DocumentImporter.ImportedDocument::reused)
                    .map(DocumentImporter.ImportedDocument::path).distinct().toList();
            return new MaterialRegistrationResult(inspect(context, importer.listRegisteredDocuments()), added, duplicates);
        });
    }

    public CompletableFuture<KnowledgeBaseSnapshot> unregisterMaterials(List<Path> paths) {
        if (paths == null || paths.isEmpty() || paths.stream().anyMatch(Objects::isNull)) return invalidSelection();
        List<Path> captured = List.copyOf(paths);
        return submit(context -> {
            for (Path path : captured) {
                context.check();
                if (importer.unregisterFile(path)) { dirty = true; reloadPending = false; }
            }
            return inspect(context, importer.listRegisteredDocuments());
        });
    }

    public CompletableFuture<KnowledgeBaseSnapshot> rebuildLibrary() {
        return submit(context -> {
            List<RegisteredDocument> documents = importer.listRegisteredDocuments();
            published = context.await(indexer.rebuild());
            reloadPending = true;
            return inspect(context, documents);
        });
    }

    private KnowledgeBaseSnapshot inspect(Context context, List<RegisteredDocument> documents) {
        context.check();
        boolean configured = !config.embedding().baseUrl().isBlank() && !config.embedding().model().isBlank();
        try {
            IndexSummary summary = context.await(reload.get());
            if (reloadPending) { dirty = false; reloadPending = false; }
            Set<String> registered = documents.stream().map(RegisteredDocument::documentId).collect(Collectors.toSet());
            boolean changed = dirty || !registered.equals(summary.documentIds());
            return new KnowledgeBaseSnapshot(documents, changed ? NEEDS_REBUILD : AVAILABLE,
                    summary.documentIds().size(), summary.chunks(), configured, changed
                    ? documents.isEmpty()
                        ? "No files are registered. The saved index still contains previous material. Add files and rebuild to replace it."
                        : "Registered materials changed. The saved index may still contain removed files; rebuild to update it."
                    : "Saved index loaded. File contents are checked only when rebuilding; rebuild after editing course files.");
        } catch (RagException error) {
            if (reloadPending) {
                return new KnowledgeBaseSnapshot(documents, RELOAD_REQUIRED, published.documents(), published.chunks(), configured,
                        "Index saved, but loading failed. Use Refresh / Load index to retry. " + error.getMessage());
            }
            var state = switch (error.getReason()) {
                case INDEX_UNAVAILABLE -> NO_INDEX;
                case INDEX_INCOMPATIBLE -> INCOMPATIBLE;
                default -> INDEX_ERROR;
            };
            return new KnowledgeBaseSnapshot(documents, state, 0, 0, configured, error.getMessage());
        }
    }

    private static <T> CompletableFuture<T> invalidSelection() {
        return CompletableFuture.failedFuture(new RagException(RagException.Reason.INVALID_INPUT,
                "Select at least one course file.", false));
    }

    private synchronized <T> CompletableFuture<T> submit(Function<Context, T> work) {
        if (closed) return CompletableFuture.failedFuture(new RagException(RagException.Reason.CLOSED,
                "Course materials service is closed.", false));
        if (active != null && !active.isDone()) return CompletableFuture.failedFuture(new RagException(
                RagException.Reason.STORAGE_ERROR, "A course materials operation is already running.", true));
        Context context = new Context();
        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean changed = super.cancel(interrupt);
                if (changed) context.cancel();
                return changed;
            }
        };
        active = result;
        try {
            context.worker(worker.submit(() -> {
                try { context.check(); T value = work.apply(context); context.check(); result.complete(value); }
                catch (CancellationException error) { result.cancel(true); }
                catch (RagException error) { result.completeExceptionally(error); }
                catch (RuntimeException error) { result.completeExceptionally(new RagException(RagException.Reason.STORAGE_ERROR,
                        "Unable to complete the course materials operation. Refresh and try again.", true)); }
            }));
        } catch (RejectedExecutionException error) { result.cancel(true); }
        return result;
    }

    private static final class Context {
        private boolean cancelled;
        private Future<?> worker;
        private Future<?> child;
        synchronized void worker(Future<?> value) { worker = value; if (cancelled) value.cancel(true); }
        synchronized void cancel() {
            cancelled = true;
            if (child != null) child.cancel(true);
            if (worker != null) worker.cancel(true);
        }
        synchronized void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
        <T> T await(CompletableFuture<T> operation) {
            synchronized (this) { child = operation; if (cancelled) operation.cancel(true); }
            try { return operation.get(); }
            catch (InterruptedException error) { operation.cancel(true); Thread.currentThread().interrupt(); throw new CancellationException(); }
            catch (ExecutionException error) {
                if (error.getCause() instanceof CancellationException cancellation) throw cancellation;
                if (error.getCause() instanceof RagException rag) throw rag;
                throw new RagException(RagException.Reason.STORAGE_ERROR, "Unable to update the course index. Refresh and retry.", true);
            } finally { synchronized (this) { if (child == operation) child = null; } }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) active.cancel(true);
        worker.shutdownNow();
    }
}
