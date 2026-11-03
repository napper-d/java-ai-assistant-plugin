package edu.teamc.courseaiassistant.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Extracts textual content; OCR and image/chart interpretation are outside this parser. */
public final class DocumentLoader {
    private final boolean includeNotes;
    public DocumentLoader(boolean includeNotes) { this.includeNotes = includeNotes; }

    public List<DocumentSection> load(Path path, String documentId) {
        try {
            List<DocumentSection> sections = switch (RagFiles.extension(path)) {
                case "pdf" -> pdf(path, documentId);
                case "pptx" -> pptx(path, documentId);
                default -> throw new RagException(RagException.Reason.INVALID_INPUT,
                        "Only PDF and PPTX course documents are supported.", false);
            };
            if (sections.stream().noneMatch(section -> !section.text().isBlank())) {
                throw new RagException(RagException.Reason.DOCUMENT_ERROR,
                        "No extractable text was found. Image-only documents require OCR.", false);
            }
            return List.copyOf(sections);
        } catch (RagException | CancellationException error) { throw error; }
        catch (IOException | RuntimeException error) {
            throw new RagException(RagException.Reason.DOCUMENT_ERROR,
                    "Unable to read the course document. Check its format, permissions and encryption.", false, error);
        }
    }

    private List<DocumentSection> pdf(Path path, String id) throws IOException {
        List<DocumentSection> result = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (!document.getCurrentAccessPermission().canExtractContent()) throw new IOException("Text extraction is restricted.");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                RagFiles.checkCancelled();
                stripper.setStartPage(page); stripper.setEndPage(page);
                result.add(new DocumentSection(id, path.getFileName().toString(), DocumentSection.Kind.PDF_PAGE,
                        page, stripper.getText(document)));
            }
        }
        return result;
    }

    private List<DocumentSection> pptx(Path path, String id) throws IOException {
        List<DocumentSection> result = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path); XMLSlideShow presentation = new XMLSlideShow(input)) {
            int number = 0;
            for (XSLFSlide slide : presentation.getSlides()) {
                RagFiles.checkCancelled();
                StringBuilder text = new StringBuilder();
                appendShapes(slide.getShapes(), text);
                if (includeNotes && slide.getNotes() != null) appendShapes(slide.getNotes().getShapes(), text);
                result.add(new DocumentSection(id, path.getFileName().toString(), DocumentSection.Kind.PPTX_SLIDE,
                        ++number, text.toString()));
            }
        }
        return result;
    }

    private void appendShapes(List<XSLFShape> shapes, StringBuilder text) {
        for (XSLFShape shape : shapes) {
            RagFiles.checkCancelled();
            if (shape instanceof XSLFGroupShape group) appendShapes(group.getShapes(), text);
            else if (shape instanceof XSLFTable table) {
                for (XSLFTableRow row : table.getRows()) {
                    for (XSLFTableCell cell : row.getCells()) text.append(cell.getText()).append('\t');
                    text.append('\n');
                }
            } else if (shape instanceof XSLFTextShape block) text.append(block.getText()).append('\n');
        }
    }
}
