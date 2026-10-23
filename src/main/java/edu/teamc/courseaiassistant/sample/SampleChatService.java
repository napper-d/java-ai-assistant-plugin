package edu.teamc.courseaiassistant.sample;

import java.util.List;

public final class SampleChatService implements SampleReplyProvider {
    @Override
    public SampleReply reply(SampleScenario scenario) {
        return switch (scenario) {
            case COURSE_CITATION -> new SampleReply(
                    "An interface defines a contract that different implementations can share. "
                            + "For example, a service interface lets callers depend on behavior "
                            + "rather than a concrete implementation. [S1]",
                    List.of(new SampleReply.Source("sample-course.pdf", 3)),
                    "The source above is invented for this UI example; no course material was retrieved.");
            case GENERAL_KNOWLEDGE -> new SampleReply(
                    "An ArrayList is usually suitable when you need indexed access to a sequence of values.",
                    List.of(),
                    "Response is based on general knowledge; no specific course material is referenced.");
        };
    }
}
