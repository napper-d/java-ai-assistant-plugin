package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.sample.SampleChatService;
import edu.teamc.courseaiassistant.sample.SampleScenario;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone EDT smoke checks; no IDE, JUnit, model service or network required. */
public final class ChatPanelSmokeCheck {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("build/reports/stage2");
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                checkInteractions();
                renderPreview(output.resolve("chat-light.png"), 420, 760, false);
                checkThemeAndOverflow();
                setDarkPreviewDefaults();
                renderPreview(output.resolve("chat-dark.png"), 440, 820, false);
                renderPreview(output.resolve("chat-general.png"), 440, 820, true);
                renderPreview(output.resolve("chat-narrow.png"), 320, 700, false);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        System.out.println("PASS: blank guards, button/keyboard send, literal multiline input, "
                + "citation/general examples, history, clear/reset, live theme switch, long tokens and overflow layout.");
        System.out.println("Standalone Swing previews: " + output.toAbsolutePath());
        System.out.println("These previews do not verify IntelliJ runtime integration or its exact theme.");
    }

    private static void checkInteractions() {
        AtomicInteger calls = new AtomicInteger();
        SampleChatService service = new SampleChatService();
        ChatPanel panel = new ChatPanel(scenario -> {
            calls.incrementAndGet();
            return service.reply(scenario);
        });
        JTextArea question = find(panel, "questionInput", JTextArea.class);
        ConversationView history = find(panel, "conversationTranscript", ConversationView.class);
        JButton send = find(panel, "sendButton", JButton.class);
        JButton clear = find(panel, "clearChatButton", JButton.class);
        JComboBox<?> scenario = find(panel, "scenarioSelector", JComboBox.class);
        require(!send.isEnabled() && !clear.isEnabled(), "Empty state disables send and clear");

        question.setText(" \t\n\u3000");
        require(!send.isEnabled(), "Whitespace-only input disables send");
        triggerSendShortcut(panel);
        require(calls.get() == 0 && !clear.isEnabled(), "Keyboard shortcut rejects blank input");

        String literal = "What does <script>alert(1)</script> mean?\n中文问题 & Java<T>";
        question.setText(literal);
        require(send.isEnabled(), "Nonempty input enables send");
        send.doClick(0);
        require(calls.get() == 1, "Send invokes the sample provider exactly once");
        require(transcriptText(history).contains(literal), "Multiline HTML-like and Unicode input stays literal");
        require(transcriptText(history).contains("[S1] sample-course.pdf — page 3"), "Course source is displayed");
        require(transcriptText(history).contains("invented"), "Source is explicitly identified as invented");
        require(question.getText().isEmpty() && !send.isEnabled(), "Successful send clears only input");

        scenario.setSelectedItem(SampleScenario.GENERAL_KNOWLEDGE);
        question.setText("When should I use an ArrayList?");
        triggerSendShortcut(panel);
        require(calls.get() == 2, "Ctrl+Enter sends once");
        require(transcriptText(history).contains(literal), "Previous exchange is retained");
        String general = transcriptText(history).substring(transcriptText(history).lastIndexOf("Course AI (fixed sample)"));
        require(general.contains("no specific course material is referenced"), "General knowledge is disclosed");
        require(!general.contains("sample-course.pdf") && !general.contains("[S1]"), "No stale citation on general reply");

        question.setText("unsent draft");
        clear.doClick(0);
        require(question.getText().isEmpty(), "Clear removes an unsent draft");
        require(!transcriptText(history).contains(literal), "Clear removes earlier messages");
        require(!clear.isEnabled() && !send.isEnabled(), "Clear restores empty state");
        require(calls.get() == 2, "Clear never requests a reply");
        scenario.setSelectedItem(SampleScenario.COURSE_CITATION);
        question.setText("New conversation");
        send.doClick(0);
        require(!transcriptText(history).contains("Ask a question to try"), "First exchange replaces the welcome text");
        require(calls.get() == 3, "Sending works after clearing");
    }

    private static String transcriptText(ConversationView conversation) {
        return conversation.getText();
    }

    private static void checkThemeAndOverflow() {
        Color originalBackground = UIManager.getColor("Panel.background");
        ChatPanel panel = new ChatPanel(new SampleChatService());
        require(originalBackground.equals(UIManager.getColor("Panel.background")), "Chat does not change the global theme");
        JTextArea question = find(panel, "questionInput", JTextArea.class);
        JButton send = find(panel, "sendButton", JButton.class);
        ConversationView history = find(panel, "conversationTranscript", ConversationView.class);
        for (int i = 0; i < 4; i++) {
            question.setText("LongJavaIdentifier".repeat(25) + "\n路径测试 " + i);
            send.doClick(0);
        }
        String before = history.getText();
        setDarkPreviewDefaults();
        Color selectedBackground = UIManager.getColor("Panel.background");
        SwingUtilities.updateComponentTreeUI(panel);
        require(before.equals(history.getText()), "Theme switch preserves messages");
        require(selectedBackground.equals(UIManager.getColor("Panel.background")), "Chat leaves global theme unchanged");
        require(question.getForeground().equals(ChatTheme.current().text()), "Input follows the new theme");
        for (int width : new int[]{440, 320, 440}) {
            panel.setSize(width, 700);
            layout(panel);
            layout(panel);
            assertTextFits(panel);
            JViewport viewport = (JViewport) history.getParent();
            require(history.getPreferredSize().height > viewport.getHeight(), "Long history overflows vertically");
            require(history.getWidth() == viewport.getWidth(), "History tracks the viewport width");
            int bottom = 0;
            for (Component message : history.getComponents()) {
                require(message.getY() >= bottom, "Messages do not overlap after resize");
                require(message.getX() >= 0 && message.getX() + message.getWidth() <= history.getWidth(),
                        "Messages stay inside the conversation width");
                bottom = message.getY() + message.getHeight();
            }
            require(history.getComponent(0).getHeight() > 80, "Unbroken tokens wrap rather than clip");
        }
    }

    private static void triggerSendShortcut(ChatPanel panel) {
        KeyStroke shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
        Object key = panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(shortcut);
        require(key != null, "Ctrl+Enter is bound");
        panel.getActionMap().get(key).actionPerformed(new ActionEvent(panel, ActionEvent.ACTION_PERFORMED, ""));
    }

    private static void renderPreview(Path output, int width, int height, boolean general) throws Exception {
        ChatPanel panel = new ChatPanel(new SampleChatService());
        if (general) {
            find(panel, "scenarioSelector", JComboBox.class).setSelectedItem(SampleScenario.GENERAL_KNOWLEDGE);
        }
        find(panel, "questionInput", JTextArea.class).setText(
                general ? "When should I use an ArrayList?" : "Why use an interface for a service?");
        find(panel, "sendButton", JButton.class).doClick(0);
        panel.setSize(width, height);
        layout(panel);
        ConversationView history = find(panel, "conversationTranscript", ConversationView.class);
        ((JViewport) history.getParent()).setViewPosition(new Point(0, 0));
        layout(panel);
        assertTextFits(panel);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static void assertTextFits(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof ChatWidgets.Text text) {
                require(text.getPreferredSize().height <= text.getHeight() + 1,
                        "Wrapped text is not clipped: " + text.plainText());
            }
            if (child instanceof Container container) assertTextFits(container);
        }
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) {
                layout(container);
            }
        }
    }

    private static void setDarkPreviewDefaults() {
        Color background = new Color(0x2B2D30);
        Color foreground = new Color(0xDFE1E5);
        for (String component : new String[]{"Panel", "Label", "TextArea", "TextPane", "ComboBox", "Button", "Viewport"}) {
            UIManager.put(component + ".background", background);
            UIManager.put(component + ".foreground", foreground);
        }
        UIManager.put("TextArea.caretForeground", foreground);
        UIManager.put("TextPane.caretForeground", foreground);
    }

    private static <T extends Component> T find(Container parent, String name, Class<T> type) {
        Component found = findNamed(parent, name);
        require(type.isInstance(found), "Component exists: " + name);
        return type.cast(found);
    }

    private static Component findNamed(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                Component found = findNamed(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
