package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.ChatAnswer;
import edu.teamc.courseaiassistant.model.SourceReference;
import edu.teamc.courseaiassistant.sample.SampleReply;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
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

    void addExchange(String question, ChatAnswer answer, CodeContext context) {
        if (!hasMessages) removeAll();
        hasMessages = true;
        add(new UserMessage(question, context));
        add(new AssistantMessage(answer));
        revalidate();
        repaint();
    }

    void addUserMessage(String question, CodeContext context) {
        if (!hasMessages) removeAll();
        hasMessages = true;
        add(new UserMessage(question, context));
        revalidate();
        repaint();
    }

    void addAssistantMessage(ChatAnswer answer) {
        add(new AssistantMessage(answer));
        revalidate();
        repaint();
    }

    void addLoadingMessage() {
        add(new LoadingMessage());
        revalidate();
        repaint();
    }

    void removeLoadingMessage() {
        for (int i = getComponentCount() - 1; i >= 0; i--) {
            if (getComponent(i) instanceof LoadingMessage) {
                remove(i);
                revalidate();
                repaint();
                return;
            }
        }
    }

    void addErrorMessage(String message, boolean retryable) {
        add(new ErrorMessage(message, retryable));
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
        private final ChatWidgets.Label sample;
        private final ChatWidgets.Text answer;
        private final ChatWidgets.Text disclosure;
        private final ChatWidgets.Text sources;
        private final JPanel sourceCard;

        AssistantMessage(SampleReply reply) {
            this(reply.answer(), reply.disclosure(), toSourceReferences(reply.sources()), true);
        }

        AssistantMessage(ChatAnswer answer) {
            this(
                    answer.getAnswer(),
                    answer.getDisclosure() != null ? answer.getDisclosure() : "",
                    answer.getSources(),
                    false
            );
        }

        private AssistantMessage(String answerText, String disclosureText, List<SourceReference> sources, boolean isSample) {
            this.sample = isSample ? new ChatWidgets.Label("Fixed sample", -1, false, true) : null;
            this.answer = new ChatWidgets.Text(answerText, false, 0);
            this.disclosure = disclosureText.isEmpty() ? null : new ChatWidgets.Text(disclosureText, true, -1);

            add(label);
            if (sample != null) add(sample);
            add(this.answer);
            if (disclosure != null) add(disclosure);

            if (sources.isEmpty()) {
                this.sources = null;
                this.sourceCard = null;
            } else {
                StringBuilder referenceText = new StringBuilder(isSample ? "Example sources (invented)\n" : "Sources\n");
                for (int i = 0; i < sources.size(); i++) {
                    SourceReference source = sources.get(i);
                    referenceText.append(source.toString()).append('\n');
                }
                this.sources = new ChatWidgets.Text(referenceText.toString().stripTrailing(), false, -1);
                this.sourceCard = new ChatWidgets.RoundedPanel(new BorderLayout(), false, true);
                this.sourceCard.setBorder(new EmptyBorder(10, 12, 10, 12));
                this.sourceCard.add(this.sources, BorderLayout.CENTER);
                add(this.sourceCard);
            }
        }

        private static List<SourceReference> toSourceReferences(List<SampleReply.Source> sampleSources) {
            return sampleSources.stream()
                    .map(s -> new SourceReference("S" + (sampleSources.indexOf(s) + 1), s.documentName(), s.page(), s.page(), null))
                    .collect(Collectors.toList());
        }

        @Override int heightAt(int width) {
            int height = (sample != null ? 40 : 24) + answer.heightAt(width) + 18;
            if (sources != null) height += sources.heightAt(Math.max(1, width - 24)) + 20 + 14;
            if (disclosure != null) height += disclosure.heightAt(width);
            return height;
        }
        @Override public void doLayout() {
            int width = getWidth();
            label.setBounds(0, 0, Math.max(1, width - 100), 24);
            if (sample != null) sample.setBounds(Math.max(0, width - 95), 0, Math.min(95, width), 24);
            int answerHeight = answer.heightAt(width);
            answer.setBounds(0, sample != null ? 40 : 24, width, answerHeight);
            int y = (sample != null ? 40 : 24) + answerHeight + 18;
            if (sources != null) {
                int sourceHeight = sources.heightAt(Math.max(1, width - 24)) + 20;
                sourceCard.setBounds(0, y, width, sourceHeight);
                sourceCard.doLayout();
                y += sourceHeight + 14;
            }
            if (disclosure != null) disclosure.setBounds(0, y, width, disclosure.heightAt(width));
        }
        @Override String plainText() {
            String result = sample != null ? "Course AI (fixed sample)\n" : "Course AI\n";
            result += answer.plainText();
            if (sources != null) result += "\n\n" + sources.plainText();
            if (disclosure != null) result += "\n\n" + disclosure.plainText();
            return result;
        }
    }

    private static final class LoadingMessage extends Message {
        private final ChatWidgets.Label label = new ChatWidgets.Label("Thinking…", -1, false, true);

        LoadingMessage() {
            add(label);
            getAccessibleContext().setAccessibleName("Loading");
        }

        @Override int heightAt(int width) {
            return label.getPreferredSize().height + 20;
        }

        @Override public void doLayout() {
            label.setBounds(0, 10, getWidth(), label.getPreferredSize().height);
        }

        @Override String plainText() {
            return "Loading…";
        }
    }

    private static final class ErrorMessage extends Message {
        private final ChatWidgets.Label label;
        private final JPanel bubble = new ChatWidgets.RoundedPanel(new BorderLayout(), false, true);

        ErrorMessage(String message, boolean retryable) {
            String text = "⚠ " + message;
            if (retryable) text += " (You can retry)";
            label = new ChatWidgets.Label(text, -1, false, false);
            bubble.setBorder(new EmptyBorder(10, 12, 10, 12));
            bubble.add(label, BorderLayout.CENTER);
            add(bubble);
            getAccessibleContext().setAccessibleName("Error message");
        }

        @Override int heightAt(int width) {
            label.setSize(width - 24, Short.MAX_VALUE);
            return label.getPreferredSize().height + 20;
        }

        @Override public void doLayout() {
            bubble.setBounds(0, 0, getWidth(), getHeight());
            label.setBounds(12, 10, getWidth() - 24, label.getPreferredSize().height);
        }

        @Override String plainText() {
            return label.getText();
        }
    }
}
