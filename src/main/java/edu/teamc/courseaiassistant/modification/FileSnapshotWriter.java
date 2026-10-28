package edu.teamc.courseaiassistant.modification;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import edu.teamc.courseaiassistant.model.ModificationRequest;
import edu.teamc.courseaiassistant.model.ChangeProposal;

/** Runs on EDT. Changes the editor document through an undoable IDE command. */
public final class FileSnapshotWriter {
    public void write(Project project, VirtualFile file, Document document,
                      ModificationRequest request, ChangeProposal proposal) {
        WriteCommandAction.runWriteCommandAction(project, "Apply reviewed F2 change", null, () -> {
            if (project.isDisposed() || !file.isValid() || !file.isWritable() || !document.isWritable())
                throw new IllegalStateException("Target is unavailable or read-only.");
            if (!file.equals(FileDocumentManager.getInstance().getFile(document))

                    || !document.getText().equals(request.originalFileText()))
                throw new IllegalStateException("File changed. Identity matches: " + file.equals(FileDocumentManager.getInstance().getFile(document)) + "; snapshot matches: " + document.getText().equals(request.originalFileText()));
            document.setText(proposal.change().proposedFileText());
        });
        // IDE saving preserves the file's original encoding and line separator conventions.
        FileDocumentManager.getInstance().saveDocument(document);
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document))
            throw new IllegalStateException("Document changed but could not be saved. Save or undo it in the editor.");
    }
}
