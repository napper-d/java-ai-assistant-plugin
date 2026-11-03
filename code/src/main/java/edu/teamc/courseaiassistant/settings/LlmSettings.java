package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.components.*;
import edu.teamc.courseaiassistant.llm.LlmConfig;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.time.Duration;

/** Non-secret project settings. No environment discovery or provider defaults. */
@Service(Service.Level.PROJECT)
@State(name = "CourseAiLlmSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class LlmSettings implements PersistentStateComponent<LlmSettings.State> {
    /** Legacy project state is retained for compatibility; production consumers use IDE-global settings. */
    public static LlmSettings getInstance(com.intellij.openapi.project.Project project) {
        var global = GlobalConfiguration.available();
        return global == null ? project.getService(LlmSettings.class) : global.llm();
    }

    public static final class State {
        public String baseUrl = "";
        public String model = "";
        public int timeoutMillis = 25_000;
        public int maxOutputTokens = 4096;
        public int maxInputCharacters = 60_000;

        public State copy() {
            State copy = new State();
            copy.baseUrl = baseUrl; copy.model = model; copy.timeoutMillis = timeoutMillis;
            copy.maxOutputTokens = maxOutputTokens; copy.maxInputCharacters = maxInputCharacters;
            return copy;
        }

        /** Allow incomplete setup to be saved, while validating every supplied value. */
        public void validate() {
            try {
                new LlmConfig(URI.create(baseUrl.isBlank() ? "https://unconfigured.invalid" : baseUrl),
                        model.isBlank() ? "unconfigured" : model, "validation-only", Duration.ofMillis(timeoutMillis),
                        maxOutputTokens, maxInputCharacters);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Check the LLM base URL, timeout (1–300000 ms), output tokens (1–32768) and input characters (100–1000000).");
            }
        }

        public LlmConfig toConfig(String key) {
            validate();
            try {
                return new LlmConfig(URI.create(baseUrl), model, key, Duration.ofMillis(timeoutMillis),
                        maxOutputTokens, maxInputCharacters);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Configure the LLM endpoint, model and API key in Settings > Tools > Course AI Assistant > Language model.");
            }
        }

        /** Outer UI guard allows five seconds for startup beyond the configured retrieval/model budget. */
        public Duration coordinatorTimeout() {
            validate();
            return Duration.ofMillis((long) timeoutMillis + 5000);
        }
    }

    private State state = new State();
    @Override public synchronized @NotNull State getState() { return state.copy(); }
    @Override public synchronized void loadState(@NotNull State state) { this.state = state.copy(); }
}
