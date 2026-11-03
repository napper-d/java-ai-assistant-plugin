package edu.teamc.courseaiassistant.model;

import java.util.Objects;

/** APPLIED means the approved change was written; cancellation must preserve the source file. */
public record ModificationResult(String requestId, String proposalId, Outcome outcome, String message) {
    public enum Outcome { APPLIED, CANCELLED }

    public ModificationResult {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(proposalId);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(message);
    }
}
