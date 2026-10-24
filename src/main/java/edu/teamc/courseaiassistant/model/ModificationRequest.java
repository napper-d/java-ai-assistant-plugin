package edu.teamc.courseaiassistant.model;

import edu.teamc.courseaiassistant.context.CodeContext;
import java.util.Objects;
import java.util.UUID;

/** Explicit F2 instruction and a full-file snapshot captured by the modification flow. No file I/O. */
public record ModificationRequest(String requestId, String instruction, CodeContext codeContext,
                                  String originalFileText) {
    public ModificationRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(instruction);
        Objects.requireNonNull(codeContext);
        Objects.requireNonNull(originalFileText);
        if (instruction.isBlank()) throw new IllegalArgumentException("Modification instruction cannot be blank");
    }

    public ModificationRequest(String instruction, CodeContext codeContext, String originalFileText) {
        this(UUID.randomUUID().toString(), instruction, codeContext, originalFileText);
    }
}
