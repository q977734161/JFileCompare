import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

interface HistoryUiHandler {
    void openHistory(CompareHistoryEntry entry);
    void toggleHistoryPinned(CompareHistoryEntry entry);
    void editHistoryNote(CompareHistoryEntry entry);
    void deleteHistory(CompareHistoryEntry entry);
    void historyChanged(List<CompareHistoryEntry> entries);
    boolean historyOpenBlocked();
}

final class RecentHistoryPanel extends JPanel {
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(218, 225, 232);
    private static final Color HEADER = new Color(247, 249, 251);
    private static final Color BLUE = new Color(37, 99, 235);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    private static final Font FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);

    private final HistoryUiHandler handler;
    private final JPanel rows = new JPanel();

    RecentHistoryPanel(HistoryUiHandler handler) {
        super(new BorderLayout());
        this.handler = handler;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JLabel title = new JLabel("最近对比");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 15));
        title.setForeground(TEXT);
        JLabel hint = new JLabel("打开后按当前磁盘内容重新扫描");
        hint.setFont(FONT);
        hint.setForeground(MUTED);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 4, 5, 4));
        heading.add(title, BorderLayout.WEST);
        heading.add(hint, BorderLayout.EAST);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(SURFACE);
        rows.setBorder(BorderFactory.createLineBorder(BORDER));
        add(heading, BorderLayout.NORTH);
        add(rows, BorderLayout.CENTER);
    }

    void setEntries(List<CompareHistoryEntry> entries) {
        rows.removeAll();
        int count = Math.min(4, entries.size());
        for (int i = 0; i < count; i++) {
            rows.add(createRow(entries.get(i), i > 0));
        }
        setVisible(count > 0);
        revalidate();
        repaint();
    }

    private JPanel createRow(final CompareHistoryEntry entry, boolean topBorder) {
        JLabel mode = new JLabel(entry.mode() == CompareHistoryMode.DIRECTORY ? "文件夹" : "文件",
                SwingConstants.CENTER);
        mode.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 11));
        mode.setForeground(BLUE);
        mode.setOpaque(true);
        mode.setBackground(new Color(239, 246, 255));
        mode.setPreferredSize(new Dimension(54, 26));

        JLabel name = new JLabel((entry.pinned() ? "固定 · " : "") + entry.displayName());
        name.setFont(FONT_BOLD);
        name.setForeground(TEXT);
        JLabel path = new JLabel(middle(entry.leftPath(), 42) + "  →  "
                + middle(entry.rightPath(), 42));
        path.setFont(FONT);
        path.setForeground(MUTED);
        path.setToolTipText(entry.leftPath() + "  →  " + entry.rightPath());
        JLabel summary = new JLabel(entry.summary().compactText() + " · "
                + formatTime(entry.lastSuccessTime()));
        summary.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        summary.setForeground(MUTED);
        JPanel labels = new JPanel(new GridLayout(3, 1, 0, 1));
        labels.setOpaque(false);
        labels.add(name);
        labels.add(path);
        labels.add(summary);

        JButton open = textButton("打开");
        open.setForeground(BLUE);
        open.addActionListener(e -> handler.openHistory(entry));
        JButton actions = textButton("操作");
        actions.addActionListener(e -> createMenu(entry).show(actions, 0, actions.getHeight()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 8));
        buttons.setOpaque(false);
        buttons.add(open);
        buttons.add(actions);

        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(SURFACE);
        row.setBorder(BorderFactory.createCompoundBorder(
                topBorder ? BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER)
                        : BorderFactory.createEmptyBorder(),
                BorderFactory.createEmptyBorder(4, 12, 4, 8)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        row.setPreferredSize(new Dimension(760, 58));
        row.add(mode, BorderLayout.WEST);
        row.add(labels, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private JPopupMenu createMenu(final CompareHistoryEntry entry) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem pin = new JMenuItem(entry.pinned() ? "取消固定" : "固定记录");
        JMenuItem note = new JMenuItem("编辑备注");
        JMenuItem delete = new JMenuItem("删除记录");
        pin.addActionListener(e -> handler.toggleHistoryPinned(entry));
        note.addActionListener(e -> handler.editHistoryNote(entry));
        delete.addActionListener(e -> handler.deleteHistory(entry));
        menu.add(pin);
        menu.add(note);
        menu.addSeparator();
        menu.add(delete);
        return menu;
    }

    private static JButton textButton(String value) {
        JButton button = new JButton(value);
        button.setUI(new BasicButtonUI());
        button.setFont(FONT_BOLD);
        button.setForeground(MUTED);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    static String formatTime(long value) {
        return value <= 0L ? "未知时间"
                : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(value));
    }

    static String middle(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        int side = (maximum - 3) / 2;
        return value.substring(0, side) + "..." + value.substring(value.length() - side);
    }
}

