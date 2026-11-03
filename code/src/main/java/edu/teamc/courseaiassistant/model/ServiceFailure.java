package edu.teamc.courseaiassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Failure from a service call with category and retryability.
 */
public final class ServiceFailure extends Exception {
    private final @NotNull String requestId;
    private final @NotNull Category category;
    private final @NotNull String message;
    private final boolean retryable;

    public enum Category {
        TIMEOUT,
        NETWORK_ERROR,
        MODEL_ERROR,
        INVALID_INPUT,
        UNKNOWN
    }

    public ServiceFailure(
            @NotNull String requestId,
            @NotNull Category category,
            @NotNull String message,
            boolean retryable
    ) {
        super(message);
        this.requestId = Objects.requireNonNull(requestId);
        this.category = Objects.requireNonNull(category);
        this.message = Objects.requireNonNull(message);
        this.retryable = retryable;
    }

    @NotNull
    public String getRequestId() {
        return requestId;
    }

    @NotNull
    public Category getCategory() {
        return category;
    }

    @NotNull
    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return "ServiceFailure{requestId=" + requestId + ", category=" + category +
                ", retryable=" + retryable + ", message=" + message + "}";
    }
}
