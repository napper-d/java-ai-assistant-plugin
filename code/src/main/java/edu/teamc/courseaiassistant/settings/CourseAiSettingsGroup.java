package edu.teamc.courseaiassistant.settings;

import com.intellij.openapi.options.Configurable;

import javax.swing.JComponent;

/** Container for the plugin's project settings children in the IDEA settings tree. */
public final class CourseAiSettingsGroup implements Configurable {
    @Override public String getDisplayName() { return "Course AI Assistant"; }
    @Override public JComponent createComponent() { return null; }
    @Override public boolean isModified() { return false; }
    @Override public void apply() {}
    @Override public void reset() {}
}
