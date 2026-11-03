package edu.teamc.courseaiassistant.service;

import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import edu.teamc.courseaiassistant.model.ServiceFailure;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for searching course knowledge base.
 * Implementations may use vector databases or other retrieval systems.
 */
public interface KnowledgeSearch {

    /**
     * Searches for relevant knowledge chunks matching the query.
     * Returns a future that completes with matched chunks or a failure.
     */
    @NotNull
    CompletableFuture<List<KnowledgeChunk>> search(@NotNull String query, int maxResults);
}
