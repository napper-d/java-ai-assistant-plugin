package edu.teamc.courseaiassistant.application;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpServer;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.settings.*;
import edu.teamc.courseaiassistant.ui.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class LiveChatIntegrationTest {
    @TempDir Path root;

    @BeforeAll static void initializeJvmVirtualSocketPollers() throws Exception {
        // The production session factory runs on virtual threads. Their first socket I/O starts
        // JVM-owned pollers (including Write-Updater) which live until JVM exit. Initialize that
        // infrastructure before IDEA takes per-test leak baselines; retain all leak checks.
        try (var listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(5000);
            var failure = new AtomicReference<Throwable>();
            var written = new CountDownLatch(1);
            Thread client = Thread.ofVirtual().start(() -> {
                try (var socket = new java.net.Socket()) {
                    socket.setSendBufferSize(1024);
                    socket.connect(listener.getLocalSocketAddress(), 5000);
                    socket.getOutputStream().write(new byte[2 * 1024 * 1024]);
                    written.countDown();
                } catch (Throwable error) { failure.set(error); }
            });
            try (var socket = listener.accept()) {
                socket.setSoTimeout(5000);
                assertFalse(written.await(100, TimeUnit.MILLISECONDS)); // Force virtual write backpressure.
                assertEquals(2 * 1024 * 1024, socket.getInputStream().readNBytes(2 * 1024 * 1024).length);
            }
            assertTrue(client.join(java.time.Duration.ofSeconds(5)));
            assertNull(failure.get());
            until(() -> {
                var names = Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName).toList();
                return names.contains("Write-Poller") && names.contains("Write-Updater");
            });
        }
    }

    @Test void realPdfAndSlidesReachHttpModelAndLiveTranscriptWithSelectedCode() throws Exception {
        try (Fixture f = new Fixture()) {
            f.build();
            f.send("inheritance", new CodeContext("Demo.java", "JAVA", "extends Parent", 3, 3, 42, null));
            f.awaitText("Grounded answer");
            assertTrue(f.text().contains("lecture.pdf")); assertTrue(f.text().contains("page 2"));
            var prompt = f.prompt.get();
            assertEquals("extends Parent", prompt.getAsJsonObject("selectedCode").get("text").getAsString());
            assertEquals(3, prompt.getAsJsonObject("selectedCode").get("startLine").getAsInt());
            assertTrue(prompt.toString().contains("Inheritance reuses superclass behavior"));
            assertFalse(prompt.has("history")); assertFalse(prompt.has("originalFileText"));
            assertEquals(1, f.modelCalls.get()); assertEquals(1, f.queryCalls.get());
            f.send("transaction", null); f.awaitText("slides.pptx");
            assertTrue(f.text().contains("slide 2"));
            assertTrue(f.prompt.get().get("selectedCode").isJsonNull());
            assertEquals(2, f.modelCalls.get()); assertEquals(2, f.queryCalls.get());
            assertFalse(f.text().contains("invented")); assertFalse(f.text().contains("Fixed sample"));
        }
    }

    @Test void successfulNoMatchDisclosesGeneralKnowledgeButRetrievalFailureNeverCallsModel() throws Exception {
        try (Fixture f = new Fixture()) {
            f.build(); f.send("cooking", null); f.awaitText("No relevant course material was found");
            assertTrue(f.text().contains("general knowledge")); assertEquals(1, f.modelCalls.get());
            f.embeddingStatus.set(503); f.send("inheritance", null); f.awaitText("embedding service");
            assertEquals(1, f.modelCalls.get());
            f.embeddingStatus.set(200); f.send("inheritance", null); f.awaitText("Grounded answer");
            assertEquals(2, f.modelCalls.get());
        }
    }

    @Test void unavailableIncompatibleAndCorruptIndexesStayFailuresAndDoNotCallModel() throws Exception {
        for (String reason : List.of("missing", "incompatible", "corrupt")) {
            try (Fixture f = new Fixture()) {
                if (!reason.equals("missing")) f.build();
                if (reason.equals("incompatible")) {
                    var changed = f.ragSettings.getState(); changed.model = "different-embedding"; f.rag.updateSettings(changed);
                }
                if (reason.equals("corrupt")) {
                    var manifest = f.ragSettings.getState().toConfig(root, "").indexDirectory().resolve("manifest.json");
                    Files.writeString(manifest, "invalid-json");
                    f.rag.updateSettings(f.ragSettings.getState());
                }
                f.send("inheritance", null);
                f.awaitText(switch (reason) { case "missing" -> "No course index"; case "incompatible" -> "does not match"; default -> "damaged"; });
                assertEquals(0, f.modelCalls.get()); assertEquals(0, f.queryCalls.get());
            }
            // Each case owns an independent local corpus.
            root = Files.createTempDirectory(root, "next-");
        }
    }

    @Test void malformedModelReplyFailsSafelyAndCanRecover() throws Exception {
        try (Fixture f = new Fixture()) {
            f.build(); f.malformed.set(true); f.send("inheritance", null); f.awaitText("invalid or incomplete");
            assertFalse(f.text().contains("secret-response"));
            f.malformed.set(false); f.send("inheritance", null); f.awaitText("Grounded answer");
        }
    }

    @Test void clearReconfigureAndContentCloseSuppressLateRepliesAndSharedServiceRemainsUsable() throws Exception {
        try (Fixture f = new Fixture()) {
            f.build(); f.block.set(true); f.send("inheritance", null);
            assertTrue(f.arrived.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> f.button("clearChatButton").doClick(0));
            f.model.updateSettings(f.llmSettings.getState());
            f.release.countDown(); f.block.set(false);
            f.send("transaction", null); f.awaitText("slides.pptx");
            assertFalse(f.text().contains("lecture.pdf"));
            SwingUtilities.invokeAndWait(() -> { f.panel.dispose(); f.binding.dispose(); });
            // Closing Chat must not close the project's model transport.
            assertNotNull(f.model.ask(new edu.teamc.courseaiassistant.model.ChatRequest("transaction", null)).get(8, TimeUnit.SECONDS));
        }
    }


    @Test void removingLastMaterialPreventsOldIndexAnswersEvenAfterReload() throws Exception {
        try (Fixture f = new Fixture()) {
            f.build(); f.send("inheritance", null); f.awaitText("Grounded answer");
            CountDownLatch changed = new CountDownLatch(1);
            Runnable unsubscribe = f.rag.onLibraryChanged(changed::countDown);
            try {
                MaterialUpdateTask.start(f.rag, List.of(root.resolve("lecture.pdf"), root.resolve("slides.pptx")), true)
                        .get(8, TimeUnit.SECONDS);
                assertTrue(changed.await(5, TimeUnit.SECONDS));
            } finally { unsubscribe.run(); }
            for (boolean reload : List.of(false, true)) {
                if (reload) f.rag.updateSettings(f.ragSettings.getState());
                var failure = assertThrows(ExecutionException.class, () -> f.model.ask(
                        new edu.teamc.courseaiassistant.model.ChatRequest("inheritance", null)).get(8, TimeUnit.SECONDS));
                assertTrue(failure.getCause().getMessage().contains("No course index"));
                assertEquals(1, f.modelCalls.get()); assertEquals(1, f.queryCalls.get());
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService workers = Executors.newCachedThreadPool(r -> new Thread(r, "LiveChatTestHttp"));
        final RagSettings ragSettings = new RagSettings();
        final LlmSettings llmSettings = new LlmSettings();
        final AtomicReference<ProjectRagService> ragRef = new AtomicReference<>();
        final AtomicBoolean disposed = new AtomicBoolean();
        final AtomicReference<JsonObject> prompt = new AtomicReference<>();
        final AtomicInteger modelCalls = new AtomicInteger(), queryCalls = new AtomicInteger(), embeddingStatus = new AtomicInteger(200);
        final AtomicBoolean malformed = new AtomicBoolean(), block = new AtomicBoolean();
        final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        final Project project;
        final ProjectRagService rag;
        final ProjectLlmService model;
        final LiveChatSession binding;
        ChatPanel panel;

        Fixture() throws Exception {
            server.setExecutor(workers);
            server.createContext("/v1/embeddings", exchange -> {
                try {
                    assertEquals("Bearer fixture-embedding-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    var input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonArray("input");
                    JsonArray data = new JsonArray();
                    for (int i = 0; i < input.size(); i++) {
                        String text = input.get(i).getAsString().toLowerCase(java.util.Locale.ROOT);
                        if (text.equals("inheritance") || text.equals("transaction") || text.equals("cooking")) queryCalls.incrementAndGet();
                        JsonObject item = new JsonObject(); item.addProperty("index", i);
                        JsonArray vector = new JsonArray();
                        vector.add(text.contains("inheritance") ? 1 : text.contains("transaction") ? 0 : -1);
                        vector.add(text.contains("transaction") ? 1 : text.contains("inheritance") ? 0 : -1);
                        item.add("embedding", vector); data.add(item);
                    }
                    JsonObject result = new JsonObject(); result.add("data", data);
                    respond(exchange, embeddingStatus.get(), result.toString());
                } finally { exchange.close(); }
            });
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    assertEquals("Bearer fixture-model-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    var input = JsonParser.parseString(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                    prompt.set(input); modelCalls.incrementAndGet(); arrived.countDown();
                    if (block.get()) release.await(8, TimeUnit.SECONDS);
                    JsonObject answer = new JsonObject(); JsonArray citations = new JsonArray();
                    var evidence = input.getAsJsonArray("evidence");
                    if (evidence.isEmpty()) answer.addProperty("answer", "General answer");
                    else {
                        String id = evidence.get(0).getAsJsonObject().get("referenceId").getAsString();
                        answer.addProperty("answer", "Grounded answer [[cite:" + id + "]]"); citations.add(id);
                    }
                    answer.add("citations", citations);
                    JsonObject message = new JsonObject(); message.addProperty("content", malformed.get() ? "secret-response" : answer.toString());
                    JsonObject choice = new JsonObject(); choice.addProperty("finish_reason", "stop"); choice.add("message", message);
                    JsonArray choices = new JsonArray(); choices.add(choice); JsonObject response = new JsonObject(); response.add("choices", choices);
                    respond(exchange, 200, response.toString());
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var rs = ragSettings.getState(); rs.baseUrl = endpoint; rs.model = "fixture-embedding"; rs.dimensions = 2;
            ragSettings.loadState(rs);
            var ls = llmSettings.getState(); ls.baseUrl = endpoint; ls.model = "fixture-chat"; ls.timeoutMillis = 6000; llmSettings.loadState(ls);
            project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getService" -> args[0] == RagSettings.class ? ragSettings : args[0] == LlmSettings.class ? llmSettings : ragRef.get();
                case "getBasePath" -> root.toString(); case "isDisposed" -> disposed.get(); case "isDefault" -> false;
                case "toString" -> "LiveChatIntegrationProject"; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
            rag = new ProjectRagService(project, () -> "fixture-embedding-key"); ragRef.set(rag);
            model = new ProjectLlmService(project, () -> new LlmSession(llmSettings.getState().toConfig("fixture-model-key"), rag::search));
            binding = new LiveChatSession(project, model);
            SwingUtilities.invokeAndWait(() -> {
                panel = new ChatPanel(binding.coordinator());
                assertFalse(text().contains("fixed samples"));
                assertNull(find(panel, "scenarioSelector"));
            });
        }
        void build() throws Exception {
            Path pdf = root.resolve("lecture.pdf");
            try (var doc = new PDDocument()) {
                doc.addPage(new PDPage()); var page = new PDPage(); doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700); stream.showText("Inheritance reuses superclass behavior."); stream.endText();
                }
                doc.save(pdf.toFile());
            }
            Path slides = root.resolve("slides.pptx");
            try (var deck = new XMLSlideShow()) {
                deck.createSlide(); deck.createSlide().createTextBox().setText("Transaction boundaries and rollback.");
                try (var out = Files.newOutputStream(slides)) { deck.write(out); }
            }
            CountDownLatch changes = new CountDownLatch(2);
            Runnable unsubscribe = rag.onLibraryChanged(changes::countDown);
            try {
                MaterialUpdateTask.start(rag, List.of(pdf, slides), false).get(15, TimeUnit.SECONDS);
                assertTrue(changes.await(5, TimeUnit.SECONDS));
            } finally { unsubscribe.run(); }
        }
        void send(String question, CodeContext context) throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                if (context != null) panel.attachCodeContext(context);
                ((JTextArea) find(panel, "questionInput")).setText(question); button("sendButton").doClick(0);
            });
        }
        JButton button(String name) { return (JButton) find(panel, name); }
        String text() {
            AtomicReference<String> result = new AtomicReference<>();
            Runnable read = () -> result.set(((ConversationView) find(panel, "conversationTranscript")).getText());
            try { if (SwingUtilities.isEventDispatchThread()) read.run(); else SwingUtilities.invokeAndWait(read); }
            catch (Exception error) { throw new AssertionError(error); }
            return result.get();
        }
        void awaitText(String expected) throws Exception { until(() -> text().contains(expected)); }
        @Override public void close() throws Exception {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> { panel.dispose(); binding.dispose(); });
            disposed.set(true); model.dispose(); rag.dispose(); server.stop(0); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    private static Component find(Component parent, String name) {
        if (name.equals(parent.getName())) return parent;
        if (parent instanceof Container container) for (Component child : container.getComponents()) {
            Component found = find(child, name); if (found != null) return found;
        }
        return null;
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Expected transcript state was not reached");
    }
}
