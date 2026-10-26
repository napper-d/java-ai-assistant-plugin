package edu.teamc.courseaiassistant.rag;

import com.google.gson.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Versioned LangChain4j JSON snapshots, published by atomically replacing the manifest. */
public final class LocalIndexStore {
    private static final Gson JSON = new Gson();
    private static final int PARTITION_SIZE = 256;
    private final RagConfig config;

    record Entry(DocumentChunk chunk, float[] vector) {
        Entry { vector = vector.clone(); }
        @Override public float[] vector() { return vector.clone(); }
    }

    /** Private stores are never mutated after construction; readers keep a whole generation. */
    public static final class Snapshot {
        private final List<Entry> entries;
        private final List<InMemoryEmbeddingStore<TextSegment>> partitions;
        private final Map<String, Entry> byId;
        private final int dimensions;
        private final String generation;

        Snapshot(List<Entry> entries, int dimensions, String generation) {
            this.entries = List.copyOf(entries);
            this.dimensions = dimensions;
            this.generation = generation;
            Set<String> ids = new HashSet<>();
            List<InMemoryEmbeddingStore<TextSegment>> stores = new ArrayList<>();
            for (int offset = 0; offset < entries.size(); offset += PARTITION_SIZE) {
                RagFiles.checkCancelled();
                InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
                for (Entry entry : entries.subList(offset, Math.min(entries.size(), offset + PARTITION_SIZE))) {
                    RagFiles.validateVector(entry.vector, dimensions);
                    if (!ids.add(entry.chunk.chunkId())) throw new IllegalArgumentException("Duplicate chunk ID.");
                    store.add(entry.chunk.chunkId(), Embedding.from(entry.vector), entry.chunk.toSegment());
                }
                stores.add(store);
            }
            partitions = List.copyOf(stores);
            Map<String, Entry> lookup = new HashMap<>();
            for (Entry entry : entries) lookup.put(entry.chunk.chunkId(), entry);
            byId = Map.copyOf(lookup);
        }
        public int chunkCount() { return entries.size(); }
        public int dimensions() { return dimensions; }
        public String generation() { return generation; }

        List<KnowledgeChunk> search(float[] query, double minScore, int maximum, RagTaskRunner.Context context) {
            return search(query, minScore, maximum, null, context);
        }

