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
import edu.teamc.courseaiassistant.application.RequestCoordinator;
import edu.teamc.courseaiassistant.application.ProjectRagService;
import edu.teamc.courseaiassistant.settings.RagSettingsConfigurable;
import edu.teamc.courseaiassistant.service.mock.MockChatService;
import edu.teamc.courseaiassistant.sample.SampleScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.JTabbedPane;

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
        Content content = ContentFactory.getInstance().createContent(panel, "Chat", false);
        content.setDisposer(() -> {
            panel.dispose();
            panelRegistry.remove(toolWindow);
        });
        content.setPreferredFocusableComponent(panel.getPreferredFocusComponent());
        toolWindow.getContentManager().addContent(content);
        panelRegistry.put(toolWindow, panel);

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
        RetrievalPreviewPanel preview = new RetrievalPreviewPanel(project.getService(ProjectRagService.class),
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, RagSettingsConfigurable.class));
        JTabbedPane sections = new JTabbedPane();
        sections.setName("courseMaterialSections");
        sections.addTab("Files & index", library);
        sections.addTab("Retrieval preview", preview);
        Content materials = ContentFactory.getInstance().createContent(sections, "Course materials", false);
        materials.setPreferredFocusableComponent(sections);
        sections.addChangeListener(event -> {
            if (sections.getSelectedComponent() == preview) {
                materials.setPreferredFocusableComponent(preview.getPreferredFocusComponent());
                if (sections.isShowing()) preview.getPreferredFocusComponent().requestFocusInWindow();
            } else {
                materials.setPreferredFocusableComponent(sections);
            }
        });
        materials.setDisposer(() -> { preview.dispose(); library.dispose(); });
        toolWindow.getContentManager().addContent(materials);
        toolWindow.getContentManager().setSelectedContent(content);
    }

    @Nullable
    public static ChatPanel findChatPanel(@NotNull ToolWindow toolWindow) {
        return panelRegistry.get(toolWindow);
    }

    @Nullable
    public static ChatPanel selectChatPanel(@NotNull ToolWindow toolWindow) {
        ChatPanel panel = findChatPanel(toolWindow);
        if (panel != null) {
            Content content = toolWindow.getContentManager().getContent(panel);
            if (content != null) toolWindow.getContentManager().setSelectedContent(content);
        }
        return panel;
    }
}
