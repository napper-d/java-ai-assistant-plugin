package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.components.*;
import edu.teamc.courseaiassistant.rag.RagConfig;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;

/** Project-local, non-secret settings; credentials are stored separately in PasswordSafe. */
@Service(Service.Level.PROJECT)
@State(name = "CourseAiRagSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class RagSettings implements PersistentStateComponent<RagSettings.State> {
    /** Legacy project state is retained for compatibility; production consumers use IDE-global settings. */
    public static RagSettings getInstance(com.intellij.openapi.project.Project project) {
        var global = GlobalConfiguration.available();
        return global == null ? project.getService(RagSettings.class) : global.rag();
    }

    public static final class State {
        public String baseUrl = "";
        public String model = "";
        public int timeoutMillis = 20_000;
        public int dimensions = 0;
        public int chunkCharacters = 800;
        public int overlapCharacters = 120;
        public int batchSize = 16;
        public double minScore = .70;
        public boolean includeSlideNotes;
        public String documentPrefix = "";
        public String queryPrefix = "";

        public State copy() {
            State copy = new State();
            copy.baseUrl = baseUrl; copy.model = model; copy.timeoutMillis = timeoutMillis;
            copy.dimensions = dimensions; copy.chunkCharacters = chunkCharacters;
            copy.overlapCharacters = overlapCharacters; copy.batchSize = batchSize;
            copy.minScore = minScore; copy.includeSlideNotes = includeSlideNotes;
            copy.documentPrefix = documentPrefix; copy.queryPrefix = queryPrefix;
            return copy;
        }

        public RagConfig toConfig(Path projectRoot, String apiKey) {
            return new RagConfig(projectRoot, Path.of(".course-ai"), "default", chunkCharacters,
                    overlapCharacters, batchSize, minScore, includeSlideNotes,
                    new RagConfig.EmbeddingSettings("openai-compatible", baseUrl, model, apiKey,
                            dimensions, Duration.ofMillis(timeoutMillis), documentPrefix, queryPrefix));
        }

        /** Incomplete settings are allowed, but every supplied value must be valid. */
        public void validate(Path projectRoot) {
            RagConfig config = toConfig(projectRoot, "");
            if (!config.embedding().baseUrl().isBlank()) config.embedding().endpoint();
        }
    }

    private State state = new State();

    @Override public synchronized @NotNull State getState() { return state.copy(); }
    @Override public synchronized void loadState(@NotNull State state) { this.state = state.copy(); }
}
