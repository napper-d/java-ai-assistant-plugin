package edu.teamc.courseaiassistant.service;

import edu.teamc.courseaiassistant.model.ChangeProposal;
import edu.teamc.courseaiassistant.model.ModificationRequest;
import java.util.concurrent.CompletableFuture;

/** Member 3 port. Generate a proposal with the same request ID; never modify source files. */
public interface CodeGenerationService {
    CompletableFuture<ChangeProposal> generate(ModificationRequest request);
}
