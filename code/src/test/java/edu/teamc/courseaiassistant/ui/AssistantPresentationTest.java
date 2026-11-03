package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.*;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.mock.MockChatService;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantPresentationTest {
    @Test void citationsUseAnswerLocalNumbersAndExpandTheirOriginalSnapshot() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var a = new SourceReference("R-a-long-internal-id", "Lecture.pdf", 2, 3, null);
            var b = new SourceReference("R-b-long-internal-id", "Slides.pptx", null, null, "slide 5");
            var answer = new ChatAnswer("q", "First [R-a-long-internal-id], second [R-b-long-internal-id], first again [R-a-long-internal-id].",
                    List.of(a,b), ChatAnswer.Provenance.COURSE_GROUNDED, null,
                    List.of(new KnowledgeChunk("chunk-a", a, "Original lecture excerpt.", .9),
                            new KnowledgeChunk("chunk-b", b, "Original slide excerpt.", .8)));
            ConversationView view = new ConversationView(); view.addUserMessage("Question", null); view.addAssistantMessage(answer, false);
            assertTrue(view.getText().contains("First [1], second [2], first again [1]"));
            assertFalse(view.getText().contains("R-a-")); assertFalse(view.getText().contains("Original lecture excerpt"));
            assertEquals("[1] Lecture.pdf · page 2–3", ((JButton) named(view,"citation1")).getText());
            ((JButton) named(view,"citation1")).doClick(0);
            assertTrue(view.getText().contains("Original lecture excerpt"));
            render(view, "citations-expanded", 440, 650);
            assertFalse(view.getText().contains("Original slide excerpt"));
            ((JButton) named(view,"citation1")).doClick(0);
            assertFalse(view.getText().contains("Original lecture excerpt"));
            render(view, "citations", 440, 650);
        });
    }

    @Test void rightAlignedIconNavigationRetainsDraftAndShowsWelcomeInBothThemes() throws Exception {
        var project = (com.intellij.openapi.project.Project) java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.openapi.project.Project.class.getClassLoader(), new Class<?>[]{com.intellij.openapi.project.Project.class},
                (proxy, method, args) -> method.getName().equals("isDisposed") ? false : null);
        SwingUtilities.invokeAndWait(() -> {
            Color old = UIManager.getColor("Panel.background");
            for (boolean dark : List.of(false, true)) {
                UIManager.put("Panel.background", dark ? new Color(0x242424) : new Color(0xFAFAFB));
                var chat = new ChatPanel(new RequestCoordinator(project, new MockChatService()));
                var backend = new KnowledgeBasePanelTest.Backend();
                var library = new KnowledgeBasePanel(backend, List::of, () -> {});
                try {
                    var host = new AssistantPanel(chat, library);
                    assertNull(named(host,"replyMode"));
                    ((JTextArea) named(host,"questionInput")).setText("Keep this draft");
                    ((JToggleButton) named(host,"materialsTab")).doClick(0);
                    assertFalse(chat.isVisible()); host.showChat(); assertTrue(chat.isVisible());
                    assertEquals("Keep this draft", ((JTextArea) named(host,"questionInput")).getText());
                    assertTrue(((JButton) named(host,"clearChatButton")).isVisible());
                    assertTrue(((ConversationView) named(host,"conversationTranscript")).getText().contains("Course Assistant"));
                    render(host, dark ? "welcome-dark" : "welcome-light", 460, 840);
                    render(host, dark ? "welcome-dark-narrow" : "welcome-light-narrow", 320, 760);
                } finally { chat.dispose(); library.dispose(); }
            }
            UIManager.put("Panel.background", old);
        });
    }
    static Component named(Component parent, String name) {
        if (name.equals(parent.getName())) return parent;
        if (parent instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
    static void render(JComponent component, String name, int width, int height) {
        boolean notify = !component.isDisplayable();
        if (notify) component.addNotify();
        component.setSize(width,height); for(int pass=0; pass<3; pass++) layout(component);
        var image = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB); var g=image.createGraphics();
        try { g.setColor(ChatTheme.current().canvas()); g.fillRect(0,0,width,height); component.printAll(g); }
        finally { g.dispose(); if (notify) component.removeNotify(); }
        try { Path file=Path.of("build/reports/assistant-ui",name+".png"); Files.createDirectories(file.getParent()); javax.imageio.ImageIO.write(image,"png",file.toFile()); }
        catch(java.io.IOException error) { throw new AssertionError(error); }
    }
    private static void layout(Container component) { component.doLayout(); for(Component child:component.getComponents()) if(child instanceof Container nested) layout(nested); }
}
