package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.sample.SampleReply;
import edu.teamc.courseaiassistant.sample.SampleReplyProvider;
import edu.teamc.courseaiassistant.sample.SampleScenario;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;

/** Local chat view. Never changes IDE theme, editor settings, or source files. */
public final class ChatPanel extends JPanel {
    private final SampleReplyProvider replies;
    private final ConversationView conversation = new ConversationView();
    private final JTextArea question = new ChatWidgets.Input();
    private final JComboBox<SampleScenario> scenario = new ChatWidgets.ExampleSelector<>(SampleScenario.values());
    private final JButton send = new ChatWidgets.Button(true);
    private final JButton clear = new ChatWidgets.Button(false);
    private final JLabel status = new ChatWidgets.Label("Enter to send, Shift+Enter for new line", -1, false, true);
    private final JScrollPane history = ChatWidgets.scrollPane(conversation);
    private final JPanel contextPreviewPanel = new JPanel(new BorderLayout());
    private CodeContext currentContext;

    public ChatPanel(SampleReplyProvider replies) {
        super(new BorderLayout(0, 8));
        this.replies = Objects.requireNonNull(replies);
        setBorder(new EmptyBorder(12, 14, 10, 14));
        setPreferredSize(new Dimension(440, 760));
        add(createHeader(), BorderLayout.NORTH);
        history.setMinimumSize(new Dimension(120, 80));
        add(history, BorderLayout.CENTER);
        add(createComposer(), BorderLayout.SOUTH);

        send.setName("sendButton");
        clear.setName("clearChatButton");
        status.setName("chatStatus");
        question.setName("questionInput");
        question.getAccessibleContext().setAccessibleName("Your question");
        question.setToolTipText("Enter sends. Shift+Enter adds a new line.");
        scenario.setName("scenarioSelector");
        contextPreviewPanel.setName("contextPreview");
        send.addActionListener(event -> submitQuestion());
        clear.addActionListener(event -> clearConversation());
        question.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateSendState(); }
            @Override public void removeUpdate(DocumentEvent event) { updateSendState(); }
            @Override public void changedUpdate(DocumentEvent event) { updateSendState(); }
        });

        question.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendQuestion");
        question.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insertNewline");
        question.getActionMap().put("sendQuestion", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { submitQuestion(); }
        });
        question.getActionMap().put("insertNewline", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                int caretPosition = question.getCaretPosition();
                question.insert("\n", caretPosition);
            }
        });

        clearConversation();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(ChatTheme.current().canvas());
        graphics.fillRect(0, 0, getWidth(), getHeight());
    }

    private JPanel createHeader() {
        JPanel header = ChatWidgets.transparent(new BorderLayout(0, 5));
        JPanel title = ChatWidgets.transparent(new BorderLayout(8, 0));
        title.add(new ChatWidgets.Label("Course AI Assistant", 1, true, false), BorderLayout.CENTER);
        title.add(clear, BorderLayout.EAST);
        header.add(title, BorderLayout.NORTH);
        header.add(new ChatWidgets.Label("Sample mode: local replies only", -1, false, true), BorderLayout.SOUTH);
        header.setBorder(new EmptyBorder(0, 2, 8, 2));
        return header;
    }

    private JPanel createComposer() {
        JPanel composer = ChatWidgets.transparent(new BorderLayout(0, 7));

        contextPreviewPanel.setOpaque(false);
        contextPreviewPanel.setVisible(false);
        contextPreviewPanel.setBorder(new EmptyBorder(0, 0, 4, 0));
        composer.add(contextPreviewPanel, BorderLayout.NORTH);

        JPanel inputCard = new ChatWidgets.RoundedPanel(new BorderLayout(0, 12), false, true);
        inputCard.setBorder(new EmptyBorder(10, 13, 10, 13));
        JScrollPane inputScroll = ChatWidgets.scrollPane(question);
        inputScroll.getViewport().setOpaque(false);
        inputCard.add(inputScroll, BorderLayout.CENTER);

        JPanel tools = ChatWidgets.transparent(new BorderLayout(6, 0));
        JPanel example = ChatWidgets.transparent(new BorderLayout(3, 0));
        JLabel exampleLabel = new ChatWidgets.Label("Example", -1, false, true);
        exampleLabel.setLabelFor(scenario);
        example.add(exampleLabel, BorderLayout.WEST);
        example.add(scenario, BorderLayout.CENTER);
        tools.add(example, BorderLayout.CENTER);
        tools.add(send, BorderLayout.EAST);
        inputCard.add(tools, BorderLayout.SOUTH);
        composer.add(inputCard, BorderLayout.CENTER);
        composer.add(status, BorderLayout.SOUTH);
        return composer;
    }

    private void submitQuestion() {
        String text = question.getText().strip();
        if (text.isBlank()) {
            status.setText("Enter a question first.");
            return;
        }
        CodeContext contextToSend = currentContext;
        clearCodeContext();
        SampleReply reply = replies.reply((SampleScenario) scenario.getSelectedItem());
        conversation.addExchange(text, reply, contextToSend);
        clear.setEnabled(true);
        question.setText("");
        status.setText("Fixed sample reply. No model connected.");
        question.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> {
            if (isDisplayable() && conversation.hasMessages()) {
                history.getVerticalScrollBar().setValue(history.getVerticalScrollBar().getMaximum());
            }
        });
    }

    private void updateSendState() {
        send.setEnabled(!question.getText().isBlank());
    }

    private void clearConversation() {
        conversation.showWelcome();
        question.setText("");
        clear.setEnabled(false);
        status.setText("Enter to send, Shift+Enter for new line");
        history.getVerticalScrollBar().setValue(0);
        updateSendState();
        clearCodeContext();
    }

    /**
     * Attaches code context from the editor selection and displays a preview.
     * Called by the right-click action when the user selects code and invokes the assistant.
     */
    public void attachCodeContext(CodeContext context) {
        this.currentContext = Objects.requireNonNull(context);
        updateContextPreview();
        question.requestFocusInWindow();
    }

    private void clearCodeContext() {
        currentContext = null;
        contextPreviewPanel.removeAll();
        contextPreviewPanel.setVisible(false);
        contextPreviewPanel.revalidate();
        contextPreviewPanel.repaint();
    }

    private void updateContextPreview() {
        contextPreviewPanel.removeAll();
        if (currentContext == null) {
            contextPreviewPanel.setVisible(false);
            return;
        }

        JPanel card = new ChatWidgets.RoundedPanel(new BorderLayout(0, 4), false, true);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));

        JLabel header = new ChatWidgets.Label("Code context attached", -1, true, false);
        card.add(header, BorderLayout.NORTH);

        String path = currentContext.getProjectRelativePath();
        String language = currentContext.getLanguageId();
        int startLine = currentContext.getStartLine();
        int endLine = currentContext.getEndLine();
        String meta = String.format("%s  •  %s  •  lines %d–%d", path, language, startLine, endLine);
        JLabel metaLabel = new ChatWidgets.Label(meta, -1, false, true);
        card.add(metaLabel, BorderLayout.SOUTH);

        String preview = currentContext.getSelectedText();
        if (preview.length() > 200) {
            preview = preview.substring(0, 200) + "…";
        }
        JTextArea codePreview = new ChatWidgets.Input();
        codePreview.setText(preview);
        codePreview.setEditable(false);
        codePreview.setRows(Math.min(6, preview.split("\n").length));
        codePreview.setFont(codePreview.getFont().deriveFont(Font.PLAIN, 12f));
        JScrollPane scroll = ChatWidgets.scrollPane(codePreview);
        scroll.getViewport().setOpaque(false);
        card.add(scroll, BorderLayout.CENTER);

        JButton removeButton = new ChatWidgets.Button(false);
        removeButton.setText("✕");
        removeButton.setToolTipText("Remove code context");
        removeButton.setMargin(new Insets(2, 6, 2, 6));
        removeButton.addActionListener(e -> clearCodeContext());
        card.add(removeButton, BorderLayout.EAST);

        contextPreviewPanel.add(card);
        contextPreviewPanel.setVisible(true);
        contextPreviewPanel.revalidate();
        contextPreviewPanel.repaint();
    }

    public JComponent getPreferredFocusComponent() {
        return question;
    }
}
