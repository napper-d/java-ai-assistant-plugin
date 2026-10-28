package edu.teamc.courseaiassistant.llm;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Explicit OpenAI-compatible chat configuration, independent of embedding settings. */
public record LlmConfig(URI baseUrl, String model, String apiKey, Duration timeout,
                        int maxOutputTokens, int maxInputCharacters) {
    public LlmConfig {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(model);
        Objects.requireNonNull(apiKey);
        Objects.requireNonNull(timeout);
        if (baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || !("https".equals(baseUrl.getScheme())
                || ("http".equals(baseUrl.getScheme()) && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost())))
                || model.isBlank() || apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n")
                || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxOutputTokens < 1 || maxOutputTokens > 32768
                || maxInputCharacters < 100 || maxInputCharacters > 1_000_000) {
            throw new IllegalArgumentException("Invalid LLM configuration. Check endpoint, model, key and limits.");
        }
    }

    public URI completionEndpoint() {
        return URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions");
    }

    @Override public String toString() { return "LlmConfig{credentials and endpoint redacted}"; }
}
