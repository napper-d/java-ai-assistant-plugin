package edu.teamc.courseaiassistant.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.text.StyleConstants;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownViewTest {
    @Test void rendersGfmTablesWithStylesAlignmentAndResponsiveRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Color old = UIManager.getColor("Panel.background");
            try {
                for (boolean dark : new boolean[]{false, true}) {
                    UIManager.put("Panel.background", new Color(dark ? 0x242424 : 0xFAFAFB));
                    var view = new MarkdownView("## Java collections\n\n| Type | Purpose | Cost |\n| :--- | :---: | ---: |\n"
                            + "| **ArrayList** | Ordered elements with indexed access | `O(1)` |\n"
                            + "| HashMap | Key \\| value lookup | O(1) |\n\nAfter the table.");
                    int wide = view.heightAt(420);
                    int narrow = view.heightAt(250);
                    assertTrue(narrow > wide, "Rows grow when the answer becomes narrower");
                    assertTrue(view.plainText().contains("ArrayList\tOrdered elements"));
                    assertTrue(view.plainText().contains("Key | value lookup"));
                    assertFalse(view.plainText().contains(":---"));
                    var doc = view.getStyledDocument();
                    JComponent table = null;
                    for (int i = 0; i < doc.getLength(); i++) {
                        var component = StyleConstants.getComponent(doc.getCharacterElement(i).getAttributes());
                        if (component instanceof JComponent found) {
                            table = found;
                            assertEquals(0f, StyleConstants.getLineSpacing(doc.getParagraphElement(i).getAttributes()),
                                    "Table height must not receive proportional prose spacing");
                        }
                    }
                    assertNotNull(table);
                    assertEquals(9, table.getComponentCount());
                    var header = (JTextPane) table.getComponent(0);
                    assertTrue(StyleConstants.isBold(header.getStyledDocument().getCharacterElement(0).getAttributes()));
                    var center = (JTextPane) table.getComponent(1);
                    assertEquals(StyleConstants.ALIGN_CENTER, StyleConstants.getAlignment(center.getStyledDocument().getParagraphElement(0).getAttributes()));
                    var right = (JTextPane) table.getComponent(2);
                    assertEquals(StyleConstants.ALIGN_RIGHT, StyleConstants.getAlignment(right.getStyledDocument().getParagraphElement(0).getAttributes()));
                    var bold = (JTextPane) table.getComponent(3);
                    assertTrue(StyleConstants.isBold(bold.getStyledDocument().getCharacterElement(0).getAttributes()));
                    var code = (JTextPane) table.getComponent(5);
                    assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(code.getStyledDocument().getCharacterElement(0).getAttributes()));
                    view.heightAt(420);
                    AssistantPresentationTest.render(view, dark ? "markdown-table-dark" : "markdown-table-light", 420, 450);
                    view.heightAt(250);
                    AssistantPresentationTest.render(view, dark ? "markdown-table-dark-narrow" : "markdown-table-light-narrow", 250, 550);
                    assertTrue(table.getWidth() <= 250);
                    for (Component cell : table.getComponents()) assertTrue(cell.getBounds().getMaxX() <= table.getWidth());
                }
                var code = new MarkdownView("```md\n| A | B |\n| --- | --- |\n| 1 | 2 |\n```");
                assertTrue(code.plainText().contains("| --- | --- |"), "Code fences retain literal Markdown");
            } finally { UIManager.put("Panel.background", old); }
        });
    }
    @Test void rendersSemanticMarkdownAsNativeStylesWithoutResourceLoading() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var view=new MarkdownView("# Heading\n\n**Bold** and *emphasis* and `inline`.\n\n- first\n- second\n\n> Quoted text\n\n```java\nclass Demo {}\n```\n\n![Diagram](http://127.0.0.1:1/image.png)\n\n<script>bad()</script>");
            String text=view.plainText();assertTrue(text.contains("Heading"));assertFalse(text.contains("**Bold**"));
            assertTrue(text.contains("•  first"));assertTrue(text.contains("[Image: Diagram]"));
            assertFalse(text.contains("image.png"));assertTrue(text.contains("<script>bad()</script>"));
            var doc=view.getStyledDocument();
            assertTrue(StyleConstants.isBold(doc.getCharacterElement(text.indexOf("Bold")).getAttributes()));
            assertTrue(StyleConstants.isItalic(doc.getCharacterElement(text.indexOf("emphasis")).getAttributes()));
            assertEquals(Font.MONOSPACED,StyleConstants.getFontFamily(doc.getCharacterElement(text.indexOf("class Demo")).getAttributes()));
            for(int i=0;i<doc.getLength();i++) {
                var attributes=doc.getCharacterElement(i).getAttributes();
                assertNull(StyleConstants.getIcon(attributes));assertNull(StyleConstants.getComponent(attributes));
            }
            AssistantPresentationTest.render(view,"markdown-styles",420,700);
        });
    }


    @Test void requestShowsThinkingOnlyInTranscript() throws Exception {
        var pending=new java.util.concurrent.CompletableFuture<edu.teamc.courseaiassistant.model.ChatAnswer>();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var coordinator=new edu.teamc.courseaiassistant.application.RequestCoordinator(
                RequestLifecycleTest.project(new java.util.concurrent.atomic.AtomicBoolean()),request -> {entered.countDown();return pending;});
        var panel=new java.util.concurrent.atomic.AtomicReference<ChatPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new ChatPanel(coordinator));
                ((JTextArea)AssistantPresentationTest.named(panel.get(),"questionInput")).setText("Explain Java");
                ((JButton)AssistantPresentationTest.named(panel.get(),"sendButton")).doClick(0);
                assertTrue(((ConversationView)AssistantPresentationTest.named(panel.get(),"conversationTranscript")).getText().contains("Thinking…"));
                assertNull(AssistantPresentationTest.named(panel.get(),"chatStatus"));
            });
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        } finally {SwingUtilities.invokeAndWait(() -> panel.get().dispose());}
    }

    @Test void responseAndCitationCardsUseThemeBorderAndExpandWithoutOverlap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Color old=UIManager.getColor("Panel.background");
            try {
                for(boolean dark:new boolean[]{false,true}) {
                    UIManager.put("Panel.background",new Color(dark?0x242424:0xFAFAFB));
                    var source=new edu.teamc.courseaiassistant.model.SourceReference("R-test","Lecture.pdf",3,3,null);
                    var answer=new edu.teamc.courseaiassistant.model.ChatAnswer("q","## Prefer an interface\n\nAn **interface** separates the contract from the implementation. [R-test]\n\n- Keep callers independent\n- Replace implementations easily\n\n```java\ninterface Repository { void save(); }\n```\n\n| Abstraction | Role |\n| --- | --- |\n| Interface | Defines the contract |\n| Class | Provides the implementation |",java.util.List.of(source),edu.teamc.courseaiassistant.model.ChatAnswer.Provenance.COURSE_GROUNDED,null,
                            java.util.List.of(new edu.teamc.courseaiassistant.model.KnowledgeChunk("chunk",source,"Original course material.",.9)));
                    var view=new ConversationView();view.addUserMessage("Explain interfaces",null);view.addAssistantMessage(answer,false);
                    ((JButton)AssistantPresentationTest.named(view,"citation1")).doClick(0);
                    assertNotNull(AssistantPresentationTest.named(view,"answerCard"));assertNotNull(AssistantPresentationTest.named(view,"sourcesCard"));
                    AssistantPresentationTest.render(view,dark?"answer-markdown-dark":"answer-markdown-light",440,850);
                    AssistantPresentationTest.render(view,dark?"answer-markdown-dark-narrow":"answer-markdown-light-narrow",320,850);
                }
            } finally {UIManager.put("Panel.background",old);}
        });
    }
}
