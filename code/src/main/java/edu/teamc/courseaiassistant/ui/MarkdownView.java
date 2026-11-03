package edu.teamc.courseaiassistant.ui;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.tables.*;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

/** CommonMark rendered as native styled text: no HTML engine, URL requests or remote images. */
final class MarkdownView extends ChatWidgets.Text {
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private String markdown;
    private final List<MarkdownTable> tables = new ArrayList<>();

    MarkdownView(String markdown) {
        super("", false, 0);
        this.markdown = markdown;
        render();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (markdown != null) render();
    }

    private void render() {
        int start = getSelectionStart(), end = getSelectionEnd();
        try {
            tables.clear();
            getStyledDocument().remove(0, getDocument().getLength());
            var style = new SimpleAttributeSet();
            StyleConstants.setFontFamily(style, ChatTheme.font(0, false).getFamily());
            StyleConstants.setFontSize(style, ChatTheme.font(0, false).getSize());
            StyleConstants.setForeground(style, ChatTheme.current().text());
            blocks(PARSER.parse(markdown), style, 0);
            while (getDocument().getLength() > 0 && getDocument().getText(getDocument().getLength()-1, 1).equals("\n"))
                getDocument().remove(getDocument().getLength()-1, 1);
            select(Math.min(start, getDocument().getLength()), Math.min(end, getDocument().getLength()));
        } catch (BadLocationException error) { throw new IllegalStateException(error); }
    }

    private void append(String text, AttributeSet style) throws BadLocationException {
        getStyledDocument().insertString(getDocument().getLength(), text, style);
    }

