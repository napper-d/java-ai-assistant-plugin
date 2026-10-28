package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import edu.teamc.courseaiassistant.application.ProjectLlmService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Project Chat/F2 settings; the form never reads a stored key or invokes a provider. */
public final class LlmSettingsConfigurable implements SearchableConfigurable {
    private final Project project;
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private JPanel root;
    private JPasswordField password;
    private JCheckBox removePassword;

    public LlmSettingsConfigurable(Project project) { this.project = project; }
    @Override public @NotNull String getId() { return "edu.teamc.courseaiassistant.llm"; }
    @Override public String getDisplayName() { return "Language model"; }

    @Override public JComponent createComponent() {
        if (root != null) return root;
        JPanel form = SettingsForm.form();
        addRow(form, "Protocol", new JLabel("OpenAI-compatible chat completions"));
        field(form, "baseUrl", "API base URL");
        SettingsForm.comment(form, "Do not include /chat/completions in the base URL.");
        field(form, "model", "Chat and code generation model");
        password = new JPasswordField(28);
        password.setName("llmApiKey");
        removePassword = new JCheckBox();
        removePassword.setName("llmClearApiKey");
        SettingsForm.credentials(form, password, removePassword);
        SettingsForm.comment(form, "Saving does not build an index or contact a model.");
        JPanel advanced = SettingsForm.form();
        field(advanced, "timeoutMillis", "Retrieval and generation timeout (ms)");
        field(advanced, "maxOutputTokens", "Maximum output tokens");
        field(advanced, "maxInputCharacters", "Maximum input characters (UTF-16)");
        SettingsForm.advanced(form, advanced);
        root = SettingsForm.page(form);
        reset();
        return root;
    }

    private void field(JPanel form, String name, String label) {
        SettingsForm.field(form, fields, "llm", name, label);
    }

    private static void addRow(JPanel form, String label, JComponent component) {
        SettingsForm.row(form, label, component);
    }


    private int number(String name) {
        try { return Integer.parseInt(fields.get(name).getText().strip()); }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(fields.get(name).getAccessibleContext().getAccessibleName() + " must be a whole number.");
        }
    }

    private LlmSettings.State readForm() {
        LlmSettings.State state = new LlmSettings.State();
        state.baseUrl = fields.get("baseUrl").getText().strip();
        state.model = fields.get("model").getText().strip();
        state.timeoutMillis = number("timeoutMillis");
        state.maxOutputTokens = number("maxOutputTokens");
        state.maxInputCharacters = number("maxInputCharacters");
        return state;
    }

    @Override public boolean isModified() {
        if (root == null) return false;
        char[] key = password.getPassword();
        try {
            if (key.length > 0 || removePassword.isSelected()) return true;
            var draft = readForm();
            var saved = project.getService(LlmSettings.class).getState();
            return !draft.baseUrl.equals(saved.baseUrl) || !draft.model.equals(saved.model)
                    || draft.timeoutMillis != saved.timeoutMillis || draft.maxOutputTokens != saved.maxOutputTokens
                    || draft.maxInputCharacters != saved.maxInputCharacters;
        } catch (RuntimeException invalid) { return true; }
        finally { Arrays.fill(key, '\0'); }
    }

    @Override public void apply() throws ConfigurationException {
        if (root == null || !isModified()) return;
        LlmSettings.State next;
        try { next = readForm(); next.validate(); }
        catch (IllegalArgumentException error) { throw new ConfigurationException(error.getMessage()); }
        char[] key = password.getPassword();
        boolean clear = removePassword.isSelected();
        try {
            if (key.length > 0 && new String(key).isBlank())
                throw new ConfigurationException("Enter a nonblank API key, or leave the field empty to keep the stored key.");
            if (new String(key).contains("\r") || new String(key).contains("\n"))
                throw new ConfigurationException("The API key must not contain line breaks.");
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                try {
                    if (project.isDisposed()) throw new IllegalStateException("Project closed");
                    if (clear || key.length > 0) LlmCredentials.write(project, key);
                    project.getService(ProjectLlmService.class).updateSettings(next);
                } catch (RuntimeException error) { failure.set(error); }
            }, "Saving language model settings", false, project);
            if (!completed || failure.get() != null)
                throw new ConfigurationException("Unable to save language model settings. Check IDE Password Safe and retry.");
            reset();
        } finally { Arrays.fill(key, '\0'); }
    }

    @Override public void reset() {
        if (root == null) return;
        var state = project.getService(LlmSettings.class).getState();
        fields.get("baseUrl").setText(state.baseUrl); fields.get("model").setText(state.model);
        fields.get("timeoutMillis").setText(Integer.toString(state.timeoutMillis));
        fields.get("maxOutputTokens").setText(Integer.toString(state.maxOutputTokens));
        fields.get("maxInputCharacters").setText(Integer.toString(state.maxInputCharacters));
        password.setText(""); password.setEnabled(true); removePassword.setSelected(false);
    }

    @Override public void disposeUIResources() {
        if (password != null) password.setText("");
        root = null; password = null; removePassword = null;
        fields.clear();
    }
}
