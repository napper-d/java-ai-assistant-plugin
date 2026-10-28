package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializer;
import edu.teamc.courseaiassistant.application.ProjectRagService;
import edu.teamc.courseaiassistant.rag.RagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RagSettingsTest {
    @TempDir Path root;

    @Test void settingsAreProjectLocalSnapshotsAndContainNoCredentialField() {
        RagSettings first = new RagSettings(), second = new RagSettings();
        var edited = first.getState();
        edited.model = "course-embedding";
        first.loadState(edited);
        edited.model = "later mutation";
        assertEquals("course-embedding", first.getState().model);
        assertEquals("", second.getState().model);
        var snapshot = first.getState(); snapshot.baseUrl = "uncommitted";
        assertEquals("", first.getState().baseUrl);
        for (var field : RagSettings.State.class.getFields()) {
            assertFalse(field.getName().toLowerCase().matches(".*(password|credential|apikey).*"));
        }
    }

    @Test void defaultConfigNeedsNoKeyAndUsesExplicitProjectRoot() {
        var settings = new RagSettings.State();
        settings.validate(root);
        var config = settings.toConfig(root, "");
        assertEquals(root.resolve(".course-ai"), config.dataDirectory());
        assertEquals("default", config.corpusId());
        assertEquals("openai-compatible", config.embedding().provider());
        assertEquals("", config.embedding().apiKey());
        assertEquals("", config.embedding().baseUrl());
        var keyed = settings.toConfig(root, "test-only-secret");
        assertFalse(keyed.toString().contains("test-only-secret"));
        assertEquals(config.embedding().fingerprint(), keyed.embedding().fingerprint());
    }

    @Test void workspaceStateRoundTripsWithThePlatformSerializer() {
        var original = new RagSettings.State();
        original.baseUrl = "https://example.invalid/v1"; original.model = "course-embedding";
        original.documentPrefix = "document: "; original.includeSlideNotes = true;
        original.maxQueries = 4;
        var xml = XmlSerializer.serialize(original);
        var restored = XmlSerializer.deserialize(xml, RagSettings.State.class);
        assertEquals(original.toConfig(root, ""), restored.toConfig(root, ""));
        assertEquals("document: ", restored.documentPrefix);
    }

    @Test void invalidFieldsCannotBeSavedButPartialConfigurationCan() {
        List<Consumer<RagSettings.State>> invalid = List.of(
                state -> state.timeoutMillis = 0,
                state -> state.dimensions = -1,
                state -> state.chunkCharacters = 1,
                state -> state.overlapCharacters = state.chunkCharacters,
                state -> state.batchSize = 129,
                state -> state.minScore = Double.NaN,
                state -> state.minScore = 1.01,
                state -> state.maxRounds = 5,
                state -> state.maxQueries = 0);
        for (var change : invalid) {
            var state = new RagSettings.State(); change.accept(state);
            assertThrows(IllegalArgumentException.class, () -> state.validate(root));
        }
        for (String url : List.of("file:///private", "https://user:password@example.invalid/v1")) {
            var badUrl = new RagSettings.State(); badUrl.baseUrl = url;
            assertEquals(RagException.Reason.CONFIGURATION,
                    assertThrows(RagException.class, () -> badUrl.validate(root)).getReason());
        }
        var state = new RagSettings.State(); state.baseUrl = "https://example.invalid/v1";
        assertDoesNotThrow(() -> state.validate(root));
    }

    @Test void changingSearchParametersPreservesIndexFingerprintButModelAndChunksDoNot() {
        var state = new RagSettings.State();
        var original = state.toConfig(root, "");
        state.minScore = .8; state.multiStepEnabled = false; state.maxQueries = 3;
        var searchOnly = state.toConfig(root, "");
        assertEquals(original.splitterFingerprint(), searchOnly.splitterFingerprint());
        assertEquals(original.embedding().fingerprint(), searchOnly.embedding().fingerprint());
        state.chunkCharacters = 1000; state.model = "new-model";
        assertNotEquals(original.splitterFingerprint(), state.toConfig(root, "").splitterFingerprint());
        assertNotEquals(original.embedding().fingerprint(), state.toConfig(root, "").embedding().fingerprint());
    }

    @Test void missingProjectRootFailsRatherThanUsingWorkingDirectory() {
        var failure = assertThrows(RagException.class, () -> ProjectRagService.projectRoot(project(null, new RagSettings())));
        assertEquals(RagException.Reason.CONFIGURATION, failure.getReason());
    }

    @Test void projectServiceAllowsUnconfiguredRegistrationAndConfigurationReplacement() throws Exception {
        var settings = new RagSettings();
        var service = new ProjectRagService(project(root.toString(), settings));
        Path file = Files.writeString(root.resolve("source.pdf"), "registration-only fixture");
        try {
            assertEquals(1, service.registerMaterials(List.of(file)).get(5, TimeUnit.SECONDS).documents().size());
            var updated = settings.getState(); updated.model = "replacement-model";
            service.updateSettings(updated);
            assertEquals("replacement-model", settings.getState().model);
            assertEquals(1, service.registerMaterials(List.of(file)).get(5, TimeUnit.SECONDS).documents().size());
            assertTrue(Files.exists(root.resolve(".course-ai/documents/default/documents.json")));
        } finally { service.dispose(); }
        var error = assertThrows(ExecutionException.class, () -> service.refreshLibrary().get());
        assertEquals(RagException.Reason.CLOSED, ((RagException) error.getCause()).getReason());
    }

    @Test void libraryNotificationsInvalidatePreviewOnSuccessAndPartialFailureAndCanBeUnsubscribed() throws Exception {
        var service = new ProjectRagService(project(root.toString(), new RagSettings()));
        AtomicInteger changes = new AtomicInteger();
        Runnable unsubscribe = service.onLibraryChanged(changes::incrementAndGet);
        try {
            Path first = Files.writeString(root.resolve("first.pdf"), "registration-only");
            service.registerMaterials(List.of(first)).get(5, TimeUnit.SECONDS);
            // Completion listeners may run just after a waiter is released.
            waitForCount(changes, 1);
            Path second = Files.writeString(root.resolve("second.pdf"), "registration-only");
            Path invalid = Files.writeString(root.resolve("invalid.txt"), "unsupported");
            assertThrows(ExecutionException.class, () -> service.registerMaterials(List.of(second, invalid)).get(5, TimeUnit.SECONDS));
            waitForCount(changes, 2);
            unsubscribe.run();
            service.unregisterMaterials(List.of(second)).get(5, TimeUnit.SECONDS);
            assertEquals(2, changes.get());
        } finally { unsubscribe.run(); service.dispose(); }
    }

    private static void waitForCount(AtomicInteger changes, int expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (changes.get() < expected && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(expected, changes.get());
    }

    @Test void settingsFormPreservesPrefixesTracksEditsAndNeverLoadsAKey() throws Exception {
        var settings = new RagSettings();
        var state = settings.getState(); state.documentPrefix = "document: "; state.queryPrefix = "query: ";
        settings.loadState(state);
        SwingUtilities.invokeAndWait(() -> {
            var configurable = new RagSettingsConfigurable(project(root.toString(), settings));
            try {
                var form = configurable.createComponent();
                assertFalse(configurable.isModified(), "Prefix whitespace must survive form reset");
                var key = (JPasswordField) named(form, "ragApiKey");
                assertEquals(0, key.getPassword().length);
                var model = (JTextField) named(form, "rag_model");
                model.setText("draft-model"); assertTrue(configurable.isModified());
                configurable.reset(); assertFalse(configurable.isModified()); assertEquals("", model.getText());
                key.setText("test-only-key"); assertTrue(configurable.isModified());
                configurable.reset(); assertEquals(0, key.getPassword().length);
                var overlap = (JTextField) named(form, "rag_overlapCharacters");
                overlap.setText("900");
                assertThrows(com.intellij.openapi.options.ConfigurationException.class, configurable::apply);
                assertEquals(120, settings.getState().overlapCharacters);
            } finally { configurable.disposeUIResources(); }
        });
    }

    @Test void credentialAccessIsRejectedOnTheUiThreadBeforeOpeningPasswordSafe() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Project project = project(root.toString(), new RagSettings());
            assertThrows(IllegalStateException.class, () -> RagCredentials.read(project));
            assertThrows(IllegalStateException.class, () -> RagCredentials.write(project, new char[0]));
        });
    }

    static Project project(String basePath, RagSettings settings) {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getBasePath" -> basePath;
                case "getService" -> args[0] == RagSettings.class ? settings : null;
                case "isDisposed", "isDefault" -> false;
                case "toString" -> "RagSettingsTestProject";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }

    private static Component named(Component parent, String name) {
        if (name.equals(parent.getName())) return parent;
        if (parent instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = named(child, name); if (found != null) return found;
            }
        }
        return null;
    }
}
