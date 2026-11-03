package edu.teamc.courseaiassistant.ui;


import javax.swing.*;
import java.awt.*;

/** A single Tool Window surface, with compact right-aligned navigation and persistent Chat. */
public final class AssistantPanel extends JPanel {
    private final ChatPanel chat;
    private final JPanel pages = ChatWidgets.transparent(new CardLayout());
    private final JToggleButton chatTab = new NavigationButton(true);
    private final JToggleButton materialsTab = new NavigationButton(false);

    public AssistantPanel(ChatPanel chat, KnowledgeBasePanel library) {
        super(new BorderLayout());
        this.chat = chat;
        setName("assistantPanel");
        JPanel toolbar = ChatWidgets.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        ButtonGroup group = new ButtonGroup(); group.add(chatTab); group.add(materialsTab);
        chatTab.setName("chatTab"); materialsTab.setName("materialsTab");
        chatTab.setToolTipText("Chat"); materialsTab.setToolTipText("Course materials");
        chatTab.getAccessibleContext().setAccessibleName("Chat");
        materialsTab.getAccessibleContext().setAccessibleName("Course materials");
        chatTab.setPreferredSize(new Dimension(32, 32)); materialsTab.setPreferredSize(new Dimension(32, 32));
        JButton clear = chat.useExternalToolbar();
        toolbar.add(chatTab); toolbar.add(clear); toolbar.add(materialsTab);
        chatTab.addActionListener(event -> showChat());
        materialsTab.addActionListener(event -> {
            ((CardLayout) pages.getLayout()).show(pages, "materials");
            clear.setVisible(false);
        });
        chatTab.addActionListener(event -> clear.setVisible(true));
        pages.add(chat, "chat"); pages.add(library, "materials");
        add(toolbar, BorderLayout.NORTH); add(pages, BorderLayout.CENTER);
        showChat();
    }

    public ChatPanel chatPanel() { return chat; }

    public ChatPanel showChat() {
        chatTab.setSelected(true);
        ((CardLayout) pages.getLayout()).show(pages, "chat");
        // Also restore toolbar visibility when the editor action navigates here.
        chat.useExternalToolbar().setVisible(true);
        return chat;
    }

    private static final class NavigationButton extends JToggleButton {
        private final boolean chat;
        NavigationButton(boolean chat) {
            this.chat = chat; setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setOpaque(false); setRolloverEnabled(true);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var theme = ChatTheme.current();
            if (isSelected() || getModel().isRollover()) {
                g.setColor(theme.bubble()); g.fillRoundRect(1, 1, getWidth()-2, getHeight()-2, 6, 6);
            }
            g.setColor(isSelected() ? theme.text() : theme.muted()); g.setStroke(new BasicStroke(1.5f));
            int x = (getWidth()-18)/2, y = (getHeight()-18)/2;
            if (chat) {
                g.drawRoundRect(x, y, 18, 14, 5, 5);
                g.drawLine(x+4, y+14, x+2, y+18); g.drawLine(x+2, y+18, x+9, y+14);
                g.drawLine(x+5, y+5, x+13, y+5); g.drawLine(x+5, y+9, x+10, y+9);
            } else {
                var folder = new java.awt.geom.Path2D.Float();
                folder.moveTo(x,y+3); folder.lineTo(x+7,y+3); folder.lineTo(x+10,y+6);
                folder.lineTo(x+18,y+6); folder.lineTo(x+18,y+17); folder.lineTo(x,y+17); folder.closePath(); g.draw(folder);
            }
            if (hasFocus()) { g.setColor(theme.border()); g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,6,6); }
            g.dispose();
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(ChatTheme.current().canvas()); graphics.fillRect(0, 0, getWidth(), getHeight());
    }
}