        List<KnowledgeChunk> search(float[] query, double minScore, int maximum, String documentId,
                                    RagTaskRunner.Context context) {
            RagFiles.validateVector(query, dimensions);
            Comparator<KnowledgeChunk> bestFirst = Comparator.comparingDouble(KnowledgeChunk::getScore).reversed()
                    .thenComparing(KnowledgeChunk::getChunkId);
            PriorityQueue<KnowledgeChunk> best = new PriorityQueue<>(maximum, bestFirst.reversed());
            for (InMemoryEmbeddingStore<TextSegment> partition : partitions) {
                context.check();
                var matches = partition.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(query))
                        .maxResults(PARTITION_SIZE).minScore(minScore).build()).matches();
                for (var match : matches) {
                    context.check();
                    DocumentChunk chunk = DocumentChunk.fromSegment(match.embedded());
                    if (documentId != null && !documentId.equals(chunk.documentId())) continue;
                    double score = Math.clamp(match.score(), 0, 1);
                    if (!Double.isFinite(score)) throw new IllegalArgumentException("Invalid retrieval score.");
                    KnowledgeChunk value = new KnowledgeChunk(chunk.chunkId(), chunk.source(), chunk.text(), score);
                    best.add(value);
                    if (best.size() > maximum) best.poll();
                }
            }
            return best.stream().sorted(bestFirst).toList();
        }

        FollowUpQueryPlanner.Passage passage(KnowledgeChunk chunk) {
            return new FollowUpQueryPlanner.Passage(byId.get(chunk.getChunkId()).chunk, chunk.getScore());
        }

        KnowledgeChunk rescore(KnowledgeChunk chunk, float[] question) {
            Entry entry = byId.get(chunk.getChunkId());
            return scored(entry, question);
        }

        List<KnowledgeChunk> definitions(String reference, String documentId, float[] question,
                                         int maximum, RagTaskRunner.Context context) {
            List<KnowledgeChunk> found = new ArrayList<>();
            for (Entry entry : entries) {
                context.check();
                if (documentId != null && !documentId.equals(entry.chunk.documentId())) continue;
                if (ReferenceQueryPlanner.defines(entry.chunk.text(), reference)) found.add(scored(entry, question));
            }
            return found.stream().sorted(Comparator.comparingDouble(KnowledgeChunk::getScore).reversed()
                    .thenComparing(KnowledgeChunk::getChunkId)).limit(maximum).toList();
        }

        private KnowledgeChunk scored(Entry entry, float[] question) {
            double dot = 0, questionNorm = 0, entryNorm = 0;
            for (int i = 0; i < question.length; i++) {
                dot += (double) question[i] * entry.vector[i];
                questionNorm += (double) question[i] * question[i];
                entryNorm += (double) entry.vector[i] * entry.vector[i];
            }
            double score = Math.clamp((dot / Math.sqrt(questionNorm * entryNorm) + 1) / 2, 0, 1);
            if (!Double.isFinite(score)) throw new IllegalArgumentException("Invalid retrieval score.");
            return new KnowledgeChunk(entry.chunk.chunkId(), entry.chunk.source(), entry.chunk.text(), score);
        }
    }

    public LocalIndexStore(RagConfig config) { this.config = Objects.requireNonNull(config); }

    Snapshot save(List<Entry> entries, int dimensions, List<IndexManifest.SourceFile> sources) {
        if (dimensions < 1 || entries.isEmpty()) throw new RagException(RagException.Reason.INVALID_INPUT,
                "An index needs at least one valid course chunk.", false);
        String generation = UUID.randomUUID().toString();
        Snapshot snapshot = new Snapshot(entries, dimensions, generation);
        Path directory = config.indexDirectory();
        Path storeFile = directory.resolve("store-" + generation + ".json");
        Path manifestTemporary = directory.resolve(".manifest-" + generation + ".tmp");
        boolean published = false;
        try {
            Files.createDirectories(directory);
            InMemoryEmbeddingStore<TextSegment> serializable = new InMemoryEmbeddingStore<>();
            for (Entry entry : entries) {
                RagFiles.checkCancelled();
                serializable.add(entry.chunk.chunkId(), Embedding.from(entry.vector), entry.chunk.toSegment());
            }
            serializable.serializeToFile(storeFile);
            try (FileChannel channel = FileChannel.open(storeFile, StandardOpenOption.WRITE)) { channel.force(true); }
            IndexManifest manifest = new IndexManifest(1, config.corpusId(), config.embedding().fingerprint(),
                    config.splitterFingerprint(), dimensions, entries.size(), storeFile.getFileName().toString(),
                    RagFiles.sha256(storeFile), List.copyOf(sources));
            Files.writeString(manifestTemporary, JSON.toJson(manifest), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try (FileChannel channel = FileChannel.open(manifestTemporary, StandardOpenOption.WRITE)) { channel.force(true); }
            RagFiles.checkCancelled();
            Files.move(manifestTemporary, directory.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            published = true;
            return snapshot;
        } catch (CancellationException error) { throw error; }
        catch (IOException | RuntimeException error) {
            throw new RagException(RagException.Reason.STORAGE_ERROR,
                    "Unable to publish the course index. The previous index has been retained.", true, error);
        } finally {
            try { Files.deleteIfExists(manifestTemporary); } catch (IOException ignored) { }
            if (!published) { try { Files.deleteIfExists(storeFile); } catch (IOException ignored) { } }
        }
    }

    public Snapshot load() {
        Path manifestPath = config.indexDirectory().resolve("manifest.json");
        try {
            IndexManifest manifest = JSON.fromJson(Files.readString(manifestPath, StandardCharsets.UTF_8), IndexManifest.class);
            validateManifest(manifest);
            Path file = config.indexDirectory().resolve(manifest.storeFile());
            if (!manifest.storeSha256().equals(RagFiles.sha256(file))) throw new IllegalArgumentException("Snapshot checksum mismatch.");
            String encoded = Files.readString(file, StandardCharsets.UTF_8);
            // This schema belongs to the pinned LangChain4j version. Validate before calling its search code.
            JsonArray rawEntries = JsonParser.parseString(encoded).getAsJsonObject().getAsJsonArray("entries");
            if (rawEntries == null || rawEntries.size() != manifest.chunkCount()) throw new IllegalArgumentException("Chunk count mismatch.");
            Set<String> ids = new HashSet<>();
            for (JsonElement element : rawEntries) {
                RagFiles.checkCancelled();
                JsonObject entry = element.getAsJsonObject();
                String id = entry.get("id").getAsString();
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate chunk ID.");
                JsonArray vector = entry.getAsJsonObject("embedding").getAsJsonArray("vector");
                float[] values = new float[vector.size()];
                for (int i = 0; i < values.length; i++) values[i] = vector.get(i).getAsFloat();
                RagFiles.validateVector(values, manifest.dimensions());
            }
            var decoded = InMemoryEmbeddingStore.fromJson(encoded);
            float[] basis = new float[manifest.dimensions()]; basis[0] = 1;
            var matches = decoded.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(basis))
                    .maxResults(manifest.chunkCount()).minScore(0.0).build()).matches();
            if (matches.size() != manifest.chunkCount()) throw new IllegalArgumentException("Incomplete snapshot.");
            Map<String, String> documents = new HashMap<>();
            for (var source : manifest.sources()) documents.put(source.documentId(), source.documentName());
            List<Entry> entries = new ArrayList<>();
            for (var match : matches) {
                RagFiles.checkCancelled();
                DocumentChunk chunk = DocumentChunk.fromSegment(match.embedded());
                if (!chunk.chunkId().equals(match.embeddingId())
                        || !chunk.source().getDocumentName().equals(documents.get(chunk.documentId()))) {
                    throw new IllegalArgumentException("Invalid chunk identity.");
                }
                entries.add(new Entry(chunk, match.embedding().vector()));
            }
            entries.sort(Comparator.comparing(entry -> entry.chunk.chunkId()));
            return new Snapshot(entries, manifest.dimensions(), manifest.storeFile());
        } catch (RagException | CancellationException error) { throw error; }
        catch (NoSuchFileException error) {
            if (error.getFile() != null && Path.of(error.getFile()).equals(manifestPath)) {
                throw new RagException(RagException.Reason.INDEX_UNAVAILABLE, "No course index exists. Import materials and build an index first.", false);
            }
            throw new RagException(RagException.Reason.INDEX_CORRUPT, "The course index snapshot is missing. Rebuild the index.", false);
        } catch (IOException | RuntimeException error) {
            throw new RagException(RagException.Reason.INDEX_CORRUPT, "Unable to load the course index. Rebuild the index.", false, error);
        }
    }

    private void validateManifest(IndexManifest manifest) {
        if (manifest == null || manifest.formatVersion() != 1 || !config.corpusId().equals(manifest.corpusId())
                || !config.embedding().fingerprint().equals(manifest.embeddingFingerprint())
                || !config.splitterFingerprint().equals(manifest.splitterFingerprint())) {
            throw new RagException(RagException.Reason.INDEX_INCOMPATIBLE, "Index configuration changed. Rebuild the course index.", false);
        }
        if (manifest.dimensions() < 1 || manifest.dimensions() > 65536 || manifest.chunkCount() < 1
                || manifest.storeFile() == null || !manifest.storeFile().matches("store-[a-f0-9-]{36}\\.json")
                || manifest.storeSha256() == null || !manifest.storeSha256().matches("[a-f0-9]{64}")
                || manifest.sources() == null || manifest.sources().isEmpty()) throw new IllegalArgumentException("Invalid index manifest.");
        if (config.embedding().dimensions() > 0 && config.embedding().dimensions() != manifest.dimensions()) {
            throw new RagException(RagException.Reason.INDEX_INCOMPATIBLE, "Embedding dimensions changed. Rebuild the index.", false);
        }
        Set<String> documentIds = new HashSet<>();
        for (var source : manifest.sources()) {
            if (source == null || source.documentId() == null || !documentIds.add(source.documentId())
                    || source.documentName() == null || source.documentName().isBlank()
                    || source.sha256() == null || !source.sha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid source manifest.");
        }
    }
}
