package edu.teamc.courseaiassistant.service;

import edu.teamc.courseaiassistant.model.ChatAnswer;
import edu.teamc.courseaiassistant.model.ChatRequest;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Service for chat question answering.
 * Implementations may use LLM APIs or local models.
 */
public interface ChatService {

    /**
     * Asks a question with optional code context.
     * Called off the EDT. Return a non-null future, completed with a non-null answer
     * carrying the same request ID, or completed exceptionally with ServiceFailure.
     * Cancellation of this future must abort owned transport/retrieval work where possible;
     * CompletableFuture.cancel(true) alone does not interrupt arbitrary background work.
     */
    @NotNull
    CompletableFuture<ChatAnswer> ask(@NotNull ChatRequest request);
}
