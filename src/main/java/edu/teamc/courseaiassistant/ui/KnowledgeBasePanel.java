package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.KnowledgeBaseOperations;
import edu.teamc.courseaiassistant.application.KnowledgeBaseSnapshot;
import edu.teamc.courseaiassistant.application.MaterialRegistrationResult;
import edu.teamc.courseaiassistant.rag.DocumentImporter.RegisteredDocument;
import edu.teamc.courseaiassistant.rag.RagException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Material management only. It never formats an LLM answer or prompts for credentials. */
public final class KnowledgeBasePanel extends JPanel {
    private final KnowledgeBaseOperations operations;
    private final Supplier<List<Path>> chooseFiles;
    private final Runnable openSettings;
    private final Consumer<String> showRegistrationNotice;
    private final JButton add = button("Add PDF / PPTX", "addMaterials");
    private final JButton remove = button("Remove", "removeMaterials");
    private final JButton rebuild = button("Rebuild index", "rebuildIndex");
    private final JButton refresh = button("Refresh / Load", "refreshMaterials");
    private final JButton cancel = button("Cancel", "cancelLibraryTask");
    private final JButton settings = button("Settings", "ragSettings");
    private final JTextArea status = messageArea();
    private final JTextArea detail = messageArea();
    private final JTextArea feedback = messageArea();
    private final JProgressBar progress = new JProgressBar();
    private final MaterialsTable model = new MaterialsTable();
    private final JTable files = new JTable(model) {
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
            return row < 0 || column < 0 ? null : String.valueOf(getValueAt(row, column));
        }
    };
    private final Runnable unsubscribe;
    private KnowledgeBaseSnapshot snapshot;
    private CompletableFuture<?> pending;
    private long generation;
    private boolean busy;
    private boolean disposed;

    public KnowledgeBasePanel(KnowledgeBaseOperations operations, Supplier<List<Path>> chooseFiles, Runnable openSettings,
                             Consumer<String> showRegistrationNotice) {
        super(new BorderLayout(0, 10));
        this.operations = Objects.requireNonNull(operations);
        this.chooseFiles = Objects.requireNonNull(chooseFiles);
        this.openSettings = Objects.requireNonNull(openSettings);
        this.showRegistrationNotice = Objects.requireNonNull(showRegistrationNotice);
        setName("knowledgeBasePanel"); setBorder(new EmptyBorder(12, 14, 12, 14));
        JPanel header = new JPanel(new BorderLayout(0, 6));
        JLabel title = new JLabel("Course materials"); title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title, BorderLayout.NORTH);
        JPanel toolbar = new JPanel(new GridLayout(0, 2, 5, 5));
        for (JButton button : List.of(add, remove, refresh, rebuild, settings)) toolbar.add(button);
        remove.setToolTipText("Remove selected registrations; keep original files and the saved index");
        refresh.setToolTipText("Refresh registrations and retry loading the saved index");
        header.add(toolbar, BorderLayout.CENTER);
        JTextArea policy = messageArea();
        policy.setText("Adding registers original paths. Rebuilding reads all registered files and calls your embedding service. Removing a registration keeps the original file and saved index.");
        header.add(policy, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        files.setName("registeredMaterials"); files.setFillsViewportHeight(true);
        files.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        files.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        files.getColumnModel().getColumn(0).setPreferredWidth(120);
        files.getColumnModel().getColumn(1).setPreferredWidth(260);
        files.getColumnModel().getColumn(2).setPreferredWidth(100);
        files.getColumnModel().getColumn(0).setMinWidth(80);
        files.getColumnModel().getColumn(1).setMinWidth(100);
        files.getColumnModel().getColumn(2).setMinWidth(80);
        files.setRowHeight(Math.max(24, files.getRowHeight()));
        JScrollPane list = new JScrollPane(files);
        list.setColumnHeaderView(files.getTableHeader());
        list.setPreferredSize(new Dimension(400, 250));
        add(list, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 6));
        JPanel information = new JPanel(new GridBagLayout());
        status.setName("libraryStatus"); detail.setName("libraryDetail"); feedback.setName("libraryFeedback");
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0; row.weightx = 1; row.fill = GridBagConstraints.HORIZONTAL;
        row.anchor = GridBagConstraints.NORTHWEST; row.insets = new Insets(0, 0, 5, 0);
        for (JTextArea area : List.of(status, detail, feedback)) {
            row.gridy++; information.add(area, row);
        }
        footer.add(information, BorderLayout.CENTER);
        JPanel activity = new JPanel(new BorderLayout(8, 0));
        progress.setIndeterminate(true); progress.setVisible(false);
        activity.add(progress, BorderLayout.CENTER); activity.add(cancel, BorderLayout.EAST);
        footer.add(activity, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        add.addActionListener(event -> addFiles());
        remove.addActionListener(event -> removeSelected());
        refresh.addActionListener(event -> refresh());
        rebuild.addActionListener(event -> run("Rebuilding index…", "Index rebuilt and loaded.", operations::rebuildLibrary, true));
        settings.addActionListener(event -> this.openSettings.run());
        cancel.addActionListener(event -> cancelTask());
        files.getSelectionModel().addListSelectionListener(event -> updateControls());
        unsubscribe = operations.onSettingsChanged(() -> SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            generation++;
            if (pending != null) pending.cancel(true);
            pending = null; busy = false;
            snapshot = null;
            feedback.setText("Settings changed. Reloading the saved index with the new configuration…");
            refresh();
        }));
        refresh();
    }

    private static JButton button(String text, String name) {
        JButton button = new JButton(text); button.setName(name); return button;
    }
    private static JTextArea messageArea() {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setOpaque(false); area.setFocusable(true); area.setFont(UIManager.getFont("Label.font"));
        return area;
    }

    private void addFiles() {
        if (busy || disposed) return;
        List<Path> selected = chooseFiles.get();
        if (selected == null || selected.isEmpty()) return;
        List<Path> captured = List.copyOf(selected);
        run("Registering course files…", "Files registered. Rebuild to update the saved index.",
                () -> operations.registerMaterials(captured), MaterialRegistrationResult::snapshot,
                this::registrationCompleted, true);
    }

    private void registrationCompleted(MaterialRegistrationResult result) {
        if (result.alreadyRegistered().isEmpty()) return;
        String summary = result.addedFiles() == 0
                ? "All selected files are already registered. No files were added."
                : result.addedFiles() + (result.addedFiles() == 1 ? " new file registered." : " new files registered.")
                    + " Already registered files were skipped. Rebuild to update the saved index.";
        feedback.setText(summary + (result.snapshot().indexState() == KnowledgeBaseSnapshot.IndexState.RELOAD_REQUIRED
                ? "\nIndex saved; loading failed. Use Refresh / Load to retry." : ""));
        showRegistrationNotice.accept(summary + "\n\nAlready registered:\n"
                + String.join("\n", result.alreadyRegistered().stream().map(Path::toString).toList()));
    }

    private void removeSelected() {
        if (busy || disposed || files.getSelectedRowCount() == 0) return;
        List<Path> selected = java.util.Arrays.stream(files.getSelectedRows())
                .mapToObj(row -> model.documents.get(files.convertRowIndexToModel(row)).path()).toList();
        run("Removing registrations…", "Registrations removed. Original files and the old index are retained.",
                () -> operations.unregisterMaterials(selected), true);
    }

    private void refresh() { run("Loading saved index and registrations…", null, operations::refreshLibrary, false); }

    private void run(String activity, String completed, Supplier<CompletableFuture<KnowledgeBaseSnapshot>> work, boolean recover) {
        run(activity, completed, work, Function.identity(), value -> {}, recover);
    }

    private <T> void run(String activity, String completed, Supplier<CompletableFuture<T>> work,
                         Function<T, KnowledgeBaseSnapshot> snapshotOf, Consumer<T> onSuccess, boolean recover) {
        if (disposed || busy) return;
        long token = ++generation;
        busy = true; progress.setVisible(true); status.setText(activity); updateControls();
        CompletableFuture<T> future;
        try { future = Objects.requireNonNull(work.get()); pending = future; }
        catch (RuntimeException error) { finish(token, null, error, completed, recover); return; }
        future.whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            if (disposed || token != generation) return;
            finish(token, value == null ? null : snapshotOf.apply(value), error, completed, recover);
            if (error == null && value != null && !disposed && token == generation) onSuccess.accept(value);
        }));
    }

    private void finish(long token, KnowledgeBaseSnapshot value, Throwable error, String completed, boolean recover) {
        if (disposed || token != generation) return;
        busy = false; pending = null; progress.setVisible(false);
        Throwable cause = unwrap(error);
        if (error == null && value != null) {
            snapshot = value; model.documents = value.documents(); model.fireTableDataChanged();
            showSnapshot();
            if (completed != null) feedback.setText(value.indexState() == KnowledgeBaseSnapshot.IndexState.RELOAD_REQUIRED
                    ? "Index saved; loading failed. Use Refresh / Load to retry." : completed);
        } else {
            status.setText(cause instanceof CancellationException ? "Operation cancelled" : "Operation failed");
            feedback.setText(cause instanceof CancellationException
                    ? "Cancelled. Earlier registrations or an already published index may remain; refresh to inspect them."
                    : cause instanceof RagException rag ? rag.getMessage() : "Unable to complete the operation. Refresh and try again.");
            if (!(cause instanceof CancellationException) && recover) {
                // A batch may have registered some files before it failed. Always reread actual state.
                refresh();
            }
        }
        updateControls(); revalidate(); repaint();
    }

    private void showSnapshot() {
        String state = switch (snapshot.indexState()) {
            case NO_INDEX -> "No saved index";
            case AVAILABLE -> "Saved index available";
            case NEEDS_REBUILD -> "Materials changed — rebuild required";
            case INCOMPATIBLE -> "Index settings changed — rebuild required";
            case INDEX_ERROR -> "Saved index could not be loaded";
            case RELOAD_REQUIRED -> "Index saved — loading needs retry";
        };
        status.setText(state);
        detail.setText(snapshot.documents().size() + " registered files · " + snapshot.indexedDocuments()
                + " indexed documents · " + snapshot.chunks() + " chunks\n" + snapshot.detail()
                + (snapshot.embeddingConfigured() ? "" : "\nSet an embedding API URL and model in Settings before rebuilding. An API key is also required."));
    }

    private void cancelTask() {
        if (!busy || pending == null) return;
        pending.cancel(true);
        // Invalidate even a success already queued on the EDT, then inspect actual disk state.
        generation++; pending = null; busy = false; progress.setVisible(false);
        feedback.setText("Cancelled. A completed registration or published index may remain; checking saved state…");
        refresh();
    }

    private void updateControls() {
        add.setEnabled(!disposed && !busy);
        remove.setEnabled(!disposed && !busy && files.getSelectedRowCount() > 0);
        refresh.setEnabled(!disposed && !busy);
        rebuild.setEnabled(!disposed && !busy && snapshot != null && snapshot.embeddingConfigured() && !snapshot.documents().isEmpty());
        settings.setEnabled(!disposed && !busy);
        cancel.setEnabled(!disposed && busy);
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }

    public void dispose() {
        disposed = true; generation++; unsubscribe.run();
        if (pending != null) pending.cancel(true);
        pending = null; busy = false;
    }

    private static final class MaterialsTable extends AbstractTableModel {
        private List<RegisteredDocument> documents = List.of();
        private final String[] names = {"Document", "Original path", "File status"};
        @Override public int getRowCount() { return documents.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int column) { return names[column]; }
        @Override public Object getValueAt(int row, int column) {
            RegisteredDocument document = documents.get(row);
            return switch (column) {
                case 0 -> document.documentName();
                case 1 -> document.path().toString();
                default -> switch (document.availability()) {
                    case AVAILABLE -> "Available";
                    case MISSING -> "Missing";
                    case UNREADABLE -> "Unreadable";
                };
            };
        }
    }
}
