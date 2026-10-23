package edu.teamc.courseaiassistant.sample;

import java.util.List;
import java.util.Objects;

/** Local UI example data, not the final chat-service contract. */
public record SampleReply(String answer, List<Source> sources, String disclosure) {
    public SampleReply {
        Objects.requireNonNull(answer);
        sources = List.copyOf(sources);
        Objects.requireNonNull(disclosure);
    }

    public record Source(String documentName, int page) {}
}
