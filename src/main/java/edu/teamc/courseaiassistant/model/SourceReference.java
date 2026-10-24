package edu.teamc.courseaiassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Reference to a course document source with page or scope information.
 */
public final class SourceReference {
    private final @NotNull String referenceId;
    private final @NotNull String documentName;
    private final @Nullable Integer pageStart;
    private final @Nullable Integer pageEnd;
    private final @Nullable String scope;

    public SourceReference(
            @NotNull String referenceId,
            @NotNull String documentName,
            @Nullable Integer pageStart,
            @Nullable Integer pageEnd,
            @Nullable String scope
    ) {
        this.referenceId = Objects.requireNonNull(referenceId);
        this.documentName = Objects.requireNonNull(documentName);
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.scope = scope;
        if (pageStart == null && pageEnd == null && scope == null) {
            throw new IllegalArgumentException("At least page or scope must be present");
        }
    }

    @NotNull
    public String getReferenceId() {
        return referenceId;
    }

    @NotNull
    public String getDocumentName() {
        return documentName;
    }

    @Nullable
    public Integer getPageStart() {
        return pageStart;
    }

    @Nullable
    public Integer getPageEnd() {
        return pageEnd;
    }

    @Nullable
    public String getScope() {
        return scope;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(referenceId).append("] ").append(documentName);
        if (pageStart != null && pageEnd != null && !pageStart.equals(pageEnd)) {
            sb.append(" — pages ").append(pageStart).append("–").append(pageEnd);
        } else if (pageStart != null) {
            sb.append(" — page ").append(pageStart);
        } else if (scope != null) {
            sb.append(" — ").append(scope);
        }
        return sb.toString();
    }
}
