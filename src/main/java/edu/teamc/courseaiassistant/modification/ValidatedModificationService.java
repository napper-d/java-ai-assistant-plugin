package edu.teamc.courseaiassistant.modification;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.*;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Single-file generation -> validation -> review -> approved IDE document write. */
public final class ValidatedModificationService implements ModificationService {
    private final Project project;
    private final CodeGenerationService generator;
    public ValidatedModificationService(Project project, CodeGenerationService generator) {
        this.project = project; this.generator = generator;
    }
    @Override public CompletableFuture<ModificationResult> modify(ModificationRequest request) {
        CompletableFuture<ModificationResult> result = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Path base = Path.of(project.getBasePath()).toRealPath();
                Path relative = Path.of(request.codeContext().getProjectRelativePath());
                if (relative.isAbsolute()) throw new IllegalArgumentException("Select a file inside this project.");
                Path target = base.resolve(relative).normalize().toRealPath();
                if (!target.startsWith(base)) throw new IllegalArgumentException("Target leaves the project.");
                var generation = generator.generate(request);
                result.whenComplete((v, e) -> { if (result.isCancelled()) generation.cancel(true); });
                generation.whenComplete((proposal, error) -> {
                    if (error != null) { result.completeExceptionally(GenerationFailures.toFailure(request.requestId(), error)); return; }
                    try { new ProposalValidator().validate(request, proposal); }
                    catch (RuntimeException ex) { fail(result, request, ex.getMessage()); return; }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (result.isDone()) return;
                        if (project.isDisposed()) { fail(result, request, "Project closed."); return; }
                        try {
                            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
                            if (file == null || !Path.of(file.getPath()).toRealPath().equals(target)) throw new IllegalStateException("Target unavailable.");
                            var document = FileDocumentManager.getInstance().getDocument(file);
                            if (document == null || document.getModificationStamp() != request.codeContext().getDocumentModificationStamp()
                                    || !document.getText().equals(request.originalFileText())) throw new IllegalStateException("File changed. Please regenerate.");
                            long diskStamp = file.getModificationStamp();
                            var dialog = new DiffReviewDialog(project, proposal);
                            var cancellation = new javax.swing.Timer(100, event -> {
                                if (result.isDone() || project.isDisposed()) dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);
                            });
                            boolean approved;
                            cancellation.start();
                            try { approved = dialog.showAndGet(); }
                            finally { cancellation.stop(); }
                            if (result.isDone()) return;
                            if (approved) {
                                file.refresh(false, false);
                                if (file.getModificationStamp() != diskStamp) throw new IllegalStateException("File changed on disk.");
                                if (!base.resolve(relative).toRealPath().equals(target)) throw new IllegalStateException("Target changed.");
                                new FileSnapshotWriter().write(project, file, document, request, proposal);
                            }
                            result.complete(new ModificationResult(request.requestId(), proposal.proposalId(),
                                approved ? ModificationResult.Outcome.APPLIED : ModificationResult.Outcome.CANCELLED,
                                approved ? "Change applied. Ctrl+Z in the editor can undo it." : "Cancelled. Original kept."));
                        } catch (Exception ex) { com.intellij.openapi.diagnostic.Logger.getInstance(ValidatedModificationService.class).warn("F2 apply failed", ex); fail(result, request, "Cannot apply change. The file may have changed or be read-only. Reopen and review again."); }
                    });
                });
            } catch (Exception ex) { fail(result, request, "Select an existing file inside this project."); }
        });
        return result;
    }
    private static void fail(CompletableFuture<?> result, ModificationRequest request, String message) {
        result.completeExceptionally(new ServiceFailure(request.requestId(), ServiceFailure.Category.INVALID_INPUT, message, false));
    }
}
