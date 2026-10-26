package edu.teamc.courseaiassistant.rag;

import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Generic bounded execution: planning, scoped retrieval, breadth-first expansion and evidence merging. */
final class MultiStepRetrieval {
    private MultiStepRetrieval() {}

    static List<KnowledgeChunk> search(String question, float[] vector, int maximum, RagConfig config,
                                       LocalIndexStore.Snapshot snapshot, EmbeddingClient embeddings,
                                       FollowUpQueryPlanner planner, RagTaskRunner.Context context) {
        var settings = config.retrieval();
        if (!settings.enabled()) return snapshot.search(vector, config.minScore(), maximum, context);
        var seeds = snapshot.search(vector, config.minScore(), Math.max(maximum, 8), context);
        LinkedHashMap<String, KnowledgeChunk> selected = new LinkedHashMap<>();
        List<KnowledgeChunk> extraEvidence = new ArrayList<>();
        Map<String, KnowledgeChunk> knownSources = new HashMap<>();
        for (KnowledgeChunk seed : seeds) knownSources.put(seed.getChunkId(), seed);
        List<FollowUpQueryPlanner.Passage> frontier = seeds.stream().limit(8).map(snapshot::passage).toList();
        if (!seeds.isEmpty()) {
            KnowledgeChunk anchor = seeds.getFirst();
            selected.put(anchor.getChunkId(), anchor);
        }
        Set<String> issued = new HashSet<>();
        for (int round = 0; round < settings.maxRounds() && selected.size() < maximum
                && issued.size() < settings.maxQueries(); round++) {
            context.check();
            List<FollowUpQueryPlanner.Query> plans = plan(planner, question, frontier, context);
            List<FollowUpQueryPlanner.Passage> nextFrontier = new ArrayList<>();
            int inspected = 0;
            for (FollowUpQueryPlanner.Query query : plans) {
                context.check();
                if (++inspected > settings.maxQueries() * 4) break;
                if (selected.size() == maximum || issued.size() == settings.maxQueries()) break;
                if (query == null) throw planningFailure();
                KnowledgeChunk source = query.sourceChunkId() == null ? null : knownSources.get(query.sourceChunkId());
                if (query.sourceChunkId() != null && source == null) throw planningFailure();
                String key = query.documentId() + "\n" + (query.reference() == null
                        ? "text:" + query.text().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                        : "reference:" + query.reference().toUpperCase(Locale.ROOT));
                if (!issued.add(key)) continue;
                List<KnowledgeChunk> candidates;
                if (query.reference() != null) {
                    candidates = snapshot.definitions(query.reference(), query.documentId(), vector, maximum, context);
                } else {
                    var vectors = context.await(embeddings.embed(List.of(query.text()), EmbeddingClient.InputType.QUERY));
                    try {
                        if (vectors == null || vectors.size() != 1) throw new IllegalArgumentException();
                        RagFiles.validateVector(vectors.getFirst(), snapshot.dimensions());
                    } catch (IllegalArgumentException error) {
                        throw new RagException(RagException.Reason.EMBEDDING_ERROR, "Follow-up embedding does not match the course index.", false);
                    }
                    candidates = snapshot.search(vectors.getFirst(), config.minScore(), maximum, query.documentId(), context)
                            .stream().map(chunk -> snapshot.rescore(chunk, vector)).toList();
                }
                // Reserve a slot for each planned dependency before spending the
                // remaining budget on multiple candidates from the same query.
                boolean added = false;
                for (KnowledgeChunk candidate : candidates) {
                    context.check();
                    if (selected.containsKey(candidate.getChunkId())) continue;
                    if (!added) {
                        int required = 1 + (source != null && !selected.containsKey(source.getChunkId())
                                && !source.getChunkId().equals(candidate.getChunkId()) ? 1 : 0);
                        if (selected.size() + required > maximum) break;
                        if (source != null) selected.putIfAbsent(source.getChunkId(), source);
                        selected.put(candidate.getChunkId(), candidate);
                        knownSources.put(candidate.getChunkId(), candidate);
                        nextFrontier.add(snapshot.passage(candidate));
                        added = true;
                    } else extraEvidence.add(candidate);
                }
            }
            if (nextFrontier.isEmpty()) break;
            frontier = List.copyOf(nextFrontier);
        }
        append(selected, extraEvidence, maximum, context);
        append(selected, seeds, maximum, context);
        return selected.values().stream().sorted(Comparator.comparingDouble(KnowledgeChunk::getScore).reversed()
                .thenComparing(KnowledgeChunk::getChunkId)).toList();
    }

    private static List<FollowUpQueryPlanner.Query> plan(FollowUpQueryPlanner planner, String question,
                                                         List<FollowUpQueryPlanner.Passage> frontier,
                                                         RagTaskRunner.Context context) {
        try {
            var future = planner.plan(question, frontier);
            if (future == null) throw planningFailure();
            var queries = context.await(future, RagException.Reason.RETRIEVAL_ERROR, "Unable to plan retrieval follow-up queries.");
            if (queries == null) throw planningFailure();
            return queries;
        } catch (RagException | CancellationException error) { throw error; }
        catch (RuntimeException error) { throw planningFailure(); }
    }

    private static RagException planningFailure() {
        return new RagException(RagException.Reason.RETRIEVAL_ERROR, "The retrieval planner returned an invalid query plan.", false);
    }

    private static void append(Map<String, KnowledgeChunk> selected, List<KnowledgeChunk> candidates,
                               int maximum, RagTaskRunner.Context context) {
        for (KnowledgeChunk chunk : candidates) {
            context.check();
            if (selected.size() == maximum) break;
            selected.putIfAbsent(chunk.getChunkId(), chunk);
        }
    }
}
