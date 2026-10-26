package edu.teamc.courseaiassistant.rag;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;

/** Runnable development entry point; prints safe errors, never configuration or API credentials. */
public final class RagCli {
    private RagCli() {}
    public static void main(String[] arguments) {
        try {
            if (arguments.length < 3) throw new IllegalArgumentException();
            RagConfig config = RagConfigLoader.load(Path.of(arguments[1]), Path.of(arguments[2]));
            switch (arguments[0]) {
                case "import", "unregister" -> {
                    if (arguments.length < 4 || arguments[3].isBlank()) throw new IllegalArgumentException();
                    // Registering/unregistering paths does not require embedding credentials.
                    var importer = new DocumentImporter(config);
                    for (String name : arguments[3].split("\\|")) {
                        if (arguments[0].equals("unregister")) {
                            boolean removed = importer.unregisterFile(Path.of(name));
                            System.out.println(removed ? "Unregistered course path." : "Course path was not registered.");
                        } else {
                            var result = importer.importFile(Path.of(name));
                            System.out.println((result.reused() ? "Already registered: " : "Registered: ") + result.documentName());
                        }
                    }
                }
                case "index" -> {
                    try (var embedding = EmbeddingProviderFactory.create(config); var indexer = new CourseIndexer(config, embedding)) {
                        var result = indexer.rebuild().join();
                        System.out.printf("Indexed %d documents, %d chunks, %d dimensions.%n", result.documents(), result.chunks(), result.dimensions());
                    }
                }
                case "search" -> {
                    if (arguments.length < 5) throw new IllegalArgumentException();
                    try (var embedding = EmbeddingProviderFactory.create(config); var search = new LocalKnowledgeSearch(config, embedding)) {
                        var results = search.search(arguments[3], Integer.parseInt(arguments[4])).join();
                        if (results.isEmpty()) System.out.println("No relevant course material found.");
                        for (var result : results) {
                            System.out.printf("%.4f %s%n%s%n%n", result.getScore(), result.getSource(), result.getText());
                        }
                    }
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof RagException rag) System.err.println(rag.getReason() + ": " + rag.getMessage());
            else System.err.println("Usage: import|unregister|index|search <project-root> <env-directory> [files-separated-by-| | question max-results]");
            System.exit(1);
        }
    }
}
