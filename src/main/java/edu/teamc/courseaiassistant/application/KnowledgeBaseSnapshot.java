package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.rag.DocumentImporter.RegisteredDocument;
import java.util.List;

/** Registry state and published-index state are deliberately separate. */
public record KnowledgeBaseSnapshot(List<RegisteredDocument> documents, IndexState indexState,
                                    int indexedDocuments, int chunks, boolean embeddingConfigured, String detail) {
    public enum IndexState { NO_INDEX, AVAILABLE, NEEDS_REBUILD, INCOMPATIBLE, INDEX_ERROR, RELOAD_REQUIRED }
    public KnowledgeBaseSnapshot { documents = List.copyOf(documents); }
}
