package edu.teamc.courseaiassistant.llm;

import com.google.gson.*;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.rag.RagException;
import edu.teamc.courseaiassistant.service.KnowledgeSearch;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LlmServicesTest {
    static LlmConfig config(Duration timeout) {
        return new LlmConfig(URI.create("http://localhost:12345/v1"), "test-model", "test-secret", timeout, 4096, 60000);
    }
    static final KnowledgeChunk CHUNK = new KnowledgeChunk("chunk", new SourceReference("R-one", "Course.pdf", 3, 3, null), "Java uses classes.", .9);
    static final class Client implements LlmClient {
        final CompletableFuture<String> response = new CompletableFuture<>();
        final CountDownLatch invoked = new CountDownLatch(1);
        volatile String prompt;
        boolean closed;
        @Override public CompletableFuture<String> complete(String id, String system, String user) {
            prompt = user; invoked.countDown(); return response;
        }
        @Override public void close() { closed = true; }
    }
    static CodeContext selection(String path) { return new CodeContext(path, "JAVA", "class A {}", 1, 1, 42, null); }
    static ServiceFailure failure(CompletableFuture<?> future) throws Exception {
        return assertInstanceOf(ServiceFailure.class, assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS)).getCause());
    }
    static void awaitCancelled(CompletableFuture<?> future) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!future.isCancelled() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(future.isCancelled());
    }
    @Test void citedAnswerPreservesSourceIdentityAndSelectedCode() throws Exception {
        Client client = new Client();
        client.response.complete("{\"answer\":\"Use classes [[cite:R-one]]\",\"citations\":[\"R-one\"]}");
        AtomicReference<String> query = new AtomicReference<>();
        KnowledgeSearch search = (q, n) -> { query.set(q); assertEquals(5, n); return CompletableFuture.completedFuture(List.of(CHUNK)); };
        ChatRequest request = new ChatRequest("Explain", selection("src/A.java"));
        try (var service = new LlmChatService(search, client, config(Duration.ofSeconds(2)))) {
            ChatAnswer answer = service.ask(request).get(3, TimeUnit.SECONDS);
            assertEquals(request.getRequestId(), answer.getRequestId());
            assertEquals(ChatAnswer.Provenance.COURSE_GROUNDED, answer.getProvenance());
            assertSame(CHUNK.getSource(), answer.getSources().getFirst());
            assertEquals("Use classes [R-one]", answer.getAnswer());
            assertEquals("Explain", query.get());
            assertTrue(client.prompt.contains("class A {}"));
            assertTrue(client.prompt.contains("Course.pdf"));
            assertNull(answer.getDisclosure());
        }
        assertFalse(client.closed);
    }
    @Test void emptyRetrievalDisclosesGeneralKnowledge() throws Exception {
        Client client = new Client(); client.response.complete("{\"answer\":\"General answer\",\"citations\":[]}");
        try (var service = new LlmChatService((q,n) -> CompletableFuture.completedFuture(List.of()), client, config(Duration.ofSeconds(2)))) {
            var answer = service.ask(new ChatRequest("Explain", null)).get();
            assertEquals(ChatAnswer.Provenance.GENERAL_KNOWLEDGE, answer.getProvenance());
            assertTrue(answer.getSources().isEmpty()); assertNotNull(answer.getDisclosure());
        }
    }
    @Test void retrievalFailureDoesNotCallModelAndUsesRequestIdentity() throws Exception {
        Client client = new Client(); ChatRequest request = new ChatRequest("Explain", null);
        try (var service = new LlmChatService((q,n) -> CompletableFuture.failedFuture(
                new RagException(RagException.Reason.NETWORK_ERROR, "private diagnostics", true)), client, config(Duration.ofSeconds(2)))) {
            var error = failure(service.ask(request));
            assertEquals(request.getRequestId(), error.getRequestId());
            assertEquals(ServiceFailure.Category.NETWORK_ERROR, error.getCategory());
            assertTrue(error.isRetryable()); assertFalse(error.getMessage().contains("private"));
            assertEquals(1, client.invoked.getCount());
        }
    }
    @Test void rejectsFabricatedMissingDuplicateAndMismatchedCitations() throws Exception {
        for (String json : List.of(
                "{\"answer\":\"Bad [[cite:R-fake]]\",\"citations\":[\"R-fake\"]}",
                "{\"answer\":\"Bad\",\"citations\":[\"R-one\"]}",
                "{\"answer\":\"Bad [[cite:R-one]]\",\"citations\":[]}",
                "{\"answer\":\"Bad\",\"citations\":[]}",
                "{\"answer\":\"Bad [[cite:R-one]]\",\"citations\":[\"R-one\",\"R-one\"]}",
                "{\"answer\":3,\"citations\":[]}", "not JSON")) {
            Client client = new Client(); client.response.complete(json);
            try (var service = new LlmChatService((q,n) -> CompletableFuture.completedFuture(List.of(CHUNK)), client, config(Duration.ofSeconds(2)))) {
                assertEquals(ServiceFailure.Category.MODEL_ERROR, failure(service.ask(new ChatRequest("Explain", null))).getCategory());
            }
        }
    }
    @Test void cancellationDuringRetrievalPropagatesAndPreventsModelCall() throws Exception {
        Client client = new Client(); CompletableFuture<List<KnowledgeChunk>> retrieval = new CompletableFuture<>();
        CountDownLatch invoked = new CountDownLatch(1);
        try (var service = new LlmChatService((q,n) -> { invoked.countDown(); return retrieval; }, client, config(Duration.ofSeconds(2)))) {
            var answer = service.ask(new ChatRequest("Explain", null));
            assertTrue(invoked.await(1, TimeUnit.SECONDS)); answer.cancel(true); awaitCancelled(retrieval);
            assertEquals(1, client.invoked.getCount());
        }
    }
    @Test void cancellationAndCloseDuringModelCallPropagate() throws Exception {
        for (boolean close : List.of(false, true)) {
            Client client = new Client();
            try (var service = new LlmChatService((q,n) -> CompletableFuture.completedFuture(List.of()), client, config(Duration.ofSeconds(2)))) {
                var answer = service.ask(new ChatRequest("Explain", null));
                assertTrue(client.invoked.await(1, TimeUnit.SECONDS));
                if (close) service.close(); else answer.cancel(true);
                awaitCancelled(client.response); assertTrue(answer.isCancelled());
            }
        }
    }
    @Test void deadlineCancelsRetrievalAndRecovers() throws Exception {
        CompletableFuture<List<KnowledgeChunk>> retrieval = new CompletableFuture<>(); Client client = new Client();
        AtomicReference<CompletableFuture<List<KnowledgeChunk>>> next = new AtomicReference<>(retrieval);
        try (var service = new LlmChatService((q,n) -> next.get(), client, config(Duration.ofMillis(150)))) {
            assertEquals(ServiceFailure.Category.TIMEOUT, failure(service.ask(new ChatRequest("Explain", null))).getCategory());
            awaitCancelled(retrieval);
            next.set(CompletableFuture.completedFuture(List.of()));
            client.response.complete("{\"answer\":\"Recovered\",\"citations\":[]}");
            assertEquals("Recovered", service.ask(new ChatRequest("Retry", null)).get(2, TimeUnit.SECONDS).getAnswer());
        }
    }
    @Test void blankAndOversizedInputsNeverReachDependencies() throws Exception {
        Client client = new Client();
        try (var service = new LlmChatService((q,n) -> { fail("must not search"); return null; }, client, config(Duration.ofSeconds(2)))) {
            for (String question : List.of(" ", "a".repeat(60001)))
                assertEquals(ServiceFailure.Category.INVALID_INPUT, failure(service.ask(new ChatRequest(question, null))).getCategory());
        }
    }
    @Test void proposalPreservesOriginalSnapshotAndRequestIdentity() throws Exception {
        Client client = new Client();
        client.response.complete("{\"path\":\"src/A.java\",\"proposedFileText\":\"class A { int x; }\",\"summary\":\"Add x\"}");
        var request = new ModificationRequest("Add x", selection("src/A.java"), "class A {}");
        try (var service = new LlmCodeGenerationService(client, config(Duration.ofSeconds(2)))) {
            var proposal = service.generate(request).get();
            assertEquals(request.requestId(), proposal.requestId());
            assertEquals("class A {}", proposal.change().originalFileText());
            assertEquals("class A { int x; }", proposal.change().proposedFileText());
            assertEquals("src/A.java", proposal.change().projectRelativePath());
        }
        assertFalse(client.closed);
    }
    @Test void rejectsChangedTargetEmptyReplacementAndNoOp() throws Exception {
        for (String[] fields : List.of(new String[]{"other.java", "class B {}"}, new String[]{"src/A.java", ""}, new String[]{"src/A.java", "class A {}"})) {
            Client client = new Client(); JsonObject json = new JsonObject();
            json.addProperty("path", fields[0]); json.addProperty("proposedFileText", fields[1]); json.addProperty("summary", "Change");
            client.response.complete(json.toString());
            try (var service = new LlmCodeGenerationService(client, config(Duration.ofSeconds(2)))) {
                assertEquals(ServiceFailure.Category.MODEL_ERROR, failure(service.generate(new ModificationRequest("Change", selection("src/A.java"), "class A {}"))).getCategory());
            }
        }
    }
    @Test void rejectsExternalTraversalTargetsAndMismatchedSnapshotsBeforeModel() throws Exception {
        Client client = new Client();
        try (var service = new LlmCodeGenerationService(client, config(Duration.ofSeconds(2)))) {
            for (String path : List.of("../A.java", "C:\\A.java", "/A.java", "src/../A.java", "src//A.java"))
                assertEquals(ServiceFailure.Category.INVALID_INPUT, failure(service.generate(new ModificationRequest("Change", selection(path), "class A {}"))).getCategory());
            assertEquals(ServiceFailure.Category.INVALID_INPUT, failure(service.generate(new ModificationRequest("Change", selection("A.java"), "class B {}"))).getCategory());
            assertEquals(1, client.invoked.getCount());
        }
    }
    @Test void generationCancellationPropagates() throws Exception {
        Client client = new Client();
        try (var service = new LlmCodeGenerationService(client, config(Duration.ofSeconds(2)))) {
            var proposal = service.generate(new ModificationRequest("Change", selection("A.java"), "class A {}"));
            assertTrue(client.invoked.await(1, TimeUnit.SECONDS)); proposal.cancel(true); awaitCancelled(client.response);
        }
    }
    @Test void synchronousDependencyFailureAndClosedServiceReturnSafeFailures() throws Exception {
        Client client = new Client();
        var service = new LlmChatService((q,n) -> { throw new RuntimeException("secret"); }, client, config(Duration.ofSeconds(2)));
        assertFalse(failure(service.ask(new ChatRequest("Explain", null))).getMessage().contains("secret"));
        service.close(); assertEquals(ServiceFailure.Category.UNKNOWN, failure(service.ask(new ChatRequest("Explain", null))).getCategory());
    }
}
