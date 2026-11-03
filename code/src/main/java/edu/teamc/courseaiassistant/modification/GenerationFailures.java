package edu.teamc.courseaiassistant.modification;

import edu.teamc.courseaiassistant.model.ServiceFailure;
import java.util.concurrent.*;

/** Preserve safe service failures without displaying raw provider diagnostics. */
public final class GenerationFailures {
    private GenerationFailures() { }
    public static Throwable toFailure(String id, Throwable error) {
        while(error.getCause()!=null && (error instanceof CompletionException || error instanceof ExecutionException)) error=error.getCause();
        if(error instanceof CancellationException) return error;
        if(error instanceof ServiceFailure f) return new ServiceFailure(id,f.getCategory(),f.getMessage(),f.isRetryable());
        if(error instanceof TimeoutException) return new ServiceFailure(id,ServiceFailure.Category.TIMEOUT,"Code generation timed out.",true);
        return new ServiceFailure(id,ServiceFailure.Category.UNKNOWN,"Unable to generate a code proposal. Please retry.",true);
    }
}
