package edu.teamc.courseaiassistant.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import edu.teamc.courseaiassistant.application.RequestCoordinator;
import edu.teamc.courseaiassistant.service.mock.MockChatService;
import edu.teamc.courseaiassistant.sample.SampleScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

public final class CourseAiToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String TOOL_WINDOW_ID = "CourseAiAssistant";

    private static final Map<ToolWindow, ChatPanel> panelRegistry = new WeakHashMap<>();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MockChatService samples = new MockChatService();
        RequestCoordinator coordinator = new RequestCoordinator(project, samples);

        ChatPanel panel = new ChatPanel(null, coordinator, example -> samples.setScenario(
                example == SampleScenario.COURSE_CITATION
                        ? MockChatService.Scenario.SUCCESS_COURSE_GROUNDED
                        : MockChatService.Scenario.SUCCESS_GENERAL_KNOWLEDGE));
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(() -> {
            panel.dispose();
            panelRegistry.remove(toolWindow);
        });
        content.setPreferredFocusableComponent(panel.getPreferredFocusComponent());
        toolWindow.getContentManager().addContent(content);
        panelRegistry.put(toolWindow, panel);
    }

    @Nullable
    public static ChatPanel findChatPanel(@NotNull ToolWindow toolWindow) {
        return panelRegistry.get(toolWindow);
    }
}
