package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.sample.SampleReply;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Width-aware message layout; user bubbles and assistant text remain selectable. */
public final class ConversationView extends JPanel implements Scrollable {
    private boolean hasMessages;

    ConversationView() {
        setLayout(null);
        setOpaque(false);
        setName("conversationTranscript");
        getAccessibleContext().setAccessibleName("Conversation");
        showWelcome();
    }

    void showWelcome() {
        removeAll();
        hasMessages = false;
        add(new Welcome());
        revalidate();
        repaint();
    }

    void addExchange(String question, SampleReply reply, CodeContext context) {
        if (!hasMessages) removeAll();
        hasMessages = true;
        add(new UserMessage(question, context));
        add(new AssistantMessage(reply));
        revalidate();
        repaint();
    }

    boolean hasMessages() { return hasMessages; }

    /** Plain text from the actual rendered message components, suitable for inspection. */
    public String getText() {
        return Arrays.stream(getComponents()).map(component -> ((Message) component).plainText())
                .collect(Collectors.joining("\n\n"));
    }

    @Override public Dimension getPreferredSize() {
        int width = getParent() instanceof JViewport viewport && viewport.getExtentSize().width > 0
                ? viewport.getExtentSize().width : Math.max(300, getWidth());
        int height = 12;
        for (Component child : getComponents()) height += ((Message) child).heightAt(Math.max(1, width - 8)) + 24;
        return new Dimension(width, height);
    }

