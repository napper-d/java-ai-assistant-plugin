package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.components.*;
import edu.teamc.courseaiassistant.rag.RagConfig;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;

/** Project-local, non-secret settings. CLI environment configuration is deliberately independent. */
@Service(Service.Level.PROJECT)
@State(name = "CourseAiRagSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class RagSettings implements PersistentStateComponent<RagSettings.State> {
    public static final class State {
        public String baseUrl = "";
        public String model = "";
        public int timeoutMillis = 20_000;
        public int dimensions = 0;
        public int chunkCharacters = 800;
        public int overlapCharacters = 120;
        public int batchSize = 16;
        public double minScore = .75;
        public boolean includeSlideNotes;
        public String documentPrefix = "";
        public String queryPrefix = "";
        public boolean multiStepEnabled = true;
        public int maxRounds = 2;
        public int maxQueries = 8;

        public State copy() {
            State copy = new State();
            copy.baseUrl = baseUrl; copy.model = model; copy.timeoutMillis = timeoutMillis;
            copy.dimensions = dimensions; copy.chunkCharacters = chunkCharacters;
            copy.overlapCharacters = overlapCharacters; copy.batchSize = batchSize;
            copy.minScore = minScore; copy.includeSlideNotes = includeSlideNotes;
            copy.documentPrefix = documentPrefix; copy.queryPrefix = queryPrefix;
            copy.multiStepEnabled = multiStepEnabled; copy.maxRounds = maxRounds; copy.maxQueries = maxQueries;
            return copy;
        }

        public RagConfig toConfig(Path projectRoot, String apiKey) {
            return new RagConfig(projectRoot, Path.of(".course-ai"), "default", chunkCharacters,
                    overlapCharacters, batchSize, minScore, includeSlideNotes,
                    new RagConfig.EmbeddingSettings("openai-compatible", baseUrl, model, apiKey,
                            dimensions, Duration.ofMillis(timeoutMillis), documentPrefix, queryPrefix),
                    new RagConfig.RetrievalSettings(multiStepEnabled, maxRounds, maxQueries, null));
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
