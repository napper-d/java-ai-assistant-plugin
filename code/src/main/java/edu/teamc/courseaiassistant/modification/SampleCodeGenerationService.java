package edu.teamc.courseaiassistant.modification;

import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.CodeGenerationService;
import java.util.concurrent.CompletableFuture;

/** Fixed local demo only. No model or network. Accepts only exact demo snapshots. */
public final class SampleCodeGenerationService implements CodeGenerationService {
    public static final String BUG = "public class SumDemo {\n    public int sum(int a, int b) {\n        return a - b;\n    }\n}\n";
    public static final String REFACTOR = "public class GreetingDemo {\n    public String greet(String name) {\n        return \"Hello, \" + name.trim();\n    }\n}\n";
    @Override public CompletableFuture<ChangeProposal> generate(ModificationRequest request) {
        String original = request.originalFileText();
        String proposed;
        if (original.equals(BUG)) proposed = original.replace("return a - b;", "return a + b;");
        else if (original.equals(REFACTOR)) proposed = "public class GreetingDemo {\n    public String greet(String name) {\n        return \"Hello, \" + normalizeName(name);\n    }\n\n    private String normalizeName(String name) {\n        return name.trim();\n    }\n}\n";
        else return CompletableFuture.failedFuture(new IllegalArgumentException("Sample only supports unchanged SumDemo.java or GreetingDemo.java."));
        return CompletableFuture.completedFuture(new ChangeProposal(request.requestId(), new CodeChange(
            request.codeContext().getProjectRelativePath(), original, proposed,
            "FIXED LOCAL SAMPLE - no AI connected. " + request.instruction())));
    }
}
