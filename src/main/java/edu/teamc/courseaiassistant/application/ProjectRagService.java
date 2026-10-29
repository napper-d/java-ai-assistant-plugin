package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import edu.teamc.courseaiassistant.rag.*;
import edu.teamc.courseaiassistant.settings.RagCredentials;
import edu.teamc.courseaiassistant.settings.RagSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/** Project owns index/search/transport. Creating this service never accesses files or credentials. */
@Service(Service.Level.PROJECT)
public final class ProjectRagService implements Disposable, KnowledgeBaseOperations {
    private final Project project;
    private final Supplier<String> credential;
    private RagSession session;
    private boolean disposed;
    private final List<Runnable> settingsListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> libraryListeners = new CopyOnWriteArrayList<>();

    public ProjectRagService(Project project) { this(project, () -> RagCredentials.read(project)); }

    ProjectRagService(Project project, Supplier<String> credential) {
        this.project = project;
        this.credential = credential;
    }

    public static Path projectRoot(Project project) {
        String path = project.getBasePath();
        if (project.isDefault() || path == null || path.isBlank()) {
            throw new RagException(RagException.Reason.CONFIGURATION,
                    "Open a local project before configuring course materials.", false);
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    @Override public CompletableFuture<List<KnowledgeChunk>> search(String query, int maxResults) {
        return invoke(current -> current.library.searchIfCurrent(() -> current.search.search(query, maxResults)));
    }

    @Override public CompletableFuture<KnowledgeBaseSnapshot> refreshLibrary() {
        return libraryChange(invoke(current -> current.library.refreshLibrary()));
    }
    @Override public CompletableFuture<MaterialRegistrationResult> registerMaterials(List<Path> paths) {
        return libraryChange(invoke(current -> current.library.registerMaterials(paths)));
    }
    @Override public CompletableFuture<KnowledgeBaseSnapshot> unregisterMaterials(List<Path> paths) {
        return libraryChange(invoke(current -> current.library.unregisterMaterials(paths)));
    }
    @Override public CompletableFuture<KnowledgeBaseSnapshot> rebuildLibrary() {
        return libraryChange(invoke(current -> current.library.rebuildLibrary()));
    }
    @Override public Runnable onSettingsChanged(Runnable listener) {
        settingsListeners.add(listener);
        return () -> settingsListeners.remove(listener);
    }

    @Override public Runnable onLibraryChanged(Runnable listener) {
        libraryListeners.add(listener);
        return () -> libraryListeners.remove(listener);
    }

    private <T> CompletableFuture<T> libraryChange(CompletableFuture<T> work) {
        // Partial registration or an index publication can survive a failure/cancellation.
        work.whenComplete((value, error) -> {
            for (Runnable listener : libraryListeners) listener.run();
        });
        return work; // Preserve the original cancellation bridge.
    }

    private synchronized <T> CompletableFuture<T> invoke(Function<RagSession, CompletableFuture<T>> action) {
        if (disposed || project.isDisposed()) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.CLOSED,
                    "Course RAG service is closed.", false));
        }
        try {
            if (session == null) {
                var state = project.getService(RagSettings.class).getState();
                session = new RagSession(state.toConfig(projectRoot(project), ""), credential);
            }
            return action.apply(session);
        } catch (RagException error) { return CompletableFuture.failedFuture(error); }
        catch (IllegalArgumentException error) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.CONFIGURATION,
                    "Check Course RAG settings before continuing.", false));
        }
    }

    /** Called after a successful settings save. Closing only cancels owned work; it never deletes data. */
    public synchronized void settingsChanged() {
        if (session != null) { session.close(); session = null; }
        for (Runnable listener : settingsListeners) listener.run();
    }

    public synchronized void updateSettings(RagSettings.State state) {
        if (disposed || project.isDisposed()) throw new IllegalStateException("Project is closed.");
        project.getService(RagSettings.class).loadState(state);
        settingsChanged();
    }

    @Override public synchronized void dispose() {
        disposed = true;
        settingsListeners.clear();
        libraryListeners.clear();
        settingsChanged();
    }
}
