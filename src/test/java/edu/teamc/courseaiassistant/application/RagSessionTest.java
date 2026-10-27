package edu.teamc.courseaiassistant.application;

import com.sun.net.httpserver.HttpServer;
import edu.teamc.courseaiassistant.rag.RagException;
import edu.teamc.courseaiassistant.settings.RagSettings;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RagSessionTest {
    @TempDir Path root;

    @Test void registrationNeedsNoCredentialOrHttpClientAndCloseRejectsFurtherWork() throws Exception {
        var reads = new AtomicInteger();
        var session = new RagSession(new RagSettings.State().toConfig(root, ""), () -> {
            reads.incrementAndGet(); throw new AssertionError("Registration must not read a key");
        });
        try {
            Path file = Files.writeString(root.resolve("registered.pdf"), "registration-only fixture");
            assertEquals(1, session.indexer.importFiles(List.of(file)).get(5, TimeUnit.SECONDS).size());
            assertEquals(0, reads.get());
        } finally { session.close(); }
        session.close();
        var failure = assertThrows(ExecutionException.class, () -> session.search.search("inheritance", 5).get());
        assertEquals(RagException.Reason.CLOSED, ((RagException) failure.getCause()).getReason());
    }

    @Test void sharedClientLoadsCredentialOnceAndReloadEnablesQueryFromPersistedIndex() throws Exception {
        AtomicInteger reads = new AtomicInteger(), requests = new AtomicInteger();
        ExecutorService serverWorkers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/v1/embeddings", exchange -> {
            assertEquals("Bearer local-stage-r1-key", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        var state = new RagSettings.State(); state.model = "local-test"; state.dimensions = 2;
        state.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try (var session = new RagSession(state.toConfig(root, ""), () -> {
            reads.incrementAndGet(); return "local-stage-r1-key";
        })) {
            session.indexer.importFiles(List.of(pdf())).get(5, TimeUnit.SECONDS);
            assertEquals(0, reads.get());
            assertEquals(1, session.indexer.rebuild().get(10, TimeUnit.SECONDS).documents());
            assertEquals(1, session.search.reload().get(5, TimeUnit.SECONDS));
            var chunks = session.search.search("Explain inheritance", 5).get(10, TimeUnit.SECONDS);
            assertEquals(1, chunks.size());
            assertEquals("lecture.pdf", chunks.getFirst().getSource().getDocumentName());
            assertEquals(1, chunks.getFirst().getSource().getPageStart());
            assertEquals(1, reads.get()); assertEquals(2, requests.get());
        } finally {
            server.stop(0); serverWorkers.shutdownNow();
            assertTrue(serverWorkers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void missingCredentialFailsExplicitlyWhenIndexingStarts() throws Exception {
        var state = new RagSettings.State(); state.model = "test"; state.baseUrl = "http://127.0.0.1:1/v1";
        try (var session = new RagSession(state.toConfig(root, ""), () -> "")) {
            session.indexer.importFiles(List.of(pdf())).get(5, TimeUnit.SECONDS);
            var error = assertThrows(ExecutionException.class, () -> session.indexer.rebuild().get(5, TimeUnit.SECONDS));
            assertEquals(RagException.Reason.CONFIGURATION, ((RagException) error.getCause()).getReason());
            assertFalse(Files.exists(state.toConfig(root, "").indexDirectory().resolve("manifest.json")));
        }
    }

    @Test void closingWhileCredentialStoreWaitsCancelsTheWorkerWithoutHoldingLifecycleLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), stopped = new CountDownLatch(1), release = new CountDownLatch(1);
        var session = new RagSession(new RagSettings.State().toConfig(root, ""), () -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); return ""; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            finally { stopped.countDown(); }
        });
        try {
            session.indexer.importFiles(List.of(pdf())).get(5, TimeUnit.SECONDS);
            var pending = session.indexer.rebuild();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            session.close();
            assertTrue(pending.isCancelled());
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); session.close(); }
    }

    private Path pdf() throws Exception {
        Path path = root.resolve("lecture.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Inheritance reuses behavior from a superclass."); content.endText();
            }
            pdf.save(path.toFile());
        }
        return path;
    }
}