    private void blocks(Node parent, AttributeSet base, int depth) throws BadLocationException {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            var style = new SimpleAttributeSet(base);
            int offset = getDocument().getLength();
            if (node instanceof Heading heading) {
                StyleConstants.setBold(style, true);
                StyleConstants.setFontSize(style, ChatTheme.font(0, false).getSize() + Math.max(1, 7-heading.getLevel()*2));
                inline(node, style); append("\n", base);
            } else if (node instanceof TableBlock) {
                MarkdownTable table = new MarkdownTable(node, base);
                tables.add(table);
                var attributes = new SimpleAttributeSet();
                StyleConstants.setComponent(attributes, table);
                append("\uFFFC", attributes);
                append("\n", base);
            } else if (node instanceof Paragraph) {
                inline(node, style); append("\n", base);
            } else if (node instanceof FencedCodeBlock code) {
                code(code.getLiteral(), style);
            } else if (node instanceof IndentedCodeBlock code) {
                code(code.getLiteral(), style);
            } else if (node instanceof BulletList || node instanceof OrderedList) {
                int number = node instanceof OrderedList list ? list.getMarkerStartNumber() : 0;
                for (Node item=node.getFirstChild(); item!=null; item=item.getNext()) {
                    int itemStart=getDocument().getLength();
                    append(node instanceof OrderedList ? (number++) + ".  " : "•  ", base);
                    blocks(item, base, depth+1);
                    String itemText=getDocument().getText(itemStart,getDocument().getLength()-itemStart);
                    int firstLine=itemText.indexOf('\n');
                    paragraph(itemStart,firstLine<0?itemText.length():firstLine,depth+1,3);
                }
                append("\n", base);
            } else if (node instanceof BlockQuote) {
                StyleConstants.setItalic(style, true); StyleConstants.setForeground(style, ChatTheme.current().muted());
                blocks(node, style, depth+1);
            } else if (node instanceof ThematicBreak) {
                append("────────────\n", base);
            } else if (node instanceof HtmlBlock html) {
                append(html.getLiteral(), style); append("\n", base);
            } else blocks(node, base, depth);
            if (!(node instanceof BulletList || node instanceof OrderedList))
                paragraph(offset, getDocument().getLength()-offset, depth, node instanceof Heading ? 8 : 6);
            if (node instanceof TableBlock) {
                // Swing applies proportional line spacing to the entire embedded component's height.
                // A table must use its measured height without the prose's extra 22% leading.
                var spacing = new SimpleAttributeSet();
                StyleConstants.setLineSpacing(spacing, 0);
                getStyledDocument().setParagraphAttributes(offset, getDocument().getLength()-offset, spacing, false);
            }
        }
    }

    @Override int heightAt(int width) {
        for (MarkdownTable table : tables) table.measure(Math.max(1, width - 2));
        return super.heightAt(width);
    }

    @Override String plainText() {
        String text = super.plainText();
        for (MarkdownTable table : tables) {
            int marker = text.indexOf('\uFFFC');
            if (marker >= 0) text = text.substring(0, marker) + table.plainText() + text.substring(marker + 1);
        }
        return text;
    }

    /** Native, selectable cell text; equal columns wrap to the current answer width. */
    private static final class MarkdownTable extends JPanel {
        private final List<List<MarkdownView>> rows = new ArrayList<>();
        private Dimension measured = new Dimension(280, 1);
        private final List<Integer> heights = new ArrayList<>();

        MarkdownTable(Node block, AttributeSet base) throws BadLocationException {
            super(null);
            setOpaque(false);
            setName("markdownTable");
            for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    List<MarkdownView> cells = new ArrayList<>();
                    for (Node child = row.getFirstChild(); child != null; child = child.getNext()) {
                        TableCell cell = (TableCell) child;
                        var view = new MarkdownView("");
                        var style = new SimpleAttributeSet(base);
                        StyleConstants.setBold(style, cell.isHeader());
                        view.inline(cell, style);
                        var paragraph = new SimpleAttributeSet();
                        int alignment = cell.getAlignment() == TableCell.Alignment.CENTER ? StyleConstants.ALIGN_CENTER
                                : cell.getAlignment() == TableCell.Alignment.RIGHT ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT;
                        StyleConstants.setAlignment(paragraph, alignment);
                        view.getStyledDocument().setParagraphAttributes(0, view.getDocument().getLength(), paragraph, false);
                        // Preserve the rendered node styles across theme changes via the outer renderer.
                        view.markdown = null;
                        cells.add(view);
                        add(view);
                    }
                    rows.add(cells);
                }
            }
            measure(280);
        }

        void measure(int width) {
            heights.clear();
            int total = 1;
            for (List<MarkdownView> row : rows) {
                int height = 0;
                int columns = Math.max(1, row.size());
                for (int i = 0; i < row.size(); i++) {
                    int left = i * (width - 1) / columns;
                    int right = (i + 1) * (width - 1) / columns;
                    height = Math.max(height, row.get(i).heightAt(Math.max(1, right - left - 16)) + 16);
                }
                heights.add(height);
                total += height;
            }
            Dimension next = new Dimension(width, total);
            if (!next.equals(measured)) { measured = next; revalidate(); }
        }

        @Override public Dimension getPreferredSize() { return new Dimension(measured); }
        @Override public Dimension getMinimumSize() { return new Dimension(0, measured.height); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override public void doLayout() {
            int y = 1;
            for (int r = 0; r < rows.size(); r++) {
                List<MarkdownView> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    int left = c * (getWidth() - 1) / row.size();
                    int right = (c + 1) * (getWidth() - 1) / row.size();
                    row.get(c).setBounds(left + 8, y + 7, Math.max(1, right - left - 16), heights.get(r) - 14);
                }
                y += heights.get(r);
            }
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            graphics.setColor(ChatTheme.current().bubble());
            if (!heights.isEmpty()) graphics.fillRect(0, 0, getWidth(), heights.getFirst() + 1);
            graphics.setColor(ChatTheme.current().border());
            int y = 0;
            for (int r = 0; r < rows.size(); r++) {
                graphics.drawLine(0, y, getWidth() - 1, y);
                int columns = rows.get(r).size();
                for (int c = 0; c <= columns; c++) {
                    int x = c * (getWidth() - 1) / Math.max(1, columns);
                    graphics.drawLine(x, y, x, y + heights.get(r));
                }
                y += heights.get(r);
            }
            graphics.drawLine(0, y, getWidth() - 1, y);
        }

        String plainText() {
            return rows.stream().map(row -> row.stream().map(MarkdownView::plainText)
                    .collect(java.util.stream.Collectors.joining("\t")))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private void code(String literal, SimpleAttributeSet style) throws BadLocationException {
        StyleConstants.setFontFamily(style, java.awt.Font.MONOSPACED);
        StyleConstants.setBackground(style, ChatTheme.current().bubble());
        append(literal, style);
        if (!literal.endsWith("\n")) append("\n", style);
        append("\n", style);
    }

    private void paragraph(int offset, int length, int depth, int space) {
        var style=new SimpleAttributeSet();
        StyleConstants.setLeftIndent(style, depth*14f);
        StyleConstants.setSpaceAbove(style, 3); StyleConstants.setSpaceBelow(style, space);
        StyleConstants.setLineSpacing(style, .22f);
        getStyledDocument().setParagraphAttributes(offset, length, style, false);
    }

    private void inline(Node parent, AttributeSet base) throws BadLocationException {
        for (Node node=parent.getFirstChild(); node!=null; node=node.getNext()) {
            var style=new SimpleAttributeSet(base);
            if (node instanceof org.commonmark.node.Text text) append(text.getLiteral(), style);
            else if (node instanceof Code code) {
                StyleConstants.setFontFamily(style, java.awt.Font.MONOSPACED);
                StyleConstants.setBackground(style, ChatTheme.current().bubble()); append(code.getLiteral(), style);
            } else if (node instanceof SoftLineBreak) append(" ", style);
            else if (node instanceof HardLineBreak) append("\n", style);
            else if (node instanceof StrongEmphasis) { StyleConstants.setBold(style,true); inline(node,style); }
            else if (node instanceof Emphasis) { StyleConstants.setItalic(style,true); inline(node,style); }
            else if (node instanceof Link link) { inline(node,style); append(" ("+link.getDestination()+")",style); }
            else if (node instanceof Image) { append("[Image: ",style); inline(node,style); append("]",style); }
            else if (node instanceof HtmlInline html) append(html.getLiteral(),style);
            else inline(node,style);
        }
    }
}
