package edu.teamc.courseaiassistant.rag;

import edu.teamc.courseaiassistant.model.SourceReference;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Paragraph -> sentence -> word -> code-point splitting, isolated to each page/slide. */
public final class ChunkSplitter {
    static final String STRATEGY_ID = "boundary-codepoint-v2";
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[\\p{Zs}\\t]*\\n(?:[\\p{Zs}\\t]*\\n)*");
    private static final Pattern WORD = Pattern.compile("\\S+\\s*", Pattern.UNICODE_CHARACTER_CLASS);
    private final int size;
    private final int overlap;
    public ChunkSplitter(int size, int overlap) {
        if (size < 2 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("Invalid chunk window.");
        this.size = size; this.overlap = overlap;
    }

    public List<DocumentChunk> split(List<DocumentSection> sections) {
        List<DocumentChunk> result = new ArrayList<>();
        for (DocumentSection section : sections) {
            RagFiles.checkCancelled();
            String original = section.text().replace("\r\n", "\n").replace('\r', '\n');
            if (original.isBlank()) continue;
            List<Unit> units = units(original);
            int start = 0;
            while (start < units.size()) {
                RagFiles.checkCancelled();
                int end = start, length = 0;
                while (end < units.size() && length + units.get(end).length() <= size) {
                    RagFiles.checkCancelled();
                    length += units.get(end++).length();
                }
                String text = original.substring(units.get(start).start(), units.get(end - 1).end()).strip();
                if (!text.isBlank()) {
                    String id = RagFiles.sha256(section.documentId() + ":" + section.kind() + ":" + section.number()
                            + ":" + STRATEGY_ID + ":" + size + ":" + overlap + ":" + units.get(start).start() + ":" + text);
                    boolean pdf = section.kind() == DocumentSection.Kind.PDF_PAGE;
                    SourceReference source = new SourceReference("R-" + id, section.documentName(),
                            pdf ? section.number() : null, pdf ? section.number() : null,
                            pdf ? null : "slide " + section.number());
                    result.add(new DocumentChunk(id, section.documentId(), text, source));
                }
                if (end == units.size()) break;
                // Reuse whole trailing units only. Leave room for at least one new unit,
                // so a large overlap can never cause duplicate-only chunks or a loop.
                int next = end, overlapLength = 0;
                while (next > start && overlapLength + units.get(next - 1).length() <= overlap) {
                    overlapLength += units.get(--next).length();
                }
                while (next < end && overlapLength + units.get(end).length() > size) {
                    overlapLength -= units.get(next++).length();
                }
                start = next;
            }
        }
        return List.copyOf(result);
    }

    private record Unit(int start, int end, int length) {}

    private List<Unit> units(String text) {
        List<Unit> result = new ArrayList<>();
        var paragraphs = PARAGRAPH_BREAK.matcher(text);
        int start = 0;
        while (paragraphs.find()) {
            splitParagraph(text, start, paragraphs.end(), result);
            start = paragraphs.end();
        }
        splitParagraph(text, start, text.length(), result);
        return result;
    }

    private void splitParagraph(String text, int start, int end, List<Unit> result) {
        RagFiles.checkCancelled();
        if (start == end) return;
        if (appendIfFits(text, start, end, result)) return;
        // Sentence boundaries are computed inside this paragraph only. Soft line wraps
        // stay in their sentence rather than being treated as paragraph boundaries.
        String paragraph = text.substring(start, end);
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(paragraph);
        int sentenceStart = sentences.first();
        for (int sentenceEnd = sentences.next(); sentenceEnd != BreakIterator.DONE; sentenceEnd = sentences.next()) {
            splitSentence(text, start + sentenceStart, start + sentenceEnd, result);
            sentenceStart = sentenceEnd;
        }
    }

    private void splitSentence(String text, int start, int end, List<Unit> result) {
        RagFiles.checkCancelled();
        if (appendIfFits(text, start, end, result)) return;
        var words = WORD.matcher(text).region(start, end);
        int wordStart = start;
        while (words.find()) {
            splitWord(text, wordStart, words.end(), result);
            wordStart = words.end();
        }
        if (wordStart < end) splitWord(text, wordStart, end, result);
    }

    private void splitWord(String text, int start, int end, List<Unit> result) {
        RagFiles.checkCancelled();
        if (appendIfFits(text, start, end, result)) return;
        // Only an overlong indivisible token falls back to code points; surrogate
        // pairs are never split, and the same packing/overlap rules still apply.
        for (int offset = start; offset < end;) {
            RagFiles.checkCancelled();
            int next = offset + Character.charCount(text.codePointAt(offset));
            result.add(new Unit(offset, next, 1));
            offset = next;
        }
    }

    private boolean appendIfFits(String text, int start, int end, List<Unit> result) {
        int length = text.codePointCount(start, end);
        if (length > size) return false;
        if (length > 0) result.add(new Unit(start, end, length));
        return true;
    }
}
