package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.RequestCoordinator;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.ChatAnswer;
import edu.teamc.courseaiassistant.model.ChatRequest;
import edu.teamc.courseaiassistant.model.ServiceFailure;
import edu.teamc.courseaiassistant.sample.SampleScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.function.Consumer;

/** Local chat view. Never changes IDE theme, editor settings, or source files. */
public final class ChatPanel extends JPanel {
    private final RequestCoordinator coordinator;
    private final @Nullable Consumer<SampleScenario> configureSample;
    private final boolean sampleMode;
    private final ConversationView conversation = new ConversationView();
    private final JTextArea question = new ChatWidgets.Input();
    private final JComboBox<SampleScenario> scenario = new ChatWidgets.ExampleSelector<>(SampleScenario.values());
    private final JButton send = new ChatWidgets.Button(true);
    private final JButton clear = new ChatWidgets.Button(false);
    private final JLabel status = new ChatWidgets.Label("Enter to send, Shift+Enter for new line", -1, false, true);
    private final JScrollPane history = ChatWidgets.scrollPane(conversation);
    private final JPanel contextPreviewPanel = new JPanel(new BorderLayout());
    private CodeContext currentContext;
    private String currentRequestId;
    private boolean isWaitingForResponse;
    private boolean disposed;

    public ChatPanel(RequestCoordinator coordinator) {
        this(coordinator, null);
    }

    /** The optional selector configures an asynchronous sample adapter before each request. */
    public ChatPanel(RequestCoordinator coordinator, @Nullable Consumer<SampleScenario> configureSample) {
        super(new BorderLayout(0, 8));
        this.coordinator = Objects.requireNonNull(coordinator);
        this.configureSample = configureSample;
        this.sampleMode = configureSample != null;
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
                question.replaceSelection("\n");
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
        String modeText = sampleMode ? "Sample mode: no model or course retrieval connected" : "Live mode";
        JLabel mode = new ChatWidgets.Label(modeText, -1, false, true);
        mode.setName("replyMode");
        header.add(mode, BorderLayout.SOUTH);
        header.setBorder(new EmptyBorder(0, 2, 8, 2));
        return header;
    }

    private JPanel createComposer() {
        contextPreviewPanel.setOpaque(false);
        contextPreviewPanel.setVisible(false);
        contextPreviewPanel.setBorder(new EmptyBorder(0, 0, 4, 0));
        JPanel tools = ChatWidgets.transparent(new BorderLayout(6, 0));
        JPanel example = ChatWidgets.transparent(new BorderLayout(3, 0));
        JLabel exampleLabel = new ChatWidgets.Label("Example", -1, false, true);
        exampleLabel.setLabelFor(scenario);
        example.add(exampleLabel, BorderLayout.WEST);
        example.add(scenario, BorderLayout.CENTER);
        if (sampleMode) tools.add(example, BorderLayout.CENTER);
        tools.add(send, BorderLayout.EAST);
        return ChatWidgets.composer(question, tools, status, contextPreviewPanel);
    }

    private void submitQuestion() {
        if (disposed) return;
        if (isWaitingForResponse) {
            status.setText("Already waiting for a response…");
            return;
        }

        String text = question.getText().strip();
        if (text.isBlank()) {
            status.setText("Enter a question first.");
            return;
        }

        CodeContext contextToSend = currentContext;
        clearCodeContext();
        question.setText("");

        if (configureSample != null) configureSample.accept((SampleScenario) scenario.getSelectedItem());
        submitAsyncRequest(text, contextToSend);
    }

    private void submitAsyncRequest(String text, CodeContext context) {
        ChatRequest request = new ChatRequest(text, context);
        currentRequestId = request.getRequestId();
        isWaitingForResponse = true;

        conversation.addUserMessage(text, context);
        showLoadingIndicator();
        clear.setEnabled(true);
        updateSendState();
        scenario.setEnabled(false);
        status.setText("Waiting for response…");

        coordinator.submitChatRequest(request, new RequestCoordinator.ChatCallback() {
            @Override
            public void onRequestStarted(@NotNull String requestId) {
            }

            @Override
            public void onRequestCompleted(@NotNull ChatAnswer answer) {
                if (isCurrentRequest(request.getRequestId())) handleResponseCompleted(answer);
            }

            @Override
            public void onRequestFailed(@NotNull ServiceFailure failure) {
                if (isCurrentRequest(request.getRequestId())) handleResponseFailed(failure);
            }

            @Override
            public void onRequestCancelled(@NotNull String requestId) {
                if (isCurrentRequest(requestId)) handleResponseCancelled();
            }
        });
    }

    private void handleResponseCompleted(ChatAnswer answer) {
        hideLoadingIndicator();
        conversation.addAssistantMessage(answer, sampleMode);
        isWaitingForResponse = false;
        currentRequestId = null;
        scenario.setEnabled(true);
        clear.setEnabled(true);
        updateSendState();
        status.setText(sampleMode ? "Fixed sample reply. No model connected." : "Response received.");
        question.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> {
            if (isDisplayable() && conversation.hasMessages()) {
                history.getVerticalScrollBar().setValue(history.getVerticalScrollBar().getMaximum());
            }
        });
    }

    private void handleResponseFailed(ServiceFailure failure) {
        hideLoadingIndicator();
        conversation.addErrorMessage(failure.getMessage(), failure.isRetryable());
        isWaitingForResponse = false;
        currentRequestId = null;
        scenario.setEnabled(true);
        clear.setEnabled(true);
        updateSendState();
        status.setText("Request failed. " + (failure.isRetryable() ? "You can retry." : ""));
        question.requestFocusInWindow();
    }

    private void handleResponseCancelled() {
        hideLoadingIndicator();
        isWaitingForResponse = false;
        currentRequestId = null;
        scenario.setEnabled(true);
        clear.setEnabled(true);
        updateSendState();
        status.setText("Request cancelled.");
        question.requestFocusInWindow();
    }

    private boolean isCurrentRequest(String requestId) {
        return !disposed && isWaitingForResponse && requestId.equals(currentRequestId);
    }

    private void showLoadingIndicator() {
        conversation.addLoadingMessage();
    }

    private void hideLoadingIndicator() {
        conversation.removeLoadingMessage();
    }

    private void updateSendState() {
        send.setEnabled(!disposed && !isWaitingForResponse && !question.getText().isBlank());
    }

    private void clearConversation() {
        String requestToCancel = currentRequestId;
        // Invalidate the UI request before cancellation can enqueue any callback.
        currentRequestId = null;
        isWaitingForResponse = false;
        if (requestToCancel != null) coordinator.cancelRequest(requestToCancel);
        conversation.showWelcome();
        question.setText("");
        clear.setEnabled(false);
        status.setText("Enter to send, Shift+Enter for new line");
        history.getVerticalScrollBar().setValue(0);
        updateSendState();
        clearCodeContext();
        scenario.setEnabled(true);
        hideLoadingIndicator();
    }

    /**
     * Attaches code context from the editor selection and displays a preview.
     * Called by the right-click action when the user selects code and invokes the assistant.
     */
    public void attachCodeContext(CodeContext context) {
        if (disposed) return;
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

    /** Called when the Tool Window content is disposed, rather than merely hidden. */
    public void dispose() {
        disposed = true;
        currentRequestId = null;
        isWaitingForResponse = false;
        coordinator.dispose();
    }
}
