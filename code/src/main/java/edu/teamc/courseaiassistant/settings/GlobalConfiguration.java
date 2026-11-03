package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** IDE-wide connection settings. Credentials and course data have separate persistent stores. */
@Service(Service.Level.APP)
@State(name = "CourseAiGlobalConfiguration", storages = @Storage("course-ai-assistant.xml"))
public final class GlobalConfiguration implements PersistentStateComponent<GlobalConfiguration.State> {
    public static final class State {
        public LlmSettings.State llm = new LlmSettings.State();
        public RagSettings.State rag = new RagSettings.State();
    }
    private final LlmSettings llm = new LlmSettings();
    private final RagSettings rag = new RagSettings();
    private final List<Runnable> modelListeners = new CopyOnWriteArrayList<>();
    private final Object credentialsLock = new Object();
    private volatile long revision;

    public static GlobalConfiguration available() {
        var app = ApplicationManager.getApplication();
        return app == null ? null : app.getService(GlobalConfiguration.class);
    }
    public static Path dataRoot() { return Path.of(PathManager.getConfigPath(), "course-ai-assistant"); }
    public static Project settingsProject() {
        var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
        return projects.length == 0 ? null : projects[0];
    }
    public LlmSettings llm() { return llm; }
    public RagSettings rag() { return rag; }
    public Object credentialsLock() { return credentialsLock; }
    public long revision() { return revision; }
    public Runnable onModelChanged(Runnable listener) { modelListeners.add(listener); return () -> modelListeners.remove(listener); }
    public synchronized <T> T withModelRevision(long expected, Supplier<T> action) {
        if (expected != revision) throw new CancellationException();
        return action.get();
    }
    public synchronized void updateLlm(LlmSettings.State next) {
        next.validate(); revision++;
        modelListeners.forEach(Runnable::run);
        llm.loadState(next);
    }
    public synchronized void updateRag(RagSettings.State next) { next.validate(dataRoot()); rag.loadState(next); }
    @Override public synchronized State getState() {
        State state = new State(); state.llm = llm.getState(); state.rag = rag.getState(); return state;
    }
    @Override public synchronized void loadState(State state) {
        llm.loadState(state.llm); rag.loadState(state.rag);
    }
}
