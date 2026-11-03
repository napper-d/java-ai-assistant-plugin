package edu.teamc.courseaiassistant.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;

import javax.swing.SwingUtilities;

/** Separate from embedding credentials; Password Safe access must stay off the EDT. */
public final class LlmCredentials {
    private LlmCredentials() {}

    static CredentialAttributes attributes(Project project) {
        return new CredentialAttributes("Course AI Assistant / LLM / global", "llm");
    }

    public static String read(Project project) {
        requireBackground();
        String password = PasswordSafe.getInstance().getPassword(attributes(project));
        return password == null ? "" : password;
    }

    public static void write(Project project, char[] password) {
        requireBackground();
        PasswordSafe.getInstance().set(attributes(project),
                password.length == 0 ? null : new Credentials("llm", password));
    }

    private static void requireBackground() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Access model credentials on a background thread.");
    }
}
