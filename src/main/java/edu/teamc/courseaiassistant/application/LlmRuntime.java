package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Lazy project transport owner. Startup never holds the lifecycle lock or runs on the EDT. */
final class LlmRuntime implements AutoCloseable {
    interface Session extends ChatService, CodeGenerationService, AutoCloseable {
        @Override void close();
    }

    private final Supplier<Session> factory;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private final Set<CompletableFuture<?>> chats = new HashSet<>();
    private Generation current;
    private boolean closed;

    LlmRuntime(Supplier<Session> factory) { this.factory = factory; }

    CompletableFuture<ChatAnswer> ask(ChatRequest request) {
        return invoke(request.getRequestId(), true, session -> session.ask(request));
    }

    CompletableFuture<ChangeProposal> generate(ModificationRequest request) {
        return invoke(request.requestId(), false, session -> session.generate(request));
    }

    private synchronized <T> CompletableFuture<T> invoke(String id, boolean chat,
                                                        Function<Session, CompletableFuture<T>> operation) {
        if (closed) return CompletableFuture.failedFuture(new ServiceFailure(id, ServiceFailure.Category.UNKNOWN,
                "The model service is closed.", false));
        if (current == null) {
            current = new Generation();
            Generation generation = current;
            workers.submit(() -> initialize(generation));
        }
        Generation generation = current;
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<T>> child = new AtomicReference<>();
        pending.add(result);
        if (chat) chats.add(result);
        Future<?> worker = workers.submit(() -> {
            try {
                Session session = generation.ready.get();
                synchronized (this) {
                    if (result.isDone() || closed || generation != current) { result.cancel(true); return; }
                    // Implementations only schedule work here; no credentials or network waits under this lock.
                    child.set(Objects.requireNonNull(operation.apply(session)));
                }
                child.get().whenComplete((value, error) -> {
                    if (error == null) result.complete(value);
                    else fail(result, id, error);
                });
                if (result.isCancelled()) child.get().cancel(true);
            } catch (Throwable error) { fail(result, id, error); }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                worker.cancel(true);
                CompletableFuture<T> active = child.get();
                if (active != null) active.cancel(true);
            }
            synchronized (this) { pending.remove(result); chats.remove(result); }
        });
        return result;
    }

    private void initialize(Generation generation) {
        Session session;
        try { session = factory.get(); }
        catch (Throwable error) {
            synchronized (this) {
                if (current == generation) current = null; // A failed unlock/configuration can be retried.
            }
            generation.ready.completeExceptionally(error);
            return;
        }
        synchronized (this) {
            if (!closed && current == generation) {
                generation.session = session;
                generation.ready.complete(session);
                return;
            }
        }
        session.close(); // Password Safe may have returned after disposal or reconfiguration.
        generation.ready.cancel(true);
    }

    private static void fail(CompletableFuture<?> result, String id, Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null)
            error = error.getCause();
        if (error instanceof CancellationException) result.cancel(true);
        else if (error instanceof ServiceFailure failure)
            result.completeExceptionally(new ServiceFailure(id, failure.getCategory(), failure.getMessage(), failure.isRetryable()));
        else result.completeExceptionally(new ServiceFailure(id, ServiceFailure.Category.INVALID_INPUT,
                "Unable to initialize the model service. Check Language model settings and IDE Password Safe, then retry.", false));
    }

    synchronized void cancelChats() {
        for (CompletableFuture<?> request : Set.copyOf(chats)) request.cancel(true);
    }

    synchronized void invalidate() {
        Generation previous = current;
        current = null;
        for (CompletableFuture<?> request : Set.copyOf(pending)) request.cancel(true);
        if (previous != null) {
            previous.ready.cancel(true);
            if (previous.session != null) previous.session.close();
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        invalidate();
        workers.shutdownNow();
    }

    private static final class Generation {
        final CompletableFuture<Session> ready = new CompletableFuture<>();
        Session session;
    }
}
