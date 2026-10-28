package edu.teamc.courseaiassistant.modification;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import edu.teamc.courseaiassistant.context.CodeContext;
import edu.teamc.courseaiassistant.llm.*;
import edu.teamc.courseaiassistant.model.*;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Explicit live entry; model configuration is loaded only from the chosen directory. */
public final class LiveModifyAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project=event.getProject(); var editor=event.getData(CommonDataKeys.EDITOR);
        if(project==null || editor==null || project.getBasePath()==null) return;
        var document=editor.getDocument();
        var file=FileDocumentManager.getInstance().getFile(document);
        if(file==null) return;
        String instruction=Messages.showInputDialog(project,
            "Your instruction and complete Java file will be sent to the configured model.\nOnly explicit Diff approval applies changes.",
            "Modify Java Code with LLM", Messages.getQuestionIcon());
        if(instruction==null || instruction.isBlank()) return;
        var chooser=FileChooserDescriptorFactory.createSingleFolderDescriptor();
        chooser.setTitle("Select directory containing your LLM .env configuration");
        var folder=FileChooser.chooseFile(chooser, project, null);
        if(folder==null) return;
        Path base=Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path target=Path.of(file.getPath()).toAbsolutePath().normalize();
        if(!target.startsWith(base)) { Messages.showErrorDialog(project,"Select a file inside this project.","F2"); return; }
        var selection=editor.getSelectionModel();
        String selected=selection.hasSelection()?selection.getSelectedText():document.getText();
        int start=selection.hasSelection()?document.getLineNumber(selection.getSelectionStart())+1:1;
        int end=selection.hasSelection()?document.getLineNumber(Math.max(selection.getSelectionStart(),selection.getSelectionEnd()-1))+1:document.getLineCount();
        var context=new CodeContext(base.relativize(target).toString().replace('\\','/'),"JAVA",selected,start,end,document.getModificationStamp(),null);
        var request=new ModificationRequest(instruction,context,document.getText());
        Path configDirectory=Path.of(folder.getPath());
        ProgressManager.getInstance().run(new Task.Backgroundable(project,"F2: Generate and review code",true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                CompletableFuture<ModificationResult> operation=new CompletableFuture<>();
                var lifetime=Disposer.newDisposable("F2 request");
                Disposer.register(lifetime,()->operation.cancel(true));
                try {
                    Disposer.register(project,lifetime);
                    var config=LlmConfigLoader.load(configDirectory);
                    try(var client=new HttpLlmClient(config); var generator=new LlmCodeGenerationService(client,config)) {
                        var pending=new ValidatedModificationService(project,generator).modify(request);
                        operation.whenComplete((v,e)->{if(operation.isCancelled()) pending.cancel(true);});
                        pending.whenComplete((v,e)->{if(e!=null) operation.completeExceptionally(e); else operation.complete(v);});
                        indicator.setText("Generating proposal; review the Diff when it opens...");
                        while(!operation.isDone()) {
                            if(indicator.isCanceled() || project.isDisposed()) {operation.cancel(true);return;}
                            try {operation.get(100,TimeUnit.MILLISECONDS);} catch(TimeoutException ignored) { }
                        }
                        if(operation.isCancelled()) return;
                        var result=operation.get();
                        ApplicationManager.getApplication().invokeLater(()->{
                            if(!project.isDisposed()) Messages.showInfoMessage(project,result.message(),"F2 Modification");
                        });
                    }
                } catch(CancellationException ignored) {
                } catch(InterruptedException error) {Thread.currentThread().interrupt();operation.cancel(true);
                } catch(Exception error) {
                    Throwable cause=error;
                    while(cause.getCause()!=null && (cause instanceof ExecutionException || cause instanceof CompletionException)) cause=cause.getCause();
                    String message=cause instanceof ServiceFailure failure?failure.getMessage():
                        "Unable to load model configuration. Check LLM_PROVIDER, LLM_BASE_URL, LLM_MODEL and LLM_API_KEY in the selected directory.";
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(!project.isDisposed()) Messages.showErrorDialog(project,message,"F2 Modification Failed");
                    });
                } finally {Disposer.dispose(lifetime);}
            }
        });
    }
    @Override public void update(@NotNull AnActionEvent event) {
        var file=event.getData(CommonDataKeys.VIRTUAL_FILE);
        event.getPresentation().setEnabledAndVisible(event.getProject()!=null && event.getData(CommonDataKeys.EDITOR)!=null
            && file!=null && "java".equalsIgnoreCase(file.getExtension()));
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
}
