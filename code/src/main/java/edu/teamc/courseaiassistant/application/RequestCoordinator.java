package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.model.ChatAnswer;
import edu.teamc.courseaiassistant.model.ChatRequest;
import edu.teamc.courseaiassistant.model.ServiceFailure;
import edu.teamc.courseaiassistant.service.ChatService;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns background invocation, deadlines and EDT delivery for one Tool Window content. */
public final class RequestCoordinator implements Disposable {
    private final Project project;
    private final ChatService chatService;
    private final Supplier<Duration> timeout;
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "CourseAI-request");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CourseAI-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean disposed;

    public RequestCoordinator(@NotNull Project project, @NotNull ChatService chatService) {
        this(project, chatService, Duration.ofSeconds(30));
    }

    public RequestCoordinator(@NotNull Project project, @NotNull ChatService chatService,
                              @NotNull Duration timeout) {
        this(project, chatService, fixedTimeout(timeout));
    }

    /** Snapshot the current project's deadline for each request, including after settings changes. */
    public RequestCoordinator(@NotNull Project project, @NotNull ChatService chatService,
                              @NotNull Supplier<Duration> timeout) {
        this.project = Objects.requireNonNull(project);
        this.chatService = Objects.requireNonNull(chatService);
        this.timeout = Objects.requireNonNull(timeout);
    }

    private static Supplier<Duration> fixedTimeout(Duration timeout) {
        if (Objects.requireNonNull(timeout).toMillis() < 1)
            throw new IllegalArgumentException("Timeout must be at least 1 ms");
        return () -> timeout;
    }

    /** All callbacks run on the EDT; terminal callbacks are suppressed after disposal. */
    public interface ChatCallback {
        void onRequestStarted(@NotNull String requestId);
        void onRequestCompleted(@NotNull ChatAnswer answer);
        void onRequestFailed(@NotNull ServiceFailure failure);
        void onRequestCancelled(@NotNull String requestId);
    }

    public void submitChatRequest(@NotNull ChatRequest request, @NotNull ChatCallback callback) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> submitChatRequest(request, callback));
            return;
        }
        if (!isActive()) return;
        PendingRequest pending = new PendingRequest(request.getRequestId(), callback);
        if (pendingRequests.putIfAbsent(pending.id, pending) != null) {
            throw new IllegalArgumentException("Request ID is already pending");
        }
        callback.onRequestStarted(pending.id);
        try {
            long timeoutMillis = Objects.requireNonNull(timeout.get()).toMillis();
            if (timeoutMillis < 1) throw new IllegalArgumentException("Timeout must be at least 1 ms");
            pending.deadline = deadlines.schedule(() -> {
                if (complete(pending, null, new ServiceFailure(pending.id,
                        ServiceFailure.Category.TIMEOUT,
                        "Request timed out after " + timeoutMillis + "ms", true))) {
                    pending.stopWork();
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            if (pending.finished.get()) pending.deadline.cancel(false);
            pending.worker = workers.submit(() -> invokeService(request, pending));
            if (pending.stopRequested.get()) pending.worker.cancel(true);
        } catch (RuntimeException error) {
            complete(pending, null, error);
        }
    }

    private void invokeService(ChatRequest request, PendingRequest pending) {
        if (pending.stopRequested.get() || !isActive()) return;
        try {
            CompletableFuture<ChatAnswer> future = Objects.requireNonNull(chatService.ask(request));
            pending.serviceFuture = future;
            if (pending.stopRequested.get()) future.cancel(true);
            future.whenComplete((answer, error) -> complete(pending, answer, error));
        } catch (Exception error) {
            // Includes a service throwing before it returns its future.
            complete(pending, null, error);
        }
    }

    private boolean complete(PendingRequest pending, ChatAnswer answer, Throwable error) {
        if (!pending.finished.compareAndSet(false, true)) return false;
        if (pending.deadline != null) pending.deadline.cancel(false);
        // Retain the entry until delivery, so cancellation can also invalidate a queued success.
        SwingUtilities.invokeLater(() -> {
            if (!pendingRequests.remove(pending.id, pending) || !isActive()) return;
            if (pending.cancelled.get() || unwrap(error) instanceof CancellationException) {
                pending.callback.onRequestCancelled(pending.id);
            } else if (error != null) {
                pending.callback.onRequestFailed(toFailure(pending.id, error));
            } else if (answer == null || !pending.id.equals(answer.getRequestId())) {
                pending.callback.onRequestFailed(new ServiceFailure(pending.id,
                        ServiceFailure.Category.UNKNOWN, "Service returned an invalid response.", false));
            } else {
                pending.callback.onRequestCompleted(answer);
            }
        });
        return true;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause
                && (cause instanceof CompletionException || cause instanceof ExecutionException
                    || cause.getClass() == RuntimeException.class)) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static ServiceFailure toFailure(String requestId, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof ServiceFailure failure) {
            return new ServiceFailure(requestId, failure.getCategory(), failure.getMessage(), failure.isRetryable());
        }
        if (cause instanceof TimeoutException) {
            return new ServiceFailure(requestId, ServiceFailure.Category.TIMEOUT, "Request timed out.", true);
        }
        // Raw exception messages may contain endpoint URLs, credentials or user code.
        return new ServiceFailure(requestId, ServiceFailure.Category.UNKNOWN,
                "Unable to complete the request. Please try again.", true);
    }

    public void cancelRequest(@NotNull String requestId) {
        PendingRequest pending = pendingRequests.get(requestId);
        if (pending == null) return;
        pending.cancelled.set(true);
        complete(pending, null, new CancellationException());
        pending.stopWork();
    }

    public void cancelAll() {
        pendingRequests.keySet().forEach(this::cancelRequest);
    }

    public boolean hasPendingRequests() {
        return !pendingRequests.isEmpty();
    }

    private boolean isActive() {
        return !disposed && !project.isDisposed();
    }

    @Override public void dispose() {
        disposed = true;
        cancelAll();
        pendingRequests.clear();
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    private static final class PendingRequest {
        private final String id;
        private final ChatCallback callback;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private volatile Future<?> worker;
        private volatile CompletableFuture<ChatAnswer> serviceFuture;
        private volatile ScheduledFuture<?> deadline;

        private PendingRequest(String id, ChatCallback callback) {
            this.id = id;
            this.callback = callback;
        }

        private void stopWork() {
            stopRequested.set(true);
            if (worker != null) worker.cancel(true);
            if (serviceFuture != null) serviceFuture.cancel(true);
        }
    }
}
