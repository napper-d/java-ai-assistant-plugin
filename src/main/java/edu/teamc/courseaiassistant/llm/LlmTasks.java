package edu.teamc.courseaiassistant.llm;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

/** Owns background invocation, total deadlines and a cancellation bridge across sequential stages. */
final class LlmTasks implements AutoCloseable {
    @FunctionalInterface interface Work<T> { T run(Context context) throws Exception; }
    static final class Context {
        private CompletableFuture<?> active;
        private boolean cancelled;
        synchronized void cancel() { cancelled = true; if (active != null) active.cancel(true); }
        synchronized void check() { if (cancelled) throw new CancellationException(); }
        <T> T await(CompletableFuture<T> future) throws Exception {
            synchronized (this) {
                if (cancelled) { future.cancel(true); throw new CancellationException(); }
                active = future;
            }
            try { return future.get(); }
            finally { synchronized (this) { if (active == future) active = null; } }
        }
    }
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "course-llm-deadline"); thread.setDaemon(true); return thread;
    });
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private boolean closed;

    synchronized <T> CompletableFuture<T> submit(String id, Duration timeout, Work<T> work) {
        if (closed) return CompletableFuture.failedFuture(LlmFailures.map(id, new IllegalStateException()));
        Context context = new Context();
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        Future<?> worker = workers.submit(() -> {
            try { context.check(); result.complete(work.run(context)); }
            catch (Throwable error) { result.completeExceptionally(LlmFailures.map(id, error)); }
        });
        ScheduledFuture<?> deadline = deadlines.schedule(() ->
                result.completeExceptionally(LlmFailures.map(id, new TimeoutException())), timeout.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete((value, error) -> {
            context.cancel(); worker.cancel(true); deadline.cancel(false);
            synchronized (LlmTasks.this) { pending.remove(result); }
        });
        return result;
    }

    @Override public synchronized void close() {
        closed = true;
        for (CompletableFuture<?> future : Set.copyOf(pending)) future.cancel(true);
        workers.shutdownNow(); deadlines.shutdownNow();
    }
}
