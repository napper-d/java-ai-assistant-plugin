package edu.teamc.courseaiassistant.rag;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalRetrievalTest {
    @TempDir Path root;

    private void build(RagConfig config, RagTestSupport.Embeddings embeddings) throws Exception {
        Path source = RagTestSupport.pdf(root.resolve("week.pdf"), "inheritance classes", "database transactions");
        try (var indexer = new CourseIndexer(config, embeddings)) {
            assertEquals(1, indexer.importFiles(List.of(source)).get(10, TimeUnit.SECONDS).size());
            var result = indexer.rebuild().get(10, TimeUnit.SECONDS);
            assertEquals(2, result.chunks()); assertEquals(2, result.dimensions());
        }
    }

    @Test void realDocumentPipelinePersistsReloadsAndFindsCitedResults() throws Exception {
        var config = RagTestSupport.config(root);
        var embeddings = new RagTestSupport.Embeddings();
        build(config, embeddings);
        int indexedInputs = embeddings.documents.get();
        try (var search = new LocalKnowledgeSearch(config, embeddings)) {
            var matches = search.search("inheritance", 1).get(10, TimeUnit.SECONDS);
            assertEquals(1, matches.size());
            assertEquals("week.pdf", matches.getFirst().getSource().getDocumentName());
            assertEquals(1, matches.getFirst().getSource().getPageStart());
            assertEquals(1.0, matches.getFirst().getScore(), 1e-6);
            assertTrue(search.search("astronomy", 5).get(10, TimeUnit.SECONDS).isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> matches.clear());
        }
        try (var restarted = new LocalKnowledgeSearch(config, embeddings)) {
            assertEquals(2, restarted.search("database", 5).get(10, TimeUnit.SECONDS).getFirst().getSource().getPageStart());
        }
        assertEquals(indexedInputs, embeddings.documents.get(), "Queries must not rebuild document vectors.");
        assertFalse(embeddings.closed, "Services borrow their embedding client.");
        assertFalse(Files.readString(config.indexDirectory().resolve("manifest.json")).contains("test-key"));
    }

    @Test void missingIndexInvalidInputsAndWrongModelAreFailuresRatherThanEmptyMatches() throws Exception {
        var config = RagTestSupport.config(root);
        var embeddings = new RagTestSupport.Embeddings();
        try (var search = new LocalKnowledgeSearch(config, embeddings)) {
            assertReason(RagException.Reason.INDEX_UNAVAILABLE, search.search("question", 2));
            assertReason(RagException.Reason.INVALID_INPUT, search.search(" ", 2));
            assertReason(RagException.Reason.INVALID_INPUT, search.search("question", 0));
        }
        build(config, embeddings);
        try (var search = new LocalKnowledgeSearch(RagTestSupport.config(root, "course", "wrong-model", 0.8), embeddings)) {
            assertReason(RagException.Reason.INDEX_INCOMPATIBLE, search.search("inheritance", 2));
        }
        try (var otherCourse = new LocalKnowledgeSearch(RagTestSupport.config(root, "other-course", "model-v1", 0.8), embeddings)) {
            assertReason(RagException.Reason.INDEX_UNAVAILABLE, otherCourse.search("inheritance", 2));
        }
    }

    @Test void oldFixedWindowIndexRequiresRebuildBeforeCallingTheApi() throws Exception {
        var config = RagTestSupport.config(root);
        var embeddings = new RagTestSupport.Embeddings();
        build(config, embeddings);
        Path manifestPath = config.indexDirectory().resolve("manifest.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        manifest.addProperty("splitterFingerprint", "unicode-codepoint-v1:" + config.chunkCharacters()
                + ":" + config.overlapCharacters() + ":notes=" + config.includeSlideNotes());
        Files.writeString(manifestPath, manifest.toString());
        try (var search = new LocalKnowledgeSearch(config, embeddings)) {
            assertReason(RagException.Reason.INDEX_INCOMPATIBLE, search.search("inheritance", 1));
        }
        assertEquals(0, embeddings.queries.get());
        assertTrue(Files.exists(manifestPath), "An incompatible index is not deleted automatically.");
    }

    @Test void corruptSnapshotAndMismatchedQueryDimensionsFail() throws Exception {
        var config = RagTestSupport.config(root);
        build(config, new RagTestSupport.Embeddings());
        EmbeddingClient wrongDimensions = (texts, type) -> CompletableFuture.completedFuture(List.of(new float[]{1, 0, 0}));
        try (var search = new LocalKnowledgeSearch(config, wrongDimensions)) {
            assertReason(RagException.Reason.EMBEDDING_ERROR, search.search("inheritance", 2));
        }
        JsonObject manifest = JsonParser.parseString(Files.readString(config.indexDirectory().resolve("manifest.json"))).getAsJsonObject();
        Files.writeString(config.indexDirectory().resolve(manifest.get("storeFile").getAsString()), "corrupt");
        try (var search = new LocalKnowledgeSearch(config, new RagTestSupport.Embeddings())) {
            assertReason(RagException.Reason.INDEX_CORRUPT, search.search("inheritance", 2));
        }
    }

    @Test void failedRebuildKeepsPreviouslyPublishedIndex() throws Exception {
        var config = RagTestSupport.config(root);
        build(config, new RagTestSupport.Embeddings());
        String manifest = Files.readString(config.indexDirectory().resolve("manifest.json"));
        EmbeddingClient failing = (texts, type) -> CompletableFuture.failedFuture(
                new RagException(RagException.Reason.TIMEOUT, "Timed out.", true));
        try (var indexer = new CourseIndexer(config, failing)) { assertReason(RagException.Reason.TIMEOUT, indexer.rebuild()); }
        assertEquals(manifest, Files.readString(config.indexDirectory().resolve("manifest.json")));
        try (var search = new LocalKnowledgeSearch(config, new RagTestSupport.Embeddings())) {
            assertEquals(1, search.search("inheritance", 1).get(10, TimeUnit.SECONDS).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void savedIndexSurvivesOriginalDeletionOrMoveButRebuildNeedsTheOriginal(boolean delete) throws Exception {
        var config = RagTestSupport.config(root);
        var embeddings = new RagTestSupport.Embeddings();
        build(config, embeddings);
        String manifest = Files.readString(config.indexDirectory().resolve("manifest.json"));
        Path original = root.resolve("week.pdf");
        if (delete) Files.delete(original);
        else Files.move(original, root.resolve("moved-week.pdf"));

        try (var restarted = new LocalKnowledgeSearch(config, embeddings)) {
            var matches = restarted.search("inheritance", 1).get(10, TimeUnit.SECONDS);
            assertEquals(1, matches.size());
            assertEquals(1, matches.getFirst().getSource().getPageStart());
            assertTrue(matches.getFirst().getText().contains("inheritance"));
        }
        int previousEmbeddings = embeddings.documents.get();
        try (var indexer = new CourseIndexer(config, embeddings)) {
            assertReason(RagException.Reason.DOCUMENT_ERROR, indexer.rebuild());
        }
        assertEquals(previousEmbeddings, embeddings.documents.get(), "Missing originals should fail before API calls.");
        assertEquals(manifest, Files.readString(config.indexDirectory().resolve("manifest.json")));
        try (var restartedAgain = new LocalKnowledgeSearch(config, embeddings)) {
            assertFalse(restartedAgain.search("inheritance", 1).get(10, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test void cancellationAndCloseCancelTheUnderlyingEmbeddingFuture() throws Exception {
        var config = RagTestSupport.config(root);
        build(config, new RagTestSupport.Embeddings());
        CountDownLatch invoked = new CountDownLatch(1);
        CompletableFuture<List<float[]>> child = new CompletableFuture<>();
        EmbeddingClient pending = (texts, type) -> { invoked.countDown(); return child; };
        try (var search = new LocalKnowledgeSearch(config, pending)) {
            var future = search.search("inheritance", 1);
            assertTrue(invoked.await(10, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertThrows(CancellationException.class, () -> child.get(5, TimeUnit.SECONDS));
        }
        CountDownLatch secondInvoked = new CountDownLatch(1);
        CompletableFuture<List<float[]>> secondChild = new CompletableFuture<>();
        var search = new LocalKnowledgeSearch(config, (texts, type) -> { secondInvoked.countDown(); return secondChild; });
        var second = search.search("inheritance", 1);
        assertTrue(secondInvoked.await(10, TimeUnit.SECONDS));
        search.close();
        assertTrue(second.isCancelled());
        assertThrows(CancellationException.class, () -> secondChild.get(5, TimeUnit.SECONDS));
        assertReason(RagException.Reason.CLOSED, search.search("inheritance", 1));
    }

    @Test void rejectsInvalidVectorsBeforePublishing() throws Exception {
        var config = RagTestSupport.config(root);
        Path source = RagTestSupport.pdf(root.resolve("week.pdf"), "inheritance");
        for (float[] bad : List.of(new float[]{0, 0}, new float[]{Float.NaN, 1}, new float[]{Float.POSITIVE_INFINITY, 1})) {
            try (var indexer = new CourseIndexer(config, (texts, type) -> CompletableFuture.completedFuture(List.of(bad)))) {
                indexer.importFiles(List.of(source)).get(10, TimeUnit.SECONDS);
                assertReason(RagException.Reason.EMBEDDING_ERROR, indexer.rebuild());
            }
        }
        assertFalse(Files.exists(config.indexDirectory().resolve("manifest.json")));
    }

    @Test void scoreNormalizationAndTieOrderingAreDeterministic() throws Exception {
        var config = RagTestSupport.config(root, "course", "model-v1", 0.5);
        var embeddings = new RagTestSupport.Embeddings();
        build(config, embeddings);
        try (var search = new LocalKnowledgeSearch(config, embeddings)) {
            var matches = search.search("inheritance", 2).get(10, TimeUnit.SECONDS);
            assertEquals(2, matches.size());
            assertEquals(1.0, matches.getFirst().getScore(), 1e-6);
            assertEquals(0.5, matches.getLast().getScore(), 1e-6);
        }
        Path tied = RagTestSupport.pdf(root.resolve("ties.pdf"), "inheritance first", "inheritance second");
        try (var indexer = new CourseIndexer(config, embeddings)) {
            indexer.importFiles(List.of(tied)).get(10, TimeUnit.SECONDS);
            indexer.rebuild().get(10, TimeUnit.SECONDS);
        }
        try (var search = new LocalKnowledgeSearch(config, embeddings)) {
            var matches = search.search("inheritance", 3).get(10, TimeUnit.SECONDS);
            var ids = matches.stream().map(value -> value.getChunkId()).toList();
            assertEquals(ids.stream().sorted().toList(), ids);
            assertEquals(ids.getFirst(), search.search("inheritance", 1).get(10, TimeUnit.SECONDS).getFirst().getChunkId());
        }
    }

    static void assertReason(RagException.Reason expected, CompletableFuture<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(RagException.class, error.getCause());
        assertEquals(expected, ((RagException) error.getCause()).getReason());
    }
}
