package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.*;
import edu.teamc.courseaiassistant.settings.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Shared project assembly for Chat and explicit F2; creating/saving it makes no model request. */
@Service(Service.Level.PROJECT)
public final class ProjectLlmService implements Disposable, ChatService, CodeGenerationService {
    private final Project project;
    private final LlmRuntime runtime;
    private final Runnable unsubscribeSettings;
    private final Runnable unsubscribeLibrary;
    private final List<Runnable> chatInvalidationListeners = new CopyOnWriteArrayList<>();
    private boolean disposed;

    public ProjectLlmService(Project project) {
        this(project, null);
    }

    ProjectLlmService(Project project, Supplier<LlmRuntime.Session> factory) {
        this.project = project;
        ProjectRagService rag = project.getService(ProjectRagService.class);
        runtime = new LlmRuntime(factory != null ? factory : () -> {
            var state = project.getService(LlmSettings.class).getState();
            state.toConfig("validation-only"); // Reject incomplete setup before opening an OS credential prompt.
            return new LlmSession(state.toConfig(LlmCredentials.read(project)), rag::search);
        });
        unsubscribeSettings = rag.onSettingsChanged(this::retrievalChanged);
        unsubscribeLibrary = rag.onLibraryChanged(this::retrievalChanged);
    }

    @Override public CompletableFuture<ChatAnswer> ask(ChatRequest request) { return runtime.ask(request); }
    @Override public CompletableFuture<ChangeProposal> generate(ModificationRequest request) { return runtime.generate(request); }

    public Duration coordinatorTimeout() {
        return project.getService(LlmSettings.class).getState().coordinatorTimeout();
    }

    /** UI owners subscribe cancelAll to invalidate even a success already queued for EDT delivery. */
    public Runnable onChatInvalidated(Runnable listener) {
        chatInvalidationListeners.add(listener);
        return () -> chatInvalidationListeners.remove(listener);
    }

    private void retrievalChanged() {
        runtime.cancelChats();
        chatInvalidationListeners.forEach(Runnable::run);
    }

    public synchronized void updateSettings(LlmSettings.State next) {
        if (disposed || project.isDisposed()) throw new IllegalStateException("Project is closed.");
        next.validate();
        project.getService(LlmSettings.class).loadState(next);
        runtime.invalidate();
        chatInvalidationListeners.forEach(Runnable::run);
    }

    @Override public synchronized void dispose() {
        if (disposed) return;
        disposed = true;
        unsubscribeSettings.run();
        unsubscribeLibrary.run();
        runtime.close();
        chatInvalidationListeners.clear();
    }
}
