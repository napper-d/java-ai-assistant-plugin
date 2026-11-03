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
public final class ProjectLlmService implements Disposable, ChatService, CodeGenerationService, ModificationService {
    private final Project project;
    private final GlobalConfiguration global;
    private final Runnable unsubscribeModel;
    private final LlmRuntime runtime;
    private final Runnable unsubscribeSettings;
    private final Runnable unsubscribeLibrary;
    private final List<Runnable> chatInvalidationListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> modificationInvalidationListeners = new CopyOnWriteArrayList<>();
    private long revision;
    private boolean disposed;

    public ProjectLlmService(Project project) {
        this(project, null);
    }

    ProjectLlmService(Project project, Supplier<LlmRuntime.Session> factory) {
        this.project = project;
        global = factory == null ? GlobalConfiguration.available() : null;
        ProjectRagService rag = project.getService(ProjectRagService.class);
        runtime = new LlmRuntime(factory != null ? factory : () -> {
            synchronized (global == null ? this : global.credentialsLock()) {
                var state = LlmSettings.getInstance(project).getState();
                state.toConfig("validation-only");
                return new LlmSession(state.toConfig(LlmCredentials.read(project)), rag::search);
            }
        });
        unsubscribeSettings = rag.onSettingsChanged(this::retrievalChanged);
        unsubscribeLibrary = rag.onLibraryChanged(this::retrievalChanged);
        unsubscribeModel = global == null ? () -> {} : global.onModelChanged(this::modelChanged);
    }

    @Override public CompletableFuture<ChatAnswer> ask(ChatRequest request) { return runtime.ask(request); }
    @Override public CompletableFuture<ChangeProposal> generate(ModificationRequest request) { return runtime.generate(request); }

    /** Own the request through review/apply, even after model generation has completed. */
    @Override public synchronized CompletableFuture<ModificationResult> modify(ModificationRequest request) {
        long expected = revision;
        long sharedRevision = global == null ? 0 : global.revision();
        var operation = new edu.teamc.courseaiassistant.modification.ValidatedModificationService(project,
                input -> guarded(sharedRevision, expected, () -> timedGeneration(input)),
                apply -> guarded(sharedRevision, expected, () -> { apply.run(); return null; })).modify(request);
        Runnable invalidate = () -> operation.cancel(true);
        modificationInvalidationListeners.add(invalidate);
        operation.whenComplete((value, error) -> modificationInvalidationListeners.remove(invalidate));
        if (disposed || project.isDisposed()) operation.cancel(true);
        return operation;
    }

    private <T> T guarded(long sharedRevision, long expected, Supplier<T> action) {
        return global == null ? withRevision(expected, action)
                : global.withModelRevision(sharedRevision, () -> withRevision(expected, action));
    }

    private synchronized <T> T withRevision(long expected, Supplier<T> work) {
        if (disposed || project.isDisposed() || expected != revision) throw new java.util.concurrent.CancellationException();
        return work.get();
    }

    private CompletableFuture<ChangeProposal> timedGeneration(ModificationRequest request) {
        var child = generate(request);
        var result = new CompletableFuture<ChangeProposal>();
        var timeout = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (result.completeExceptionally(new ServiceFailure(request.requestId(), ServiceFailure.Category.TIMEOUT,
                    "Code generation timed out. Check Language model settings and retry.", true))) child.cancel(true);
        }, coordinatorTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        child.whenComplete((value, error) -> {
            if (child.isCancelled()) result.cancel(true);
            else if (error != null) result.completeExceptionally(error);
            else result.complete(value);
        });
        result.whenComplete((value, error) -> { timeout.cancel(false); if (result.isCancelled()) child.cancel(true); });
        return result;
    }

    public Duration coordinatorTimeout() {
        return LlmSettings.getInstance(project).getState().coordinatorTimeout();
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

    public void updateSettings(LlmSettings.State next) {
        if (global != null) { global.updateLlm(next); return; }
        synchronized (this) {
            if (disposed || project.isDisposed()) throw new IllegalStateException("Project is closed.");
            next.validate(); LlmSettings.getInstance(project).loadState(next); modelChanged();
        }
    }

    private synchronized void modelChanged() {
        if (disposed) return;
        revision++;
        modificationInvalidationListeners.forEach(Runnable::run);
        runtime.invalidate();
        chatInvalidationListeners.forEach(Runnable::run);
    }

    @Override public synchronized void dispose() {
        if (disposed) return;
        disposed = true;
        revision++;
        modificationInvalidationListeners.forEach(Runnable::run);
        modificationInvalidationListeners.clear();
        unsubscribeModel.run();
        unsubscribeSettings.run();
        unsubscribeLibrary.run();
        runtime.close();
        chatInvalidationListeners.clear();
    }
}
