package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import edu.teamc.courseaiassistant.rag.*;
import edu.teamc.courseaiassistant.settings.RagCredentials;
import edu.teamc.courseaiassistant.settings.RagSettings;
import edu.teamc.courseaiassistant.settings.GlobalConfiguration;

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
    private final ProjectRagService shared;
    private final java.util.Set<CompletableFuture<?>> owned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private RagSession session;
    private boolean disposed;
    private final List<Runnable> settingsListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> libraryListeners = new CopyOnWriteArrayList<>();

    public ProjectRagService(Project project) { this(project, () -> RagCredentials.read(project), true); }

    ProjectRagService(Project project, Supplier<String> credential) {
        this(project, credential, false);
    }
    private ProjectRagService(Project project, Supplier<String> credential, boolean global) {
        this.project = project;
        this.credential = credential;
        shared = global && GlobalConfiguration.available() != null
                ? com.intellij.openapi.application.ApplicationManager.getApplication().getService(GlobalRagLibrary.class).library : null;
    }

    public static Path projectRoot(Project project) {
        if (GlobalConfiguration.available() != null) return GlobalConfiguration.dataRoot();
        String path = project.getBasePath();
        if (project.isDefault() || path == null || path.isBlank()) {
            throw new RagException(RagException.Reason.CONFIGURATION,
                    "Open a local project before configuring course materials.", false);
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    @Override public CompletableFuture<List<KnowledgeChunk>> search(String query, int maxResults) {
        if (shared != null) return forward(() -> shared.search(query, maxResults));
        return invoke(current -> current.library.searchIfCurrent(() -> current.search.search(query, maxResults)));
    }

    @Override public CompletableFuture<KnowledgeBaseSnapshot> refreshLibrary() {
        if (shared != null) return forward(shared::refreshLibrary);
        if (project == null) return invoke(current -> current.library.refreshLibrary());
        return libraryChange(invoke(current -> current.library.refreshLibrary()));
    }
    @Override public CompletableFuture<MaterialRegistrationResult> registerMaterials(List<Path> paths) {
        if (shared != null) return forward(() -> shared.registerMaterials(paths));
        return libraryChange(invoke(current -> current.library.registerMaterials(paths)));
    }
    @Override public CompletableFuture<KnowledgeBaseSnapshot> unregisterMaterials(List<Path> paths) {
        if (shared != null) return forward(() -> shared.unregisterMaterials(paths));
        return libraryChange(invoke(current -> current.library.unregisterMaterials(paths)));
    }
    @Override public CompletableFuture<KnowledgeBaseSnapshot> rebuildLibrary() {
        if (shared != null) return forward(shared::rebuildLibrary);
        return libraryChange(invoke(current -> current.library.rebuildLibrary()));
    }
    @Override public Runnable onSettingsChanged(Runnable listener) {
        if (shared != null) return shared.onSettingsChanged(listener);
        settingsListeners.add(listener);
        return () -> settingsListeners.remove(listener);
    }

    @Override public Runnable onLibraryChanged(Runnable listener) {
        if (shared != null) return shared.onLibraryChanged(listener);
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
        if (disposed || project != null && project.isDisposed()) {
            return CompletableFuture.failedFuture(new RagException(RagException.Reason.CLOSED,
                    "Course RAG service is closed.", false));
        }
        try {
            if (session == null) {
                var state = project == null ? GlobalConfiguration.available().rag().getState() : project.getService(RagSettings.class).getState();
                session = new RagSession(state.toConfig(project == null ? GlobalConfiguration.dataRoot() : projectRoot(project), ""), credential);
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
        if (shared != null) { shared.updateSettings(state); return; }
        if (project == null) {
            GlobalConfiguration.available().updateRag(state); settingsChanged(); return;
        }
        if (disposed || project.isDisposed()) throw new IllegalStateException("Project is closed.");
        project.getService(RagSettings.class).loadState(state);
        settingsChanged();
    }

    @Override public synchronized void dispose() {
        disposed = true;
        owned.forEach(work -> work.cancel(true)); owned.clear();
        if (shared != null) return;
        settingsListeners.clear();
        libraryListeners.clear();
        settingsChanged();
    }

    private synchronized <T> CompletableFuture<T> forward(Supplier<CompletableFuture<T>> action) {
        var child = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<T>>();
        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean cancelled = super.cancel(interrupt);
                var current = child.get(); if (cancelled && current != null) current.cancel(interrupt);
                return cancelled;
            }
        };
        if (disposed || project.isDisposed()) { result.cancel(true); return result; }
        owned.add(result);
        result.whenComplete((value, error) -> owned.remove(result));
        try {
            var work = action.get(); child.set(work);
            if (result.isCancelled()) work.cancel(true);
            work.whenComplete((value, failure) -> {
                if (work.isCancelled()) result.cancel(true);
                else if (failure != null) result.completeExceptionally(failure); else result.complete(value);
            });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }
}
