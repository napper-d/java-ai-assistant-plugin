package edu.teamc.courseaiassistant.ui;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Local chat palette only; never changes the IDE's look and feel or editor scheme. */
record ChatTheme(Color canvas, Color bubble, Color composer, Color border,
                 Color text, Color muted, Color accent, Color selection) {
    static ChatTheme current() {
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = panel != null && panel.getRed() * .299 + panel.getGreen() * .587
                + panel.getBlue() * .114 < 145;
        return dark
                ? new ChatTheme(color(0x242424), color(0x303030), color(0x26292D), color(0x44474D),
                color(0xD5D7DC), color(0x9499A3), color(0x28566C), color(0x34566D))
                : new ChatTheme(color(0xFAFAFB), color(0xEDEEF1), color(0xF3F4F6), color(0xD5D8DF),
                color(0x282C34), color(0x6C727E), color(0x306B91), color(0xD3E4F1));
    }

    static Font font(float offset, boolean bold) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 13);
        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, Math.max(13, base.getSize2D()) + offset);
    }

    private static Color color(int rgb) {
        return new Color(rgb);
    }
}
