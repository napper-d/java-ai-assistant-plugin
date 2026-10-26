package edu.teamc.courseaiassistant.rag;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Bridges CompletableFuture cancellation to the actual worker and its current child operation. */
final class RagTaskRunner implements AutoCloseable {
    private final ExecutorService workers = Executors.newFixedThreadPool(2, work -> {
        Thread thread = new Thread(work, "CourseAI-RAG"); thread.setDaemon(true); return thread;
    });
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    static final class Context {
        private boolean cancelled;
        private Future<?> worker;
        private Future<?> child;
        synchronized void worker(Future<?> value) { worker = value; if (cancelled) value.cancel(true); }
        synchronized void cancel() {
            cancelled = true;
            if (child != null) child.cancel(true);
            if (worker != null) worker.cancel(true);
        }
        synchronized void check() { if (cancelled) throw new CancellationException(); RagFiles.checkCancelled(); }
        <T> T await(CompletableFuture<T> future) {
            return await(future, RagException.Reason.EMBEDDING_ERROR, "Unable to generate course embeddings.");
        }
        <T> T await(CompletableFuture<T> future, RagException.Reason reason, String safeMessage) {
            synchronized (this) { child = future; if (cancelled) future.cancel(true); }
            try { return future.get(); }
            catch (InterruptedException error) { future.cancel(true); Thread.currentThread().interrupt(); throw new CancellationException(); }
            catch (ExecutionException error) {
                if (error.getCause() instanceof RagException rag) throw rag;
                if (error.getCause() instanceof CancellationException cancelled) throw cancelled;
                throw new RagException(reason, safeMessage, true);
            } finally { synchronized (this) { if (child == future) child = null; } }
        }
    }

    <T> CompletableFuture<T> submit(Function<Context, T> action) {
        Context context = new Context();
        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean changed = super.cancel(interrupt);
                if (changed) context.cancel();
                return changed;
            }
        };
        active.add(result);
        result.whenComplete((value, error) -> active.remove(result));
        if (closed.get()) {
            result.completeExceptionally(new RagException(RagException.Reason.CLOSED, "RAG service is closed.", false));
            return result;
        }
        try {
            context.worker(workers.submit(() -> {
                try { context.check(); T value = action.apply(context); context.check(); result.complete(value); }
                catch (CancellationException error) { result.cancel(true); }
                catch (RagException error) { result.completeExceptionally(error); }
                catch (RuntimeException error) {
                    result.completeExceptionally(new RagException(RagException.Reason.INDEX_CORRUPT,
                            "Unable to complete the local retrieval operation.", false, error));
                }
            }));
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(new RagException(RagException.Reason.CLOSED, "RAG service is closed.", false));
        }
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.forEach(future -> future.cancel(true));
        workers.shutdownNow();
    }
}
