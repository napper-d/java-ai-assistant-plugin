package edu.teamc.courseaiassistant.modification;

import edu.teamc.courseaiassistant.model.*;
import java.util.Objects;

/** Pure validation shared by real and sample generators. */
public final class ProposalValidator {
    public void validate(ModificationRequest request, ChangeProposal proposal) {
        Objects.requireNonNull(proposal, "Missing proposal");
        if (!request.requestId().equals(proposal.requestId())) throw new IllegalArgumentException("Unexpected request ID.");
        if (proposal.proposalId().isBlank()) throw new IllegalArgumentException("Missing proposal ID.");
        CodeChange change = proposal.change();
        if (!request.codeContext().getProjectRelativePath().equals(change.projectRelativePath())) throw new IllegalArgumentException("Unexpected target file.");
        if (!request.originalFileText().equals(change.originalFileText())) throw new IllegalArgumentException("Original snapshot mismatch.");
        if (change.proposedFileText().isBlank()) throw new IllegalArgumentException("Empty replacement rejected.");
        if (change.proposedFileText().contains("\r")) throw new IllegalArgumentException("Expected LF line endings.");
        if (change.proposedFileText().equals(change.originalFileText())) throw new IllegalArgumentException("No changes proposed.");
    }
}
