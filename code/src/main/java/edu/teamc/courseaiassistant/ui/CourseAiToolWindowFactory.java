package edu.teamc.courseaiassistant.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import edu.teamc.courseaiassistant.application.LiveChatSession;
import edu.teamc.courseaiassistant.application.ProjectLlmService;
import edu.teamc.courseaiassistant.application.ProjectRagService;
import edu.teamc.courseaiassistant.settings.RagSettingsConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.Arrays;
import java.util.Locale;


public final class CourseAiToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String TOOL_WINDOW_ID = "CourseAiAssistant";

    private static final Map<ToolWindow, AssistantPanel> panelRegistry = new WeakHashMap<>();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LiveChatSession session = new LiveChatSession(project, project.getService(ProjectLlmService.class));
        ChatPanel panel = new ChatPanel(session.coordinator());
        KnowledgeBasePanel library = new KnowledgeBasePanel(project.getService(ProjectRagService.class), () -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                    .withTitle("Add course PDF / PPTX files")
                    .withDescription("Register original paths. Files remain in their current locations.")
                    .withFileFilter(file -> {
                        String extension = file.getExtension();
                        return extension != null && java.util.Set.of("pdf", "pptx").contains(extension.toLowerCase(Locale.ROOT));
                    });
            return Arrays.stream(FileChooser.chooseFiles(descriptor, project, null)).map(file -> file.toNioPath()).toList();
        }, () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, RagSettingsConfigurable.class));
        AssistantPanel host = new AssistantPanel(panel, library);
        Content content = ContentFactory.getInstance().createContent(host, "", false);
        content.setDisposer(() -> {
            panel.dispose(); session.dispose(); library.dispose(); panelRegistry.remove(toolWindow);
        });
        content.setPreferredFocusableComponent(panel.getPreferredFocusComponent());
        toolWindow.getContentManager().addContent(content);
        panelRegistry.put(toolWindow, host);
    }

    @Nullable
    public static ChatPanel findChatPanel(@NotNull ToolWindow toolWindow) {
        AssistantPanel host = panelRegistry.get(toolWindow);
        return host == null ? null : host.chatPanel();
    }

    @Nullable
    public static ChatPanel selectChatPanel(@NotNull ToolWindow toolWindow) {
        AssistantPanel host = panelRegistry.get(toolWindow);
        return host == null ? null : host.showChat();
    }
}
