package edu.teamc.courseaiassistant.application;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;

/** One Chat content borrows the project model service and owns only its coordinator/subscription. */
public final class LiveChatSession implements Disposable {
    private final RequestCoordinator coordinator;
    private final Runnable unsubscribe;

    public LiveChatSession(Project project, ProjectLlmService model) {
        coordinator = new RequestCoordinator(project, model, model::coordinatorTimeout);
        unsubscribe = model.onChatInvalidated(coordinator::cancelAll);
    }

    public RequestCoordinator coordinator() { return coordinator; }

    @Override public void dispose() {
        unsubscribe.run();
        coordinator.dispose();
    }
}
