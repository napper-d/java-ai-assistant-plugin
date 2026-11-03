package edu.teamc.courseaiassistant.rag;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSplitterTest {
    private static DocumentSection pdf(String text) {
        return new DocumentSection("document", "lecture.pdf", DocumentSection.Kind.PDF_PAGE, 3, text);
    }

    @Test void keepsParagraphsWholeAndPacksAdjacentShortParagraphs() {
        var chunks = new ChunkSplitter(35, 0).split(List.of(pdf("First paragraph.\n\nSecond paragraph.\n\nThird paragraph.")));
        assertEquals(List.of("First paragraph.", "Second paragraph.\n\nThird paragraph."),
                chunks.stream().map(DocumentChunk::text).toList());
    }

    @Test void splitsAnOversizedParagraphAtFullSentences() {
        String text = "Alpha sentence. Bravo sentence. Charlie sentence.";
        var chunks = new ChunkSplitter(34, 0).split(List.of(pdf(text)));
        assertEquals(List.of("Alpha sentence. Bravo sentence.", "Charlie sentence."),
                chunks.stream().map(DocumentChunk::text).toList());
    }

    @Test void preservesSoftLineWrapsAndChineseSentenceBoundaries() {
        var soft = new ChunkSplitter(38, 0).split(List.of(pdf("The first sentence\ncontinues here. The next sentence ends.")));
        assertEquals("The first sentence\ncontinues here.", soft.getFirst().text());
        var chinese = new ChunkSplitter(9, 0).split(List.of(pdf("第一句话结束。第二句话结束！第三句？")));
        assertEquals(List.of("第一句话结束。", "第二句话结束！", "第三句？"), chinese.stream().map(DocumentChunk::text).toList());
    }

    @Test void onlySplitsAnOversizedSentenceAtWords() {
        var chunks = new ChunkSplitter(16, 0).split(List.of(pdf("alpha bravo charlie delta echo foxtrot")));
        assertEquals(List.of("alpha bravo", "charlie delta", "echo foxtrot"), chunks.stream().map(DocumentChunk::text).toList());
    }

    @Test void overlapReusesWholeSentencesWithinItsBudget() {
        var chunks = new ChunkSplitter(26, 12).split(List.of(pdf("Alpha ends. Bravo ends. Charlie ends. Delta ends.")));
        assertEquals(List.of("Alpha ends. Bravo ends.", "Bravo ends. Charlie ends.", "Delta ends."),
                chunks.stream().map(DocumentChunk::text).toList());
    }

    @Test void overlapMakesProgressWhenTheNextUnitIsLarge() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            String text = "Small. A much larger complete sentence. Tail.";
            var chunks = new ChunkSplitter(34, 30).split(List.of(pdf(text)));
            assertEquals(3, chunks.size());
            assertEquals("Small.", chunks.getFirst().text());
            assertEquals("A much larger complete sentence.", chunks.get(1).text());
            assertEquals("Tail.", chunks.getLast().text());
        });
    }

    @Test void overlongTokensKeepUnicodeAndOverlapWithoutDroppingContent() {
        var chunks = new ChunkSplitter(4, 1).split(List.of(pdf("甲乙😀丙丁戊己")));
        assertEquals(List.of("甲乙😀丙", "丙丁戊己"), chunks.stream().map(DocumentChunk::text).toList());
        for (var chunk : chunks) assertTrue(chunk.text().codePointCount(0, chunk.text().length()) <= 4);
    }

    @Test void keepsPageAndSlideLocationsAndStableIds() {
        var sections = List.of(pdf("First sentence. Second sentence."),
                new DocumentSection("slides", "lecture.pptx", DocumentSection.Kind.PPTX_SLIDE, 7,
                        "Slide sentence. Another sentence."));
        var splitter = new ChunkSplitter(20, 4);
        var first = splitter.split(sections);
        var second = splitter.split(sections);
        assertEquals(first.stream().map(DocumentChunk::chunkId).toList(), second.stream().map(DocumentChunk::chunkId).toList());
        assertEquals(first.size(), first.stream().map(DocumentChunk::chunkId).distinct().count());
        for (var chunk : first) {
            assertTrue(chunk.text().codePointCount(0, chunk.text().length()) <= 20);
            if (chunk.documentId().equals("document")) assertEquals(3, chunk.source().getPageStart());
            else { assertEquals("slide 7", chunk.source().getScope()); assertNull(chunk.source().getPageStart()); }
        }
    }

    @Test void coversEveryWordWhenPackingAndOverlapping() {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 100; i++) words.add("word" + i);
        String text = String.join(" ", words);
        var chunks = new ChunkSplitter(45, 20).split(List.of(pdf(text)));
        List<String> recovered = chunks.stream().flatMap(chunk -> List.of(chunk.text().split("\\s+")).stream()).toList();
        assertTrue(recovered.containsAll(words));
        assertTrue(recovered.stream().allMatch(words::contains), "No word may be split in the middle.");
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().codePointCount(0, chunk.text().length()) <= 45));
    }
}
