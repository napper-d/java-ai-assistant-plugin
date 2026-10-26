package edu.teamc.courseaiassistant.rag;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Loads only the explicitly supplied development directory; never searches arbitrary project ancestors. */
public final class RagConfigLoader {
    private RagConfigLoader() {}

    public static RagConfig load(Path projectRoot, Path envDirectory) {
        return load(projectRoot, envDirectory, System.getenv());
    }

    public static RagConfig load(Path projectRoot, Path envDirectory, Map<String, String> environment) {
        try {
            Dotenv dotenv = envDirectory == null ? null : Dotenv.configure()
                    .directory(envDirectory.toAbsolutePath().normalize().toString()).ignoreIfMissing().load();
            Function<String, String> value = key -> environment.containsKey(key) ? environment.get(key)
                    : dotenv == null ? null : dotenv.get(key);
            return new RagConfig(projectRoot, Path.of(get(value, "RAG_DATA_DIR", ".course-ai")),
                    get(value, "RAG_CORPUS_ID", "default"), integer(value, "RAG_CHUNK_CHARACTERS", 800),
                    integer(value, "RAG_OVERLAP_CHARACTERS", 120), integer(value, "RAG_BATCH_SIZE", 16),
                    Double.parseDouble(get(value, "RAG_MIN_SCORE", "0.75")),
                    bool(value, "RAG_INCLUDE_SLIDE_NOTES", false),
                    new RagConfig.EmbeddingSettings(get(value, "RAG_EMBEDDING_PROVIDER", ""),
                            get(value, "RAG_EMBEDDING_BASE_URL", ""), get(value, "RAG_EMBEDDING_MODEL", ""),
                            get(value, "RAG_EMBEDDING_API_KEY", ""), integer(value, "RAG_EMBEDDING_DIMENSIONS", 0),
                            Duration.ofMillis(integer(value, "RAG_HTTP_TIMEOUT_MS", 20000)),
                            get(value, "RAG_DOCUMENT_PREFIX", ""), get(value, "RAG_QUERY_PREFIX", "")),
                    new RagConfig.RetrievalSettings(bool(value, "RAG_MULTI_STEP_ENABLED", true),
                            integer(value, "RAG_MULTI_STEP_MAX_ROUNDS", 2), integer(value, "RAG_MULTI_STEP_MAX_QUERIES", 8),
                            get(value, "RAG_REFERENCE_PATTERN", RagConfig.RetrievalSettings.DEFAULT_REFERENCE_PATTERN)));
        } catch (RuntimeException error) {
            // Dotenv parsing errors may include entire lines containing credentials.
            throw new RagException(RagException.Reason.CONFIGURATION,
                    "Unable to load RAG configuration. Check the configured .env directory and parameter formats.", false);
        }
    }

    private static String get(Function<String, String> values, String key, String fallback) {
        String value = values.apply(key);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static int integer(Function<String, String> values, String key, int fallback) {
        return Integer.parseInt(get(values, key, Integer.toString(fallback)));
    }
    private static boolean bool(Function<String, String> values, String key, boolean fallback) {
        String value = get(values, key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }
}
