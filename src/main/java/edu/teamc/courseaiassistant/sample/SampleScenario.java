package edu.teamc.courseaiassistant.sample;

public enum SampleScenario {
    COURSE_CITATION("With course citation"),
    GENERAL_KNOWLEDGE("General knowledge");

    private final String label;

    SampleScenario(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
