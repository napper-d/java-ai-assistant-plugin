package edu.teamc.courseaiassistant.model;

import java.util.Objects;

/** A proposed whole-file replacement. The path is an identity, never permission to read or write it. */
public record CodeChange(String projectRelativePath, String originalFileText, String proposedFileText,
                         String summary) {
    public CodeChange {
        Objects.requireNonNull(projectRelativePath);
        Objects.requireNonNull(originalFileText);
        Objects.requireNonNull(proposedFileText);
        Objects.requireNonNull(summary);
    }
}
