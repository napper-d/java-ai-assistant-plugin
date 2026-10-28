package edu.teamc.courseaiassistant.llm;

import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Borrows retrieval and model clients. Closing this service cancels requests, not shared dependencies. */
public final class LlmChatService implements ChatService, AutoCloseable {
    private final KnowledgeSearch search;
    private final LlmClient client;
    private final LlmConfig config;
    private final LlmTasks tasks = new LlmTasks();

    public LlmChatService(KnowledgeSearch search, LlmClient client, LlmConfig config) {
        this.search = Objects.requireNonNull(search); this.client = Objects.requireNonNull(client);
        this.config = Objects.requireNonNull(config);
    }

    @Override public CompletableFuture<ChatAnswer> ask(ChatRequest request) {
        Objects.requireNonNull(request);
        return tasks.submit(request.getRequestId(), config.timeout(), context -> {
            String id = request.getRequestId();
            if (request.getQuestion().isBlank() || request.getQuestion().length() > config.maxInputCharacters())
                throw LlmFailures.invalid(id);
            if (request.getCodeContext() != null
                    && (long) request.getCodeContext().getSelectedText().length() + request.getQuestion().length() > config.maxInputCharacters())
                throw LlmFailures.invalid(id);
            if ((long) LlmPrompts.CHAT.length() + LlmPrompts.chat(request, List.of()).length() > config.maxInputCharacters())
                throw LlmFailures.invalid(id);
            List<KnowledgeChunk> evidence = List.copyOf(context.await(search.search(request.getQuestion(), 5)));
            context.check();
            String prompt = LlmPrompts.chat(request, evidence);
            if ((long) prompt.length() + LlmPrompts.CHAT.length() > config.maxInputCharacters()) throw LlmFailures.invalid(id);
            String response = context.await(client.complete(id, LlmPrompts.CHAT, prompt));
            context.check();
            return StructuredReplies.chat(id, response, evidence);
        });
    }
    @Override public void close() { tasks.close(); }
}
