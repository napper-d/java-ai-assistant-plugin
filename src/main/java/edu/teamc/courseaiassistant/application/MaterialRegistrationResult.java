package edu.teamc.courseaiassistant.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Registration outcome; duplicate paths are the originals resolved by the importer. */
public record MaterialRegistrationResult(KnowledgeBaseSnapshot snapshot, int addedFiles, List<Path> alreadyRegistered) {
    public MaterialRegistrationResult {
        Objects.requireNonNull(snapshot);
        if (addedFiles < 0) throw new IllegalArgumentException("Invalid added-file count.");
        alreadyRegistered = List.copyOf(alreadyRegistered);
    }
}
