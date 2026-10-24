package edu.teamc.courseaiassistant.service;

import edu.teamc.courseaiassistant.model.ModificationRequest;
import edu.teamc.courseaiassistant.model.ModificationResult;
import java.util.concurrent.CompletableFuture;

/**
 * Member 4 port: generation -> built-in Diff Viewer -> explicit approval -> validated write.
 * Reject changed snapshots/targets; cancel or closing review must leave files unchanged.
 * Failures complete exceptionally with ServiceFailure; no implementation is registered yet.
 */
public interface ModificationService {
    CompletableFuture<ModificationResult> modify(ModificationRequest request);
}
