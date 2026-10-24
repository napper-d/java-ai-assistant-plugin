package edu.teamc.courseaiassistant.context;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable snapshot of selected code and its source location.
 * Captured when the editor action is invoked, before the question is submitted.
 */
public final class CodeContext {
    private final @NotNull String projectRelativePath;
    private final @NotNull String languageId;
    private final @NotNull String selectedText;
    private final int startLine;
    private final int endLine;
    private final long documentModificationStamp;
    private final @Nullable String surroundingText;

    public CodeContext(
            @NotNull String projectRelativePath,
            @NotNull String languageId,
            @NotNull String selectedText,
            int startLine,
            int endLine,
            long documentModificationStamp,
            @Nullable String surroundingText
    ) {
        this.projectRelativePath = Objects.requireNonNull(projectRelativePath);
        this.languageId = Objects.requireNonNull(languageId);
        this.selectedText = Objects.requireNonNull(selectedText);
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Invalid line range: [" + startLine + ", " + endLine + "]");
        }
        this.startLine = startLine;
        this.endLine = endLine;
        this.documentModificationStamp = documentModificationStamp;
        this.surroundingText = surroundingText;
    }

    @NotNull
    public String getProjectRelativePath() {
        return projectRelativePath;
    }

    @NotNull
    public String getLanguageId() {
        return languageId;
    }

    @NotNull
    public String getSelectedText() {
        return selectedText;
    }

    /** One-based line number, inclusive start. */
    public int getStartLine() {
        return startLine;
    }

    /** One-based line number, inclusive end. */
    public int getEndLine() {
        return endLine;
    }

    public long getDocumentModificationStamp() {
        return documentModificationStamp;
    }

    /** Optional bounded surrounding text for additional context. */
    @Nullable
    public String getSurroundingText() {
        return surroundingText;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CodeContext other)) return false;
        return startLine == other.startLine
                && endLine == other.endLine
                && documentModificationStamp == other.documentModificationStamp
                && projectRelativePath.equals(other.projectRelativePath)
                && languageId.equals(other.languageId)
                && selectedText.equals(other.selectedText)
                && Objects.equals(surroundingText, other.surroundingText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectRelativePath, languageId, selectedText, startLine, endLine,
                documentModificationStamp, surroundingText);
    }

    @Override
    public String toString() {
        return "CodeContext{" +
                "path='" + projectRelativePath + '\'' +
                ", language='" + languageId + '\'' +
                ", lines=" + startLine + "-" + endLine +
                ", stamp=" + documentModificationStamp +
                ", selected=" + selectedText.length() + " chars" +
                (surroundingText != null ? ", surrounding=" + surroundingText.length() + " chars" : "") +
                '}';
    }
}
