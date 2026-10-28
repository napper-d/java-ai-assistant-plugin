package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.*;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.rag.RagException;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalPreviewPanelTest {
    private static final KnowledgeChunk PDF = new KnowledgeChunk("pdf-chunk",
            new SourceReference("R-real-pdf", "lecture.pdf", 2, 3, null), "Literal List<T> inheritance evidence.", .9);
    private static final KnowledgeChunk SLIDE = new KnowledgeChunk("slide-chunk",
            new SourceReference("R-real-slide", "slides.pptx", null, null, "slide 4"), "Transaction slide evidence.", .4);

    @Test void blankInputKeyboardNewlineAndCapturedQuestionUseExactlyFiveResults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            edt(() -> {
                assertFalse(fixture.button("searchEvidence").isEnabled());
                fixture.question().getActionMap().get("searchCourseEvidence").actionPerformed(null);
                assertTrue(fixture.status().contains("Enter a question first"));
                fixture.question().setText(" first line");
                fixture.question().getActionMap().get("evidenceNewline").actionPerformed(null);
                assertTrue(fixture.question().getText().contains("\n"));
                fixture.question().setText("  inheritance  "); fixture.button("searchEvidence").doClick(0);
                fixture.question().setText("next draft");
                fixture.question().getActionMap().get("searchCourseEvidence").actionPerformed(null);
                assertFalse(fixture.button("searchEvidence").isEnabled());
            });
            var call = fixture.next(); assertEquals("inheritance", call.query); assertEquals(5, call.maximum);
            assertTrue(fixture.backend.calls.isEmpty());
            complete(call, List.of(PDF));
            edt(() -> { assertTrue(fixture.evidence().contains("You\ninheritance")); assertEquals("next draft", fixture.question().getText()); });
        }
    }

    @Test void preservesPdfPagesPptxScopesReferenceIdsAndLowScoreExpandedEvidence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.submit("course question"); complete(fixture.next(), List.of(PDF, SLIDE));
            edt(() -> {
                String text = fixture.evidence();
                assertTrue(text.contains("[R-real-pdf] lecture.pdf — pages 2–3"));
                assertTrue(text.contains("[R-real-slide] slides.pptx — slide 4"));
                assertTrue(text.contains("Literal List<T>")); assertTrue(text.contains(SLIDE.getText()));
                assertFalse(text.contains("Query similarity"));
                ((JCheckBox) named(fixture.panel, "retrievalScores")).doClick(0);
                assertTrue(fixture.evidence().contains("0.400 (not a probability)"));
                assertTrue(fixture.evidence().contains("Chunk: slide-chunk"));
                assertTrue(fixture.backend.calls.isEmpty(), "Showing scores must not send another request");
                Color previous = UIManager.getColor("Panel.background");
                try {
                    for (boolean dark : new boolean[]{false, true}) {
                        UIManager.put("Panel.background", dark ? new Color(0x242424) : new Color(0xFAFAFB));
                        SwingUtilities.updateComponentTreeUI(fixture.panel);
                        assertEquals(ChatTheme.current().text(), fixture.question().getForeground());
                        assertTrue(fixture.evidence().contains("Literal List<T>"));
                        snapshot(fixture, 440, 760, dark ? "retrieval-chat-dark.png" : "retrieval-chat-light.png");
                    }
                } finally {
                    UIManager.put("Panel.background", previous);
                    SwingUtilities.updateComponentTreeUI(fixture.panel);
                }
            });
        }
    }

    @Test void emptySearchAndNetworkOrIndexFailuresRemainDifferentAndRecoverable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.submit("unrelated"); complete(fixture.next(), List.of());
            edt(() -> { assertTrue(fixture.status().startsWith("No relevant course material")); assertFalse(fixture.status().contains("failed")); });
            for (var reason : List.of(RagException.Reason.NETWORK_ERROR, RagException.Reason.INDEX_UNAVAILABLE, RagException.Reason.INDEX_INCOMPATIBLE)) {
                fixture.submit("retry query"); var call = fixture.next();
                call.future.completeExceptionally(new CompletionException(new RagException(reason, "Safe " + reason, true))); flush();
                edt(() -> { assertTrue(fixture.status().startsWith("Search failed.")); assertTrue(fixture.status().contains(reason.name()));
                    assertFalse(fixture.status().contains("No relevant")); assertTrue(fixture.evidence().contains("Safe " + reason));
                    assertEquals("", fixture.question().getText()); assertTrue(fixture.question().isEditable()); });
            }
            fixture.submit("working query"); complete(fixture.next(), List.of(PDF));
            edt(() -> assertTrue(fixture.status().startsWith("1 excerpt")));
        }
    }

    @Test void cancelInvalidatesQueuedSuccessAndLateOldResultsCannotReplaceTheNextQuery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.submit("old query"); var old = fixture.next();
            edt(() -> { old.future.complete(List.of(PDF)); fixture.button("cancelEvidence").doClick(0); });
            flush();
            edt(() -> { assertTrue(fixture.status().startsWith("Search cancelled")); assertFalse(fixture.evidence().contains("R-real-pdf")); });
            edt(() -> fixture.backend.stickyNext = true);
            fixture.submit("uncancellable old query"); var late = fixture.next();
            edt(() -> fixture.button("cancelEvidence").doClick(0));
            fixture.submit("new query"); var current = fixture.next();
            complete(late, List.of(PDF));
            edt(() -> { assertTrue(fixture.status().startsWith("Searching")); assertFalse(fixture.button("searchEvidence").isEnabled()); });
            complete(current, List.of(SLIDE));
            edt(() -> { assertTrue(fixture.evidence().contains("new query")); assertFalse(fixture.evidence().contains("lecture.pdf")); });
        }
    }

    @Test void settingsAndLibraryChangesClearEvidenceAndCancelPendingQueriesWithoutAutoSearching() throws Exception {
        try (Fixture fixture = new Fixture()) {
            edt(() -> fixture.question().setText("unsubmitted draft"));
            fixture.backend.libraryListener.get().run(); flush();
            edt(() -> assertEquals("unsubmitted draft", fixture.question().getText()));
            assertTrue(fixture.backend.calls.isEmpty());
            fixture.submit("first query"); complete(fixture.next(), List.of(PDF));
            fixture.backend.libraryListener.get().run(); flush();
            edt(() -> { assertFalse(fixture.evidence().contains("R-real-pdf")); assertTrue(fixture.status().contains("materials or index loaded/changed")); });
            fixture.submit("second query"); var pending = fixture.next();
            edt(() -> fixture.question().setText("next draft"));
            fixture.backend.settingsListener.get().run(); flush();
            assertTrue(pending.future.isCancelled());
            edt(() -> { assertFalse(fixture.evidence().contains("R-real-pdf")); assertTrue(fixture.status().contains("Settings changed"));
                assertEquals("next draft", fixture.question().getText()); assertTrue(fixture.button("searchEvidence").isEnabled()); });
            assertTrue(fixture.backend.calls.isEmpty());
        }
    }

    @Test void clearCancelsWorkAndResetsDraftWhileDisposalUnsubscribesBothListeners() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.submit("clear this"); var pending = fixture.next();
            edt(() -> fixture.button("clearEvidence").doClick(0));
            assertTrue(pending.future.isCancelled()); flush();
            edt(() -> { assertEquals("", fixture.question().getText()); assertFalse(fixture.evidence().contains("R-real-pdf"));
                assertFalse(fixture.button("searchEvidence").isEnabled()); fixture.button("evidenceSettings").doClick(0); });
            assertEquals(1, fixture.settingsOpened.get());
            fixture.submit("close this"); var closing = fixture.next();
            edt(() -> fixture.panel.dispose());
            assertTrue(closing.future.isCancelled()); assertNull(fixture.backend.settingsListener.get()); assertNull(fixture.backend.libraryListener.get());
            complete(closing, List.of(PDF));
            edt(() -> assertFalse(fixture.evidence().contains("R-real-pdf")));
        }
    }

    @Test void startupUnknownAndInvalidResponseFailuresAreSafeAndDoNotBecomeNoMatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            edt(() -> fixture.backend.startupFailure = new IllegalStateException("private-key-and-source"));
            fixture.submit("startup error");
            edt(() -> { assertTrue(fixture.status().startsWith("Search failed.")); assertFalse(fixture.status().contains("private-key"));
                assertEquals("", fixture.question().getText()); });
            fixture.submit("null response"); var invalid = fixture.next(); invalid.future.complete(null); flush();
            edt(() -> { assertTrue(fixture.status().startsWith("Search failed.")); assertFalse(fixture.status().contains("No relevant")); });
            fixture.submit("exceptional response"); var failed = fixture.next(); failed.future.completeExceptionally(new RuntimeException("private-key-and-source")); flush();
            edt(() -> assertFalse(fixture.status().contains("private-key")));
            fixture.submit("recovered"); complete(fixture.next(), List.of(PDF));
            edt(() -> assertTrue(fixture.evidence().contains("R-real-pdf")));
        }
    }

    @Test void narrowLayoutWrapsLongLiteralEvidenceAndRetainsReadableControls() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.submit("Explain course evidence");
            KnowledgeChunk longText = new KnowledgeChunk("long-chunk", new SourceReference("R-" + "a".repeat(64),
                    "course-material.pdf", 8, 8, null), "Literal <T> & course evidence. ".repeat(70), .82);
            complete(fixture.next(), List.of(longText));
            edt(() -> {
                fixture.panel.setSize(340, 760);
                for (int pass = 0; pass < 3; pass++) layout(fixture.panel);
                Component body = named(fixture.panel, "retrievalEvidence");
                assertTrue(body.getParent().getHeight() > 160);
                assertEquals(32, fixture.button("searchEvidence").getWidth());
                Point input = SwingUtilities.convertPoint(fixture.question(), 0, 0, fixture.panel);
                Point transcript = SwingUtilities.convertPoint(body, 0, 0, fixture.panel);
                assertTrue(input.y > 450, "Input belongs at the bottom");
                assertTrue(transcript.y < input.y);
                assertTrue(fixture.question().getHeight() >= 45);
                assertTrue(fixture.evidence().contains("Literal <T> &"));
                try {
                    Path output = Path.of("build/reports/rag-gui-r3/retrieval-narrow.png"); Files.createDirectories(output.getParent());
                    var image = new BufferedImage(340, 760, BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); fixture.panel.printAll(graphics); graphics.dispose();
                    ImageIO.write(image, "png", output.toFile());
                } catch (java.io.IOException error) { throw new AssertionError(error); }
            });
        }
    }

    @Test void successiveQuestionsRetainHistoryAndCancellationPreservesCompletedEvidence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.submit("first question"); complete(fixture.next(), List.of(PDF));
            fixture.submit("second question"); var second = fixture.next();
            edt(() -> {
                assertEquals("", fixture.question().getText());
                assertTrue(fixture.evidence().contains("first question"));
                assertTrue(fixture.evidence().contains("R-real-pdf"));
                assertTrue(fixture.evidence().contains("second question"));
                fixture.question().setText("third draft");
                fixture.button("cancelEvidence").doClick(0);
            });
            assertTrue(second.future.isCancelled());
            edt(() -> {
                assertTrue(fixture.evidence().contains("R-real-pdf"));
                assertTrue(fixture.evidence().contains("Search cancelled"));
                assertEquals("third draft", fixture.question().getText());
            });
            fixture.submit("third question"); complete(fixture.next(), List.of(SLIDE));
            edt(() -> {
                String transcript = fixture.evidence();
                assertTrue(transcript.indexOf("first question") < transcript.indexOf("second question"));
                assertTrue(transcript.indexOf("second question") < transcript.indexOf("third question"));
                assertTrue(transcript.contains("R-real-slide"));
                ((JCheckBox) named(fixture.panel, "retrievalScores")).doClick(0);
                assertTrue(fixture.evidence().contains("0.900"));
                assertTrue(fixture.evidence().contains("0.400"));
                assertTrue(fixture.backend.calls.isEmpty());
                fixture.button("clearEvidence").doClick(0);
                assertFalse(fixture.evidence().contains("first question"));
                assertTrue(fixture.evidence().contains("Search your course"));
            });
        }
    }

    private static void layout(Component component) {
        if (component instanceof Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) layout(child); }
    }

    private static void snapshot(Fixture fixture, int width, int height, String name) {
        fixture.panel.setSize(width, height);
        for (int pass = 0; pass < 3; pass++) layout(fixture.panel);
        Component transcript = named(fixture.panel, "retrievalEvidence");
        ((JViewport) transcript.getParent()).setViewPosition(new Point());
        try {
            Path output = Path.of("build/reports/rag-gui-r3/", name); Files.createDirectories(output.getParent());
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); fixture.panel.printAll(graphics); graphics.dispose();
            ImageIO.write(image, "png", output.toFile());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private record Call(String query, int maximum, CompletableFuture<List<KnowledgeChunk>> future) {}
    private static final class Backend implements KnowledgeBaseOperations {
        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final AtomicReference<Runnable> settingsListener = new AtomicReference<>(), libraryListener = new AtomicReference<>();
        boolean stickyNext;
        RuntimeException startupFailure;
        @Override public CompletableFuture<List<KnowledgeChunk>> search(String query, int maximum) {
            if (startupFailure != null) { var failure = startupFailure; startupFailure = null; throw failure; }
            CompletableFuture<List<KnowledgeChunk>> future = stickyNext ? new CompletableFuture<>() {
                @Override public boolean cancel(boolean interrupt) { return false; }
            } : new CompletableFuture<>();
            stickyNext = false; calls.add(new Call(query, maximum, future)); return future;
        }
        @Override public CompletableFuture<KnowledgeBaseSnapshot> refreshLibrary() { throw new AssertionError("Preview must not scan the registry"); }
        @Override public CompletableFuture<MaterialRegistrationResult> registerMaterials(List<Path> paths) { throw new AssertionError(); }
        @Override public CompletableFuture<KnowledgeBaseSnapshot> unregisterMaterials(List<Path> paths) { throw new AssertionError(); }
        @Override public CompletableFuture<KnowledgeBaseSnapshot> rebuildLibrary() { throw new AssertionError(); }
        @Override public Runnable onSettingsChanged(Runnable listener) { settingsListener.set(listener); return () -> settingsListener.set(null); }
        @Override public Runnable onLibraryChanged(Runnable listener) { libraryListener.set(listener); return () -> libraryListener.set(null); }
    }
    private static final class Fixture implements AutoCloseable {
        final Backend backend = new Backend();
        final AtomicInteger settingsOpened = new AtomicInteger();
        RetrievalPreviewPanel panel;
        Fixture() throws Exception { edt(() -> panel = new RetrievalPreviewPanel(backend, settingsOpened::incrementAndGet)); }
        JButton button(String name) { return (JButton) named(panel, name); }
        JTextArea question() { return (JTextArea) named(panel, "retrievalQuestion"); }
        String evidence() { return ((ConversationView) named(panel, "retrievalEvidence")).getText(); }
        String status() { return ((JTextArea) named(panel, "retrievalStatus")).getText(); }
        void submit(String text) throws Exception { edt(() -> { question().setText(text); button("searchEvidence").doClick(0); }); }
        Call next() throws Exception { var call = backend.calls.poll(5, TimeUnit.SECONDS); assertNotNull(call); return call; }
        @Override public void close() throws Exception { edt(() -> panel.dispose()); }
    }
    private static void complete(Call call, List<KnowledgeChunk> result) throws Exception { call.future.complete(result); flush(); }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void flush() throws Exception { edt(() -> {}); }
    private static Component named(Component parent, String name) {
        if (name.equals(parent.getName())) return parent;
        if (parent instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
}
