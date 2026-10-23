package edu.teamc.courseaiassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Answer from chat service with citations and provenance.
 */
public final class ChatAnswer {
    private final @NotNull String requestId;
    private final @NotNull String answer;
    private final @NotNull List<SourceReference> sources;
    private final @NotNull Provenance provenance;
    private final @Nullable String disclosure;

    public enum Provenance {
        COURSE_GROUNDED,
        GENERAL_KNOWLEDGE
    }

    public ChatAnswer(
            @NotNull String requestId,
            @NotNull String answer,
            @NotNull List<SourceReference> sources,
            @NotNull Provenance provenance,
            @Nullable String disclosure
    ) {
        this.requestId = Objects.requireNonNull(requestId);
        this.answer = Objects.requireNonNull(answer);
        this.sources = List.copyOf(sources);
        this.provenance = Objects.requireNonNull(provenance);
        this.disclosure = disclosure;
    }

    @NotNull
    public String getRequestId() {
        return requestId;
    }

    @NotNull
    public String getAnswer() {
        return answer;
    }

    @NotNull
    public List<SourceReference> getSources() {
        return sources;
    }

    @NotNull
    public Provenance getProvenance() {
        return provenance;
    }

    @Nullable
    public String getDisclosure() {
        return disclosure;
    }

    @Override
    public String toString() {
        return "ChatAnswer{requestId=" + requestId + ", provenance=" + provenance +
                ", sources=" + sources.size() + "}";
    }
}
