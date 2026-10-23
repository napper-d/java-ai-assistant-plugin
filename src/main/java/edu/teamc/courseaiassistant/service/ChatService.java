package edu.teamc.courseaiassistant.service;

import edu.teamc.courseaiassistant.model.ChatAnswer;
import edu.teamc.courseaiassistant.model.ChatRequest;
import edu.teamc.courseaiassistant.model.ServiceFailure;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Service for chat question answering.
 * Implementations may use LLM APIs or local models.
 */
public interface ChatService {

    /**
     * Asks a question with optional code context.
     * Returns a future that completes with either an answer or a failure.
     */
    @NotNull
    CompletableFuture<ChatAnswer> ask(@NotNull ChatRequest request);
}
