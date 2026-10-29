package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.application.ProjectRagService;
import edu.teamc.courseaiassistant.rag.RagException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Project settings only; no credentials are read back into the form and no API request is made. */
public final class RagSettingsConfigurable implements SearchableConfigurable {
    private final Project project;
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private JPanel root;
    private JPasswordField password;
    private JCheckBox removePassword;
    private JCheckBox notes;

    public RagSettingsConfigurable(Project project) { this.project = project; }

    @Override public @NotNull String getId() { return "edu.teamc.courseaiassistant.rag"; }
    @Override public String getDisplayName() { return "Course RAG"; }

    @Override public JComponent createComponent() {
        if (root != null) return root;
        JPanel form = SettingsForm.form();
        addRow(form, "Protocol", new JLabel("OpenAI-compatible embeddings"));
        field(form, "baseUrl", "API base URL");
        SettingsForm.comment(form, "Do not include /embeddings in the base URL.");
        field(form, "model", "Embedding model");
        password = new JPasswordField(28);
        password.setName("ragApiKey");
        removePassword = new JCheckBox();
        removePassword.setName("ragClearApiKey");
        SettingsForm.credentials(form, password, removePassword);
        addRow(form, "Data", new JLabel("Current project / .course-ai / default"));
        SettingsForm.comment(form, "Saving does not build an index or contact a model.");

        JPanel advanced = SettingsForm.form();
        field(advanced, "timeoutMillis", "HTTP request timeout (ms)");
        field(advanced, "dimensions", "Dimensions (0 = infer automatically)");
        field(advanced, "chunkCharacters", "Chunk size (Unicode code points)");
        field(advanced, "overlapCharacters", "Maximum overlap (code points)");
        field(advanced, "batchSize", "Embedding batch size");
        field(advanced, "minScore", "Minimum semantic score (0-1)");
        notes = new JCheckBox("Include PPTX slide notes");
        addRow(advanced, "", notes);
        field(advanced, "documentPrefix", "Document input prefix");
        field(advanced, "queryPrefix", "Query input prefix");
        SettingsForm.advanced(form, advanced);
        root = SettingsForm.page(form);
        reset();
        return root;
    }

    private void field(JPanel form, String name, String label) {
        SettingsForm.field(form, fields, "rag", name, label);
    }

    private static void addRow(JPanel form, String label, JComponent component) {
        SettingsForm.row(form, label, component);
    }


    private String text(String field) { return fields.get(field).getText().strip(); }
    private int number(String name) {
        try { return Integer.parseInt(text(name)); }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(fields.get(name).getAccessibleContext().getAccessibleName() + " must be a whole number.");
        }
    }

    private RagSettings.State readForm() {
        RagSettings.State state = new RagSettings.State();
        state.baseUrl = text("baseUrl"); state.model = text("model");
        state.timeoutMillis = number("timeoutMillis"); state.dimensions = number("dimensions");
        state.chunkCharacters = number("chunkCharacters"); state.overlapCharacters = number("overlapCharacters");
        state.batchSize = number("batchSize");
        try { state.minScore = Double.parseDouble(text("minScore")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Enter a minimum score between 0 and 1."); }
        state.includeSlideNotes = notes.isSelected();
        // Prefix whitespace is meaningful to providers and must survive a settings round-trip.
        state.documentPrefix = fields.get("documentPrefix").getText();
        state.queryPrefix = fields.get("queryPrefix").getText();
        return state;
    }

    @Override public boolean isModified() {
        if (root == null) return false;
        char[] key = password.getPassword();
        try {
            if (key.length > 0 || removePassword.isSelected()) return true;
            var path = ProjectRagService.projectRoot(project);
            return !readForm().toConfig(path, "").equals(project.getService(RagSettings.class).getState().toConfig(path, ""));
        } catch (RuntimeException invalid) { return true; }
        finally { Arrays.fill(key, '\0'); }
    }

    @Override public void apply() throws ConfigurationException {
        if (root == null || !isModified()) return;
        RagSettings.State next;
        try {
            next = readForm();
            next.validate(ProjectRagService.projectRoot(project));
        } catch (RagException error) { throw new ConfigurationException(error.getMessage()); }
        catch (IllegalArgumentException error) { throw new ConfigurationException(error.getMessage()); }

        char[] key = password.getPassword();
        boolean clear = removePassword.isSelected();
        if (key.length > 0 && new String(key).isBlank()) {
            Arrays.fill(key, '\0');
            throw new ConfigurationException("Enter a nonblank API key, or leave the field empty to keep the stored key.");
        }
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            // A synchronous modal progress task lets Apply report OS key-store failures without EDT I/O.
            boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                try {
                    if (project.isDisposed()) throw new IllegalStateException("Project closed");
                    if (clear || key.length > 0) RagCredentials.write(project, key);
                    project.getService(ProjectRagService.class).updateSettings(next);
                } catch (RuntimeException error) { failure.set(error); }
            }, "Saving Course RAG settings", false, project);
            if (!completed || failure.get() != null) {
                throw new ConfigurationException("Unable to save Course RAG settings. Check IDE Password Safe and retry.");
            }
            reset();
        } finally { Arrays.fill(key, '\0'); }
    }

    @Override public void reset() {
        if (root == null) return;
        var state = project.getService(RagSettings.class).getState();
        fields.get("baseUrl").setText(state.baseUrl); fields.get("model").setText(state.model);
        fields.get("timeoutMillis").setText(Integer.toString(state.timeoutMillis));
        fields.get("dimensions").setText(Integer.toString(state.dimensions));
        fields.get("chunkCharacters").setText(Integer.toString(state.chunkCharacters));
        fields.get("overlapCharacters").setText(Integer.toString(state.overlapCharacters));
        fields.get("batchSize").setText(Integer.toString(state.batchSize));
        fields.get("minScore").setText(Double.toString(state.minScore));
        notes.setSelected(state.includeSlideNotes);
        fields.get("documentPrefix").setText(state.documentPrefix);
        fields.get("queryPrefix").setText(state.queryPrefix);
        password.setText(""); password.setEnabled(true); removePassword.setSelected(false);
    }

    @Override public void disposeUIResources() {
        if (password != null) password.setText("");
        root = null; password = null; removePassword = null; notes = null;
        fields.clear();
    }
}
