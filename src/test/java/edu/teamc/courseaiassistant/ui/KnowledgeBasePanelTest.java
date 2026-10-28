package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.*;
import edu.teamc.courseaiassistant.rag.DocumentImporter.*;
import edu.teamc.courseaiassistant.rag.RagException;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static edu.teamc.courseaiassistant.application.KnowledgeBaseSnapshot.IndexState.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBasePanelTest {
    private static final Path FILE = Path.of("lecture.pdf").toAbsolutePath();
    private static final RegisteredDocument DOCUMENT = new RegisteredDocument("doc", "lecture.pdf", FILE, Availability.MISSING);

    @Test void registrationAndRemovalUseCapturedPathsAndBusyGuardsWithoutConfiguredEmbedding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var initial = fixture.next("refresh");
            edt(() -> { assertFalse(fixture.button("addMaterials").isEnabled()); assertTrue(fixture.button("cancelLibraryTask").isEnabled()); });
            complete(initial, snapshot(List.of(), NO_INDEX, false));
            edt(() -> {
                assertFalse(fixture.button("rebuildIndex").isEnabled());
                fixture.button("addMaterials").doClick(0);
            });
            var register = fixture.next("register"); assertEquals(List.of(FILE), register.paths);
            edt(() -> { assertFalse(fixture.button("addMaterials").isEnabled()); assertFalse(fixture.button("ragSettings").isEnabled()); });
            complete(register, new MaterialRegistrationResult(snapshot(List.of(DOCUMENT), NO_INDEX, false), 1, List.of()));
            assertTrue(fixture.notices.isEmpty());
            edt(() -> {
                assertEquals(1, fixture.table().getRowCount());
                assertEquals("Missing", fixture.table().getValueAt(0, 2));
                assertFalse(fixture.button("rebuildIndex").isEnabled());
                fixture.table().setRowSelectionInterval(0, 0);
                fixture.button("removeMaterials").doClick(0);
            });
            var remove = fixture.next("remove"); assertEquals(List.of(FILE), remove.paths);
            complete(remove, snapshot(List.of(), NEEDS_REBUILD, false));
            edt(() -> { assertEquals(0, fixture.table().getRowCount()); assertTrue(fixture.text("libraryFeedback").contains("Original files")); });
        }
    }

    @Test void repeatedFilesShowOneNoticeWithoutReportingNewRegistrations() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> fixture.button("addMaterials").doClick(0));
            complete(fixture.next("register"), new MaterialRegistrationResult(
                    snapshot(List.of(DOCUMENT), AVAILABLE, true), 0, List.of(FILE)));
            String notice = fixture.notices.poll(5, TimeUnit.SECONDS);
            assertNotNull(notice); assertTrue(notice.contains("No files were added"));
            assertTrue(notice.contains(FILE.toString())); assertTrue(fixture.notices.isEmpty());
            edt(() -> {
                assertEquals(1, fixture.table().getRowCount());
                assertEquals("Saved index available", fixture.text("libraryStatus"));
                assertTrue(fixture.text("libraryFeedback").contains("already registered"));
                assertFalse(fixture.text("libraryFeedback").contains("Rebuild"));
            });
        }
    }

    @Test void mixedSelectionAddsNewFilesAndListsOnlyDuplicatesInTheNotice() throws Exception {
        Path other = FILE.resolveSibling("other-lecture.pdf");
        var otherDocument = new RegisteredDocument("other", "other-lecture.pdf", other, Availability.AVAILABLE);
        try (Fixture fixture = new Fixture(List.of(FILE, other))) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> fixture.button("addMaterials").doClick(0));
            var registration = fixture.next("register"); assertEquals(List.of(FILE, other), registration.paths);
            complete(registration, new MaterialRegistrationResult(
                    snapshot(List.of(DOCUMENT, otherDocument), NEEDS_REBUILD, true), 1, List.of(FILE)));
            String notice = fixture.notices.poll(5, TimeUnit.SECONDS);
            assertNotNull(notice); assertTrue(notice.contains("1 new file registered"));
            assertTrue(notice.contains(FILE.toString())); assertFalse(notice.contains(other.toString()));
            edt(() -> { assertEquals(2, fixture.table().getRowCount()); assertTrue(fixture.button("addMaterials").isEnabled()); });
        }
    }

    @Test void cancelledRegistrationCannotShowALateDuplicateNotice() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> fixture.button("addMaterials").doClick(0));
            var registration = fixture.next("register");
            edt(() -> {
                registration.future.complete(new MaterialRegistrationResult(
                        snapshot(List.of(DOCUMENT), AVAILABLE, true), 0, List.of(FILE)));
                fixture.button("cancelLibraryTask").doClick(0);
            });
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            assertTrue(fixture.notices.isEmpty());
            edt(() -> assertTrue(fixture.text("libraryFeedback").startsWith("Cancelled")));
        }
    }

    @Test void failedBatchRefreshesActualRegistrationsAndPreservesTheSafeError() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(), NO_INDEX, false));
            edt(() -> fixture.button("addMaterials").doClick(0));
            var failed = fixture.next("register");
            failed.future.completeExceptionally(new RagException(RagException.Reason.INVALID_INPUT, "One file was rejected; earlier files may remain.", false));
            flush();
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), NO_INDEX, false));
            edt(() -> {
                assertEquals(1, fixture.table().getRowCount());
                assertTrue(fixture.text("libraryFeedback").contains("One file was rejected"));
                assertTrue(fixture.button("addMaterials").isEnabled());
            });
        }
    }

    @Test void publishedButNotLoadedDoesNotDisplayRebuildSuccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> fixture.button("rebuildIndex").doClick(0));
            complete(fixture.next("rebuild"), snapshot(List.of(DOCUMENT), RELOAD_REQUIRED, true));
            edt(() -> {
                assertTrue(fixture.text("libraryStatus").contains("loading needs retry"));
                assertTrue(fixture.text("libraryFeedback").contains("loading failed"));
                assertFalse(fixture.text("libraryFeedback").contains("rebuilt and loaded"));
                fixture.button("refreshMaterials").doClick(0);
            });
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> assertEquals("Saved index available", fixture.text("libraryStatus")));
        }
    }

    @Test void cancelInvalidatesASuccessAlreadyQueuedOnTheEdtAndRefreshesDiskState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> fixture.button("rebuildIndex").doClick(0));
            var old = fixture.next("rebuild");
            edt(() -> {
                old.future.complete(snapshot(List.of(DOCUMENT), AVAILABLE, true));
                fixture.button("cancelLibraryTask").doClick(0);
            });
            complete(fixture.next("refresh"), snapshot(List.of(), NO_INDEX, false));
            edt(() -> {
                assertEquals(0, fixture.table().getRowCount());
                assertEquals("No saved index", fixture.text("libraryStatus"));
                assertTrue(fixture.text("libraryFeedback").startsWith("Cancelled"));
            });
        }
    }

    @Test void settingsChangeReplacesPendingWorkAndLateOldCallbacksCannotRestoreOldState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> { fixture.button("ragSettings").doClick(0); assertEquals(1, fixture.settingsOpened.get());
                fixture.backend.stickyNext = true; fixture.button("rebuildIndex").doClick(0); });
            var old = fixture.next("rebuild");
            fixture.backend.listener.get().run(); flush();
            var fresh = fixture.next("refresh");
            complete(fresh, snapshot(List.of(DOCUMENT), INCOMPATIBLE, true));
            complete(old, snapshot(List.of(), AVAILABLE, true));
            edt(() -> {
                assertEquals(1, fixture.table().getRowCount());
                assertTrue(fixture.text("libraryStatus").contains("settings changed"));
                assertTrue(fixture.button("rebuildIndex").isEnabled());
            });
        }
    }

    @Test void contentDisposalCancelsCurrentWorkAndUnsubscribesSettingsUpdates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var initial = fixture.next("refresh");
            edt(() -> fixture.panel.dispose());
            assertTrue(initial.future.isCancelled()); assertNull(fixture.backend.listener.get());
            complete(initial, snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> assertEquals(0, fixture.table().getRowCount()));
            assertTrue(fixture.backend.calls.isEmpty());
        }
    }

    @Test void narrowLayoutKeepsControlsAndMaterialListUsable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            complete(fixture.next("refresh"), snapshot(List.of(DOCUMENT), NEEDS_REBUILD, false));
            edt(() -> {
                fixture.panel.setSize(340, 760);
                for (int pass = 0; pass < 3; pass++) layout(fixture.panel);
                assertTrue(fixture.table().getParent().getHeight() >= 80, "The list must retain usable space in a narrow Tool Window");
                for (String name : List.of("addMaterials", "removeMaterials", "refreshMaterials", "rebuildIndex", "ragSettings")) {
                    JButton button = fixture.button(name);
                    assertTrue(button.getWidth() >= 120, "Toolbar buttons must fit the narrow width");
                }
                try {
                    Path output = Path.of("build/reports/rag-gui-r2/materials-narrow.png");
                    Files.createDirectories(output.getParent());
                    var bitmap = new BufferedImage(340, 760, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = bitmap.createGraphics(); fixture.panel.printAll(graphics); graphics.dispose();
                    ImageIO.write(bitmap, "png", output.toFile());
                } catch (java.io.IOException error) { throw new AssertionError(error); }
            });
        }
    }

    private static void layout(Component component) {
        if (component instanceof Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) layout(child); }
    }

    private static KnowledgeBaseSnapshot snapshot(List<RegisteredDocument> documents, KnowledgeBaseSnapshot.IndexState state, boolean configured) {
        return new KnowledgeBaseSnapshot(documents, state, state == NO_INDEX ? 0 : 1, state == NO_INDEX ? 0 : 4,
                configured, "Saved file contents are not checked until an explicit rebuild.");
    }
    private record Call(String kind, List<Path> paths, CompletableFuture<Object> future) {}
    private static final class Backend implements KnowledgeBaseOperations {
        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final AtomicReference<Runnable> listener = new AtomicReference<>();
        boolean stickyNext;
        @SuppressWarnings("unchecked")
        private <T> CompletableFuture<T> call(String name, List<Path> paths) {
            CompletableFuture<Object> future = stickyNext ? new CompletableFuture<>() {
                @Override public boolean cancel(boolean interrupt) { return false; }
            } : new CompletableFuture<>();
            stickyNext = false; calls.add(new Call(name, paths, future)); return (CompletableFuture<T>) (CompletableFuture<?>) future;
        }
        @Override public CompletableFuture<KnowledgeBaseSnapshot> refreshLibrary() { return call("refresh", List.of()); }
        @Override public CompletableFuture<MaterialRegistrationResult> registerMaterials(List<Path> paths) { return call("register", paths); }
        @Override public CompletableFuture<KnowledgeBaseSnapshot> unregisterMaterials(List<Path> paths) { return call("remove", paths); }
        @Override public CompletableFuture<KnowledgeBaseSnapshot> rebuildLibrary() { return call("rebuild", List.of()); }
        @Override public CompletableFuture<List<edu.teamc.courseaiassistant.model.KnowledgeChunk>> search(String query, int maxResults) {
            throw new AssertionError("Material management must not query embeddings");
        }
        @Override public Runnable onSettingsChanged(Runnable callback) { listener.set(callback); return () -> listener.set(null); }
    }
    private static final class Fixture implements AutoCloseable {
        final Backend backend = new Backend();
        final AtomicInteger settingsOpened = new AtomicInteger();
        final BlockingQueue<String> notices = new LinkedBlockingQueue<>();
        KnowledgeBasePanel panel;
        Fixture() throws Exception { this(List.of(FILE)); }
        Fixture(List<Path> selected) throws Exception {
            edt(() -> panel = new KnowledgeBasePanel(backend, () -> selected, settingsOpened::incrementAndGet, message -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); notices.add(message);
            }));
        }
        Call next(String expected) throws Exception {
            Call call = backend.calls.poll(5, TimeUnit.SECONDS); assertNotNull(call); assertEquals(expected, call.kind); return call;
        }
        JButton button(String name) { return (JButton) named(panel, name); }
        JTable table() { return (JTable) named(panel, "registeredMaterials"); }
        String text(String name) { return ((JTextArea) named(panel, name)).getText(); }
        @Override public void close() throws Exception { edt(() -> panel.dispose()); }
    }
    private static void complete(Call call, Object state) throws Exception { call.future.complete(state); flush(); }
    private static void flush() throws Exception { edt(() -> {}); }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static Component named(Component parent, String name) {
        if (name.equals(parent.getName())) return parent;
        if (parent instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
}
