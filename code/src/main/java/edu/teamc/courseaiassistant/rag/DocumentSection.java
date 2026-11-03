package edu.teamc.courseaiassistant.rag;

import java.util.Objects;

/** One PDF page or PPTX slide; page numbers refer to the physical PDF page order. */
public record DocumentSection(String documentId, String documentName, Kind kind, int number, String text) {
    public enum Kind { PDF_PAGE, PPTX_SLIDE }
    public DocumentSection {
        if (documentId == null || documentId.isBlank() || documentName == null || documentName.isBlank() || number < 1) {
            throw new IllegalArgumentException("A section needs a document identity and one-based location.");
        }
        Objects.requireNonNull(kind);
        Objects.requireNonNull(text);
    }
}
