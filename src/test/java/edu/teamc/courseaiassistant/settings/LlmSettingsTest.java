package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializer;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LlmSettingsTest {
    @Test void settingsSnapshotsAndSerializationHaveNoSecretsAndAreProjectLocal() {
        var first = new LlmSettings();
        var second = new LlmSettings();
        var state = first.getState(); state.model = "course-model";
        state.baseUrl = "https://example.invalid/v1"; state.timeoutMillis = 90_000;
        first.loadState(state); state.model = "mutated";
        assertEquals("course-model", first.getState().model);
        assertEquals("", second.getState().model);
        var snapshot = first.getState(); snapshot.model = "uncommitted";
        assertEquals("course-model", first.getState().model);
        var xml = XmlSerializer.serialize(first.getState());
        var restored = XmlSerializer.deserialize(xml, LlmSettings.State.class);
        assertEquals(first.getState().toConfig("fixture-key"), restored.toConfig("fixture-key"));
        for (var field : LlmSettings.State.class.getFields())
            assertFalse(field.getName().toLowerCase().matches(".*(password|credential|apikey).*"));
        assertFalse(xml.toString().contains("fixture-key"));
    }

    @Test void incompleteSettingsCanBeSavedButInvalidEndpointsAndLimitsCannot() {
        var empty = new LlmSettings.State();
        assertDoesNotThrow(empty::validate);
        assertThrows(IllegalArgumentException.class, () -> empty.toConfig("fixture-key"));
        var partial = new LlmSettings.State(); partial.baseUrl = "https://example.invalid/v1";
        assertDoesNotThrow(partial::validate);
        assertThrows(IllegalArgumentException.class, () -> partial.toConfig("fixture-key"));
        List<Consumer<LlmSettings.State>> invalid = List.of(
                s -> s.baseUrl = "http://remote.invalid/v1", s -> s.baseUrl = "file:///private",
                s -> s.baseUrl = "https://user:secret@example.invalid", s -> s.baseUrl = "https://example.invalid?key=secret",
                s -> s.baseUrl = "https://example.invalid/#secret", s -> s.baseUrl = "not a URI",
                s -> s.timeoutMillis = 0, s -> s.timeoutMillis = 300001,
                s -> s.maxOutputTokens = 0, s -> s.maxOutputTokens = 32769,
                s -> s.maxInputCharacters = 99, s -> s.maxInputCharacters = 1000001);
        for (var change : invalid) {
            var state = new LlmSettings.State(); change.accept(state);
            var error = assertThrows(IllegalArgumentException.class, state::validate);
            assertFalse(error.getMessage().contains("secret"));
        }
        var local = new LlmSettings.State(); local.baseUrl = "http://127.0.0.1:1234/v1"; local.model = "local";
        assertEquals("http://127.0.0.1:1234/v1/chat/completions", local.toConfig("fixture-key").completionEndpoint().toString());
        assertThrows(IllegalArgumentException.class, () -> local.toConfig(""));
    }

    @Test void outerDeadlineTracksModelConfigurationBeyondTheOldThirtySecondLimit() {
        var settings = new LlmSettings.State();
        assertEquals(Duration.ofSeconds(30), settings.coordinatorTimeout());
        settings.timeoutMillis = 90_000;
        assertEquals(Duration.ofSeconds(95), settings.coordinatorTimeout());
        settings.timeoutMillis = 300_000;
        assertEquals(Duration.ofSeconds(305), settings.coordinatorTimeout());
    }

    @Test void formDoesNotReadCredentialsAndTracksResetClearAndInvalidInput() throws Exception {
        var settings = new LlmSettings();
        SwingUtilities.invokeAndWait(() -> {
            var configurable = new LlmSettingsConfigurable(project("first", settings));
            try {
                var form = configurable.createComponent();
                assertFalse(configurable.isModified());
                verifyPresentation(form, configurable::isModified, "language-model");
                var password = (JPasswordField) named(form, "llmApiKey");
                assertEquals(0, password.getPassword().length);
                var model = (JTextField) named(form, "llm_model");
                model.setText("draft"); assertTrue(configurable.isModified());
                configurable.reset(); assertFalse(configurable.isModified());
                password.setText("fixture-key"); assertTrue(configurable.isModified());
                var clear = (JCheckBox) named(form, "llmClearApiKey");
                clear.doClick();
                assertFalse(password.isEnabled()); assertEquals(0, password.getPassword().length);
                configurable.reset(); assertTrue(password.isEnabled()); assertFalse(clear.isSelected());
                var timeout = (JTextField) named(form, "llm_timeoutMillis");
                timeout.setText("300001");
                assertThrows(com.intellij.openapi.options.ConfigurationException.class, configurable::apply);
                assertEquals(25_000, settings.getState().timeoutMillis);
                configurable.reset(); password.setText("   ");
                assertThrows(com.intellij.openapi.options.ConfigurationException.class, configurable::apply);
            } finally { configurable.disposeUIResources(); }
        });
    }

    @Test void keysAreNamespacedPerProjectAndCredentialIoIsRejectedOnEdt() throws Exception {
        var first = project("one", new LlmSettings());
        var second = project("two", new LlmSettings());
        assertNotEquals(LlmCredentials.attributes(first), LlmCredentials.attributes(second));
        assertTrue(LlmCredentials.attributes(first).getServiceName().contains(" / LLM / "));
        SwingUtilities.invokeAndWait(() -> {
            assertThrows(IllegalStateException.class, () -> LlmCredentials.read(first));
            assertThrows(IllegalStateException.class, () -> LlmCredentials.write(first, new char[0]));
        });
    }

    private static Project project(String id, LlmSettings settings) {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getService" -> args[0] == LlmSettings.class ? settings : null;
            case "getLocationHash" -> id;
            case "isDisposed" -> false;
            case "toString" -> "LlmSettingsTestProject";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    static void verifyPresentation(JComponent form, java.util.function.BooleanSupplier modified, String name) {
        JPanel section = (JPanel) named(form, "advancedSettings");
        var decorator = (com.intellij.ui.HideableDecorator) section.getClientProperty("settingsDecorator");
        assertFalse(decorator.isExpanded());
        decorator.setOn(true);
        assertFalse(modified.getAsBoolean(), "Expanding a section must not change settings");
        var comment = (com.intellij.ui.components.JBLabel) named(form, "settingsComment");
        assertEquals(com.intellij.util.ui.UIUtil.ComponentStyle.SMALL, comment.getComponentStyle());
        assertTrue(comment.getFont().getSize2D() < new JLabel().getFont().getSize2D(),
                "Hint text must use a visibly smaller font than field labels");
        assertEquals(com.intellij.util.ui.UIUtil.getContextHelpForeground(), comment.getForeground());
        render(form, name + "-expanded");
        decorator.setOn(false);
        assertFalse(modified.getAsBoolean(), "Collapsing a section must not change settings");
        render(form, name + "-collapsed");
    }

    private static void render(JComponent form, String name) {
        form.setSize(820, 1000);
        layout(form);
        var preview = new java.awt.image.BufferedImage(820, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = preview.createGraphics();
        try { form.printAll(graphics); } finally { graphics.dispose(); }
        try {
            var output = java.nio.file.Path.of("build/reports/settings-ui", name + ".png");
            java.nio.file.Files.createDirectories(output.getParent());
            javax.imageio.ImageIO.write(preview, "png", output.toFile());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static Component named(Component parent, String name) {
        if (name.equals(parent.getName())) return parent;
        if (parent instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
}
