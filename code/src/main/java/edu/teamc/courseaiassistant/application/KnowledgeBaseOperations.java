package edu.teamc.courseaiassistant.application;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import edu.teamc.courseaiassistant.model.KnowledgeChunk;

/** UI-facing material operations; all file/network work runs outside Swing. */
public interface KnowledgeBaseOperations {
    CompletableFuture<KnowledgeBaseSnapshot> refreshLibrary();
    CompletableFuture<MaterialRegistrationResult> registerMaterials(List<Path> paths);
    CompletableFuture<KnowledgeBaseSnapshot> unregisterMaterials(List<Path> paths);
    CompletableFuture<KnowledgeBaseSnapshot> rebuildLibrary();
    CompletableFuture<List<KnowledgeChunk>> search(String query, int maxResults);
    /** Returns an unsubscribe action. Listeners must marshal updates to the EDT. */
    default Runnable onSettingsChanged(Runnable listener) { return () -> {}; }
    default Runnable onLibraryChanged(Runnable listener) { return () -> {}; }
}
