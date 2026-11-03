package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import edu.teamc.courseaiassistant.settings.RagCredentials;

/** Only the IDE owns this library; project facades borrow it and cancel their own requests. */
@Service(Service.Level.APP)
public final class GlobalRagLibrary implements Disposable {
    final ProjectRagService library = new ProjectRagService(null, () -> RagCredentials.read(null));
    public void updateSettings(edu.teamc.courseaiassistant.settings.RagSettings.State state) { library.updateSettings(state); }
    @Override public void dispose() { library.dispose(); }
}
