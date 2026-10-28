package edu.teamc.courseaiassistant.modification;

import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import edu.teamc.courseaiassistant.model.ChangeProposal;
import javax.swing.*;
import java.awt.*;

/** Built-in read-only side-by-side diff embedded in explicit approval dialog. */
final class DiffReviewDialog extends DialogWrapper {
    private final Project project;
    private final ChangeProposal proposal;
    DiffReviewDialog(Project project, ChangeProposal proposal) {
        super(project, true);
        this.project = project; this.proposal = proposal;
        setTitle("F2: Review " + proposal.change().projectRelativePath());
        setOKButtonText("Approve and Apply"); setCancelButtonText("Cancel - Keep Original");
        init();
    }
    @Override protected JComponent createCenterPanel() {
        DiffRequestPanel diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest(proposal.change().summary(),
            factory.create(project, proposal.change().originalFileText()),
            factory.create(project, proposal.change().proposedFileText()), "Original snapshot", "Proposed replacement");
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
        diff.setRequest(request);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JTextArea summary = new JTextArea(proposal.change().summary());
        summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        panel.add(summary, BorderLayout.NORTH); panel.add(diff.getComponent(), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(1000, 620));
        return panel;
    }
}
