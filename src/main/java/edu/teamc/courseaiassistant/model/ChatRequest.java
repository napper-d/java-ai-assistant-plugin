package edu.teamc.courseaiassistant.model;

import edu.teamc.courseaiassistant.context.CodeContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Request for chat question with optional code context.
 */
public final class ChatRequest {
    private final @NotNull String requestId;
    private final @NotNull String question;
    private final @Nullable CodeContext codeContext;

    public ChatRequest(@NotNull String question, @Nullable CodeContext codeContext) {
        this.requestId = UUID.randomUUID().toString();
        this.question = Objects.requireNonNull(question);
        this.codeContext = codeContext;
    }

    @NotNull
    public String getRequestId() {
        return requestId;
    }

    @NotNull
    public String getQuestion() {
        return question;
    }

    @Nullable
    public CodeContext getCodeContext() {
        return codeContext;
    }

    @Override
    public String toString() {
        return "ChatRequest{id=" + requestId + ", question=" + question.length() + " chars" +
                (codeContext != null ? ", hasContext" : "") + "}";
    }
}
