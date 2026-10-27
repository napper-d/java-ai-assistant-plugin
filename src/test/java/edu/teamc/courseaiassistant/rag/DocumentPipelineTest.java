package edu.teamc.courseaiassistant.rag;

import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentPipelineTest {
    @TempDir Path root;

    @Test void pdfKeepsPhysicalPageNumbersAndDoesNotIndexBlankPages() throws Exception {
        Path source = RagTestSupport.pdf(root.resolve("lecture.pdf"), "inheritance", "", "database");
        var sections = new DocumentLoader(false).load(source, "document");
        assertEquals(3, sections.size());
        var chunks = new ChunkSplitter(100, 10).split(sections);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.getFirst().source().getPageStart());
        assertEquals(3, chunks.getLast().source().getPageStart());
        assertEquals(chunks.stream().map(DocumentChunk::chunkId).toList(),
                new ChunkSplitter(100, 10).split(sections).stream().map(DocumentChunk::chunkId).toList());
        assertEquals(3, DocumentChunk.fromSegment(chunks.getLast().toSegment()).source().getPageStart());
    }

    @Test void pptxKeepsSlideNumberAndExtractsTablesAndGroupedText() throws Exception {
        Path source = root.resolve("lecture.pptx");
        try (XMLSlideShow deck = new XMLSlideShow()) {
            deck.createSlide();
            XSLFSlide slide = deck.createSlide();
            slide.createTextBox().setText("Inheritance title");
            slide.createGroup().createTextBox().setText("Grouped explanation");
            XSLFTable table = slide.createTable(1, 1);
            table.getCell(0, 0).setText("Database table cell");
            try (var output = Files.newOutputStream(source)) { deck.write(output); }
        }
        var chunks = new ChunkSplitter(1000, 0).split(new DocumentLoader(false).load(source, "ppt-document"));
        assertEquals(1, chunks.size());
        assertEquals("slide 2", chunks.getFirst().source().getScope());
        assertNull(chunks.getFirst().source().getPageStart());
        assertTrue(chunks.getFirst().text().contains("Grouped explanation"));
        assertTrue(chunks.getFirst().text().contains("Database table cell"));
        assertEquals("slide 2", DocumentChunk.fromSegment(chunks.getFirst().toSegment()).source().getScope());
    }

    @Test void windowsOverlapWithoutBreakingUnicode() {
        var section = new DocumentSection("doc", "unicode.pdf", DocumentSection.Kind.PDF_PAGE, 1, "甲乙😀丙丁戊己");
        var chunks = new ChunkSplitter(4, 1).split(List.of(section));
        assertEquals(List.of("甲乙😀丙", "丙丁戊己"), chunks.stream().map(DocumentChunk::text).toList());
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(4, 4));
    }

    @Test void registersOriginalPathsWithoutCopyingAndKeepsSameNamesSeparate() throws Exception {
        var config = RagTestSupport.config(root);
        Path firstDirectory = Files.createDirectories(root.resolve("a"));
        Path secondDirectory = Files.createDirectories(root.resolve("b"));
        Path first = RagTestSupport.pdf(firstDirectory.resolve("week.pdf"), "inheritance");
        Path second = RagTestSupport.pdf(secondDirectory.resolve("week.pdf"), "database");
        var importer = new DocumentImporter(config);
        var importedFirst = importer.importFile(first);
        assertFalse(importedFirst.reused());
        assertEquals(first.toRealPath(), importedFirst.path());
        assertTrue(importer.importFile(first).reused());
        var importedSecond = importer.importFile(second);
        assertEquals(second.toRealPath(), importedSecond.path());
        assertNotEquals(importedFirst.documentId(), importedSecond.documentId());
        assertNotEquals(importedFirst.path(), importedSecond.path());
        assertEquals(2, importer.listDocuments().size());
        assertTrue(Files.exists(first));
        assertEquals(importedFirst.documentId(), new DocumentImporter(config).listDocuments().stream()
                .filter(document -> document.path().equals(importedFirst.path())).findFirst().orElseThrow().documentId());
        try (var generated = Files.walk(config.dataDirectory())) {
            assertTrue(generated.noneMatch(file -> !RagFiles.extension(file).isEmpty()), "Registration must not copy PDF/PPTX files.");
        }
    }

    @Test void tracksOriginalChangesAndCanUnregisterAMissingOriginal() throws Exception {
        var config = RagTestSupport.config(root);
        Path original = RagTestSupport.pdf(root.resolve("week.pdf"), "inheritance");
        var importer = new DocumentImporter(config);
        var first = importer.importFile(original);
        RagTestSupport.pdf(original, "database");
        var changed = importer.listDocuments().getFirst();
        assertEquals(first.documentId(), changed.documentId(), "The same original path keeps its document identity.");
        assertNotEquals(first.sha256(), changed.sha256());
        assertEquals("database", new DocumentLoader(false).load(changed.path(), changed.documentId()).getFirst().text().strip());
        Files.delete(original);
        assertEquals(RagException.Reason.DOCUMENT_ERROR, assertThrows(RagException.class, importer::listDocuments).getReason());
        assertTrue(importer.unregisterFile(original));
        assertFalse(importer.unregisterFile(original));
        assertTrue(importer.listDocuments().isEmpty());
    }

    @Test void managementListKeepsMissingAndUnreadablePathsWithoutParsingOriginals() throws Exception {
        var importer = new DocumentImporter(RagTestSupport.config(root));
        Path original = Files.writeString(root.resolve("registered.pdf"), "not parsed while listing");
        String id = importer.importFile(original).documentId();
        assertEquals(DocumentImporter.Availability.AVAILABLE, importer.listRegisteredDocuments().getFirst().availability());
        Files.delete(original);
        var missing = importer.listRegisteredDocuments().getFirst();
        assertEquals(id, missing.documentId());
        assertEquals(original.toAbsolutePath(), missing.path());
        assertEquals(DocumentImporter.Availability.MISSING, missing.availability());
        Files.createDirectory(original);
        assertEquals(DocumentImporter.Availability.UNREADABLE, importer.listRegisteredDocuments().getFirst().availability());
        assertTrue(importer.unregisterFile(original));
        assertTrue(Files.isDirectory(original));
    }

    @Test void managementListDoesNotTreatCorruptRegistryAsAnEmptyLibrary() throws Exception {
        var config = RagTestSupport.config(root);
        Files.createDirectories(config.documentsDirectory());
        Files.writeString(config.documentsDirectory().resolve("documents.json"), "broken");
        var error = assertThrows(RagException.class, () -> new DocumentImporter(config).listRegisteredDocuments());
        assertEquals(RagException.Reason.STORAGE_ERROR, error.getReason());
    }

    @Test void emptyScannedOrCorruptDocumentsFailClearly() throws Exception {
        var parser = new DocumentLoader(false);
        var blank = RagTestSupport.pdf(root.resolve("scan.pdf"), "");
        assertEquals(RagException.Reason.DOCUMENT_ERROR, assertThrows(RagException.class, () -> parser.load(blank, "scan")).getReason());
        Path broken = Files.writeString(root.resolve("broken.pdf"), "not a PDF");
        assertThrows(RagException.class, () -> parser.load(broken, "broken"));
        assertEquals(RagException.Reason.INVALID_INPUT, assertThrows(RagException.class,
                () -> new DocumentImporter(RagTestSupport.config(root)).importFile(root.resolve("missing.pptx"))).getReason());
    }
}
