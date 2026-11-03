package edu.teamc.courseaiassistant.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.context.ContextCollector;
import edu.teamc.courseaiassistant.ui.ChatPanel;
import edu.teamc.courseaiassistant.ui.CourseAiToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Editor context-menu action that captures the current selection and opens the assistant.
 * Disabled when no editor or selection is available.
 */
public final class AskAssistantAction extends AnAction {

    public AskAssistantAction() {
        super("Ask Course AI Assistant", "Send selected code to the AI assistant", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) return;
        CodeContext context = ContextCollector.captureFromEditor(project, editor);
        if (context == null) {
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(CourseAiToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }

        toolWindow.activate(() -> {
            ChatPanel panel = CourseAiToolWindowFactory.selectChatPanel(toolWindow);
            if (panel != null) {
                panel.attachCodeContext(context);
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }

        boolean hasSelection = event.getData(CommonDataKeys.EDITOR) != null
                && event.getData(CommonDataKeys.EDITOR).getSelectionModel().hasSelection();

        event.getPresentation().setEnabledAndVisible(hasSelection);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
