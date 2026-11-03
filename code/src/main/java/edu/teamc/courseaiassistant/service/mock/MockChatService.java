package edu.teamc.courseaiassistant.service.mock;

import edu.teamc.courseaiassistant.model.*;
import edu.teamc.courseaiassistant.service.ChatService;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mock chat service for testing with configurable delays and failure scenarios.
 */
public final class MockChatService implements ChatService {

    public enum Scenario {
        SUCCESS_COURSE_GROUNDED,
        SUCCESS_GENERAL_KNOWLEDGE,
        TIMEOUT,
        NETWORK_ERROR,
        SLOW_SUCCESS
    }

    private volatile Scenario scenario;
    private final long delayMillis;

    public MockChatService(Scenario scenario, long delayMillis) {
        this.scenario = Objects.requireNonNull(scenario);
        if (delayMillis < 0) throw new IllegalArgumentException("Delay cannot be negative");
        this.delayMillis = delayMillis;
    }

    public MockChatService() {
        this(Scenario.SUCCESS_COURSE_GROUNDED, 500);
    }

    public void setScenario(Scenario scenario) {
        this.scenario = Objects.requireNonNull(scenario);
    }

    @Override
    @NotNull
    public CompletableFuture<ChatAnswer> ask(@NotNull ChatRequest request) {
        Scenario selected = scenario;
        long delay = selected == Scenario.SLOW_SUCCESS ? Math.max(2000, delayMillis) : delayMillis;
        CompletableFuture<ChatAnswer> future = new CompletableFuture<>();
        // No sleeping common-pool worker. A cancelled mock skips reply generation.
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            if (future.isDone()) return;
            try {
                switch (selected) {
                    case SUCCESS_COURSE_GROUNDED, SLOW_SUCCESS -> future.complete(createCourseGroundedAnswer(request));
                    case SUCCESS_GENERAL_KNOWLEDGE -> future.complete(createGeneralKnowledgeAnswer(request));
                    case TIMEOUT -> future.completeExceptionally(new ServiceFailure(request.getRequestId(),
                            ServiceFailure.Category.TIMEOUT, "Simulated request timeout", true));
                    case NETWORK_ERROR -> future.completeExceptionally(new ServiceFailure(request.getRequestId(),
                            ServiceFailure.Category.NETWORK_ERROR, "Simulated network connection failure", true));
                }
            } catch (Exception error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private ChatAnswer createCourseGroundedAnswer(ChatRequest request) {
        SourceReference source = new SourceReference(
                "S1",
                "lecture-notes-week3.pdf",
                15,
                17,
                null
        );

        String answer;
        if (request.getCodeContext() != null) {
            answer = String.format(
                    "Based on the code you selected from %s (lines %d–%d), this appears to be a %s method. " +
                    "According to the course materials, when working with such methods, consider the following:\n\n" +
                    "1. Ensure proper null checks before accessing object properties\n" +
                    "2. Follow the design patterns discussed in Week 3 lectures\n" +
                    "3. Consider edge cases and exception handling\n\n" +
                    "The course notes emphasize defensive programming practices in this context.",
                    request.getCodeContext().getProjectRelativePath(),
                    request.getCodeContext().getStartLine(),
                    request.getCodeContext().getEndLine(),
                    request.getCodeContext().getLanguageId()
            );
        } else {
            answer = "According to the course materials from Week 3, this concept relates to object-oriented design principles. " +
                    "The lecture notes emphasize the importance of encapsulation and proper method design.\n\n" +
                    "Key points:\n" +
                    "• Maintain clear separation of concerns\n" +
                    "• Use appropriate access modifiers\n" +
                    "• Consider the Single Responsibility Principle\n\n" +
                    "Refer to the textbook Chapter 5 for more detailed examples.";
        }

        return new ChatAnswer(
                request.getRequestId(),
                answer + " [S1]",
                List.of(source),
                ChatAnswer.Provenance.COURSE_GROUNDED,
                "The source above is invented for this UI example; no course material was retrieved."
        );
    }

    private ChatAnswer createGeneralKnowledgeAnswer(ChatRequest request) {
        String answer = "This is a general programming question that doesn't directly relate to specific course materials. " +
                "Based on general Java knowledge:\n\n" +
                "The concept you're asking about is commonly used in software development. " +
                "Best practices suggest following established design patterns and maintaining clean code principles.\n\n" +
                "For course-specific guidance, please refer to the lecture notes or ask about topics covered in class.";

        return new ChatAnswer(
                request.getRequestId(),
                answer,
                List.of(),
                ChatAnswer.Provenance.GENERAL_KNOWLEDGE,
                "Response is based on general knowledge; no specific course material is referenced."
        );
    }
}
