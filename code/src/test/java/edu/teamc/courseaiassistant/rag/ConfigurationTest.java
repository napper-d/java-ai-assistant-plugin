package edu.teamc.courseaiassistant.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path root;

    @Test void configurationResolvesProjectDataAndRedactsCredentials() {
        var config = RagTestSupport.config(root);
        assertEquals(root.resolve(".course-ai"), config.dataDirectory());
        assertEquals("test-key", config.embedding().apiKey());
        assertFalse(config.toString().contains("test-key"));
    }

    @Test void keysDoNotChangeModelFingerprintButModelsDo() {
        var config = RagTestSupport.config(root);
        var settings = config.embedding();
        var otherKey = new RagConfig.EmbeddingSettings(settings.provider(), settings.baseUrl(), settings.model(),
                "another-key", settings.dimensions(), settings.timeout(), "", "");
        assertEquals(settings.fingerprint(), otherKey.fingerprint());
        assertNotEquals(settings.fingerprint(), RagTestSupport.config(root, "course", "other", 0.8).embedding().fingerprint());
    }

    @Test void invalidCorpusPathsAndScoresAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RagTestSupport.config(root, "../outside", "model", 0.75));
        assertThrows(IllegalArgumentException.class, () -> RagTestSupport.config(root, "course", "model", Double.NaN));
    }

}
