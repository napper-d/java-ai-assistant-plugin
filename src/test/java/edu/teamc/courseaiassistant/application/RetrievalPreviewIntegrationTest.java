package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.rag.*;
import edu.teamc.courseaiassistant.settings.RagSettings;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalPreviewIntegrationTest {
    @TempDir Path root;

    @Test void pdfPagesPptxScopesAndReferenceIdsSurviveOriginalRemovalAndFreshIndexLoad() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.build();
            var first = get(fixture.search.search("inheritance", 5));
            assertEquals(1, first.size()); assertEquals("lecture.pdf", first.getFirst().getSource().getDocumentName());
            assertEquals(2, first.getFirst().getSource().getPageStart());
            assertTrue(first.getFirst().getText().contains("Inheritance"));
            String reference = first.getFirst().getSource().getReferenceId();
            assertTrue(reference.startsWith("R-"));
            Files.delete(root.resolve("lecture.pdf")); Files.delete(root.resolve("slides.pptx"));
            try (var reopened = new LocalKnowledgeSearch(fixture.config, fixture.embeddings)) {
                assertEquals(reference, get(reopened.search("inheritance", 5)).getFirst().getSource().getReferenceId());
                var slide = get(reopened.search("transaction", 5));
                assertEquals(1, slide.size()); assertEquals("slides.pptx", slide.getFirst().getSource().getDocumentName());
                assertEquals("slide 2", slide.getFirst().getSource().getScope()); assertNull(slide.getFirst().getSource().getPageStart());
                assertTrue(get(reopened.search("cooking", 5)).isEmpty());
            }
        }
    }

    @Test void noIndexAndModelMismatchFailBeforeEmbeddingInsteadOfBecomingNoMatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertReason(RagException.Reason.INDEX_UNAVAILABLE, fixture.search.search("inheritance", 5));
            assertEquals(0, fixture.embeddings.queries.get());
            fixture.build();
            var changed = fixture.settings(); changed.model = "different-model";
            try (var mismatched = new LocalKnowledgeSearch(changed.toConfig(root, ""), fixture.embeddings)) {
                assertReason(RagException.Reason.INDEX_INCOMPATIBLE, mismatched.search("inheritance", 5));
                assertEquals(0, fixture.embeddings.queries.get());
            }
            assertEquals(1, get(fixture.search.search("inheritance", 5)).size());
        }
    }

    @Test void queryFailureIsExceptionalAndCanRecoverWithoutRebuilding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.build();
            fixture.embeddings.failure.set(new RagException(RagException.Reason.NETWORK_ERROR, "Controlled network failure", true));
            assertReason(RagException.Reason.NETWORK_ERROR, fixture.search.search("inheritance", 5));
            assertEquals(1, get(fixture.search.search("inheritance", 5)).size());
            assertTrue(get(fixture.search.search("cooking", 5)).isEmpty());
        }
    }

    @Test void cancellingOrClosingSearchPropagatesToThePendingQueryEmbedding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.build(); fixture.embeddings.block.set(true);
            var cancelled = fixture.search.search("inheritance", 5);
            var transport = fixture.embeddings.started.poll(5, TimeUnit.SECONDS); assertNotNull(transport);
            assertTrue(cancelled.cancel(true));
            assertThrows(CancellationException.class, () -> transport.get(5, TimeUnit.SECONDS));
            fixture.embeddings.block.set(false);
            assertEquals(1, get(fixture.search.search("inheritance", 5)).size());
            fixture.embeddings.block.set(true);
            var closing = fixture.search.search("transaction", 5);
            var secondTransport = fixture.embeddings.started.poll(5, TimeUnit.SECONDS); assertNotNull(secondTransport);
            fixture.search.close();
            assertTrue(closing.isCancelled());
            assertThrows(CancellationException.class, () -> secondTransport.get(5, TimeUnit.SECONDS));
            assertReason(RagException.Reason.CLOSED, fixture.search.search("inheritance", 5));
        }
    }

    @Test void searchOnlySettingsReloadTheSamePersistedVectorsWithoutDocumentEmbedding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.build();
            String manifest = Files.readString(fixture.config.indexDirectory().resolve("manifest.json"));
            var changed = fixture.settings(); changed.minScore = .99; changed.multiStepEnabled = false;
            try (var adjusted = new LocalKnowledgeSearch(changed.toConfig(root, ""), fixture.embeddings)) {
                assertEquals(2, get(adjusted.reloadSummary()).documentIds().size());
                assertEquals(1, get(adjusted.search("inheritance", 5)).size());
                assertEquals(manifest, Files.readString(fixture.config.indexDirectory().resolve("manifest.json")));
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final Embeddings embeddings = new Embeddings();
        final RagConfig config = settings().toConfig(root, "");
        final CourseIndexer indexer = new CourseIndexer(config, embeddings);
        final LocalKnowledgeSearch search = new LocalKnowledgeSearch(config, embeddings);
        RagSettings.State settings() {
            var state = new RagSettings.State(); state.baseUrl = "https://example.invalid/v1";
            state.model = "controlled-course-model"; state.dimensions = 2; return state;
        }
        void build() throws Exception {
            get(indexer.importFiles(List.of(pdf(), slides()))); get(indexer.rebuild());
        }
        @Override public void close() { search.close(); indexer.close(); }
    }
    private static final class Embeddings implements EmbeddingClient {
        final AtomicInteger queries = new AtomicInteger();
        final AtomicReference<RagException> failure = new AtomicReference<>();
        final AtomicBoolean block = new AtomicBoolean();
        final BlockingQueue<CompletableFuture<List<float[]>>> started = new LinkedBlockingQueue<>();
        @Override public CompletableFuture<List<float[]>> embed(List<String> texts, InputType type) {
            if (type == InputType.QUERY) {
                queries.incrementAndGet();
                RagException rejected = failure.getAndSet(null);
                if (rejected != null) return CompletableFuture.failedFuture(rejected);
                if (block.get()) { var pending = new CompletableFuture<List<float[]>>(); started.add(pending); return pending; }
            }
            return CompletableFuture.completedFuture(texts.stream().map(text -> {
                String lower = text.toLowerCase(Locale.ROOT);
                return lower.contains("inheritance") ? new float[]{1, 0}
                        : lower.contains("transaction") ? new float[]{0, 1} : new float[]{-1, -1};
            }).toList());
        }
    }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void assertReason(RagException.Reason reason, CompletableFuture<?> future) {
        var error = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertEquals(reason, assertInstanceOf(RagException.class, error.getCause()).getReason());
    }
    private Path pdf() throws Exception {
        Path path = root.resolve("lecture.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage()); var page = new PDPage(); document.addPage(page);
            try (var text = new PDPageContentStream(document, page)) {
                text.beginText(); text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(50, 700); text.showText("Inheritance reuses superclass behavior."); text.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
    private Path slides() throws Exception {
        Path path = root.resolve("slides.pptx");
        try (var deck = new XMLSlideShow()) {
            deck.createSlide(); deck.createSlide().createTextBox().setText("Transaction boundaries and rollback.");
            try (var output = Files.newOutputStream(path)) { deck.write(output); }
        }
        return path;
    }
}
