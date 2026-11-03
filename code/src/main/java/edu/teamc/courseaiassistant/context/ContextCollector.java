package edu.teamc.courseaiassistant.context;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Captures immutable CodeContext snapshots from the active editor.
 * Returns null when no suitable selection or file is available.
 */
public final class ContextCollector {

    private ContextCollector() {}

    /**
     * Captures context from the currently selected code in the given project.
     * Returns null if no editor is active, no selection exists, or no file is associated.
     */
    @Nullable
    public static CodeContext captureFromActiveEditor(@NotNull Project project) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return null;
        }
        return captureFromEditor(project, editor);
    }

    /**
     * Captures context from the specified editor's current selection.
     * Returns null if no selection exists or no file is associated.
     */
    @Nullable
    public static CodeContext captureFromEditor(@NotNull Project project, @NotNull Editor editor) {
        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            return null;
        }

        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            return null;
        }

        String selectedText = selectionModel.getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return null;
        }

        int startOffset = selectionModel.getSelectionStart();
        int endOffset = selectionModel.getSelectionEnd();
        long modificationStamp = document.getModificationStamp();

        int startLine = document.getLineNumber(startOffset) + 1;
        int endLine = document.getLineNumber(endOffset) + 1;
        if (endOffset > startOffset && document.getLineStartOffset(endLine - 1) == endOffset) {
            endLine--;
        }

        String projectRelativePath = computeProjectRelativePath(project, file);
        String languageId = detectLanguageId(project, document);

        return new CodeContext(
                projectRelativePath,
                languageId,
                selectedText,
                startLine,
                endLine,
                modificationStamp,
                null
        );
    }

    @NotNull
    private static String computeProjectRelativePath(@NotNull Project project, @NotNull VirtualFile file) {
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return file.getName();
        }
        String basePath = baseDir.getPath();
        String filePath = file.getPath();
        if (filePath.startsWith(basePath)) {
            String relative = filePath.substring(basePath.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative.isEmpty() ? file.getName() : relative;
        }
        return filePath;
    }

    @NotNull
    private static String detectLanguageId(@NotNull Project project, @NotNull Document document) {
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (psiFile != null && psiFile.getLanguage() != null) {
            String id = psiFile.getLanguage().getID();
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }
        return "unknown";
    }
}
