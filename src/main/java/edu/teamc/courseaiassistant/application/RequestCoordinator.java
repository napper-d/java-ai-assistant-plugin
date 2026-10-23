package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.ChatService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/**
 * Coordinates chat requests between UI and services.
 * Handles async execution, cancellation, and lifecycle management.
 */
public final class RequestCoordinator implements Disposable {

    private final @NotNull Project project;
    private final @NotNull ChatService chatService;
    private final @NotNull Map<String, CompletableFuture<?>> pendingRequests = new ConcurrentHashMap<>();

    public RequestCoordinator(@NotNull Project project, @NotNull ChatService chatService) {
        this.project = project;
        this.chatService = chatService;
    }

    /**
     * Callback interface for chat request lifecycle events.
     */
    public interface ChatCallback {
        void onRequestStarted(@NotNull String requestId);
        void onRequestCompleted(@NotNull ChatAnswer answer);
        void onRequestFailed(@NotNull ServiceFailure failure);
        void onRequestCancelled(@NotNull String requestId);
    }

    /**
     * Submits a chat request asynchronously.
     * Returns immediately; results are delivered via callback on the EDT.
     */
    public void submitChatRequest(@NotNull ChatRequest request, @NotNull ChatCallback callback) {
        if (project.isDisposed()) {
            return;
        }

        String requestId = request.getRequestId();
        callback.onRequestStarted(requestId);

        CompletableFuture<ChatAnswer> future = chatService.ask(request);
        pendingRequests.put(requestId, future);

        future.whenComplete((answer, error) -> {
            pendingRequests.remove(requestId);

            if (project.isDisposed()) {
                return;
            }

            SwingUtilities.invokeLater(() -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                    if (cause instanceof ServiceFailure sf) {
                        callback.onRequestFailed(sf);
                    } else {
                        ServiceFailure failure = new ServiceFailure(
                                requestId,
                                ServiceFailure.Category.UNKNOWN,
                                "Unexpected error: " + cause.getMessage(),
                                false
                        );
                        callback.onRequestFailed(failure);
                    }
                } else if (answer != null) {
                    callback.onRequestCompleted(answer);
                }
            });
        });
    }

    /**
     * Cancels a pending request if it exists.
     */
    public void cancelRequest(@NotNull String requestId) {
        CompletableFuture<?> future = pendingRequests.remove(requestId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            if (!project.isDisposed()) {
                SwingUtilities.invokeLater(() -> {
                });
            }
        }
    }

    /**
     * Cancels all pending requests.
     */
    public void cancelAll() {
        pendingRequests.forEach((id, future) -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        pendingRequests.clear();
    }

    /**
     * Checks if there are any pending requests.
     */
    public boolean hasPendingRequests() {
        return !pendingRequests.isEmpty();
    }

    @Override
    public void dispose() {
        cancelAll();
    }
}
