package edu.teamc.courseaiassistant.rag;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

final class RagTestSupport {
    static RagConfig config(Path root) { return config(root, "course", "model-v1", 0.8); }
    static RagConfig config(Path root, String corpus, String model, double threshold) {
        return new RagConfig(root, Path.of(".course-ai"), corpus, 1000, 100, 2, threshold, false,
                new RagConfig.EmbeddingSettings("test", "https://example.invalid/v1", model, "test-key", 2,
                        Duration.ofSeconds(2), "", ""));
    }
    static Path pdf(Path path, String... pages) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            for (String text : pages) {
                PDPage page = new PDPage(); pdf.addPage(page);
                if (!text.isEmpty()) {
                    try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 700); content.showText(text); content.endText();
                    }
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }
    static final class Embeddings implements EmbeddingClient {
        final AtomicInteger documents = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        boolean closed;
        @Override public CompletableFuture<List<float[]>> embed(List<String> texts, InputType type) {
            (type == InputType.QUERY ? queries : documents).addAndGet(texts.size());
            return CompletableFuture.completedFuture(texts.stream().map(text -> {
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains("inheritance")) return new float[]{1, 0};
                if (lower.contains("database")) return new float[]{0, 1};
                return new float[]{-1, 0};
            }).toList());
        }
        @Override public void close() { closed = true; }
    }
}
