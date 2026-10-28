package edu.teamc.courseaiassistant.llm;

import com.google.gson.*;
import edu.teamc.courseaiassistant.model.*;
import java.util.*;
import java.util.regex.Pattern;

/** Only structural citation membership can be checked here; semantic faithfulness needs corpus acceptance. */
final class StructuredReplies {
    private static final Pattern CITATION = Pattern.compile("\\[\\[cite:([^\\[\\]\\r\\n]+)]]");
    private StructuredReplies() {}

    static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException();
        return value.getAsString();
    }

    static ChatAnswer chat(String id, String raw, List<KnowledgeChunk> evidence) throws ServiceFailure {
        try {
            JsonObject object = JsonParser.parseString(raw).getAsJsonObject();
            String answer = string(object, "answer");
            if (answer.isBlank()) throw new IllegalArgumentException();
            Map<String, SourceReference> available = new LinkedHashMap<>();
            for (KnowledgeChunk chunk : evidence) available.put(chunk.getSource().getReferenceId(), chunk.getSource());
            Set<String> cited = new LinkedHashSet<>();
            for (JsonElement value : object.getAsJsonArray("citations")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || !available.containsKey(value.getAsString()) || !cited.add(value.getAsString()))
                    throw new IllegalArgumentException();
            }
            Set<String> markers = new LinkedHashSet<>();
            var matcher = CITATION.matcher(answer);
            while (matcher.find()) markers.add(matcher.group(1));
            if (!markers.equals(cited) || (!evidence.isEmpty() && cited.isEmpty())) throw new IllegalArgumentException();
            String withoutMarkers = CITATION.matcher(answer).replaceAll("");
            if (withoutMarkers.contains("[[cite:")) throw new IllegalArgumentException();
            for (String source : cited) answer = answer.replace("[[cite:" + source + "]]", "[" + source + "]");
            return new ChatAnswer(id, answer, cited.stream().map(available::get).toList(),
                    cited.isEmpty() ? ChatAnswer.Provenance.GENERAL_KNOWLEDGE : ChatAnswer.Provenance.COURSE_GROUNDED,
                    cited.isEmpty() ? "No relevant course material was found. This answer uses general knowledge." : null);
        } catch (RuntimeException error) { throw LlmFailures.malformed(id); }
    }

    static ChangeProposal proposal(ModificationRequest request, String raw) throws ServiceFailure {
        try {
            JsonObject object = JsonParser.parseString(raw).getAsJsonObject();
            String path = string(object, "path");
            String text = string(object, "proposedFileText");
            String summary = string(object, "summary");
            if (!path.equals(request.codeContext().getProjectRelativePath()) || summary.isBlank() || text.isBlank()
                    || text.equals(request.originalFileText())) throw new IllegalArgumentException();
            return new ChangeProposal(request.requestId(), new CodeChange(path, request.originalFileText(), text, summary));
        } catch (RuntimeException error) { throw LlmFailures.malformed(request.requestId()); }
    }
}
