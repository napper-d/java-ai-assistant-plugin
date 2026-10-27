package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.KnowledgeBaseOperations;
import edu.teamc.courseaiassistant.model.KnowledgeChunk;
import edu.teamc.courseaiassistant.rag.RagException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;

/** Raw evidence preview, intentionally independent of ChatAnswer and the sample chat. */
public final class RetrievalPreviewPanel extends JPanel {
    private static final int MAX_RESULTS = 5;
    private final KnowledgeBaseOperations operations;
    private final JTextArea question = new ChatWidgets.Input("Search course materials…");
    private final ConversationView evidence = new ConversationView();
    private final JScrollPane history = ChatWidgets.scrollPane(evidence);
    private final JTextArea status = textArea();
    private final JButton search = new ChatWidgets.Button(true);
    private final JButton cancel = button("Cancel", "cancelEvidence");
    private final JButton clear = new ChatWidgets.Button(false);
    private final JButton settings = button("Settings", "evidenceSettings");
    private final JCheckBox scores = new JCheckBox("Show similarity scores");
    private final JProgressBar progress = new JProgressBar();
    private final Runnable unsubscribeSettings;
    private final Runnable unsubscribeLibrary;
    private CompletableFuture<List<KnowledgeChunk>> pending;
    private record Turn(String query, List<KnowledgeChunk> chunks, String message) {}
    private final List<Turn> turns = new ArrayList<>();
    private String activeQuery;
    private long generation;
    private boolean busy;
    private boolean disposed;

