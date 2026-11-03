package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.llm.*;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.KnowledgeSearch;

import java.util.concurrent.CompletableFuture;

/** One immutable configuration generation. Retrieval is borrowed from the project RAG service. */
final class LlmSession implements LlmRuntime.Session {
    private final HttpLlmClient client;
    private final LlmChatService chat;
    private final LlmCodeGenerationService generator;

    LlmSession(LlmConfig config, KnowledgeSearch search) {
        client = new HttpLlmClient(config);
        chat = new LlmChatService(search, client, config);
        generator = new LlmCodeGenerationService(client, config);
    }

    @Override public CompletableFuture<ChatAnswer> ask(ChatRequest request) { return chat.ask(request); }
    @Override public CompletableFuture<ChangeProposal> generate(ModificationRequest request) { return generator.generate(request); }
    @Override public void close() {
        chat.close();
        generator.close();
        client.close();
    }
}
