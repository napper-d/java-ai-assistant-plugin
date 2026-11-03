package edu.teamc.courseaiassistant.modification;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.Messages;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.model.ModificationRequest;
import com.intellij.openapi.application.ApplicationManager;
import java.nio.file.Path;

/** Separate sample entry; ordinary chat remains unchanged. */
public final class SampleModifyAction extends AnAction {
    @Override public void actionPerformed(AnActionEvent event) {
        var project = event.getProject(); var editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null || project.getBasePath() == null) return;
        var document = editor.getDocument();
        var file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) return;
        String instruction = Messages.showInputDialog(project,
            "Fixed local sample only. Use SumDemo.java or GreetingDemo.java from examples/f2.\nEnter the modification instruction:",
            "F2 Sample Review (No AI)", Messages.getQuestionIcon());
        if (instruction == null || instruction.isBlank()) return;
        String path = Path.of(project.getBasePath()).relativize(Path.of(file.getPath())).toString().replace('\\', '/');
        var selection = editor.getSelectionModel();
        String text = selection.hasSelection() ? selection.getSelectedText() : document.getText();
        int first = selection.hasSelection() ? document.getLineNumber(selection.getSelectionStart()) + 1 : 1;
        int last = selection.hasSelection() ? document.getLineNumber(Math.max(selection.getSelectionStart(), selection.getSelectionEnd()-1)) + 1 : document.getLineCount();
        var context = new CodeContext(path, "JAVA", text, first, last, document.getModificationStamp(), null);
        var request = new ModificationRequest(instruction, context, document.getText());
        new ValidatedModificationService(project, new SampleCodeGenerationService()).modify(request)
            .whenComplete((result, error) -> ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (error != null) Messages.showErrorDialog(project, "Sample modification failed. Use an unchanged demo file; if edited while waiting, regenerate.", "F2 Sample");
                else Messages.showInfoMessage(project, result.message(), "F2 Sample");
            }));
    }
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject()!=null && event.getData(CommonDataKeys.EDITOR)!=null);
    }
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
