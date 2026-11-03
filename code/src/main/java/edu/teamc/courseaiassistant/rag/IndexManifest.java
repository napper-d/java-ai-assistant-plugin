package edu.teamc.courseaiassistant.rag;

import java.util.List;

/** Versioned snapshot descriptor. Contains no API credentials or raw endpoint responses. */
public record IndexManifest(int formatVersion, String corpusId, String embeddingFingerprint,
                            String splitterFingerprint, int dimensions, int chunkCount,
                            String storeFile, String storeSha256, List<SourceFile> sources) {
    public record SourceFile(String documentId, String documentName, String sha256) {}
}
