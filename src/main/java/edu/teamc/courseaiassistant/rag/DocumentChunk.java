package edu.teamc.courseaiassistant.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import edu.teamc.courseaiassistant.model.SourceReference;
import java.util.Objects;

public record DocumentChunk(String chunkId, String documentId, String text, SourceReference source) {
    public DocumentChunk {
        if (chunkId == null || chunkId.isBlank() || documentId == null || documentId.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chunk identity and text cannot be blank.");
        }
        Objects.requireNonNull(source);
    }

    public TextSegment toSegment() {
        Metadata metadata = new Metadata();
        metadata.put("chunkId", chunkId);
        metadata.put("documentId", documentId);
        metadata.put("referenceId", source.getReferenceId());
        metadata.put("documentName", source.getDocumentName());
        if (source.getPageStart() != null) metadata.put("pageStart", source.getPageStart());
        if (source.getPageEnd() != null) metadata.put("pageEnd", source.getPageEnd());
        if (source.getScope() != null) {
            metadata.put("scope", source.getScope());
            if (source.getScope().matches("slide [1-9][0-9]*")) {
                metadata.put("slideNumber", Integer.parseInt(source.getScope().substring(6)));
            }
        }
        return TextSegment.from(text, metadata);
    }

    public static DocumentChunk fromSegment(TextSegment segment) {
        Metadata meta = Objects.requireNonNull(segment).metadata();
        String referenceId = required(meta, "referenceId");
        String documentName = required(meta, "documentName");
        Integer start = meta.getInteger("pageStart"), end = meta.getInteger("pageEnd");
        String scope = meta.getString("scope");
        if (start != null ? start < 1 || end == null || end < start
                : end != null || scope == null || scope.isBlank()) throw new IllegalArgumentException("Invalid source location.");
        if (meta.containsKey("slideNumber") && !Objects.equals(scope, "slide " + meta.getInteger("slideNumber"))) {
            throw new IllegalArgumentException("Inconsistent slide location.");
        }
        return new DocumentChunk(required(meta, "chunkId"), required(meta, "documentId"), segment.text(),
                new SourceReference(referenceId, documentName, start, end, scope));
    }

    private static String required(Metadata metadata, String key) {
        String value = metadata.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing source metadata.");
        return value;
    }
}