    public RetrievalPreviewPanel(KnowledgeBaseOperations operations, Runnable openSettings) {
        super(new BorderLayout(0, 10));
        this.operations = Objects.requireNonNull(operations);
        setName("retrievalPreviewPanel"); setBorder(new EmptyBorder(12, 14, 12, 14));
        setPreferredSize(new Dimension(440, 760));
        search.setName("searchEvidence"); search.setToolTipText("Search course materials (Enter)");
        search.getAccessibleContext().setAccessibleName("Search course materials");
        clear.setName("clearEvidence"); clear.setToolTipText("Clear retrieval conversation");
        clear.getAccessibleContext().setAccessibleName("Clear retrieval conversation");
        JPanel header = ChatWidgets.transparent(new BorderLayout(0, 5));
        JPanel title = ChatWidgets.transparent(new BorderLayout(8, 0));
        title.add(new ChatWidgets.Label("Course retrieval", 1, true, false), BorderLayout.CENTER);
        title.add(clear, BorderLayout.EAST);
        header.add(title, BorderLayout.NORTH);
        JTextArea explanation = textArea();
        explanation.setText("Original course excerpts · up to 5 per question");
        header.add(explanation, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        evidence.setName("retrievalEvidence");
        evidence.getAccessibleContext().setAccessibleName("Questions and retrieved course evidence");
        history.setMinimumSize(new Dimension(120, 80)); add(history, BorderLayout.CENTER);
        question.setName("retrievalQuestion");
        question.getAccessibleContext().setAccessibleName("Question to search in course materials");
        question.setToolTipText("Enter to search; Shift+Enter for a new line");
        JPanel tools = ChatWidgets.transparent(new BorderLayout(6, 0));
        JPanel actions = ChatWidgets.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(settings); actions.add(cancel);
        tools.add(actions, BorderLayout.CENTER); tools.add(search, BorderLayout.EAST);
        JPanel footer = ChatWidgets.transparent(new BorderLayout(0, 5));
        scores.setOpaque(false); scores.setFont(ChatTheme.font(-1, false));
        scores.setName("retrievalScores"); footer.add(scores, BorderLayout.NORTH);
        status.setName("retrievalStatus"); status.setText("Enter to search · Shift+Enter for a new line");
        footer.add(status, BorderLayout.CENTER);
        progress.setIndeterminate(true); progress.setVisible(false); footer.add(progress, BorderLayout.SOUTH);
        JPanel composer = ChatWidgets.composer(question, tools, footer, null);
        composer.setName("retrievalComposer"); add(composer, BorderLayout.SOUTH);
        render();

        search.addActionListener(event -> submit());
        cancel.addActionListener(event -> cancelSearch());
        clear.addActionListener(event -> {
            question.setText(""); invalidate("Enter a question to inspect up to 5 saved excerpts.");
        });
        settings.addActionListener(event -> openSettings.run());
        scores.addActionListener(event -> render());
        question.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateControls(); }
            @Override public void removeUpdate(DocumentEvent event) { updateControls(); }
            @Override public void changedUpdate(DocumentEvent event) { updateControls(); }
        });
        question.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "searchCourseEvidence");
        question.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "evidenceNewline");
        question.getActionMap().put("searchCourseEvidence", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { submit(); }
        });
        question.getActionMap().put("evidenceNewline", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { question.replaceSelection("\n"); }
        });
        unsubscribeSettings = operations.onSettingsChanged(() -> SwingUtilities.invokeLater(() -> {
            if (!disposed) invalidate("Settings changed. Results cleared; rebuild if the model or chunk settings changed, then search again.");
        }));
        unsubscribeLibrary = operations.onLibraryChanged(() -> SwingUtilities.invokeLater(() -> {
            if (!disposed && (busy || !turns.isEmpty())) {
                invalidate("Course materials or index loaded/changed. Results cleared; rebuild as needed and search again.");
            }
        }));
        updateControls();
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea() {
            @Override public void updateUI() {
                super.updateUI();
                setFont(ChatTheme.font(-1, false)); setForeground(ChatTheme.current().muted());
            }
        };
        area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setOpaque(false); return area;
    }
    private static JButton button(String text, String name) {
        JButton button = new JButton(text); button.setName(name); return button;
    }

    private void submit() {
        if (disposed || busy) return;
        String query = question.getText().strip();
        if (query.isBlank()) { status.setText("Enter a question first."); return; }
        long token = ++generation;
        busy = true; activeQuery = query;
        question.setText(""); status.setText("Searching saved course evidence…");
        render(); scrollToLatest();
        progress.setVisible(true); updateControls();
        try { pending = Objects.requireNonNull(operations.search(query, MAX_RESULTS)); }
        catch (RuntimeException error) { finish(token, query, null, error); return; }
        pending.whenComplete((chunks, error) -> SwingUtilities.invokeLater(() -> finish(token, query, chunks, error)));
    }

    private void finish(long token, String query, List<KnowledgeChunk> chunks, Throwable error) {
        if (disposed || token != generation) return;
        pending = null; busy = false; activeQuery = null; progress.setVisible(false);
        Throwable cause = unwrap(error);
        if (cause instanceof CancellationException) {
            status.setText("Search cancelled. No results from the cancelled request are displayed.");
        } else if (error != null || chunks == null || chunks.stream().anyMatch(Objects::isNull)) {
            status.setText("Search failed. " + (cause instanceof RagException rag ? rag.getMessage()
                    : "Unable to retrieve course evidence. Check settings or the saved index and retry."));
        } else {
            List<KnowledgeChunk> results = List.copyOf(chunks);
            status.setText(results.isEmpty()
                    ? "No relevant course material found in the saved index. No model answer was generated."
                    : results.size() + " excerpt(s) found. Sources refer to the saved index; rebuild after changing course files.");
            turns.add(new Turn(query, results, results.isEmpty() ? status.getText() : null));
        }
        if (error != null || chunks == null || chunks.stream().anyMatch(Objects::isNull)) {
            turns.add(new Turn(query, List.of(), status.getText()));
        }
        render(); scrollToLatest(); question.requestFocusInWindow();
        updateControls(); revalidate(); repaint();
    }

    private void render() {
        evidence.showWelcome("Search your course", "Ask a question below to find original passages in your saved PDF and slide index.\n\nResults are course evidence, not an AI-generated answer. Each question is searched independently.");
        for (Turn turn : turns) {
            evidence.addUserMessage(turn.query(), null);
            evidence.addTextMessage("Course evidence", turn.message() != null ? turn.message() : excerpts(turn.chunks()));
        }
        if (activeQuery != null) {
            evidence.addUserMessage(activeQuery, null);
            evidence.addLoadingMessage("Searching course materials…");
        }
    }

    private String excerpts(List<KnowledgeChunk> results) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            KnowledgeChunk chunk = results.get(index);
            text.append(index + 1).append(". ").append(chunk.getSource()).append('\n');
            if (scores.isSelected()) {
                text.append(String.format(Locale.ROOT, "Query similarity: %.3f (not a probability)\n", chunk.getScore()));
                text.append("Chunk: ").append(chunk.getChunkId()).append('\n');
            }
            text.append(chunk.getText()).append("\n\n");
        }
        return text.toString().stripTrailing();
    }

    private void scrollToLatest() {
        long token = generation;
        SwingUtilities.invokeLater(() -> {
            if (!disposed && token == generation) {
                history.getVerticalScrollBar().setValue(history.getVerticalScrollBar().getMaximum());
            }
        });
    }

    private void cancelSearch() {
        if (!busy) return;
        String query = activeQuery;
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null; busy = false; activeQuery = null;
        progress.setVisible(false);
        String message = "Search cancelled. No results from the cancelled request are displayed.";
        turns.add(new Turn(query, List.of(), message)); status.setText(message);
        render(); scrollToLatest(); updateControls();
        question.requestFocusInWindow();
    }

    private void invalidate(String message) {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null; busy = false; activeQuery = null; turns.clear();
        progress.setVisible(false); render(); status.setText(message);
        updateControls(); revalidate(); repaint();
    }

    public JComponent getPreferredFocusComponent() { return question; }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(ChatTheme.current().canvas());
        graphics.fillRect(0, 0, getWidth(), getHeight());
    }

    private void updateControls() {
        search.setEnabled(!disposed && !busy && !question.getText().isBlank());
        cancel.setEnabled(!disposed && busy); clear.setEnabled(!disposed);
        settings.setEnabled(!disposed && !busy); scores.setEnabled(!disposed);
    }
    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    public void dispose() {
        if (disposed) return;
        disposed = true; unsubscribeSettings.run(); unsubscribeLibrary.run();
        invalidate("Retrieval preview closed.");
    }
}