final class HistoryManagerDialog {
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(218, 225, 232);
    private static final Color HEADER = new Color(247, 249, 251);
    private static final Color BLUE = new Color(37, 99, 235);
    private static final Color RED = new Color(214, 69, 69);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private static final Font FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);

    private final JDialog dialog;
    private final CompareHistoryService service;
    private final HistoryUiHandler handler;
    private final JTextField searchField = new JTextField();
    private final HistoryTableModel model = new HistoryTableModel();
    private final JTable table = new JTable(model);
    private final Map<String, HistoryPathStatus> statuses =
            new HashMap<String, HistoryPathStatus>();
    private final JButton openButton = new JButton("重新对比");
    private final JButton pinButton = new JButton("固定");
    private final JButton noteButton = new JButton("编辑备注");
    private final JButton leftButton = new JButton("重新定位左侧");
    private final JButton rightButton = new JButton("重新定位右侧");
    private final JButton deleteButton = new JButton("删除");
    private String filter = "ALL";

    private HistoryManagerDialog(Window owner, CompareHistoryService service,
                                 HistoryUiHandler handler) {
        this.service = service;
        this.handler = handler;
        this.dialog = new JDialog(owner, "对比历史", JDialog.ModalityType.MODELESS);
        buildUi();
        reload();
        dialog.setMinimumSize(new Dimension(900, 560));
        dialog.setSize(1080, 640);
        dialog.setLocationRelativeTo(owner);
    }

    static void show(Window owner, CompareHistoryService service, HistoryUiHandler handler) {
        new HistoryManagerDialog(owner, service, handler).dialog.setVisible(true);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createLineBorder(BORDER));
        root.add(createHeader(), BorderLayout.NORTH);
        root.add(createTable(), BorderLayout.CENTER);
        root.add(createFooter(), BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    }

    private JPanel createHeader() {
        JLabel title = new JLabel("对比历史管理");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("记录用于重新发起任务，不缓存旧对比结果");
        subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        subtitle.setForeground(MUTED);
        JPanel labels = new JPanel(new BorderLayout());
        labels.setOpaque(false);
        labels.add(title, BorderLayout.NORTH);
        labels.add(subtitle, BorderLayout.SOUTH);

        searchField.setFont(FONT);
        searchField.setPreferredSize(new Dimension(330, 34));
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(4, 9, 4, 9)));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        JLabel searchLabel = new JLabel("搜索");
        searchLabel.setFont(FONT_BOLD);
        searchLabel.setForeground(TEXT);
        JPanel search = new JPanel(new BorderLayout(8, 0));
        search.setOpaque(false);
        search.add(searchLabel, BorderLayout.WEST);
        search.add(searchField, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(18, 0));
        top.setOpaque(false);
        top.add(labels, BorderLayout.WEST);
        top.add(search, BorderLayout.EAST);

        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabs.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        addFilterButton(tabs, group, "全部", "ALL", true);
        addFilterButton(tabs, group, "固定", "PINNED", false);
        addFilterButton(tabs, group, "文件夹", "DIRECTORY", false);
        addFilterButton(tabs, group, "文件", "FILE", false);

        JPanel header = new JPanel(new BorderLayout(0, 14));
        header.setBackground(SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(16, 22, 12, 22)));
        header.add(top, BorderLayout.NORTH);
        header.add(tabs, BorderLayout.SOUTH);
        return header;
    }

    private void addFilterButton(JPanel parent, ButtonGroup group, String text,
                                 final String value, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setFont(FONT_BOLD);
        button.setFocusPainted(false);
        button.setBackground(SURFACE);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        button.addActionListener(e -> {
            filter = value;
            applyFilter();
        });
        group.add(button);
        parent.add(button);
    }

    private JScrollPane createTable() {
        table.setFont(FONT);
        table.setRowHeight(42);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setFont(FONT_BOLD);
        table.getTableHeader().setBackground(HEADER);
        table.getTableHeader().setPreferredSize(new Dimension(0, 36));
        table.setDefaultRenderer(Object.class, new HistoryCellRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 20, 8, 20));
        return scroll;
    }

    private JPanel createFooter() {
        styleSecondary(pinButton);
        styleSecondary(noteButton);
        styleSecondary(leftButton);
        styleSecondary(rightButton);
        styleText(deleteButton);
        deleteButton.setForeground(RED);
        stylePrimary(openButton);
        JButton clear = new JButton("清空全部");
        JButton close = new JButton("关闭");
        styleText(clear);
        stylePrimary(close);

        pinButton.addActionListener(e -> runMutation("更新固定状态", new MutationTask() {
            @Override public List<CompareHistoryEntry> run(CompareHistoryEntry entry)
                    throws IOException { return service.togglePinned(entry.id()); }
        }));
        noteButton.addActionListener(e -> editNote());
        leftButton.addActionListener(e -> relocate(true));
        rightButton.addActionListener(e -> relocate(false));
        deleteButton.addActionListener(e -> deleteSelected());
        openButton.addActionListener(e -> openSelected());
        clear.addActionListener(e -> clearAll());
        close.addActionListener(e -> dialog.dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(pinButton);
        actions.add(noteButton);
        actions.add(leftButton);
        actions.add(rightButton);
        actions.add(deleteButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(clear);
        right.add(openButton);
        right.add(close);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        footer.add(actions, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        updateButtons();
        return footer;
    }

    private void reload() {
        applyFilter();
        validatePaths(service.entries());
    }

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>();
        for (CompareHistoryEntry entry : service.entries()) {
            if ("PINNED".equals(filter) && !entry.pinned()) continue;
            if ("DIRECTORY".equals(filter) && entry.mode() != CompareHistoryMode.DIRECTORY) continue;
            if ("FILE".equals(filter) && entry.mode() != CompareHistoryMode.FILE) continue;
            String searchable = (entry.note() + " " + entry.leftPath() + " "
                    + entry.rightPath()).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || searchable.contains(query)) {
                values.add(entry);
            }
        }
        model.setEntries(values, statuses);
        updateButtons();
    }

    private void validatePaths(final List<CompareHistoryEntry> source) {
        new SwingWorker<Map<String, HistoryPathStatus>, Void>() {
            @Override protected Map<String, HistoryPathStatus> doInBackground() {
                Map<String, HistoryPathStatus> result =
                        new HashMap<String, HistoryPathStatus>();
                for (CompareHistoryEntry entry : source) {
                    result.put(entry.id(), HistoryPathValidator.validate(entry));
                }
                return result;
            }

            @Override protected void done() {
                try {
                    statuses.clear();
                    statuses.putAll(get());
                    applyFilter();
                } catch (Exception ex) {
                    // The list remains usable for management if validation is interrupted.
                }
            }
        }.execute();
    }

    private CompareHistoryEntry selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.entryAt(row);
    }

    private void updateButtons() {
        CompareHistoryEntry entry = selected();
        boolean selected = entry != null;
        HistoryPathStatus status = selected ? statuses.get(entry.id()) : null;
        boolean available = status == HistoryPathStatus.AVAILABLE;
        openButton.setEnabled(selected && available && !handler.historyOpenBlocked());
        pinButton.setEnabled(selected);
        noteButton.setEnabled(selected);
        leftButton.setEnabled(selected);
        rightButton.setEnabled(selected);
        deleteButton.setEnabled(selected);
        pinButton.setText(selected && entry.pinned() ? "取消固定" : "固定");
    }

    private void openSelected() {
        CompareHistoryEntry entry = selected();
        if (entry == null) return;
        if (handler.historyOpenBlocked()) {
            JOptionPane.showMessageDialog(dialog, "请先取消当前扫描并等待任务结束。",
                    "扫描进行中", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        HistoryPathStatus status = statuses.get(entry.id());
        if (status != HistoryPathStatus.AVAILABLE) {
            JOptionPane.showMessageDialog(dialog,
                    status == null ? "路径仍在检查中。" : status.displayName() + "，请先重新定位。",
                    "历史任务不可用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        dialog.dispose();
        handler.openHistory(entry);
    }

    private void editNote() {
        final CompareHistoryEntry entry = selected();
        if (entry == null) return;
        String note = JOptionPane.showInputDialog(dialog,
                "备注最多 " + CompareHistoryEntry.MAX_NOTE_LENGTH + " 个字符：",
                entry.note());
        if (note == null) return;
        final String value = note;
        runMutation("保存备注", new MutationTask() {
            @Override public List<CompareHistoryEntry> run(CompareHistoryEntry ignored)
                    throws IOException { return service.updateNote(entry.id(), value); }
        });
    }

    private void relocate(final boolean left) {
        final CompareHistoryEntry entry = selected();
        if (entry == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(left ? "重新定位左侧" : "重新定位右侧");
        chooser.setFileSelectionMode(entry.mode() == CompareHistoryMode.DIRECTORY
                ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        chooser.setSelectedFile(new java.io.File(left ? entry.leftPath() : entry.rightPath()));
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
        final String nextLeft = left ? chooser.getSelectedFile().getAbsolutePath()
                : entry.leftPath();
        final String nextRight = left ? entry.rightPath()
                : chooser.getSelectedFile().getAbsolutePath();
        runMutation("重新定位路径", new MutationTask() {
            @Override public List<CompareHistoryEntry> run(CompareHistoryEntry ignored)
                    throws IOException { return service.relocate(entry.id(), nextLeft, nextRight); }
        });
    }

    private void deleteSelected() {
        final CompareHistoryEntry entry = selected();
        if (entry == null) return;
        if (JOptionPane.showConfirmDialog(dialog, "删除这条对比历史？", "删除历史",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        runMutation("删除历史", new MutationTask() {
            @Override public List<CompareHistoryEntry> run(CompareHistoryEntry ignored)
                    throws IOException { return service.delete(entry.id()); }
        });
    }

    private void clearAll() {
        if (service.entries().isEmpty()) return;
        if (JOptionPane.showConfirmDialog(dialog,
                "清空全部对比历史？此操作不会删除实际文件。", "清空历史",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        setActionsEnabled(false);
        new SwingWorker<List<CompareHistoryEntry>, Void>() {
            @Override protected List<CompareHistoryEntry> doInBackground() throws Exception {
                service.clear();
                return Collections.emptyList();
            }
            @Override protected void done() {
                try {
                    List<CompareHistoryEntry> values = get();
                    handler.historyChanged(values);
                    statuses.clear();
                    applyFilter();
                } catch (Exception ex) {
                    showFailure("清空历史", ex);
                } finally {
                    setActionsEnabled(true);
                }
            }
        }.execute();
    }

    private void runMutation(final String action, final MutationTask task) {
        final CompareHistoryEntry entry = selected();
        if (entry == null) return;
        setActionsEnabled(false);
        new SwingWorker<List<CompareHistoryEntry>, Void>() {
            @Override protected List<CompareHistoryEntry> doInBackground() throws Exception {
                return task.run(entry);
            }
            @Override protected void done() {
                try {
                    List<CompareHistoryEntry> values = get();
                    handler.historyChanged(values);
                    applyFilter();
                    validatePaths(values);
                } catch (Exception ex) {
                    showFailure(action, ex);
                } finally {
                    setActionsEnabled(true);
                }
            }
        }.execute();
    }

    private void setActionsEnabled(boolean enabled) {
        table.setEnabled(enabled);
        searchField.setEnabled(enabled);
        if (!enabled) {
            openButton.setEnabled(false);
            pinButton.setEnabled(false);
            noteButton.setEnabled(false);
            leftButton.setEnabled(false);
            rightButton.setEnabled(false);
            deleteButton.setEnabled(false);
        } else {
            updateButtons();
        }
    }

    private void showFailure(String action, Exception ex) {
        Throwable cause = ex instanceof ExecutionException && ex.getCause() != null
                ? ex.getCause() : ex;
        JOptionPane.showMessageDialog(dialog, action + "失败：" + rootMessage(cause),
                "历史操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static void stylePrimary(JButton button) {
        styleButton(button, BLUE, SURFACE, BLUE);
    }

    private static void styleSecondary(JButton button) {
        styleButton(button, SURFACE, TEXT, BORDER);
    }

    private static void styleText(JButton button) {
        button.setUI(new BasicButtonUI());
        button.setFont(FONT_BOLD);
        button.setForeground(MUTED);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        button.setFocusPainted(false);
    }

    private static void styleButton(JButton button, Color background, Color foreground,
                                    Color border) {
        button.setUI(new BasicButtonUI());
        button.setFont(FONT_BOLD);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        button.setFocusPainted(false);
    }

    private interface MutationTask {
        List<CompareHistoryEntry> run(CompareHistoryEntry entry) throws IOException;
    }

    private static final class HistoryTableModel extends AbstractTableModel {
        private final String[] columns = {"固定", "模式", "任务", "左侧", "右侧",
                "上次结果", "时间", "状态"};
        private List<CompareHistoryEntry> entries = Collections.emptyList();
        private Map<String, HistoryPathStatus> statuses = Collections.emptyMap();

        void setEntries(List<CompareHistoryEntry> values,
                        Map<String, HistoryPathStatus> nextStatuses) {
            entries = new ArrayList<CompareHistoryEntry>(values);
            statuses = new HashMap<String, HistoryPathStatus>(nextStatuses);
            fireTableDataChanged();
        }

        CompareHistoryEntry entryAt(int row) { return entries.get(row); }
        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Object getValueAt(int row, int column) {
            CompareHistoryEntry entry = entries.get(row);
            switch (column) {
                case 0: return entry.pinned() ? "是" : "";
                case 1: return entry.mode().displayName();
                case 2: return entry.displayName();
                case 3: return RecentHistoryPanel.middle(entry.leftPath(), 34);
                case 4: return RecentHistoryPanel.middle(entry.rightPath(), 34);
                case 5: return entry.summary().compactText().replace("上次：", "");
                case 6: return RecentHistoryPanel.formatTime(entry.lastSuccessTime());
                case 7:
                    HistoryPathStatus status = statuses.get(entry.id());
                    return status == null ? "检查中" : status.displayName();
                default: return "";
            }
        }
    }

    private static final class HistoryCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setFont(FONT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setToolTipText(null);
            if (table.getModel() instanceof HistoryTableModel) {
                CompareHistoryEntry entry = ((HistoryTableModel) table.getModel()).entryAt(row);
                if (column == 3) {
                    setToolTipText(entry.leftPath());
                } else if (column == 4) {
                    setToolTipText(entry.rightPath());
                }
            }
            if (!selected) {
                setBackground(row % 2 == 0 ? SURFACE : new Color(250, 251, 252));
                setForeground(column == 7 && value != null
                        && !"可用".equals(value) && !"检查中".equals(value) ? RED
                        : column == 0 && "是".equals(value) ? BLUE : TEXT);
            }
            return this;
        }
    }
}
