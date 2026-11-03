package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.ChatAnswer;
import edu.teamc.courseaiassistant.model.SourceReference;

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
        showWelcome("Ask about Java", "Ask a question to try the chat layout.\n\nChoose a local example below. "
                + "Replies are fixed samples, not generated answers.");
    }

    void showWelcome(String title, String description) {
        removeAll();
        hasMessages = false;
        add(new Welcome(title, description));
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

    void addAssistantMessage(ChatAnswer answer, boolean isSample) {
        add(new AssistantMessage(answer, isSample));
        revalidate();
        repaint();
    }

    void addLoadingMessage() {
        addLoadingMessage("Thinking…");
    }

    void addLoadingMessage(String label) {
        add(new LoadingMessage(label));
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
        return new Dimension(width, !hasMessages && getParent() instanceof JViewport viewport
                ? Math.max(height, viewport.getExtentSize().height) : height);
    }

    @Override public void doLayout() {
        int width = Math.max(1, getWidth() - 8);
        int y = !hasMessages && getComponentCount() == 1
                ? Math.max(12, (getHeight() - ((Message) getComponent(0)).heightAt(width)) / 2) : 12;
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
        private final ChatWidgets.Label title;
        private final java.util.List<ChatWidgets.Text> lines = new java.util.ArrayList<>();
        Welcome(String title, String description) {
            this.title = new ChatWidgets.Label(title, 10, true, false);
            this.title.setHorizontalAlignment(SwingConstants.CENTER);
            add(this.title);
            for (String line : description.split("\\n\\s*\\n")) {
                ChatWidgets.Text text = new ChatWidgets.Text(line.replace("✓  ", ""), lines.isEmpty(), lines.isEmpty() ? -1 : 0);
                var centered = new javax.swing.text.SimpleAttributeSet();
                javax.swing.text.StyleConstants.setAlignment(centered, javax.swing.text.StyleConstants.ALIGN_CENTER);
                text.getStyledDocument().setParagraphAttributes(0,text.getDocument().getLength(),centered,false);
                lines.add(text); add(text);
            }
        }
        @Override int heightAt(int width) {
            int inner = Math.max(1, Math.min(340, width-32));
            int height = 86;
            for (ChatWidgets.Text line : lines) height += line.heightAt(inner)+14;
            return height;
        }
        @Override public void doLayout() {
            int width = Math.max(1, Math.min(340,getWidth()-32)), x=(getWidth()-width)/2;
            title.setBounds(x,20,width,42);
            int y=82;
            for (int i=0; i<lines.size(); i++) {
                ChatWidgets.Text line=lines.get(i); int height=line.heightAt(width);
                line.setBounds(x,y,width,height); y+=height+(i==0?24:12);
            }
        }
        @Override String plainText() { return title.getText()+"\n"+lines.stream().map(ChatWidgets.Text::plainText).collect(Collectors.joining("\n")); }
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
            int natural = text.plainText().lines().mapToInt(metrics::stringWidth).max().orElse(0) + 38;
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
        private final ChatWidgets.Label label;
        private final MarkdownView answer;
        private final ChatWidgets.Text disclosure;
        private final JPanel answerCard = new ChatWidgets.RoundedPanel(null, false, true);
        private final JPanel sourceCard = new ChatWidgets.RoundedPanel(null, false, true);
        private final ChatWidgets.Label sourceTitle = new ChatWidgets.Label("Sources", -1, true, false);
        private final java.util.List<JButton> references = new java.util.ArrayList<>();
        private final java.util.List<ChatWidgets.Text> details = new java.util.ArrayList<>();
        AssistantMessage(ChatAnswer result, boolean sample) {
            label = new ChatWidgets.Label(sample ? "Course AI (fixed sample) · Example sources (invented)" : "Course AI", 0, true, false);
            String text = result.getAnswer();
            for (int i = 0; i < result.getSources().size(); i++)
                text = text.replace("[" + result.getSources().get(i).getReferenceId() + "]", "[" + (i + 1) + "]");
            answer = new MarkdownView(text);
            disclosure = result.getDisclosure() == null ? null : new ChatWidgets.Text(result.getDisclosure(), true, -1);
            add(label); add(answerCard); answerCard.setName("answerCard"); answerCard.add(answer);
            if (disclosure != null) answerCard.add(disclosure);
            sourceCard.setName("sourcesCard"); sourceCard.add(sourceTitle);
            for (int i = 0; i < result.getSources().size(); i++) {
                SourceReference source = result.getSources().get(i);
                String position = source.getPageStart() == null ? source.getScope() : "page " + source.getPageStart()
                        + (java.util.Objects.equals(source.getPageStart(), source.getPageEnd()) || source.getPageEnd() == null ? "" : "–" + source.getPageEnd());
                JButton reference = new JButton("[" + (i + 1) + "] " + source.getDocumentName() + " · " + position) {
                    @Override public void updateUI() {
                        super.updateUI(); setFont(ChatTheme.font(-1, false)); setForeground(ChatTheme.current().text());
                    }
                };
                reference.setName("citation" + (i + 1)); reference.setHorizontalAlignment(SwingConstants.LEFT);
                reference.setToolTipText(source.getDocumentName() + " · " + position + " — show or hide original evidence");
                reference.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                reference.setContentAreaFilled(false); reference.setBorder(new EmptyBorder(4, 0, 4, 0));
                reference.setFont(ChatTheme.font(-1, false)); reference.setForeground(ChatTheme.current().text());
                String excerpts = result.getEvidence().stream()
                        .filter(chunk -> chunk.getSource().getReferenceId().equals(source.getReferenceId()))
                        .map(chunk -> chunk.getText() + "\n\nChunk: " + chunk.getChunkId()
                                + "\nRetrieval score: " + String.format(java.util.Locale.ROOT, "%.3f", chunk.getScore()))
                        .collect(Collectors.joining("\n\n"));
                ChatWidgets.Text detail = new ChatWidgets.Text(excerpts.isEmpty() ? "No excerpt is attached to this answer." : excerpts, true, -1);
                detail.setName("citationDetail" + (i + 1)); detail.setVisible(false);
                reference.addActionListener(event -> {
                    detail.setVisible(!detail.isVisible());
                    Container transcript = getParent();
                    if (transcript != null) { transcript.revalidate(); transcript.repaint(); }
                });
                references.add(reference); details.add(detail); sourceCard.add(reference); sourceCard.add(detail);
            }
            if (!references.isEmpty()) add(sourceCard);
        }
        private int answerHeight(int width) {
            return 28+answer.heightAt(Math.max(1,width-32))+(disclosure==null?0:14+disclosure.heightAt(Math.max(1,width-32)));
        }
        private int sourcesHeight(int width) {
            int height=38;
            for (int i=0;i<references.size();i++) {
                height+=32;
                if(details.get(i).isVisible()) height+=details.get(i).heightAt(Math.max(1,width-32))+14;
            }
            return height+10;
        }
        @Override int heightAt(int width) {
            return 32+answerHeight(width)+(references.isEmpty()?0:12+sourcesHeight(width));
        }
        @Override public void doLayout() {
            int width=getWidth(), inner=Math.max(1,width-32);
            label.setBounds(0,0,width,24);
            int bodyHeight=answerHeight(width); answerCard.setBounds(0,32,width,bodyHeight);
            int textHeight=answer.heightAt(inner); answer.setBounds(16,14,inner,textHeight);
            if(disclosure!=null) disclosure.setBounds(16,28+textHeight,inner,disclosure.heightAt(inner));
            if(!references.isEmpty()) {
                sourceCard.setBounds(0,44+bodyHeight,width,sourcesHeight(width)); sourceTitle.setBounds(16,10,inner,22);
                int y=38;
                for(int i=0;i<references.size();i++) {
                    references.get(i).setBounds(16,y,inner,32); y+=32;
                    ChatWidgets.Text detail=details.get(i);
                    if(detail.isVisible()) { int height=detail.heightAt(inner); detail.setBounds(16,y,inner,height); y+=height+14; }
                }
            }
        }
        @Override String plainText() {
            StringBuilder text = new StringBuilder(label.getText()).append("\n").append(answer.plainText());
            for (int i = 0; i < references.size(); i++) {
                text.append("\n").append(references.get(i).getText());
                if (details.get(i).isVisible()) text.append("\n").append(details.get(i).plainText());
            }
            if (disclosure != null) text.append("\n").append(disclosure.plainText());
            return text.toString();
        }
    }

    private static final class LoadingMessage extends Message {
        private final ChatWidgets.Label label;

        LoadingMessage(String text) {
            label = new ChatWidgets.Label(text, -1, false, true);
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
            return label.getText();
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
