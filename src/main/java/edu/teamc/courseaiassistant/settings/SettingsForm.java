package edu.teamc.courseaiassistant.settings;

import com.intellij.ui.HideableDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/** Shared presentation for the sibling project settings pages. Contains no persistence or service logic. */
final class SettingsForm {
    private SettingsForm() {}

    static JPanel form() { return new JPanel(new GridBagLayout()); }

    static JPanel page(JPanel form) {
        form.setBorder(JBUI.Borders.empty(12));
        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top);
        scroll.setBorder(JBUI.Borders.empty());
        JPanel root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    static void row(JPanel form, String label, JComponent component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = form.getComponentCount() / 2; constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = JBUI.insets(5, 0, 5, 10);
        JLabel caption = new JLabel(label);
        caption.setLabelFor(component);
        form.add(caption, constraints);
        constraints.gridx = 1; constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(component, constraints);
    }

    static void field(JPanel form, Map<String, JTextField> fields, String prefix, String name, String label) {
        JTextField input = new JTextField(28);
        input.setName(prefix + "_" + name);
        input.getAccessibleContext().setAccessibleName(label);
        fields.put(name, input);
        row(form, label, input);
    }

    static void comment(JPanel form, String text) {
        JBLabel hint = new JBLabel(text, UIUtil.ComponentStyle.SMALL) {
            @Override public Color getForeground() { return UIUtil.getContextHelpForeground(); }
        };
        hint.setAllowAutoWrapping(true);
        hint.setName("settingsComment");
        row(form, "", hint);
    }

    static void credentials(JPanel form, JPasswordField password, JCheckBox clear) {
        row(form, "New API key", password);
        clear.setText("Clear the stored API key");
        clear.addActionListener(event -> {
            password.setEnabled(!clear.isSelected());
            if (clear.isSelected()) password.setText("");
        });
        row(form, "", clear);
        comment(form, "Leave the key blank to keep it. Keys are stored in IDE Password Safe.");
    }

    static void advanced(JPanel form, JPanel content) {
        JPanel section = new JPanel(new BorderLayout());
        section.setName("advancedSettings");
        HideableDecorator decorator = new HideableDecorator(section, "Advanced settings", false);
        decorator.setContentComponent(content);
        decorator.setOn(false);
        section.putClientProperty("settingsDecorator", decorator);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = form.getComponentCount() / 2;
        constraints.gridwidth = 2; constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = JBUI.insetsTop(12);
        form.add(section, constraints);
    }
}
