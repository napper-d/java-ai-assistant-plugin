package edu.teamc.courseaiassistant.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.AbstractDocument;
import javax.swing.text.LabelView;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import java.awt.*;

/** Shared Swing components for chat and retrieval, including live theme updates. */
final class ChatWidgets {
    private ChatWidgets() {}

    static Graphics2D graphics(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return copy;
    }

    static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static final class Label extends JLabel {
        private final float sizeOffset;
        private final boolean bold;
        private final boolean muted;

        Label(String text, float sizeOffset, boolean bold, boolean muted) {
            super(text);
            this.sizeOffset = sizeOffset;
            this.bold = bold;
            this.muted = muted;
            updateUI();
        }

        @Override public void updateUI() {
            super.updateUI();
            setFont(ChatTheme.font(sizeOffset, bold));
            setForeground(muted ? ChatTheme.current().muted() : ChatTheme.current().text());
        }
    }

    static class Text extends JTextPane {
        private final boolean muted;
        private final float sizeOffset;

        Text(String text, boolean muted, float sizeOffset) {
            this.muted = muted;
            this.sizeOffset = sizeOffset;
            setEditorKit(new StyledEditorKit() {
                @Override public ViewFactory getViewFactory() {
                    ViewFactory standard = super.getViewFactory();
                    return element -> AbstractDocument.ContentElementName.equals(element.getName())
                            ? new LabelView(element) {
                                @Override public float getMinimumSpan(int axis) {
                                    return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                                }
                            } : standard.create(element);
                }
            });
            setEditable(false);
            setOpaque(false);
            setBorder(null);
            setMargin(new Insets(0, 0, 0, 0));
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            setText(text);
            updateUI();
        }

        @Override public void updateUI() {
            super.updateUI();
            setFont(ChatTheme.font(sizeOffset, false));
            setForeground(muted ? ChatTheme.current().muted() : ChatTheme.current().text());
            setSelectionColor(ChatTheme.current().selection());
            setSelectedTextColor(ChatTheme.current().text());
            if (getDocument() != null) {
                SimpleAttributeSet spacing = new SimpleAttributeSet();
                StyleConstants.setLineSpacing(spacing, .22f);
                getStyledDocument().setParagraphAttributes(0, getDocument().getLength(), spacing, false);
            }
        }

        int heightAt(int width) {
            setSize(Math.max(1, width), Short.MAX_VALUE);
            return getPreferredSize().height;
        }

