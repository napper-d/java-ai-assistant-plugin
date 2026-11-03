package edu.teamc.courseaiassistant.llm;

import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.CodeGenerationService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Produces proposals only; never opens or writes a source path. */
public final class LlmCodeGenerationService implements CodeGenerationService, AutoCloseable {
    private final LlmClient client;
    private final LlmConfig config;
    private final LlmTasks tasks = new LlmTasks();

    public LlmCodeGenerationService(LlmClient client, LlmConfig config) {
        this.client = Objects.requireNonNull(client); this.config = Objects.requireNonNull(config);
    }
    @Override public CompletableFuture<ChangeProposal> generate(ModificationRequest request) {
        Objects.requireNonNull(request);
        return tasks.submit(request.requestId(), config.timeout(), context -> {
            var selection = request.codeContext();
            String path = selection.getProjectRelativePath();
            if ((long) request.originalFileText().length() + selection.getSelectedText().length()
                    + request.instruction().length() > config.maxInputCharacters()) throw LlmFailures.invalid(request.requestId());
            // Cross-platform relative identity validation only. Member 4 still validates the resolved target.
            if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains(":")
                    || path.indexOf('\0') >= 0 || java.util.Arrays.asList(path.replace('\\', '/').split("/", -1)).stream()
                    .anyMatch(part -> part.isEmpty() || part.equals("..") || part.equals("."))
                    || selection.getSelectedText().isBlank() || !request.originalFileText().contains(selection.getSelectedText()))
                throw LlmFailures.invalid(request.requestId());
            String prompt = LlmPrompts.code(request);
            if ((long) prompt.length() + LlmPrompts.CODE.length() > config.maxInputCharacters()) throw LlmFailures.invalid(request.requestId());
            String response = context.await(client.complete(request.requestId(), LlmPrompts.CODE, prompt));
            context.check(); return StructuredReplies.proposal(request, response);
        });
    }
    @Override public void close() { tasks.close(); }
}
