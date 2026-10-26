package edu.teamc.courseaiassistant.rag;

public final class EmbeddingProviderFactory {
    private EmbeddingProviderFactory() {}
    public static EmbeddingClient create(RagConfig config) {
        if (!"openai-compatible".equals(config.embedding().provider())) {
            throw new RagException(RagException.Reason.CONFIGURATION,
                    "Choose an embedding provider. This build supports the openai-compatible embeddings protocol.", false);
        }
        return new HttpEmbeddingClient(config.embedding());
    }
}
