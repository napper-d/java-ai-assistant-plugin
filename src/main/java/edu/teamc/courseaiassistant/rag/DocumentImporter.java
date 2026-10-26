package edu.teamc.courseaiassistant.rag;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Registers original paths, without copying course files. Call blocking file work off the UI thread. */
public final class DocumentImporter {
    public record ImportedDocument(String documentId, String documentName, String sha256, Path path, boolean reused) {}
    private record Registry(int formatVersion, String corpusId, List<String> paths) {}
    private static final Gson JSON = new Gson();
    private final RagConfig config;
    public DocumentImporter(RagConfig config) { this.config = java.util.Objects.requireNonNull(config); }

    public ImportedDocument importFile(Path source) {
        if (source == null || !Files.isRegularFile(source) || RagFiles.extension(source).isEmpty()) {
            throw new RagException(RagException.Reason.INVALID_INPUT, "Select a readable PDF or PPTX file.", false);
        }
        try {
            Path original = source.toRealPath();
            if (RagFiles.extension(original).isEmpty()) throw new RagException(RagException.Reason.INVALID_INPUT,
                    "The original file must have a PDF or PPTX extension.", false);
            String hash = RagFiles.sha256(original);
            Files.createDirectories(config.documentsDirectory());
            try (FileChannel channel = registryLockChannel(); FileLock lock = lock(channel)) {
                List<Path> paths = new ArrayList<>(readPaths());
                boolean reused = paths.contains(original);
                if (!reused) {
                    paths.add(original);
                    writePaths(paths);
                }
                return new ImportedDocument(identity(original), original.getFileName().toString(), hash, original, reused);
            }
        } catch (IOException error) {
            throw new RagException(RagException.Reason.STORAGE_ERROR, "Unable to register the selected course file.", true, error);
        }
    }

    /** Removes a path from the library only; works even when the original file no longer exists. */
    public boolean unregisterFile(Path source) {
        if (source == null) throw new RagException(RagException.Reason.INVALID_INPUT, "Select a registered course path.", false);
        try {
            Path original = Files.exists(source) ? source.toRealPath() : source.toAbsolutePath().normalize();
            Files.createDirectories(config.documentsDirectory());
            try (FileChannel channel = registryLockChannel(); FileLock lock = lock(channel)) {
                List<Path> paths = new ArrayList<>(readPaths());
                if (!paths.remove(original)) return false;
                writePaths(paths);
                return true;
            }
        } catch (IOException error) {
            throw new RagException(RagException.Reason.STORAGE_ERROR, "Unable to update registered course paths.", true, error);
        }
    }

    /** Reads originals only while explicitly rebuilding; searching an existing index never calls this. */
    public List<ImportedDocument> listDocuments() {
        try {
            List<ImportedDocument> result = new ArrayList<>();
            for (Path path : readPaths()) {
                RagFiles.checkCancelled();
                if (!Files.isRegularFile(path)) throw unavailable();
                String hash = RagFiles.sha256(path);
                result.add(new ImportedDocument(identity(path), path.getFileName().toString(), hash, path, true));
            }
            result.sort(Comparator.comparing(ImportedDocument::documentId));
            return List.copyOf(result);
        } catch (IOException error) {
            throw unavailable();
        }
    }

    private Path registryFile() { return config.documentsDirectory().resolve("documents.json"); }

    private List<Path> readPaths() {
        Path registry = registryFile();
        try {
            String encoded;
            try { encoded = Files.readString(registry, StandardCharsets.UTF_8); }
            catch (NoSuchFileException error) { return List.of(); }
            Registry parsed = JSON.fromJson(encoded, Registry.class);
            if (parsed == null || parsed.formatVersion() != 1 || !config.corpusId().equals(parsed.corpusId())
                    || parsed.paths() == null) throw new IllegalArgumentException();
            Set<Path> unique = new LinkedHashSet<>();
            for (String name : parsed.paths()) {
                if (name == null) throw new IllegalArgumentException();
                Path path = Path.of(name).normalize();
                if (!path.isAbsolute() || RagFiles.extension(path).isEmpty() || !unique.add(path)) throw new IllegalArgumentException();
            }
            return List.copyOf(unique);
        } catch (IOException | RuntimeException error) {
            throw new RagException(RagException.Reason.STORAGE_ERROR,
                    "Unable to read the course path registry. Restore or correct documents.json.", false);
        }
    }

    private void writePaths(List<Path> paths) throws IOException {
        Path temporary = config.documentsDirectory().resolve(".documents-" + UUID.randomUUID() + ".tmp");
        try {
            Registry registry = new Registry(1, config.corpusId(), paths.stream().map(Path::toString).toList());
            Files.writeString(temporary, JSON.toJson(registry), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            RagFiles.checkCancelled();
            Files.move(temporary, registryFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private FileChannel registryLockChannel() throws IOException {
        return FileChannel.open(config.documentsDirectory().resolve(".registry.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private FileLock lock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException ignored) { }
        throw new RagException(RagException.Reason.STORAGE_ERROR, "Course paths are being updated. Try again later.", true);
    }

    private String identity(Path original) { return RagFiles.sha256(config.corpusId() + "\n" + original); }

    private static RagException unavailable() {
        return new RagException(RagException.Reason.DOCUMENT_ERROR,
                "A registered course file is missing or unreadable. Restore it or update its registered path before rebuilding.", false);
    }
}
