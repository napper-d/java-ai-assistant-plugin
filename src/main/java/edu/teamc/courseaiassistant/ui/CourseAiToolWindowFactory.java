package edu.teamc.courseaiassistant.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import edu.teamc.courseaiassistant.application.RequestCoordinator;
import edu.teamc.courseaiassistant.service.mock.MockChatService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

public final class CourseAiToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String TOOL_WINDOW_ID = "CourseAiAssistant";

    private static final Map<ToolWindow, ChatPanel> panelRegistry = new WeakHashMap<>();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        RequestCoordinator coordinator = new RequestCoordinator(project, new MockChatService());
        Disposer.register(toolWindow.getDisposable(), coordinator);

        ChatPanel panel = new ChatPanel(null, coordinator);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setPreferredFocusableComponent(panel.getPreferredFocusComponent());
        toolWindow.getContentManager().addContent(content);
        panelRegistry.put(toolWindow, panel);
    }

    @Nullable
    public static ChatPanel findChatPanel(@NotNull ToolWindow toolWindow) {
        return panelRegistry.get(toolWindow);
    }
}
