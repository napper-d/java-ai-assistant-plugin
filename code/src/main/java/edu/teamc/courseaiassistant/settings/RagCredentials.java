package edu.teamc.courseaiassistant.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;

import javax.swing.SwingUtilities;

/** PasswordSafe may unlock an OS store; callers must use a background thread. */
public final class RagCredentials {
    private RagCredentials() {}

    private static CredentialAttributes attributes(Project project) {
        return new CredentialAttributes("Course AI Assistant / RAG / global", "embedding");
    }

    public static String read(Project project) {
        requireBackground();
        String password = PasswordSafe.getInstance().getPassword(attributes(project));
        return password == null ? "" : password;
    }

    public static void write(Project project, char[] password) {
        requireBackground();
        PasswordSafe.getInstance().set(attributes(project),
                password.length == 0 ? null : new Credentials("embedding", password));
    }

    private static void requireBackground() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Access course credentials on a background thread.");
        }
    }
}
