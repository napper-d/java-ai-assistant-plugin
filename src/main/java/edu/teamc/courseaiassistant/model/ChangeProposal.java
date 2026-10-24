package edu.teamc.courseaiassistant.model;

import java.util.Objects;
import java.util.UUID;

/** Initial F2 contract is single-file. Generating a proposal does not apply it. */
public record ChangeProposal(String proposalId, String requestId, CodeChange change) {
    public ChangeProposal {
        Objects.requireNonNull(proposalId);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(change);
    }

    public ChangeProposal(String requestId, CodeChange change) {
        this(UUID.randomUUID().toString(), requestId, change);
    }
}
