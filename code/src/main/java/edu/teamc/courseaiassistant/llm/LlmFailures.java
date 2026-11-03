package edu.teamc.courseaiassistant.llm;

import edu.teamc.courseaiassistant.model.ServiceFailure;
import edu.teamc.courseaiassistant.rag.RagException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.*;

final class LlmFailures {
    private LlmFailures() {}
    static Throwable map(String id, Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        if (error instanceof CancellationException) return error;
        if (error instanceof ServiceFailure failure)
            return new ServiceFailure(id, failure.getCategory(), failure.getMessage(), failure.isRetryable());
        if (error instanceof RagException rag) {
            ServiceFailure.Category category = switch (rag.getReason()) {
                case INVALID_INPUT, CONFIGURATION -> ServiceFailure.Category.INVALID_INPUT;
                case TIMEOUT -> ServiceFailure.Category.TIMEOUT;
                case NETWORK_ERROR -> ServiceFailure.Category.NETWORK_ERROR;
                case EMBEDDING_ERROR -> ServiceFailure.Category.MODEL_ERROR;
                default -> ServiceFailure.Category.UNKNOWN;
            };
            String message = switch (rag.getReason()) {
                case CONFIGURATION -> "Configure the embedding endpoint, model and API key in Course RAG settings.";
                case INDEX_UNAVAILABLE -> "No course index is available. Add course materials to create the index automatically.";
                case INDEX_INCOMPATIBLE -> "The course index does not match the current RAG settings. Use Retry indexing in Course materials.";
                case INDEX_CORRUPT -> "The course index is damaged. Use Retry indexing in Course materials.";
                case NETWORK_ERROR -> "Unable to reach the embedding service. Check Course RAG settings and retry.";
                case TIMEOUT -> "Course retrieval timed out. Check Course RAG settings and retry.";
                case EMBEDDING_ERROR -> "The embedding service failed. Check the model and API key in Course RAG settings.";
                default -> "Course retrieval failed. Check the course index and RAG settings.";
            };
            return new ServiceFailure(id, category, message, rag.isRetryable());
        }
        if (error instanceof HttpTimeoutException || error instanceof TimeoutException)
            return new ServiceFailure(id, ServiceFailure.Category.TIMEOUT, "The model request timed out. Please retry.", true);
        if (error instanceof IOException)
            return new ServiceFailure(id, ServiceFailure.Category.NETWORK_ERROR, "Unable to reach the model service.", true);
        return new ServiceFailure(id, ServiceFailure.Category.UNKNOWN, "The AI request could not be completed.", false);
    }
    static ServiceFailure invalid(String id) {
        return new ServiceFailure(id, ServiceFailure.Category.INVALID_INPUT, "Input is empty or exceeds the configured context limit.", false);
    }
    static ServiceFailure malformed(String id) {
        return new ServiceFailure(id, ServiceFailure.Category.MODEL_ERROR, "The model returned an invalid or incomplete structured response.", false);
    }
}