    @Override public void doLayout() {
        int y = 12;
        int width = Math.max(1, getWidth() - 8);
        for (Component child : getComponents()) {
            int height = ((Message) child).heightAt(width);
            child.setBounds(4, y, width, height);
            child.doLayout();
            y += height + 24;
        }
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(400, 440); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(20, visible.height - 40); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    private abstract static class Message extends JPanel {
        Message() { super(null); setOpaque(false); }
        abstract int heightAt(int width);
        abstract String plainText();
    }

    private static final class Welcome extends Message {
        private final ChatWidgets.Label title = new ChatWidgets.Label("Ask about Java", 4, true, false);
        private final ChatWidgets.Text description = new ChatWidgets.Text(
                "Ask a question to try the chat layout.\n\nChoose a local example below. "
                        + "Replies are fixed samples, not generated answers.", true, 0);
        Welcome() { add(title); add(description); }
        @Override int heightAt(int width) { return 76 + description.heightAt(Math.max(1, width - 24)); }
        @Override public void doLayout() {
            title.setBounds(12, 24, Math.max(1, getWidth() - 24), 30);
            description.setBounds(12, 68, Math.max(1, getWidth() - 24), description.heightAt(Math.max(1, getWidth() - 24)));
        }
        @Override String plainText() { return title.getText() + "\n" + description.plainText(); }
    }

    private static final class UserMessage extends Message {
        private final ChatWidgets.Text text;
        private final ChatWidgets.Label contextLabel;
        private final JPanel bubble = new ChatWidgets.RoundedPanel(new BorderLayout(), true, false);
        private int bubbleWidth;
        UserMessage(String question, CodeContext context) {
            text = new ChatWidgets.Text(question, false, 0);
            bubble.setBorder(new EmptyBorder(13, 15, 13, 15));
            bubble.add(text, BorderLayout.CENTER);
            add(bubble);
            if (context != null) {
                String contextText = String.format("📎 %s  lines %d–%d",
                        context.getProjectRelativePath(),
                        context.getStartLine(),
                        context.getEndLine());
                contextLabel = new ChatWidgets.Label(contextText, -2, false, false);
                contextLabel.setHorizontalAlignment(SwingConstants.RIGHT);
                add(contextLabel);
            } else {
                contextLabel = null;
            }
            getAccessibleContext().setAccessibleName("Your question");
        }
        @Override int heightAt(int width) {
            FontMetrics metrics = text.getFontMetrics(text.getFont());
            int natural = text.plainText().lines().mapToInt(metrics::stringWidth).max().orElse(0) + 30;
            bubbleWidth = Math.max(1, Math.min((int) (width * .86), Math.max(64, natural)));
            int height = text.heightAt(Math.max(1, bubbleWidth - 30)) + 26;
            if (contextLabel != null) {
                contextLabel.setSize(width, Short.MAX_VALUE);
                height += contextLabel.getPreferredSize().height + 2;
            }
            return height;
        }
        @Override public void doLayout() {
            heightAt(getWidth());
            int y = 0;
            bubble.setBounds(getWidth() - bubbleWidth, y, bubbleWidth, text.heightAt(Math.max(1, bubbleWidth - 30)) + 26);
            bubble.doLayout();
            if (contextLabel != null) {
                y += bubble.getHeight() + 2;
                contextLabel.setSize(getWidth(), contextLabel.getPreferredSize().height);
                contextLabel.setBounds(0, y, getWidth(), contextLabel.getPreferredSize().height);
            }
        }
        @Override String plainText() {
            String result = "You\n" + text.plainText();
            if (contextLabel != null) {
                result += "\n" + contextLabel.getText();
            }
            return result;
        }
    }

    private static final class AssistantMessage extends Message {
        private final ChatWidgets.Label label = new ChatWidgets.Label("Course AI", 0, true, false);
        private final ChatWidgets.Label sample = new ChatWidgets.Label("Fixed sample", -1, false, true);
        private final ChatWidgets.Text answer;
        private final ChatWidgets.Text disclosure;
        private final ChatWidgets.Text sources;
        private final JPanel sourceCard;
        AssistantMessage(SampleReply reply) {
            answer = new ChatWidgets.Text(reply.answer(), false, 0);
            disclosure = new ChatWidgets.Text(reply.disclosure(), true, -1);
            add(label); add(sample); add(answer); add(disclosure);
            if (reply.sources().isEmpty()) {
                sources = null;
                sourceCard = null;
            } else {
                StringBuilder referenceText = new StringBuilder("Example sources (invented)\n");
                for (int i = 0; i < reply.sources().size(); i++) {
                    SampleReply.Source source = reply.sources().get(i);
                    referenceText.append("[S").append(i + 1).append("] ").append(source.documentName())
                            .append(" — page ").append(source.page()).append('\n');
                }
                sources = new ChatWidgets.Text(referenceText.toString().stripTrailing(), false, -1);
                sourceCard = new ChatWidgets.RoundedPanel(new BorderLayout(), false, true);
                sourceCard.setBorder(new EmptyBorder(10, 12, 10, 12));
                sourceCard.add(sources, BorderLayout.CENTER);
                add(sourceCard);
            }
        }
        @Override int heightAt(int width) {
            int height = 40 + answer.heightAt(width) + 18;
            if (sources != null) height += sources.heightAt(Math.max(1, width - 24)) + 20 + 14;
            return height + disclosure.heightAt(width);
        }
        @Override public void doLayout() {
            int width = getWidth();
            label.setBounds(0, 0, Math.max(1, width - 100), 24);
            sample.setBounds(Math.max(0, width - 95), 0, Math.min(95, width), 24);
            int answerHeight = answer.heightAt(width);
            answer.setBounds(0, 40, width, answerHeight);
            int y = 40 + answerHeight + 18;
            if (sources != null) {
                int sourceHeight = sources.heightAt(Math.max(1, width - 24)) + 20;
                sourceCard.setBounds(0, y, width, sourceHeight);
                sourceCard.doLayout();
                y += sourceHeight + 14;
            }
            disclosure.setBounds(0, y, width, disclosure.heightAt(width));
        }
        @Override String plainText() {
            return "Course AI (fixed sample)\n" + answer.plainText() + "\n\n"
                    + (sources == null ? "" : sources.plainText() + "\n\n") + disclosure.plainText();
        }
    }
}
