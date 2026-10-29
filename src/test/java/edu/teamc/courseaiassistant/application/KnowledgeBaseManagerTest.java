package edu.teamc.courseaiassistant.application;

import edu.teamc.courseaiassistant.rag.*;
import edu.teamc.courseaiassistant.settings.RagSettings;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static edu.teamc.courseaiassistant.application.KnowledgeBaseSnapshot.IndexState.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseManagerTest {
    @TempDir Path root;

    @Test void managesSameNamePathsAndDuplicatesWithoutEmbeddingCalls() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertEquals(NO_INDEX, get(fixture.manager.refreshLibrary()).indexState());
            Path a = Files.writeString(Files.createDirectories(root.resolve("a")).resolve("week.pdf"), "registration only");
            Path b = Files.writeString(Files.createDirectories(root.resolve("b")).resolve("week.pdf"), "another registration");
            var state = get(fixture.manager.registerMaterials(List.of(a, b, a)));
            assertEquals(2, state.documents().size());
            assertNotEquals(state.documents().get(0).documentId(), state.documents().get(1).documentId());
            assertEquals(0, fixture.embeddings.calls.get());
            assertEquals(0, get(fixture.manager.unregisterMaterials(List.of(a, b))).documents().size());
            assertTrue(Files.exists(a)); assertTrue(Files.exists(b));
        }
    }

    @Test void rebuildLoadsRealSnapshotAndRemovingRegistrationKeepsOldEvidenceAcrossReopen() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path source = pdf(root.resolve("lecture.pdf"));
            get(fixture.manager.registerMaterials(List.of(source)));
            var ready = get(fixture.manager.rebuildLibrary());
            assertEquals(AVAILABLE, ready.indexState()); assertEquals(1, ready.chunks()); assertEquals(1, ready.indexedDocuments());
            var removed = get(fixture.manager.unregisterMaterials(List.of(source)));
            assertEquals(NEEDS_REBUILD, removed.indexState()); assertEquals(0, removed.documents().size());
            assertEquals(1, removed.chunks()); assertTrue(Files.exists(source));
            assertEquals(1, get(fixture.search.search("inheritance", 5)).size());
            fixture.manager.close();
            try (var reopened = new KnowledgeBaseManager(fixture.config, fixture.indexer, fixture.search::reloadSummary)) {
                var state = get(reopened.refreshLibrary());
                assertEquals(NEEDS_REBUILD, state.indexState());
                assertTrue(state.detail().contains("Add materials to create a current index"));
            }
        }
    }

    @Test void missingOriginalCanBeListedButRebuildFailsWithoutReplacingTheIndex() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path source = pdf(root.resolve("lecture.pdf"));
            get(fixture.manager.registerMaterials(List.of(source))); get(fixture.manager.rebuildLibrary());
            String published = Files.readString(fixture.config.indexDirectory().resolve("manifest.json"));
            Files.delete(source);
            var state = get(fixture.manager.refreshLibrary());
            assertEquals(AVAILABLE, state.indexState());
            assertEquals(DocumentImporter.Availability.MISSING, state.documents().getFirst().availability());
            assertReason(RagException.Reason.DOCUMENT_ERROR, fixture.manager.rebuildLibrary());
            assertEquals(published, Files.readString(fixture.config.indexDirectory().resolve("manifest.json")));
            assertEquals(1, get(fixture.search.search("inheritance", 5)).size());
        }
    }

    @Test void partialRegistrationSurvivesFailureAndRefreshShowsChangedRegistry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path first = pdf(root.resolve("first.pdf"));
            get(fixture.manager.registerMaterials(List.of(first))); get(fixture.manager.rebuildLibrary());
            Path second = pdf(root.resolve("second.pdf"));
            Path rejected = Files.writeString(root.resolve("invalid.txt"), "not supported");
            assertReason(RagException.Reason.INVALID_INPUT, fixture.manager.registerMaterials(List.of(second, rejected)));
            var actual = get(fixture.manager.refreshLibrary());
            assertEquals(2, actual.documents().size()); assertEquals(1, actual.indexedDocuments());
            assertEquals(NEEDS_REBUILD, actual.indexState());
        }
    }

    @Test void publishedButNotLoadedCanBeRecoveredWithoutEmbeddingAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicBoolean reject = new AtomicBoolean(true);
            fixture.replaceManager(() -> reject.getAndSet(false)
                    ? CompletableFuture.failedFuture(new RagException(RagException.Reason.STORAGE_ERROR, "Test load failure", true))
                    : fixture.search.reloadSummary());
            get(fixture.indexer.importFiles(List.of(pdf(root.resolve("lecture.pdf")))));
            var saved = get(fixture.manager.rebuildLibrary());
            assertEquals(RELOAD_REQUIRED, saved.indexState()); assertEquals(1, saved.chunks());
            assertTrue(Files.exists(fixture.config.indexDirectory().resolve("manifest.json")));
            int calls = fixture.embeddings.calls.get();
            assertEquals(AVAILABLE, get(fixture.manager.refreshLibrary()).indexState());
            assertEquals(calls, fixture.embeddings.calls.get());
        }
    }

    @Test void cancellationAndDuplicateGuardPropagateToEmbeddingAndLeaveOldSnapshot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            get(fixture.manager.registerMaterials(List.of(pdf(root.resolve("lecture.pdf")))));
            get(fixture.manager.rebuildLibrary());
            String original = Files.readString(fixture.config.indexDirectory().resolve("manifest.json"));
            fixture.embeddings.block.set(true);
            var pending = fixture.manager.rebuildLibrary();
            var embedding = fixture.embeddings.started.poll(5, TimeUnit.SECONDS); assertNotNull(embedding);
            assertReason(RagException.Reason.STORAGE_ERROR, fixture.manager.refreshLibrary());
            assertTrue(pending.cancel(true));
            assertThrows(CancellationException.class, () -> embedding.get(5, TimeUnit.SECONDS));
            assertEquals(AVAILABLE, get(fixture.manager.refreshLibrary()).indexState());
            assertEquals(original, Files.readString(fixture.config.indexDirectory().resolve("manifest.json")));
            fixture.embeddings.block.set(false);
            assertEquals(AVAILABLE, get(fixture.manager.rebuildLibrary()).indexState());
        }
    }

    @Test void cancellationAfterPublishingDoesNotPretendToRollBackTheIndex() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch loading = new CountDownLatch(1);
            CompletableFuture<LocalKnowledgeSearch.IndexSummary> waitForLoad = new CompletableFuture<>();
            AtomicBoolean blockFirst = new AtomicBoolean(true);
            fixture.replaceManager(() -> {
                if (blockFirst.getAndSet(false)) { loading.countDown(); return waitForLoad; }
                return fixture.search.reloadSummary();
            });
            get(fixture.indexer.importFiles(List.of(pdf(root.resolve("lecture.pdf")))));
            var operation = fixture.manager.rebuildLibrary();
            assertTrue(loading.await(5, TimeUnit.SECONDS)); assertTrue(operation.cancel(true));
            assertThrows(CancellationException.class, () -> waitForLoad.get(5, TimeUnit.SECONDS));
            assertTrue(Files.exists(fixture.config.indexDirectory().resolve("manifest.json")));
            assertEquals(AVAILABLE, get(fixture.manager.refreshLibrary()).indexState());
        }
    }

    @Test void changedConfigurationAndCorruptSnapshotAreDistinctFromNoIndex() throws Exception {
        try (Fixture fixture = new Fixture()) {
            get(fixture.manager.registerMaterials(List.of(pdf(root.resolve("lecture.pdf")))));
            get(fixture.manager.rebuildLibrary());
            var changed = new RagSettings.State(); changed.model = "other"; changed.baseUrl = fixture.config.embedding().baseUrl();
            try (var otherSearch = new LocalKnowledgeSearch(changed.toConfig(root, ""), fixture.embeddings);
                 var otherManager = new KnowledgeBaseManager(changed.toConfig(root, ""), fixture.indexer, otherSearch::reloadSummary)) {
                assertEquals(INCOMPATIBLE, get(otherManager.refreshLibrary()).indexState());
            }
            Files.writeString(fixture.config.indexDirectory().resolve("manifest.json"), "corrupt");
            assertEquals(INDEX_ERROR, get(fixture.manager.refreshLibrary()).indexState());
        }
    }

    @Test void closingCancelsOutstandingWorkAndRejectsFurtherOperations() throws Exception {
        try (Fixture fixture = new Fixture()) {
            get(fixture.manager.registerMaterials(List.of(pdf(root.resolve("lecture.pdf")))));
            fixture.embeddings.block.set(true);
            var pending = fixture.manager.rebuildLibrary();
            var embedding = fixture.embeddings.started.poll(5, TimeUnit.SECONDS); assertNotNull(embedding);
            fixture.manager.close();
            assertTrue(pending.isCancelled());
            // The embedding can be announced just before the worker attaches its child future.
            assertThrows(CancellationException.class, () -> embedding.get(5, TimeUnit.SECONDS));
            assertReason(RagException.Reason.CLOSED, fixture.manager.refreshLibrary());
        }
    }

    private final class Fixture implements AutoCloseable {
        final RagConfig config;
        final Embeddings embeddings = new Embeddings();
        final CourseIndexer indexer;
        final LocalKnowledgeSearch search;
        KnowledgeBaseManager manager;
        Fixture() {
            var state = new RagSettings.State(); state.model = "controlled-test"; state.baseUrl = "https://example.invalid/v1"; state.dimensions = 2;
            config = state.toConfig(root, ""); indexer = new CourseIndexer(config, embeddings); search = new LocalKnowledgeSearch(config, embeddings);
            manager = new KnowledgeBaseManager(config, indexer, search::reloadSummary);
        }
        void replaceManager(Supplier<CompletableFuture<LocalKnowledgeSearch.IndexSummary>> load) {
            manager.close(); manager = new KnowledgeBaseManager(config, indexer, load);
        }
        @Override public void close() { manager.close(); search.close(); indexer.close(); }
    }
    private static final class Embeddings implements EmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean block = new AtomicBoolean();
        final BlockingQueue<CompletableFuture<List<float[]>>> started = new LinkedBlockingQueue<>();
        @Override public CompletableFuture<List<float[]>> embed(List<String> texts, InputType type) {
            calls.incrementAndGet();
            if (block.get()) { var pending = new CompletableFuture<List<float[]>>(); started.add(pending); return pending; }
            return CompletableFuture.completedFuture(texts.stream().map(text -> new float[]{1, 0}).toList());
        }
    }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void assertReason(RagException.Reason reason, CompletableFuture<?> future) {
        var error = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertEquals(reason, assertInstanceOf(RagException.class, error.getCause()).getReason());
    }
    private static Path pdf(Path path) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Inheritance reuses superclass behavior."); content.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
}
