package edu.teamc.courseaiassistant.rag;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Generic identifier-based policy; no group names or assignment-specific feature numbers are hardcoded. */
public final class ReferenceQueryPlanner implements FollowUpQueryPlanner {
    private final Pattern references;
    private final int budget;

    public ReferenceQueryPlanner(RagConfig.RetrievalSettings settings) {
        references = Pattern.compile(settings.referencePattern(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        budget = settings.maxQueries();
    }

    @Override public CompletableFuture<List<Query>> plan(String question, List<Passage> evidence) {
        LinkedHashMap<String, Query> queries = new LinkedHashMap<>();
        if (evidence.isEmpty()) addReferences(question, null, null, queries);
        else for (Passage passage : evidence) {
            RagFiles.checkCancelled();
            addReferences(passage.chunk().text(), passage.chunk().documentId(), passage.chunk().chunkId(), queries);
            if (queries.size() == budget) break;
        }
        return CompletableFuture.completedFuture(List.copyOf(queries.values()));
    }

    private void addReferences(String text, String documentId, String sourceChunkId, Map<String, Query> queries) {
        var matcher = references.matcher(text);
        while (matcher.find() && queries.size() < budget) {
            RagFiles.checkCancelled();
            String reference = matcher.group().strip();
            if (reference.isEmpty() || reference.length() > 64) continue;
            if (sourceChunkId != null && defines(text, reference)) continue;
            String key = documentId + "\n" + reference.toUpperCase(Locale.ROOT);
            queries.putIfAbsent(key, new Query("Definition of " + reference, documentId, reference, sourceChunkId));
        }
    }

    static Pattern exactReference(String reference) {
        return Pattern.compile("(?<![\\p{IsLatin}\\p{N}_.-])" + Pattern.quote(reference)
                        + "(?![\\p{IsLatin}\\p{N}_]|[.-][\\p{IsLatin}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Recognize explicit headings or definition phrases, rather than another passing mention. */
    static boolean defines(String text, String reference) {
        Pattern exact = exactReference(reference);
        for (String line : text.split("\\R")) {
            RagFiles.checkCancelled();
            String heading = line.strip().replaceFirst("^#{1,6}\\s*", "")
                    .replaceFirst("^(?:[-*•]\\s+|\\d+[.)]\\s+)", "")
                    .replaceFirst("(?i)^(?:(?:feature|function|requirement|task|section|chapter)\\s+|(?:功能|需求|任务|章节)\\s*)+", "");
            var identifier = exact.matcher(heading);
            if (!identifier.find() || identifier.start() != 0) continue;
            String remainder = heading.substring(identifier.end()).stripLeading();
            if (remainder.isEmpty()) return text.strip().length() > heading.length();
            if ("+/,，、&".indexOf(remainder.charAt(0)) >= 0 || remainder.matches("(?i)^and\\b.*")) continue;
            if (":：.)-–—".indexOf(remainder.charAt(0)) >= 0) remainder = remainder.substring(1).stripLeading();
            if (remainder.matches("(?i)^(?:[A-Z]{1,12}[-_]?\\d+(?:[._-]\\d+)*\\s*(?:[+,/&，、]\\s*|\\s+)?)+$")) continue;
            if (!remainder.isBlank() && "+/,，、&".indexOf(remainder.charAt(0)) < 0) return true;
        }
        return Pattern.compile(exact.pattern() + "(?:\\s+(?:is|means|refers\\s+to|requires)\\b|\\s*(?:是|指的是|表示|要求))",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
}