        String plainText() {
            try {
                return getDocument().getText(0, getDocument().getLength());
            } catch (BadLocationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    static class RoundedPanel extends JPanel {
        private final boolean userBubble;
        private final boolean outlined;

        RoundedPanel(LayoutManager layout, boolean userBubble, boolean outlined) {
            super(layout);
            this.userBubble = userBubble;
            this.outlined = outlined;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            ChatTheme theme = ChatTheme.current();
            g.setColor(userBubble ? theme.bubble() : theme.composer());
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            if (outlined) {
                g.setColor(theme.border());
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static final class Input extends JTextArea {
        private final String placeholder;
        Input() {
            this("Ask about Java…");
        }

        Input(String placeholder) {
            super(3, 18);
            this.placeholder = placeholder;
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setBorder(null);
            setMargin(new Insets(4, 0, 4, 0));
            updateUI();
        }

        @Override public void updateUI() {
            super.updateUI();
            setFont(ChatTheme.font(0, false));
            setForeground(ChatTheme.current().text());
            setCaretColor(ChatTheme.current().text());
            setSelectionColor(ChatTheme.current().selection());
            setSelectedTextColor(ChatTheme.current().text());
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                Graphics2D g = graphics(graphics);
                g.setColor(ChatTheme.current().muted());
                g.setFont(getFont());
                g.drawString(placeholder, getInsets().left,
                        getInsets().top + getFontMetrics(getFont()).getAscent());
                g.dispose();
            }
        }
    }

    static final class Button extends JButton {
        private final boolean send;

        Button(boolean send) {
            super(send ? "Send" : "Clear chat");
            this.send = send;
            setPreferredSize(new Dimension(32, 32));
            setMinimumSize(new Dimension(32, 32));
            setBorder(new EmptyBorder(0, 0, 0, 0));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setToolTipText(send ? "Send question (Enter)" : "Clear chat");
            getAccessibleContext().setAccessibleName(send ? "Send" : "Clear chat");
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            ChatTheme theme = ChatTheme.current();
            boolean hover = getModel().isRollover() || getModel().isPressed();
            if (send || hover) {
                g.setColor(send && isEnabled() ? theme.accent() : theme.bubble());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
            g.setColor(isEnabled() ? (send ? Color.WHITE : theme.text()) : theme.muted());
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int x = getWidth() / 2;
            int y = getHeight() / 2;
            if (send) {
                g.drawLine(x, y + 7, x, y - 7);
                g.drawLine(x, y - 7, x - 6, y - 1);
                g.drawLine(x, y - 7, x + 6, y - 1);
            } else {
                g.drawRoundRect(x - 6, y - 6, 12, 14, 2, 2);
                g.drawLine(x - 8, y - 8, x + 8, y - 8);
                g.drawLine(x - 3, y - 11, x + 3, y - 11);
                g.drawLine(x - 2, y - 2, x - 2, y + 4);
                g.drawLine(x + 2, y - 2, x + 2, y + 4);
            }
            if (hasFocus()) {
                g.setColor(theme.text());
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
            }
            g.dispose();
        }
    }

    static final class ExampleSelector<T> extends JComboBox<T> {
        ExampleSelector(T[] items) {
            super(items);
            setRenderer((list, value, index, selected, focused) -> {
                Label label = new Label(value == null ? "" : value.toString(), -1, false, !selected);
                label.setBorder(new EmptyBorder(6, 8, 6, 8));
                label.setOpaque(true);
                label.setBackground(selected ? ChatTheme.current().selection() : ChatTheme.current().composer());
                return label;
            });
            setToolTipText("Choose a fixed local example; no model is connected");
            getAccessibleContext().setAccessibleName("Sample reply example");
        }

        @Override public void updateUI() {
            setUI(new BasicComboBoxUI() {
                @Override protected JButton createArrowButton() {
                    JButton arrow = new JButton() {
                        @Override protected void paintComponent(Graphics graphics) {
                            Graphics2D g = graphics(graphics);
                            g.setColor(ChatTheme.current().muted());
                            g.setStroke(new BasicStroke(1.4f));
                            int x = getWidth() / 2;
                            int y = getHeight() / 2;
                            g.drawLine(x - 3, y - 2, x, y + 1);
                            g.drawLine(x, y + 1, x + 3, y - 2);
                            g.dispose();
                        }
                    };
                    arrow.setOpaque(false);
                    arrow.setFocusable(false);
                    arrow.setContentAreaFilled(false);
                    arrow.setBorder(null);
                    arrow.setPreferredSize(new Dimension(18, 24));
                    return arrow;
                }
                @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean focused) {}
            });
            setFont(ChatTheme.font(-1, false));
            setForeground(ChatTheme.current().muted());
            setBackground(ChatTheme.current().composer());
            setBorder(null);
            setOpaque(false);
        }

        @Override protected void paintBorder(Graphics graphics) {
            if (hasFocus()) {
                Graphics2D g = graphics(graphics);
                g.setColor(ChatTheme.current().muted());
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 7, 7);
                g.dispose();
            }
        }
    }

    /** Shared composer with its actions inside the input card. */
    static JPanel composer(JTextArea question, JComponent tools, JComponent above) {
        JPanel composer = transparent(new BorderLayout(0, 7));
        if (above != null) composer.add(above, BorderLayout.NORTH);
        JPanel inputCard = new RoundedPanel(new BorderLayout(0, 12), false, true);
        inputCard.setBorder(new EmptyBorder(10, 13, 10, 13));
        JScrollPane inputScroll = scrollPane(question);
        inputScroll.getViewport().setOpaque(false);
        inputCard.add(inputScroll, BorderLayout.CENTER);
        inputCard.add(tools, BorderLayout.SOUTH);
        composer.add(inputCard, BorderLayout.CENTER);
        return composer;
    }

    static JScrollPane scrollPane(Component content) {
        JScrollPane pane = new JScrollPane(content) {
            @Override public void updateUI() {
                super.updateUI();
                setBorder(new EmptyBorder(0, 0, 0, 0));
            }
        };
        pane.setBorder(new EmptyBorder(0, 0, 0, 0));
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setVerticalScrollBar(new Scrollbar());
        return pane;
    }

    private static final class Scrollbar extends JScrollBar {
        Scrollbar() {
            setOpaque(false);
            setPreferredSize(new Dimension(7, 0));
            setUnitIncrement(20);
        }
        @Override public void updateUI() {
            setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }
            private JButton emptyButton() {
                JButton button = new JButton();
                button.setFocusable(false);
                button.setPreferredSize(new Dimension(0, 0));
                return button;
            }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {}
            @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle bounds) {
                Graphics2D g = graphics(graphics);
                g.setColor(ChatTheme.current().border());
                g.fillRoundRect(bounds.x + 1, bounds.y, Math.max(3, bounds.width - 2), bounds.height, 5, 5);
                g.dispose();
            }
            });
        }
    }
}
