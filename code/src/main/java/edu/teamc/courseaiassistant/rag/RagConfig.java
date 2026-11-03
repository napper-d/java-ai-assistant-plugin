package edu.teamc.courseaiassistant.rag;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record RagConfig(Path projectRoot, Path dataDirectory, String corpusId, int chunkCharacters,
                        int overlapCharacters, int batchSize, double minScore, boolean includeSlideNotes,
                        EmbeddingSettings embedding) {
    public RagConfig {
        projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
        dataDirectory = Objects.requireNonNull(dataDirectory);
        dataDirectory = (dataDirectory.isAbsolute() ? dataDirectory : projectRoot.resolve(dataDirectory))
                .toAbsolutePath().normalize();
        if (corpusId == null || !corpusId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Corpus ID must contain 1-64 letters, digits, underscores or hyphens.");
        }
        if (chunkCharacters < 2 || overlapCharacters < 0 || overlapCharacters >= chunkCharacters) {
            throw new IllegalArgumentException("Chunk size must exceed overlap and be at least two characters.");
        }
        if (batchSize < 1 || batchSize > 128 || !Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("Batch size must be 1-128 and minimum score must be in [0, 1].");
        }
        Objects.requireNonNull(embedding);
    }

    /** Stores the original-path registry only; course files remain at their original locations. */
    public Path documentsDirectory() { return dataDirectory.resolve("documents").resolve(corpusId); }
    public Path indexDirectory() { return dataDirectory.resolve("indexes").resolve(corpusId); }
    public String splitterFingerprint() {
        return ChunkSplitter.STRATEGY_ID + ":" + chunkCharacters + ":" + overlapCharacters + ":notes=" + includeSlideNotes;
    }

    /** Never include the credential in logging, fingerprints or persisted manifests. */
    public record EmbeddingSettings(String provider, String baseUrl, String model, String apiKey,
                                    int dimensions, Duration timeout, String documentPrefix, String queryPrefix) {
        public EmbeddingSettings {
            provider = Objects.requireNonNullElse(provider, "").strip();
            baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
            model = Objects.requireNonNullElse(model, "").strip();
            apiKey = Objects.requireNonNullElse(apiKey, "").strip();
            documentPrefix = Objects.requireNonNullElse(documentPrefix, "");
            queryPrefix = Objects.requireNonNullElse(queryPrefix, "");
            Objects.requireNonNull(timeout);
            if (dimensions < 0 || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Dimensions cannot be negative and timeout must be positive.");
            }
        }

        public URI endpoint() {
            try {
                URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings");
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
                return uri;
            } catch (IllegalArgumentException error) {
                throw new RagException(RagException.Reason.CONFIGURATION,
                        "Set a valid HTTP(S) embedding API base URL without credentials, query or fragment.", false);
            }
        }

        public String fingerprint() {
            return RagFiles.sha256(provider + "\n" + baseUrl.replaceAll("/+$", "") + "\n" + model
                    + "\n" + dimensions + "\n" + documentPrefix + "\n" + queryPrefix);
        }

        @Override public String toString() {
            return "EmbeddingSettings{provider=" + provider + ", model=" + model + ", apiKey=<redacted>}";
        }
    }
}
