package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.RequestCoordinator;
import edu.teamc.courseaiassistant.sample.SampleScenario;
import edu.teamc.courseaiassistant.service.mock.MockChatService;
import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static edu.teamc.courseaiassistant.ui.RequestLifecycleTest.*;

/** Exercises the same asynchronous panel path used by the Tool Window. */
class ChatPanelTest {
    private final Map<String, Object> defaults = new HashMap<>();
    @BeforeEach void saveTheme() throws Exception {
        edt(() -> {
            for (String component : new String[]{"Panel", "Label", "TextArea", "TextPane", "ComboBox", "Button", "Viewport"}) {
                for (String attribute : new String[]{"background", "foreground"}) {
                    String key = component + "." + attribute; defaults.put(key, UIManager.get(key));
                }
            }
            for (String key : new String[]{"TextArea.caretForeground", "TextPane.caretForeground"}) defaults.put(key, UIManager.get(key));
        });
    }
    @AfterEach void restoreTheme() throws Exception { edt(() -> defaults.forEach(UIManager::put)); }

    @Test void inputShortcutsExamplesHistoryAndClear() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                require(!f.send().isEnabled() && !f.clear().isEnabled(), "Empty state disables send and clear");
                f.input().setText(" \t\n\u3000"); triggerSendShortcut(f.panel);
                require(!f.send().isEnabled() && f.calls.get() == 0, "Whitespace shortcut does not call the service");
            });
            String literal = "What does <script>alert(1)</script> mean?\n中文问题 & Java<T>";
            f.submit(literal, false);
            edt(() -> {
                require(f.calls.get() == 1, "Send invokes service once");
                require(f.history().contains(literal), "Multiline Unicode and HTML-like input stays literal");
                require(f.history().contains("[1] lecture-notes-week3.pdf · page 15–17"), "Real mock source is displayed");
                require(f.history().contains("invented"), "Sample source is disclosed");
                require(f.input().getText().isEmpty() && !f.send().isEnabled(), "Submission clears input");
                require(AssistantPresentationTest.named(f.panel, "chatStatus") == null, "No footer status exists after a response");
                find(f.panel, "scenarioSelector", JComboBox.class).setSelectedItem(SampleScenario.GENERAL_KNOWLEDGE);
            });
            f.submit("When should I use an ArrayList?", true);
            edt(() -> {
                require(f.calls.get() == 2 && f.history().contains(literal), "Enter sends once and retains history");
                String general = f.history().substring(f.history().lastIndexOf("Course AI (fixed sample)"));
                require(general.contains("no specific course material is referenced") && !general.contains("[S1]"), "General answer has disclosure and no stale source");
                f.input().setText("unsent draft"); f.clear().doClick(0);
                require(f.input().getText().isEmpty() && !f.history().contains(literal), "Clear removes draft and messages");
                require(!f.send().isEnabled() && !f.clear().isEnabled() && f.calls.get() == 2, "Clear restores empty state without a request");
            });
            f.submit("New conversation", false);
            edt(() -> {
                require(f.calls.get() == 3 && !f.history().contains("Ask a question to try"), "Sending after clear replaces welcome");
                f.input().setText("before selected after"); f.input().select(7, 15);
                Object key = f.input().getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK));
                require(key != null, "Shift+Enter is bound");
                f.input().getActionMap().get(key).actionPerformed(new ActionEvent(f.input(), 0, ""));
                require(f.input().getText().equals("before \n after") && f.calls.get() == 3, "Newline replaces selection without sending");
            });
        }
    }

    @Test void liveThemeAndLongHistoryWrapAtNarrowWidths() throws Exception {
        Color original = UIManager.getColor("Panel.background");
        try (Fixture f = new Fixture()) {
            require(original.equals(UIManager.getColor("Panel.background")), "Creating chat does not change global theme");
            for (int i = 0; i < 4; i++) f.submit("LongJavaIdentifier".repeat(25) + "\n路径测试 " + i, false);
            edt(() -> {
                String before = f.history(); setDarkPreviewDefaults();
                Color chosen = UIManager.getColor("Panel.background"); SwingUtilities.updateComponentTreeUI(f.panel);
                require(f.history().equals(before) && chosen.equals(UIManager.getColor("Panel.background")), "Theme change retains messages and global choice");
                require(f.input().getForeground().equals(ChatTheme.current().text()), "Input follows theme");
                for (int width : new int[]{440, 320, 440}) {
                    f.panel.setSize(width, 700); layout(f.panel); layout(f.panel); assertTextFits(f.panel);
                    ConversationView history = find(f.panel, "conversationTranscript", ConversationView.class);
                    JViewport viewport = (JViewport) history.getParent();
                    require(history.getPreferredSize().height > viewport.getHeight(), "Long history scrolls vertically");
                    require(history.getWidth() == viewport.getWidth(), "History tracks viewport width");
                    int bottom = 0;
                    for (Component message : history.getComponents()) {
                        require(message.getY() >= bottom, "Messages do not overlap after resize");
                        require(message.getX() >= 0 && message.getX() + message.getWidth() <= history.getWidth(), "Messages fit history width");
                        bottom = message.getY() + message.getHeight();
                    }
                    require(history.getComponent(0).getHeight() > 80, "Long tokens wrap");
                }
            });
        }
    }

    @Test void rendersLightDarkGeneralAndNarrowPreviews() throws Exception {
        Path output = Path.of("build/reports/stage2"); Files.createDirectories(output);
        for (String name : new String[]{"light", "dark", "general", "narrow"}) {
            if (!name.equals("light")) edt(ChatPanelTest::setDarkPreviewDefaults);
            try (Fixture f = new Fixture()) {
                if (name.equals("general")) edt(() -> find(f.panel, "scenarioSelector", JComboBox.class).setSelectedItem(SampleScenario.GENERAL_KNOWLEDGE));
                f.submit("Why use an interface for a service?", false);
                edt(() -> {
                    int width = name.equals("narrow") ? 320 : 440;
                    int height = name.equals("narrow") ? 700 : 820;
                    f.panel.setSize(width, height); layout(f.panel); layout(f.panel);
                    ConversationView history = find(f.panel, "conversationTranscript", ConversationView.class);
                    ((JViewport) history.getParent()).setViewPosition(new Point()); layout(f.panel); assertTextFits(f.panel);
                    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try { f.panel.printAll(graphics); } finally { graphics.dispose(); }
                    try { ImageIO.write(image, "png", output.resolve("chat-" + name + ".png").toFile()); }
                    catch (java.io.IOException error) { throw new AssertionError(error); }
                });
            }
        }
    }

    private static void triggerSendShortcut(ChatPanel panel) {
        JTextArea input = find(panel, "questionInput", JTextArea.class);
        Object key = input.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        require(key != null, "Enter is bound"); input.getActionMap().get(key).actionPerformed(new ActionEvent(input, 0, ""));
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final MockChatService service = new MockChatService(MockChatService.Scenario.SUCCESS_COURSE_GROUNDED, 0);
        ChatPanel panel;
        Fixture() throws Exception {
            var coordinator = new RequestCoordinator(project(new AtomicBoolean()), request -> { calls.incrementAndGet(); return service.ask(request); });
            edt(() -> panel = new ChatPanel(coordinator, choice -> service.setScenario(choice == SampleScenario.COURSE_CITATION
                    ? MockChatService.Scenario.SUCCESS_COURSE_GROUNDED : MockChatService.Scenario.SUCCESS_GENERAL_KNOWLEDGE)));
        }
        JTextArea input() { return find(panel, "questionInput", JTextArea.class); }
        JButton send() { return find(panel, "sendButton", JButton.class); }
        JButton clear() { return find(panel, "clearChatButton", JButton.class); }
        String history() { return find(panel, "conversationTranscript", ConversationView.class).getText(); }
        void submit(String query, boolean keyboard) throws Exception {
            edt(() -> { input().setText(query); if (keyboard) triggerSendShortcut(panel); else send().doClick(0); });
            awaitEdt(() -> clear().isEnabled() && !history().contains("Thinking…") && history().contains("Course AI"));
        }
        public void close() throws Exception { edt(panel::dispose); }
    }
    private static void assertTextFits(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
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
