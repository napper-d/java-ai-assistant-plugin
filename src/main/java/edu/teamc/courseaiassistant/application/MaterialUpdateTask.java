package edu.teamc.courseaiassistant.application;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Registration/removal followed by indexing, with cancellation across the phase transition. */
public final class MaterialUpdateTask {
    private MaterialUpdateTask() {}
    public static CompletableFuture<KnowledgeBaseSnapshot> start(KnowledgeBaseOperations operations,
                                                                List<Path> paths, boolean remove) {
        return start(operations, paths, remove, ignored -> {});
    }
    public static CompletableFuture<KnowledgeBaseSnapshot> start(KnowledgeBaseOperations operations,
                    List<Path> paths, boolean remove, java.util.function.Consumer<KnowledgeBaseSnapshot> onRegistered) {
        class Update extends CompletableFuture<KnowledgeBaseSnapshot> {
            CompletableFuture<?> active;
            synchronized void attach(CompletableFuture<?> child) {
                active = child; if (isCancelled()) child.cancel(true);
            }
            @Override public synchronized boolean cancel(boolean interrupt) {
                boolean changed = super.cancel(interrupt);
                if (changed && active != null) active.cancel(interrupt);
                return changed;
            }
            synchronized void index(KnowledgeBaseSnapshot registered) {
                if (isDone()) return;
                try { onRegistered.accept(registered); }
                catch (RuntimeException error) { completeExceptionally(error); return; }
                if (!registered.embeddingConfigured() || registered.documents().isEmpty()) { complete(registered); return; }
                try {
                    var build = operations.rebuildLibrary(); attach(build);
                    build.whenComplete((value, error) -> { if (error != null) completeExceptionally(error); else complete(value); });
                } catch (RuntimeException error) { completeExceptionally(error); }
            }
        }
        Update result = new Update();
        try {
            var registration = remove ? operations.unregisterMaterials(List.copyOf(paths)) : operations.registerMaterials(List.copyOf(paths));
            result.attach(registration);
            registration.whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); else result.index(value); });
        } catch (RuntimeException error) { result.completeExceptionally(error); }
        return result;
    }
}
