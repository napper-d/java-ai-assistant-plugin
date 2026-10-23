package edu.teamc.courseaiassistant.sample;

/** Deliberately accepts no question or editor context: these are fixed local examples. */
@FunctionalInterface
public interface SampleReplyProvider {
    SampleReply reply(SampleScenario scenario);
}
