package edu.teamc.courseaiassistant.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LlmConfigTest {
    @TempDir Path directory;
    @Test void explicitEnvironmentOverridesDotEnvWithoutLoggingSecrets() throws Exception {
        Files.writeString(directory.resolve(".env"), "LLM_PROVIDER=openai-compatible\nLLM_BASE_URL=https://example.com/v1\nLLM_MODEL=file-model\nLLM_API_KEY=secret\n");
        var config = LlmConfigLoader.load(directory, Map.of("LLM_MODEL", "override-model"));
        assertEquals("override-model", config.model());
        assertEquals("https://example.com/v1/chat/completions", config.completionEndpoint().toString());
        assertFalse(config.toString().contains("secret"));
        assertEquals(25000, config.timeout().toMillis());
    }
    @Test void requiresExplicitProviderAndDoesNotBorrowEmbeddingSettings() {
        assertThrows(IllegalArgumentException.class, () -> LlmConfigLoader.load(directory, Map.of("RAG_EMBEDDING_API_KEY", "secret")));
    }
    @Test void rejectsUnsafeUrlsInvalidLimitsAndMissingCredentials() {
        var base = Map.of("LLM_PROVIDER", "openai-compatible", "LLM_BASE_URL", "https://example.com/v1", "LLM_MODEL", "test", "LLM_API_KEY", "secret");
        for (var override : Map.of("LLM_BASE_URL", "http://example.com/v1", "LLM_TIMEOUT_MS", "0", "LLM_API_KEY", "", "LLM_MAX_OUTPUT_TOKENS", "-1", "LLM_MAX_INPUT_CHARACTERS", "invalid").entrySet()) {
            var config = new java.util.HashMap<>(base); config.put(override.getKey(), override.getValue());
            var error = assertThrows(IllegalArgumentException.class, () -> LlmConfigLoader.load(directory, config));
            assertFalse(error.getMessage().contains("secret")); assertNull(error.getCause());
        }
        for (String url : new String[]{"https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#fragment"}) {
            var config = new java.util.HashMap<>(base); config.put("LLM_BASE_URL", url);
            assertThrows(IllegalArgumentException.class, () -> LlmConfigLoader.load(directory, config));
        }
    }
}
