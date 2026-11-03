import edu.teamc.courseaiassistant.modification.*;
import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.context.CodeContext;

/** No IDE startup, model, or network. Checks actual F2 sample/validation production code. */
public final class F2ContractCheck {
    static int count;
    static void check(boolean ok) { if (!ok) throw new AssertionError(); count++; }
    static void reject(ModificationRequest request, ChangeProposal proposal) {
        try { new ProposalValidator().validate(request, proposal); throw new AssertionError("Accepted invalid proposal"); }
        catch (IllegalArgumentException | NullPointerException expected) { count++; }
    }
    public static void main(String[] args) {
        var context = new CodeContext("SumDemo.java", "JAVA", "sum", 1, 1, 1L, null);
        var request = new ModificationRequest("Fix sum", context, SampleCodeGenerationService.BUG);
        var proposal = new SampleCodeGenerationService().generate(request).join();
        new ProposalValidator().validate(request, proposal);
        check(proposal.change().proposedFileText().contains("return a + b;"));
        check(proposal.requestId().equals(request.requestId()));
        var change = proposal.change();
        reject(request, new ChangeProposal("other", change));
        reject(request, new ChangeProposal(request.requestId(), new CodeChange("other.java", change.originalFileText(), change.proposedFileText(), "")));
        reject(request, new ChangeProposal(request.requestId(), new CodeChange("SumDemo.java", "stale", change.proposedFileText(), "")));
        reject(request, new ChangeProposal(request.requestId(), new CodeChange("SumDemo.java", change.originalFileText(), change.originalFileText(), "")));
        reject(request, new ChangeProposal(request.requestId(), new CodeChange("SumDemo.java", change.originalFileText(), "", "")));
        reject(request, new ChangeProposal(request.requestId(), new CodeChange("SumDemo.java", change.originalFileText(), "x\r\n", "")));
        reject(request, null);
        var ref = new ModificationRequest("Extract method", context, SampleCodeGenerationService.REFACTOR);
        check(new SampleCodeGenerationService().generate(ref).join().change().proposedFileText().contains("private String normalizeName"));
        try { new SampleCodeGenerationService().generate(new ModificationRequest("fix", context, "arbitrary")).join(); throw new AssertionError(); }
        catch (java.util.concurrent.CompletionException expected) { count++; }
        System.out.println("PASS: " + count + " F2 contract checks. Diff/write/undo require IDE acceptance.");
    }
}
