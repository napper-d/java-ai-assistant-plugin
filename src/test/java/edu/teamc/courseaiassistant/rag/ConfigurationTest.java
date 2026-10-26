package edu.teamc.courseaiassistant.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path root;

    @Test void loadsExplicitEnvAndProcessValuesTakePrecedence() throws Exception {
        Path envDirectory = Files.createDirectories(root.resolve("settings"));
        Files.writeString(envDirectory.resolve(".env"), "RAG_CORPUS_ID=from_file\nRAG_DATA_DIR=course-data\nRAG_EMBEDDING_API_KEY=local-secret\n");
        var config = RagConfigLoader.load(root, envDirectory, Map.of("RAG_CORPUS_ID", "from_process"));
        assertEquals("from_process", config.corpusId());
        assertEquals(root.resolve("course-data"), config.dataDirectory());
        assertEquals("local-secret", config.embedding().apiKey());
        assertFalse(config.toString().contains("local-secret"));
    }

    @Test void keysDoNotChangeModelFingerprintButModelAndPrefixesDo() {
        var config = RagTestSupport.config(root);
        var settings = config.embedding();
        var otherKey = new RagConfig.EmbeddingSettings(settings.provider(), settings.baseUrl(), settings.model(),
                "another-key", settings.dimensions(), settings.timeout(), "", "");
        assertEquals(settings.fingerprint(), otherKey.fingerprint());
        assertNotEquals(settings.fingerprint(), RagTestSupport.config(root, "course", "other", 0.8).embedding().fingerprint());
    }

    @Test void missingProviderAndInvalidPathsOrParametersAreExplicitErrors() {
        var config = RagConfigLoader.load(root, null, Map.of());
        assertEquals(root.resolve(".course-ai"), config.dataDirectory());
        assertEquals(RagException.Reason.CONFIGURATION, assertThrows(RagException.class,
                () -> EmbeddingProviderFactory.create(config)).getReason());
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_CORPUS_ID", "../outside")));
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_MIN_SCORE", "NaN")));
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_INCLUDE_SLIDE_NOTES", "maybe")));
    }

    @Test void loadsBoundedMultiStepSettingsWithoutChangingTheIndexFingerprint() {
        var original = RagConfigLoader.load(root, null, Map.of());
        var changed = RagConfigLoader.load(root, null, Map.of("RAG_MULTI_STEP_ENABLED", "false", "RAG_MULTI_STEP_MAX_ROUNDS", "3"));
        assertFalse(changed.retrieval().enabled());
        assertEquals(3, changed.retrieval().maxRounds());
        assertEquals(original.splitterFingerprint(), changed.splitterFingerprint());
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_MULTI_STEP_MAX_ROUNDS", "0")));
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_MULTI_STEP_MAX_QUERIES", "17")));
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_REFERENCE_PATTERN", "[")));
        assertThrows(RagException.class, () -> RagConfigLoader.load(root, null, Map.of("RAG_REFERENCE_PATTERN", "^")));
    }

}
