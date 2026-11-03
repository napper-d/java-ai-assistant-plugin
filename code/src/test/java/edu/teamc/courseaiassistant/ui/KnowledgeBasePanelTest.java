package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.*;
import edu.teamc.courseaiassistant.rag.DocumentImporter.*;
import edu.teamc.courseaiassistant.rag.RagException;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static edu.teamc.courseaiassistant.application.KnowledgeBaseSnapshot.IndexState.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBasePanelTest {
    private static final Path FILE=Path.of("lecture.pdf").toAbsolutePath();
    private static final RegisteredDocument DOCUMENT=new RegisteredDocument("doc","lecture.pdf",FILE,Availability.MISSING);

    @Test void duplicateNoticeUsesCompactToolbarAndStillRefreshesOriginalContents() throws Exception {
        try (Fixture f = new Fixture()) {
            complete(f.next("refresh"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> f.button("addMaterials").doClick(0));
            f.next("register").future.complete(new MaterialRegistrationResult(
                    snapshot(List.of(DOCUMENT), AVAILABLE, true), 0, List.of(FILE)));
            flush();
            complete(f.next("rebuild"), snapshot(List.of(DOCUMENT), AVAILABLE, true));
            edt(() -> {
                assertEquals(1, f.table().getRowCount());
                assertEquals("Already added", f.text("libraryStatus"));
                assertTrue(((JLabel) named(f.panel, "libraryStatus")).getToolTipText().contains(FILE.toString()));
                assertFalse(named(f.panel, "indexingProgress").isVisible());
            });
        }
    }

    @Test void listShowsFilenameOnlyAndRowTrashCapturesOriginalPath() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(),NO_INDEX,false));
            edt(() -> f.button("addMaterials").doClick(0));
            var call=f.next("register");assertEquals(List.of(FILE),call.paths);
            complete(call,snapshot(List.of(DOCUMENT),NO_INDEX,false));
            edt(() -> {
                assertEquals(2,f.table().getColumnCount());assertEquals("lecture.pdf",f.table().getValueAt(0,0));
                assertNull(f.table().getTableHeader());assertNull(named(f.panel,"updateMaterials"));
                assertNull(named(f.panel,"libraryFeedback"));assertNull(named(f.panel,"libraryDetail"));
                assertTrue(f.table().editCellAt(0,1));((JButton)f.table().getEditorComponent()).doClick(0);
            });
            var remove=f.next("remove");assertEquals(List.of(FILE),remove.paths);
            complete(remove,snapshot(List.of(),NO_INDEX,false));
            edt(() -> assertEquals(0,f.table().getRowCount()));
        }
    }
    @Test void automaticIndexingKeepsActivityAtTopAndCancellationReachesBuild() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(),NO_INDEX,true));
            edt(() -> f.button("addMaterials").doClick(0));
            complete(f.next("register"),snapshot(List.of(DOCUMENT),NEEDS_REBUILD,true));
            var build=f.next("rebuild");
            edt(() -> {
                assertTrue(named(f.panel,"indexingProgress").isVisible());assertFalse(f.button("addMaterials").isEnabled());
                assertEquals("lecture.pdf",f.table().getValueAt(0,0));
                AssistantPresentationTest.render(f.panel,"materials-indexing",340,760);
                f.button("cancelLibraryTask").doClick(0);
            });
            assertTrue(build.future.isCancelled());complete(f.next("refresh"),snapshot(List.of(DOCUMENT),NEEDS_REBUILD,true));
            edt(() -> f.button("retryIndexing").doClick(0));
            complete(f.next("rebuild"),snapshot(List.of(DOCUMENT),AVAILABLE,true));
            edt(() -> {assertFalse(named(f.panel,"indexingProgress").isVisible());assertEquals("",f.text("libraryStatus"));});
        }
    }
    @Test void partialBatchFailureRecoversListAndPreservesSafeTooltip() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(),NO_INDEX,false));
            edt(() -> f.button("addMaterials").doClick(0));
            f.next("register").future.completeExceptionally(new RagException(RagException.Reason.INVALID_INPUT,"One file was rejected",false));flush();
            complete(f.next("refresh"),snapshot(List.of(DOCUMENT),NO_INDEX,false));
            edt(() -> {
                assertEquals(1,f.table().getRowCount());assertEquals("Update failed",f.text("libraryStatus"));
                assertTrue(((JLabel)named(f.panel,"libraryStatus")).getToolTipText().contains("One file was rejected"));
            });
        }
    }
    @Test void retryAfterPublicationOnlyReloadsSavedIndex() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(DOCUMENT),RELOAD_REQUIRED,true));
            edt(() -> f.button("retryIndexing").doClick(0));
            complete(f.next("refresh"),snapshot(List.of(DOCUMENT),AVAILABLE,true));
            edt(() -> assertFalse(f.button("retryIndexing").isVisible()));
        }
    }
    @Test void settingsInvalidateOldImportAndIgnoreLateCallback() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(DOCUMENT),AVAILABLE,true));
            edt(() -> {f.button("ragSettings").doClick(0);assertEquals(1,f.settingsOpened.get());f.backend.stickyNext=true;f.button("addMaterials").doClick(0);});
            var old=f.next("register");f.backend.listener.get().run();flush();
            complete(f.next("refresh"),snapshot(List.of(DOCUMENT),INCOMPATIBLE,true));
            complete(old,snapshot(List.of(),AVAILABLE,true));
            edt(() -> {assertEquals(1,f.table().getRowCount());assertTrue(f.button("retryIndexing").isVisible());});
            assertTrue(f.backend.calls.isEmpty());
        }
    }
    @Test void queuedIndexSuccessCannotWinOverCancelAndReload() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(),NO_INDEX,true));edt(() -> f.button("addMaterials").doClick(0));
            complete(f.next("register"),snapshot(List.of(DOCUMENT),NEEDS_REBUILD,true));var build=f.next("rebuild");
            edt(() -> {build.future.complete(snapshot(List.of(DOCUMENT),AVAILABLE,true));f.button("cancelLibraryTask").doClick(0);});
            complete(f.next("refresh"),snapshot(List.of(),NO_INDEX,true));
            edt(() -> assertEquals(0,f.table().getRowCount()));
        }
    }
    @Test void disposalCancelsWorkAndReleasesSubscription() throws Exception {
        try(Fixture f=new Fixture()) {
            var initial=f.next("refresh");edt(f.panel::dispose);assertTrue(initial.future.isCancelled());assertNull(f.backend.listener.get());
            complete(initial,snapshot(List.of(DOCUMENT),AVAILABLE,true));edt(() -> assertEquals(0,f.table().getRowCount()));
        }
    }
    @Test void compactListKeepsTopControlsUsableAtNarrowWidth() throws Exception {
        try(Fixture f=new Fixture()) {
            complete(f.next("refresh"),snapshot(List.of(DOCUMENT),AVAILABLE,true));
            edt(() -> {
                String[] keys={"Panel.background","Table.background","Table.foreground","Viewport.background","Label.foreground","Button.foreground"};
                var previous=new java.util.HashMap<String,Object>();for(String key:keys) previous.put(key,UIManager.get(key));
                try {
                    for(boolean dark:new boolean[]{false,true}) {
                        Color background=new Color(dark?0x242424:0xFAFAFB), foreground=new Color(dark?0xDFE1E5:0x282C34);
                        for(String key:keys) UIManager.put(key,key.endsWith("background")?background:foreground);
                        SwingUtilities.updateComponentTreeUI(f.panel);
                        AssistantPresentationTest.render(f.panel,dark?"materials-list-dark":"materials-list-light",340,760);
                    }
                } finally {for(String key:keys) UIManager.put(key,previous.get(key));}

                assertTrue(f.table().getHeight()>500);assertEquals(28,f.button("ragSettings").getWidth());
                assertNull(named(f.panel,"updateMaterials"));
            });
        }
    }
    private static KnowledgeBaseSnapshot snapshot(List<RegisteredDocument> documents, KnowledgeBaseSnapshot.IndexState state, boolean configured) {
        return new KnowledgeBaseSnapshot(documents, state, state == NO_INDEX ? 0 : 1, state == NO_INDEX ? 0 : 4,
                configured, "Saved file contents are not checked until an explicit rebuild.");
    }
    private record Call(String kind, List<Path> paths, CompletableFuture<Object> future) {}
    static final class Backend implements KnowledgeBaseOperations {
        final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        final AtomicReference<Runnable> listener = new AtomicReference<>();
        boolean stickyNext;
        @SuppressWarnings("unchecked") // The call queue deliberately erases the backend operation's result type.
        private <T> CompletableFuture<T> call(String name, List<Path> paths) {
            CompletableFuture<T> future = stickyNext ? new CompletableFuture<>() {
                @Override public boolean cancel(boolean interrupt) { return false; }
            } : new CompletableFuture<>();
            stickyNext = false; calls.add(new Call(name, paths, (CompletableFuture<Object>)(CompletableFuture<?>)future)); return future;
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
        KnowledgeBasePanel panel;
        Fixture() throws Exception { edt(() -> panel = new KnowledgeBasePanel(backend, () -> List.of(FILE), settingsOpened::incrementAndGet)); }
        Call next(String expected) throws Exception {
            Call call = backend.calls.poll(5, TimeUnit.SECONDS); assertNotNull(call); assertEquals(expected, call.kind); return call;
        }
        JButton button(String name) { return (JButton) named(panel, name); }
        JTable table() { return (JTable) named(panel, "registeredMaterials"); }
        String text(String name) { return ((JLabel) named(panel, name)).getText(); }
        @Override public void close() throws Exception { edt(() -> panel.dispose()); }
    }
    private static void complete(Call call, KnowledgeBaseSnapshot state) throws Exception { call.future.complete(call.kind.equals("register") ? new MaterialRegistrationResult(state, 1, List.of()) : state); flush(); }
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
