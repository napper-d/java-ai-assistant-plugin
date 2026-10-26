package edu.teamc.courseaiassistant.rag;

import edu.teamc.courseaiassistant.model.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MultiStepRetrievalTest {
    @TempDir Path root;

    private RagConfig config() { return RagTestSupport.config(root); }
    private RagConfig settings(boolean enabled, int rounds, int budget) {
        RagConfig base = config();
        return new RagConfig(base.projectRoot(), base.dataDirectory(), base.corpusId(), base.chunkCharacters(),
                base.overlapCharacters(), base.batchSize(), base.minScore(), base.includeSlideNotes(), base.embedding(),
                new RagConfig.RetrievalSettings(enabled, rounds, budget, null));
    }
    private LocalIndexStore.Entry entry(String document, int page, String text, float x, float y) {
        String id = RagFiles.sha256(document + ":" + page + ":" + text);
        return new LocalIndexStore.Entry(new DocumentChunk(id, document, text,
                new SourceReference("R-" + id, document + ".pdf", page, page, null)), new float[]{x, y});
    }
    private void save(LocalIndexStore.Entry... entries) {
        Map<String, IndexManifest.SourceFile> sources = new LinkedHashMap<>();
        for (var entry : entries) {
            String document = entry.chunk().documentId();
            sources.put(document, new IndexManifest.SourceFile(document, document + ".pdf", RagFiles.sha256(document)));
        }
        new LocalIndexStore(config()).save(List.of(entries), 2, List.copyOf(sources.values()));
    }
    private static final class QueryEmbeddings implements EmbeddingClient {
        final AtomicInteger calls = new AtomicInteger();
        @Override public CompletableFuture<List<float[]>> embed(List<String> texts, InputType type) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(texts.stream().map(text ->
                    text.equals("transport semantics") ? new float[]{0, 1} : new float[]{1, 0}).toList());
        }
    }

    @Test void combinesDistantPdfDefinitionsWithTheirOriginalPagesAfterRestart() throws Exception {
        Path pdf = RagTestSupport.pdf(root.resolve("assignment.pdf"), "Group A must implement F0 and F1.",
                "Unrelated presentation instructions.", "", "F0: Retrieve course information with citations.",
                "F1: Generate unit tests for selected code.");
        AtomicInteger queryCalls = new AtomicInteger();
        EmbeddingClient embeddings = (texts, type) -> {
            if (type == EmbeddingClient.InputType.QUERY) queryCalls.incrementAndGet();
            return CompletableFuture.completedFuture(texts.stream().map(text -> type == EmbeddingClient.InputType.QUERY
                    || text.startsWith("Group A") ? new float[]{1, 0} : new float[]{0, 1}).toList());
        };
        try (var indexer = new CourseIndexer(config(), embeddings)) {
            indexer.importFiles(List.of(pdf)).get(10, TimeUnit.SECONDS);
            indexer.rebuild().get(10, TimeUnit.SECONDS);
        }
        Files.delete(pdf);
        try (var search = new LocalKnowledgeSearch(config(), embeddings)) {
            var result = search.search("What does Group A have to implement?", 3).get(10, TimeUnit.SECONDS);
            assertEquals(3, result.size());
            assertEquals(Set.of(1, 4, 5), new HashSet<>(result.stream().map(chunk -> chunk.getSource().getPageStart()).toList()));
            assertTrue(result.stream().allMatch(chunk -> chunk.getSource().getDocumentName().equals("assignment.pdf")));
            assertEquals(2, result.stream().filter(chunk -> Math.abs(chunk.getScore() - 0.5) < 1e-6).count(),
                    "Referenced definitions keep their real original-question scores instead of being boosted.");
        }
        assertEquals(1, queryCalls.get(), "Exact reference expansion must not call the embedding API again.");
    }

    @Test void scopesDefinitionsToTheAnchorDocumentAndMatchesWholeIdentifiers() throws Exception {
        save(entry("assignment", 1, "Group A requires F0 and F1.", 1, 0),
                entry("assignment", 8, "F0: Course retrieval.", 0, 1),
                entry("assignment", 9, "F1: Code tests.", 0, 1),
                entry("assignment", 10, "F10: An unrelated feature.", 0, 1),
                entry("other", 4, "F1: Different requirements in another document.", 0.9f, 0.1f));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            var result = search.search("Group A responsibilities", 3).get(10, TimeUnit.SECONDS);
            assertEquals(3, result.size());
            assertTrue(result.stream().allMatch(chunk -> chunk.getSource().getDocumentName().equals("assignment.pdf")));
            assertTrue(result.stream().noneMatch(chunk -> chunk.getText().contains("F10")));
        }
    }

    @Test void anAssignmentInASecondarySeedStillTriggersExpansionAndIsKeptAsEvidence() throws Exception {
        save(entry("assignment", 1, "Group A requires F0 and F1.", 0.9f, 0.2f),
                entry("assignment", 8, "F0: Course retrieval.", 0, 1),
                entry("assignment", 9, "F1: Code tests for Group A.", 1, 0));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            var result = search.search("Group A work", 3).get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(1, 8, 9), new HashSet<>(result.stream().map(chunk -> chunk.getSource().getPageStart()).toList()));
            assertEquals(3, result.size(), "The group mapping must accompany the expanded definition.");
        }
    }

    @Test void genericRequirementAndSectionReferencesExpandAcrossTwoRounds() throws Exception {
        save(entry("requirements", 1, "Project X must implement REQ-42.", 1, 0),
                entry("requirements", 5, "REQ-42: Follow section 2.1 for transport constraints.", 0, 1),
                entry("requirements", 12, "2.1: Messages must preserve ordering and acknowledgements.", 0, 1));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            var result = search.search("Project X work", 3).get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(1, 5, 12), new HashSet<>(result.stream().map(chunk -> chunk.getSource().getPageStart()).toList()));
        }
    }

    @Test void identifiersCanTouchChineseTextAndUseChineseDefinitionHeadings() throws Exception {
        save(entry("requirements", 1, "组甲需要实现F0和F1。", 1, 0),
                entry("requirements", 4, "功能F0：检索课程资料并提供引用。", 0, 1),
                entry("requirements", 5, "功能F1：为选中代码生成单元测试。", 0, 1));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            var result = search.search("组甲要做什么？", 3).get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(1, 4, 5), new HashSet<>(result.stream().map(chunk -> chunk.getSource().getPageStart()).toList()));
        }
    }

    @Test void cyclesAreDeduplicatedAndRoundAndQueryBudgetsAreRespected() throws Exception {
        save(entry("requirements", 1, "Project X requires R1.", 1, 0),
                entry("requirements", 2, "R1: Follow R2.", 0, 1),
                entry("requirements", 3, "R2: Follow R1 and R3.", 0, 1),
                entry("requirements", 4, "R3: Additional work.", 0, 1));
        AtomicInteger planned = new AtomicInteger();
        RagConfig limited = settings(true, 4, 2);
        var delegate = new ReferenceQueryPlanner(limited.retrieval());
        FollowUpQueryPlanner planner = (question, evidence) -> { planned.incrementAndGet(); return delegate.plan(question, evidence); };
        try (var search = new LocalKnowledgeSearch(limited, new QueryEmbeddings(), planner)) {
            var result = search.search("Project X work", 5).get(10, TimeUnit.SECONDS);
            assertEquals(3, result.size());
            assertEquals(3, result.stream().map(chunk -> chunk.getChunkId()).distinct().count());
            assertEquals(2, planned.get());
            assertTrue(result.stream().noneMatch(chunk -> chunk.getText().startsWith("R3:")));
        }
        try (var search = new LocalKnowledgeSearch(settings(true, 1, 8), new QueryEmbeddings())) {
            assertEquals(2, search.search("Project X work", 5).get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test void maximumResultsIsAHardLimitAndKeepsTheAnchor() throws Exception {
        save(entry("assignment", 1, "Group A requires F0 and F1.", 1, 0),
                entry("assignment", 8, "F0: Course retrieval.", 0, 1), entry("assignment", 9, "F1: Code tests.", 0, 1));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            var one = search.search("Group A work", 1).get(10, TimeUnit.SECONDS);
            assertEquals(1, one.size()); assertEquals(1, one.getFirst().getSource().getPageStart());
            var two = search.search("Group A work", 2).get(10, TimeUnit.SECONDS);
            assertEquals(2, two.size());
            assertEquals(2, two.stream().map(chunk -> chunk.getChunkId()).distinct().count());
        }
    }

    @Test void canDisableExpansionWithoutRebuildingTheIndex() throws Exception {
        save(entry("assignment", 1, "Group A requires F0.", 1, 0), entry("assignment", 8, "F0: Course retrieval.", 0, 1));
        assertEquals(config().splitterFingerprint(), settings(false, 2, 8).splitterFingerprint());
        try (var search = new LocalKnowledgeSearch(settings(false, 2, 8), new QueryEmbeddings())) {
            assertEquals(1, search.search("Group A work", 5).get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test void explicitReferenceQuestionsCanFindDefinitionsWithoutASemanticSeed() throws Exception {
        save(entry("requirements", 4, "REQ-42: Preserve message ordering.", 0, 1));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            var result = search.search("What is REQ-42?", 3).get(10, TimeUnit.SECONDS);
            assertEquals(1, result.size()); assertTrue(result.getFirst().getText().startsWith("REQ-42:"));
        }
    }

    @Test void missingDefinitionsDoNotTurnMentionsOrPrefixesIntoDefinitions() throws Exception {
        save(entry("assignment", 1, "Group A requires F1.", 1, 0),
                entry("assignment", 2, "Other teams also mention F1 without explaining it.", 0, 1),
                entry("assignment", 3, "F10: Another feature.", 0, 1),
                entry("assignment", 4, "F1 F2", 0, 1));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings())) {
            assertEquals(1, search.search("Group A work", 5).get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test void anInjectedPlannerSupportsSemanticFollowUpsWithoutAnyNumberedReferences() throws Exception {
        save(entry("architecture", 1, "The runtime delegates delivery to the transport subsystem.", 1, 0),
                entry("architecture", 8, "The transport subsystem retries and acknowledges every message.", 0, 1));
        var embeddings = new QueryEmbeddings();
        FollowUpQueryPlanner planner = (question, evidence) -> CompletableFuture.completedFuture(
                !evidence.isEmpty() && evidence.getFirst().chunk().text().contains("delegates")
                        ? List.of(new FollowUpQueryPlanner.Query("transport semantics", "architecture", null)) : List.of());
        try (var search = new LocalKnowledgeSearch(config(), embeddings, planner)) {
            var result = search.search("How does delivery work?", 3).get(10, TimeUnit.SECONDS);
            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(chunk -> chunk.getText().contains("acknowledges")));
            assertEquals(0.5, result.getLast().getScore(), 1e-6);
        }
        assertEquals(2, embeddings.calls.get());
    }

    @Test void cancellationPropagatesToAnAsynchronousPlanner() throws Exception {
        save(entry("assignment", 1, "A primary result.", 1, 0));
        var child = new CompletableFuture<List<FollowUpQueryPlanner.Query>>();
        CountDownLatch started = new CountDownLatch(1);
        FollowUpQueryPlanner planner = (question, evidence) -> { started.countDown(); return child; };
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings(), planner)) {
            var result = search.search("question", 5);
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertThrows(CancellationException.class, () -> child.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void plannerFailuresAreSafeFailuresRatherThanEmptyMatches() throws Exception {
        save(entry("assignment", 1, "A primary result.", 1, 0));
        FollowUpQueryPlanner failed = (question, evidence) -> CompletableFuture.failedFuture(new RuntimeException("private-provider-response"));
        try (var search = new LocalKnowledgeSearch(config(), new QueryEmbeddings(), failed)) {
            var error = assertThrows(ExecutionException.class, () -> search.search("question", 5).get(10, TimeUnit.SECONDS));
            var failure = assertInstanceOf(RagException.class, error.getCause());
            assertEquals(RagException.Reason.RETRIEVAL_ERROR, failure.getReason());
            assertFalse(failure.getMessage().contains("private-provider-response"));
        }
    }

    @Test void cancellationAlsoStopsTheFollowUpEmbeddingWork() throws Exception {
        save(entry("architecture", 1, "The runtime delegates delivery.", 1, 0));
        var pending = new CompletableFuture<List<float[]>>();
        CountDownLatch invoked = new CountDownLatch(1);
        EmbeddingClient embeddings = (texts, type) -> {
            if (texts.getFirst().equals("transport semantics")) { invoked.countDown(); return pending; }
            return CompletableFuture.completedFuture(List.of(new float[]{1, 0}));
        };
        FollowUpQueryPlanner planner = (question, evidence) -> CompletableFuture.completedFuture(
                List.of(new FollowUpQueryPlanner.Query("transport semantics", "architecture", null)));
        try (var search = new LocalKnowledgeSearch(config(), embeddings, planner)) {
            var result = search.search("question", 5);
            assertTrue(invoked.await(10, TimeUnit.SECONDS));
            result.cancel(true);
            assertThrows(CancellationException.class, () -> pending.get(5, TimeUnit.SECONDS));
        }
    }
}
