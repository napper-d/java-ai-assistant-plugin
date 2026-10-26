package edu.teamc.courseaiassistant.rag;

/** Safe retrieval failure. The chat adapter supplies its own request ID when mapping this to ServiceFailure. */
public final class RagException extends RuntimeException {
    public enum Reason { INVALID_INPUT, CONFIGURATION, DOCUMENT_ERROR, INDEX_UNAVAILABLE,
        INDEX_INCOMPATIBLE, INDEX_CORRUPT, STORAGE_ERROR, EMBEDDING_ERROR, NETWORK_ERROR, TIMEOUT, RETRIEVAL_ERROR, CLOSED }

    private final Reason reason;
    private final boolean retryable;

    public RagException(Reason reason, String message, boolean retryable) {
        this(reason, message, retryable, null);
    }

    public RagException(Reason reason, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason);
        this.retryable = retryable;
    }

    public Reason getReason() { return reason; }
    public boolean isRetryable() { return retryable; }
}
