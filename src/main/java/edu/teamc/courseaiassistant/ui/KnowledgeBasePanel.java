package edu.teamc.courseaiassistant.ui;

import edu.teamc.courseaiassistant.application.*;
import edu.teamc.courseaiassistant.rag.DocumentImporter.RegisteredDocument;
import edu.teamc.courseaiassistant.rag.RagException;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Filename list with automatic indexing; failures and activity stay in the compact top toolbar. */
public final class KnowledgeBasePanel extends JPanel {
    private final KnowledgeBaseOperations operations;
    private final Supplier<List<Path>> chooseFiles;
    private final MaterialsTable model = new MaterialsTable();
    private final JTable files = new JTable(model) {
        @Override public void updateUI() {
            super.updateUI(); setBackground(ChatTheme.current().canvas()); setForeground(ChatTheme.current().text());
        }
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row=rowAtPoint(event.getPoint());
            if(row<0) return null;
            var file=model.documents.get(convertRowIndexToModel(row));
            return file.path()+ (file.availability()==edu.teamc.courseaiassistant.rag.DocumentImporter.Availability.AVAILABLE ? "" : " — file unavailable");
        }
    };
    private final JButton add=iconButton("+","Add PDF / PPTX","addMaterials");
    private final JButton settings=iconButton("⚙","Course RAG settings","ragSettings");
    private final JButton cancel=iconButton("×","Cancel indexing","cancelLibraryTask");
    private final JButton retry=iconButton("↻","Retry indexing","retryIndexing");
    private final JLabel status=new ChatWidgets.Label("",-1,false,true);
    private final JProgressBar progress=new JProgressBar();
    private final Runnable unsubscribe;
    private KnowledgeBaseSnapshot snapshot;
    private CompletableFuture<?> pending;
    private long generation;
    private boolean busy, disposed;

    public KnowledgeBasePanel(KnowledgeBaseOperations operations, Supplier<List<Path>> chooseFiles, Runnable openSettings) {
        super(new BorderLayout(0,8));
        this.operations=Objects.requireNonNull(operations); this.chooseFiles=Objects.requireNonNull(chooseFiles);
        setName("knowledgeBasePanel"); setBorder(com.intellij.util.ui.JBUI.Borders.empty(10,12));
        JPanel header=ChatWidgets.transparent(new BorderLayout(8,0));
        header.add(new ChatWidgets.Label("Course materials",0,true,false),BorderLayout.WEST);
        JPanel tools=ChatWidgets.transparent(new FlowLayout(FlowLayout.RIGHT,3,0));
        status.setName("libraryStatus");
        progress.setName("indexingProgress"); progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(72,6)); progress.setVisible(false);
        progress.getAccessibleContext().setAccessibleName("Loading or indexing course materials");
        tools.add(status); tools.add(progress); tools.add(cancel); tools.add(retry); tools.add(add); tools.add(settings);
        header.add(tools,BorderLayout.EAST); add(header,BorderLayout.NORTH);
        files.setName("registeredMaterials"); files.setTableHeader(null); files.setShowGrid(false);
        files.setFillsViewportHeight(true); files.setRowHeight(38);
        files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        files.getColumnModel().getColumn(1).setMinWidth(36); files.getColumnModel().getColumn(1).setMaxWidth(36);
        files.getColumnModel().getColumn(1).setCellRenderer((table,value,selected,focus,row,column) -> {
            JButton trash=trashButton(); trash.setEnabled(!busy&&!disposed); return trash;
        });
        files.getColumnModel().getColumn(1).setCellEditor(new TrashEditor());
        files.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column) {
                JLabel label=(JLabel)super.getTableCellRendererComponent(table,value,selected,focus,row,column);
                label.setBorder(com.intellij.util.ui.JBUI.Borders.empty(0,10)); return label;
            }
        });
        JScrollPane list=new JScrollPane(files); list.setColumnHeaderView(null);
        list.setBorder(com.intellij.util.ui.JBUI.Borders.customLine(ChatTheme.current().border()));
        add(list,BorderLayout.CENTER);
        add.addActionListener(event -> addFiles()); settings.addActionListener(event -> openSettings.run());
        cancel.addActionListener(event -> cancelTask()); retry.addActionListener(event -> retry());
        unsubscribe=operations.onSettingsChanged(() -> SwingUtilities.invokeLater(() -> {
            if(disposed) return;
            generation++; if(pending!=null) pending.cancel(true); pending=null; busy=false;
            snapshot=null; refresh();
        }));
        refresh();
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(ChatTheme.current().canvas());graphics.fillRect(0,0,getWidth(),getHeight());
    }
    private static JButton iconButton(String text,String tooltip,String name) {
        JButton button=new JButton(text); button.setName(name); button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(28,28)); button.setMargin(new Insets(0,0,0,0));
        button.setContentAreaFilled(false); button.setBorderPainted(false);
        button.setText(null);
        button.setIcon(new Icon() {
            public int getIconWidth(){return 18;} public int getIconHeight(){return 18;}
            public void paintIcon(Component c,Graphics graphics,int x,int y) {
                Graphics2D g=(Graphics2D)graphics.create(); g.translate(x,y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(ChatTheme.current().muted()); g.setStroke(new BasicStroke(1.5f));
                if(text.equals("+")){g.drawLine(9,3,9,15);g.drawLine(3,9,15,9);}
                else if(text.equals("×")){g.drawLine(4,4,14,14);g.drawLine(4,14,14,4);}
                else if(text.equals("↻")){g.drawArc(3,3,12,12,30,285);g.drawLine(14,2,15,7);g.drawLine(15,7,10,6);}
                else {g.drawOval(4,4,10,10);g.drawOval(7,7,4,4);for(int i=0;i<8;i++){double angle=i*Math.PI/4;g.drawLine(9+(int)(5*Math.cos(angle)),9+(int)(5*Math.sin(angle)),9+(int)(8*Math.cos(angle)),9+(int)(8*Math.sin(angle)));}}
                g.dispose();
            }
        }); return button;
    }
    private static JButton trashButton() {
        JButton button=new ChatWidgets.Button(false); button.setName("removeMaterial");
        button.setToolTipText("Remove this file"); button.getAccessibleContext().setAccessibleName("Remove this file");
        return button;
    }
    private final class TrashEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button=trashButton(); private Path path;
        TrashEditor() { button.addActionListener(event -> { Path captured=path; fireEditingStopped(); remove(captured); }); }
        @Override public Component getTableCellEditorComponent(JTable table,Object value,boolean selected,int row,int column) {
            path=model.documents.get(table.convertRowIndexToModel(row)).path(); button.setEnabled(!busy&&!disposed); return button;
        }
        @Override public Object getCellEditorValue() { return ""; }
    }
    private void addFiles() {
        if(busy||disposed) return;
        List<Path> chosen=chooseFiles.get(); if(chosen==null||chosen.isEmpty()) return;
        var paths=List.copyOf(chosen);
        run("Indexing…", () -> update(paths,false),true);
    }
    private void remove(Path path) {
        if(busy||disposed||path==null) return;
        run("Indexing…", () -> update(List.of(path),true),true);
    }
    private CompletableFuture<KnowledgeBaseSnapshot> update(List<Path> paths,boolean remove) {
        long token=generation;
        return MaterialUpdateTask.start(operations,paths,remove,registered -> SwingUtilities.invokeLater(() -> {
            if(disposed||token!=generation||!busy) return;
            snapshot=registered;model.documents=registered.documents();model.fireTableDataChanged();
        }));
    }
    private void refresh() { run("Loading…",operations::refreshLibrary,false); }
    private void retry() {
        if(snapshot!=null && !snapshot.documents().isEmpty()) run("Indexing…", () ->
                snapshot.indexState()==KnowledgeBaseSnapshot.IndexState.RELOAD_REQUIRED ? operations.refreshLibrary() : operations.rebuildLibrary(),true);
        else refresh();
    }
    private void run(String activity,Supplier<CompletableFuture<KnowledgeBaseSnapshot>> work,boolean recover) {
        if(disposed||busy) return;
        long token=++generation; busy=true; progress.setToolTipText(activity); status.setText(activity); status.setToolTipText(null); retry.setVisible(false); updateControls();
        try {
            var future=Objects.requireNonNull(work.get()); pending=future;
            future.whenComplete((value,error) -> SwingUtilities.invokeLater(() -> finish(token,value,error,recover)));
        } catch(RuntimeException error) { finish(token,null,error,recover); }
    }
    private void finish(long token,KnowledgeBaseSnapshot value,Throwable error,boolean recover) {
        if(disposed||token!=generation) return;
        busy=false; pending=null;
        Throwable cause=error; while(cause!=null && cause.getCause()!=null && (cause instanceof CompletionException||cause instanceof ExecutionException)) cause=cause.getCause();
        if(error==null && value!=null) {
            snapshot=value; model.documents=value.documents(); model.fireTableDataChanged();
            boolean issue=!value.documents().isEmpty() && value.indexState()!=KnowledgeBaseSnapshot.IndexState.AVAILABLE;
            status.setText(issue ? value.embeddingConfigured()?"Needs attention":"Configure RAG" : "");
            status.setToolTipText(issue ? value.detail() : null); retry.setVisible(issue);
        } else {
            String message=cause instanceof CancellationException?"Cancelled":cause instanceof RagException rag?rag.getMessage():"Unable to update materials. Retry or check Course RAG settings.";
            status.setText(cause instanceof CancellationException?"Cancelled":"Update failed"); status.setToolTipText(message); retry.setVisible(true);
            if(recover && !(cause instanceof CancellationException)) {
                recoverState(message);
            }
        }
        updateControls(); revalidate(); repaint();
    }
    private void recoverState(String message) {
        long token=++generation; busy=true;
        try {
            var refresh=operations.refreshLibrary(); pending=refresh;
            refresh.whenComplete((value,error) -> SwingUtilities.invokeLater(() -> {
                if(disposed||token!=generation) return;
                if(value!=null) {snapshot=value;model.documents=value.documents();model.fireTableDataChanged();}
                busy=false;pending=null;status.setText("Update failed");status.setToolTipText(message);retry.setVisible(true);
                updateControls();
            }));
        } catch(RuntimeException ignored) {busy=false;pending=null;}
    }
    private void cancelTask() {
        if(!busy||pending==null) return;
        generation++; pending.cancel(true); pending=null; busy=false; refresh();
    }
    private void updateControls() {
        status.setVisible(!busy&&!status.getText().isBlank());
        progress.setVisible(!disposed&&busy); cancel.setVisible(!disposed&&busy);
        add.setEnabled(!disposed&&!busy); settings.setEnabled(!disposed&&!busy);
        retry.setEnabled(!disposed&&!busy); files.setEnabled(!disposed&&!busy);
    }
    public void dispose() {
        disposed=true; generation++; unsubscribe.run(); if(pending!=null) pending.cancel(true);
        pending=null; busy=false;
    }
    private static final class MaterialsTable extends AbstractTableModel {
        private List<RegisteredDocument> documents=List.of();
        @Override public int getRowCount() {return documents.size();}
        @Override public int getColumnCount() {return 2;}
        @Override public String getColumnName(int column) {return column==0?"Document":"Remove";}
        @Override public Object getValueAt(int row,int column) {return column==0?documents.get(row).documentName():"";}
        @Override public boolean isCellEditable(int row,int column) {return column==1;}
    }
}
