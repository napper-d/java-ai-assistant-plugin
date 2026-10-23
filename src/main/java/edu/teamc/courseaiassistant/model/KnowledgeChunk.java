package edu.teamc.courseaiassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A chunk of knowledge retrieved from course materials.
 */
public final class KnowledgeChunk {
    private final @NotNull String chunkId;
    private final @NotNull SourceReference source;
    private final @NotNull String text;
    private final double score;

    public KnowledgeChunk(
            @NotNull String chunkId,
            @NotNull SourceReference source,
            @NotNull String text,
            double score
    ) {
        this.chunkId = Objects.requireNonNull(chunkId);
        this.source = Objects.requireNonNull(source);
        this.text = Objects.requireNonNull(text);
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Score must be in [0.0, 1.0]: " + score);
        }
        this.score = score;
    }

    @NotNull
    public String getChunkId() {
        return chunkId;
    }

    @NotNull
    public SourceReference getSource() {
        return source;
    }

    @NotNull
    public String getText() {
        return text;
    }

    public double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "KnowledgeChunk{id=" + chunkId + ", score=" + score + ", text=" + text.length() + " chars}";
    }
}
