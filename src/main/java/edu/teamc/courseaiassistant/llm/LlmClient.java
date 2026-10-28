package edu.teamc.courseaiassistant.llm;

import java.util.concurrent.CompletableFuture;

/** Borrowed by services. Caller owns close; cancellation must propagate to transport. */
public interface LlmClient extends AutoCloseable {
    CompletableFuture<String> complete(String requestId, String systemPrompt, String userPrompt);
    @Override void close();
}
