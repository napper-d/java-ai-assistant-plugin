package edu.teamc.courseaiassistant.llm;

import io.github.cdimascio.dotenv.Dotenv;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Development-only loader. IDE assembly should inject settings and Password Safe credentials. */
public final class LlmConfigLoader {
    private LlmConfigLoader() {}

    public static LlmConfig load(Path directory) { return load(directory, System.getenv()); }

    public static LlmConfig load(Path directory, Map<String, String> environment) {
        try {
            Dotenv dotenv = directory == null ? null : Dotenv.configure()
                    .directory(directory.toAbsolutePath().normalize().toString()).ignoreIfMissing().load();
            Function<String, String> get = key -> environment.containsKey(key) ? environment.get(key)
                    : dotenv == null ? null : dotenv.get(key);
            if (!"openai-compatible".equals(value(get, "LLM_PROVIDER", ""))) throw new IllegalArgumentException();
            return new LlmConfig(URI.create(value(get, "LLM_BASE_URL", "")), value(get, "LLM_MODEL", ""),
                    value(get, "LLM_API_KEY", ""), Duration.ofMillis(Long.parseLong(value(get, "LLM_TIMEOUT_MS", "25000"))),
                    Integer.parseInt(value(get, "LLM_MAX_OUTPUT_TOKENS", "4096")),
                    Integer.parseInt(value(get, "LLM_MAX_INPUT_CHARACTERS", "60000")));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Unable to load LLM configuration. Check the explicit directory and LLM parameters.");
        }
    }

    private static String value(Function<String, String> get, String name, String fallback) {
        String value = get.apply(name);
        return value == null ? fallback : value;
    }
}
