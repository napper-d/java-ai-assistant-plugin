package edu.teamc.courseaiassistant.modification;
import edu.teamc.courseaiassistant.model.ChangeProposal;
import java.util.concurrent.CompletableFuture;
@FunctionalInterface
public interface ModificationReviewer { CompletableFuture<ReviewDecision> review(ChangeProposal proposal); }
