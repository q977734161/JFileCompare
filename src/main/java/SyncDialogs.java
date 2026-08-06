import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.text.SimpleDateFormat;
import java.util.Date;

final class SyncUi {
    static final Color SURFACE = Color.WHITE;
    static final Color APP_BACKGROUND = new Color(237, 241, 245);
    static final Color HEADER = new Color(247, 249, 251);
    static final Color TEXT = new Color(31, 41, 55);
    static final Color MUTED = new Color(100, 116, 139);
    static final Color BORDER = new Color(218, 225, 232);
    static final Color PRIMARY = new Color(37, 99, 235);
    static final Color GREEN = new Color(36, 138, 75);
    static final Color GREEN_BG = new Color(235, 247, 239);
    static final Color RED = new Color(214, 69, 69);
    static final Color RED_BG = new Color(253, 237, 237);
    static final Color WARNING = new Color(161, 98, 7);
    static final Color WARNING_BG = new Color(255, 248, 225);
    static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    static final Font FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);

    private SyncUi() {
    }

    static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(FONT_BOLD);
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(8, 16, 8, 16));
        return button;
    }

    static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(FONT_BOLD);
        button.setForeground(TEXT);
        button.setBackground(SURFACE);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(7, 14, 7, 14)));
        return button;
    }

    static JLabel label(String text, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(bold ? FONT_BOLD : FONT);
        label.setForeground(TEXT);
        return label;
    }

    static String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return seconds < 60 ? seconds + " 秒" : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    static String formatTimestamp(long millis) {
        return millis <= 0L ? "-"
                : new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
    }
}

final class SyncPreviewDialog extends JDialog {
    private final SyncPlan plan;
    private final SyncService service;
    private final SyncPreviewTableModel model;
    private final JCheckBox backupCheck = new JCheckBox("备份被覆盖的目标文件", true);
    private final JLabel selectionSummary = SyncUi.label("", false);
    private SyncRequest request;

    private SyncPreviewDialog(Window owner, SyncPlan plan, SyncService service) {
        super(owner, "同步预览", ModalityType.APPLICATION_MODAL);
        this.plan = plan;
        this.service = service;
        this.model = new SyncPreviewTableModel(plan);
        setContentPane(createPage());
        setSize(1120, 700);
        setMinimumSize(new Dimension(900, 580));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        updateSummary();
    }

    static SyncRequest showDialog(Window owner, SyncPlan plan, SyncService service) {
        SyncPreviewDialog dialog = new SyncPreviewDialog(owner, plan, service);
        dialog.setVisible(true);
        return dialog.request;
    }

    static JDialog createForPreview(Window owner, SyncPlan plan, SyncService service) {
        return new SyncPreviewDialog(owner, plan, service);
    }

    private JPanel createPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(SyncUi.SURFACE);
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(SyncUi.SURFACE);
        top.add(createHeader(), BorderLayout.NORTH);
        top.add(createFilterBar(), BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFont(SyncUi.FONT);
        table.setRowHeight(34);
        table.setGridColor(SyncUi.BORDER);
        table.setShowVerticalLines(false);
        table.setSelectionBackground(new Color(219, 234, 254));
        table.setDefaultRenderer(Object.class, new SyncPreviewCellRenderer(model));
        TableColumn selected = table.getColumnModel().getColumn(0);
        selected.setMinWidth(48);
        selected.setMaxWidth(48);
        TableColumn action = table.getColumnModel().getColumn(1);
        action.setMinWidth(82);
        action.setMaxWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(480);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        table.getColumnModel().getColumn(4).setPreferredWidth(230);
        table.getModel().addTableModelListener(e -> updateSummary());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(new MatteBorder(1, 0, 1, 0, SyncUi.BORDER));
        scroll.getViewport().setBackground(SyncUi.SURFACE);
        page.add(scroll, BorderLayout.CENTER);
        page.add(createFooter(), BorderLayout.SOUTH);
        return page;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SyncUi.SURFACE);
        header.setBorder(new EmptyBorder(18, 24, 16, 24));

        JPanel title = new JPanel(new GridBagLayout());
        title.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        JLabel heading = SyncUi.label("同步预览", true);
        heading.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        title.add(heading, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(4, 0, 0, 0);
        JLabel direction = SyncUi.label(plan.getDirection().getDisplayName(), true);
        direction.setForeground(SyncUi.PRIMARY);
        title.add(direction, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(10, 0, 0, 0);
        title.add(pathLine("来源：", plan.getSourceRoot()), gbc);
        gbc.gridy++;
        gbc.insets = new Insets(4, 0, 0, 0);
        title.add(pathLine("目标：", plan.getTargetRoot()), gbc);
        header.add(title, BorderLayout.WEST);

        JPanel counts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        counts.setOpaque(false);
        counts.add(summaryBadge("新增", plan.count(SyncAction.ADD), SyncUi.GREEN, SyncUi.GREEN_BG));
        counts.add(summaryBadge("覆盖", plan.count(SyncAction.OVERWRITE), SyncUi.RED, SyncUi.RED_BG));
        counts.add(summaryBadge("跳过", plan.count(SyncAction.SKIP), SyncUi.MUTED, SyncUi.HEADER));
        header.add(counts, BorderLayout.EAST);
        return header;
    }

    private JPanel createFilterBar() {
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        bar.setBackground(SyncUi.SURFACE);
        bar.setBorder(new MatteBorder(1, 0, 0, 0, SyncUi.BORDER));
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        addFilterButton(bar, group, "全部 " + plan.getEntries().size(), null, true);
        addFilterButton(bar, group, "将执行 "
                + (plan.count(SyncAction.ADD) + plan.count(SyncAction.OVERWRITE)
                + plan.count(SyncAction.CREATE_DIRECTORY)), "EXECUTABLE", false);
        addFilterButton(bar, group, "覆盖 " + plan.count(SyncAction.OVERWRITE),
                SyncAction.OVERWRITE.name(), false);
        addFilterButton(bar, group, "新增 " + plan.count(SyncAction.ADD),
                SyncAction.ADD.name(), false);
        addFilterButton(bar, group, "跳过 " + plan.count(SyncAction.SKIP),
                SyncAction.SKIP.name(), false);
        if (plan.getExcludedCount() > 0) {
            JLabel excluded = SyncUi.label("过滤规则已排除 " + plan.getExcludedCount() + " 项", false);
            excluded.setForeground(SyncUi.MUTED);
            excluded.setBorder(new EmptyBorder(0, 16, 0, 0));
            bar.add(excluded);
        }
        return bar;
    }

    private void addFilterButton(final JPanel bar, javax.swing.ButtonGroup group,
                                 String text, final String filter, boolean selected) {
        final JToggleButton button = new JToggleButton(text, selected);
        button.setFont(selected ? SyncUi.FONT_BOLD : SyncUi.FONT);
        button.setFocusPainted(false);
        button.setBackground(SyncUi.SURFACE);
        button.setForeground(selected ? SyncUi.PRIMARY : SyncUi.MUTED);
        button.setBorder(new EmptyBorder(7, 12, 7, 12));
        button.addActionListener(e -> {
            model.setFilter(filter);
            for (Component component : bar.getComponents()) {
                if (component instanceof JToggleButton) {
                    JToggleButton current = (JToggleButton) component;
                    current.setFont(current.isSelected() ? SyncUi.FONT_BOLD : SyncUi.FONT);
                    current.setForeground(current.isSelected() ? SyncUi.PRIMARY : SyncUi.MUTED);
                }
            }
        });
        group.add(button);
        bar.add(button);
    }

    private JPanel pathLine(String label, Path path) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JLabel key = SyncUi.label(label, true);
        key.setForeground(SyncUi.MUTED);
        row.add(key);
        row.add(SyncUi.label(path.toString(), false));
        return row;
    }

    private JPanel summaryBadge(String text, int count, Color color, Color background) {
        JPanel badge = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        badge.setBackground(background);
        JLabel label = SyncUi.label(text + "  " + count, true);
        label.setForeground(color);
        badge.add(label);
        return badge;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SyncUi.HEADER);
        footer.setBorder(new EmptyBorder(12, 24, 12, 24));

        JPanel backupLine = new JPanel(new BorderLayout());
        backupLine.setOpaque(false);
        JPanel backup = new JPanel(new GridBagLayout());
        backup.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        backupCheck.setFont(SyncUi.FONT_BOLD);
        backupCheck.setOpaque(false);
        backupCheck.setEnabled(plan.count(SyncAction.OVERWRITE) > 0);
        backupCheck.setSelected(plan.count(SyncAction.OVERWRITE) > 0);
        backup.add(backupCheck, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(4, 24, 0, 0);
        long backupBytes = 0L;
        for (SyncPlanEntry entry : plan.getEntries()) {
            backupBytes += entry.getBackupSize();
        }
        JLabel location = SyncUi.label("预计 " + SyncText.formatSize(backupBytes)
                + " · " + service.getBackupRoot() + " · 最近保留 10 次", false);
        location.setForeground(SyncUi.MUTED);
        backup.add(location, gbc);
        backupLine.add(backup, BorderLayout.WEST);
        JButton openBackup = SyncUi.secondaryButton("查看备份位置");
        openBackup.addActionListener(e -> openBackupRoot());
        backupLine.add(openBackup, BorderLayout.EAST);
        footer.add(backupLine, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(12, 0, 0, 0));
        selectionSummary.setForeground(SyncUi.MUTED);
        bottom.add(selectionSummary, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton selectAll = SyncUi.secondaryButton("全选可执行项");
        JButton cancel = SyncUi.secondaryButton("取消");
        JButton start = SyncUi.primaryButton("开始同步");
        selectAll.addActionListener(e -> model.selectAllExecutable());
        cancel.addActionListener(e -> dispose());
        start.addActionListener(e -> confirmStart());
        actions.add(selectAll);
        actions.add(cancel);
        actions.add(start);
        bottom.add(actions, BorderLayout.EAST);
        footer.add(bottom, BorderLayout.SOUTH);
        return footer;
    }

    private void confirmStart() {
        Set<Integer> selected = model.selectedIndexes();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "至少选择一个新增、覆盖或创建目录操作。",
                    "没有可执行项", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (plan.count(SyncAction.OVERWRITE) > 0 && !backupCheck.isSelected()
                && model.hasSelectedOverwrite()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "已关闭覆盖备份。被覆盖的目标文件将无法通过本次事务恢复。\n确定继续吗？",
                    "未启用覆盖备份", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        request = new SyncRequest(selected, backupCheck.isSelected());
        dispose();
    }

    private void updateSummary() {
        selectionSummary.setText("已选择 " + model.selectedCount() + " 项，共 "
                + SyncText.formatSize(model.selectedCopyBytes()) + "；预计备份 "
                + SyncText.formatSize(model.selectedBackupBytes()));
    }

    private void openBackupRoot() {
        try {
            java.nio.file.Files.createDirectories(service.getBackupRoot());
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(service.getBackupRoot().toFile());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "无法打开备份位置：" + ex.getMessage(),
                    "打开失败", JOptionPane.ERROR_MESSAGE);
        }
    }
}

final class SyncPreviewTableModel extends AbstractTableModel {
    private final List<SyncPlanEntry> allEntries;
    private final List<SyncPlanEntry> entries = new ArrayList<SyncPlanEntry>();
    private final Set<Integer> selected;
    private final String[] columns = {"选择", "操作", "相对路径", "来源", "目标"};

    SyncPreviewTableModel(SyncPlan plan) {
        allEntries = plan.getEntries();
        entries.addAll(allEntries);
        selected = new LinkedHashSet<Integer>(plan.defaultSelection());
    }

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 0 ? Boolean.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        SyncPlanEntry entry = entries.get(rowIndex);
        if (columnIndex == 0) {
            return Boolean.valueOf(selected.contains(Integer.valueOf(entry.getIndex())));
        }
        if (columnIndex == 1) {
            return entry.getAction().getDisplayName();
        }
        if (columnIndex == 2) {
            return entry.getRelativePath();
        }
        if (columnIndex == 3) {
            return entry.getSourceFingerprint().exists()
                    ? SyncText.formatSize(entry.getSourceFingerprint().getSize()) + " · "
                    + SyncUi.formatTimestamp(entry.getSourceFingerprint().getModifiedTime())
                    : "不存在";
        }
        if (entry.getAction() == SyncAction.SKIP) {
            return entry.getSkipReason();
        }
        if (entry.getTargetFingerprint().exists()) {
            return SyncText.formatSize(entry.getTargetFingerprint().getSize()) + " · "
                    + SyncUi.formatTimestamp(entry.getTargetFingerprint().getModifiedTime())
                    + (entry.getAction() == SyncAction.OVERWRITE ? " · 将覆盖" : "");
        }
        return "不存在";
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 0 && entries.get(rowIndex).isExecutable();
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        SyncPlanEntry entry = entries.get(rowIndex);
        if (!entry.isExecutable()) {
            return;
        }
        if (Boolean.TRUE.equals(value)) {
            selected.add(Integer.valueOf(entry.getIndex()));
        } else {
            selected.remove(Integer.valueOf(entry.getIndex()));
        }
        fireTableCellUpdated(rowIndex, columnIndex);
    }

    SyncPlanEntry entryAt(int row) {
        return entries.get(row);
    }

    Set<Integer> selectedIndexes() {
        return new LinkedHashSet<Integer>(selected);
    }

    void selectAllExecutable() {
        for (SyncPlanEntry entry : allEntries) {
            if (entry.isExecutable()) {
                selected.add(Integer.valueOf(entry.getIndex()));
            }
        }
        fireTableDataChanged();
    }

    int selectedCount() {
        return selected.size();
    }

    long selectedCopyBytes() {
        long total = 0L;
        for (SyncPlanEntry entry : allEntries) {
            if (selected.contains(Integer.valueOf(entry.getIndex()))) {
                total += entry.getCopySize();
            }
        }
        return total;
    }

    long selectedBackupBytes() {
        long total = 0L;
        for (SyncPlanEntry entry : allEntries) {
            if (selected.contains(Integer.valueOf(entry.getIndex()))) {
                total += entry.getBackupSize();
            }
        }
        return total;
    }

    boolean hasSelectedOverwrite() {
        for (SyncPlanEntry entry : allEntries) {
            if (entry.getAction() == SyncAction.OVERWRITE
                    && selected.contains(Integer.valueOf(entry.getIndex()))) {
                return true;
            }
        }
        return false;
    }

    void setFilter(String filter) {
        entries.clear();
        for (SyncPlanEntry entry : allEntries) {
            if (filter == null
                    || ("EXECUTABLE".equals(filter) && entry.isExecutable())
                    || entry.getAction().name().equals(filter)) {
                entries.add(entry);
            }
        }
        fireTableDataChanged();
    }
}

final class SyncPreviewCellRenderer extends DefaultTableCellRenderer {
    private final SyncPreviewTableModel model;

    SyncPreviewCellRenderer(SyncPreviewTableModel model) {
        this.model = model;
        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean selected, boolean focused,
                                                   int row, int column) {
        super.getTableCellRendererComponent(table, value, selected, focused, row, column);
        SyncPlanEntry entry = model.entryAt(row);
        setFont(column == 1 ? SyncUi.FONT_BOLD : SyncUi.FONT);
        setBorder(new EmptyBorder(0, 8, 0, 8));
        setHorizontalAlignment(column == 0 ? SwingConstants.CENTER : SwingConstants.LEFT);
        if (!selected) {
            setBackground(entry.getAction() == SyncAction.OVERWRITE
                    ? new Color(255, 249, 249) : SyncUi.SURFACE);
        }
        if (column == 1) {
            setForeground(entry.getAction() == SyncAction.OVERWRITE ? SyncUi.RED
                    : entry.getAction() == SyncAction.ADD ? SyncUi.GREEN : SyncUi.MUTED);
        } else {
            setForeground(entry.getAction() == SyncAction.SKIP ? SyncUi.MUTED : SyncUi.TEXT);
        }
        return this;
    }
}

final class SyncProgressDialog extends JDialog {
    private final SyncPlan plan;
    private final SyncRequest request;
    private final SyncService service;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final JLabel stageLabel = SyncUi.label("正在复核文件", true);
    private final JLabel countLabel = SyncUi.label("0 / 0", true);
    private final JLabel currentPath = SyncUi.label("", true);
    private final JLabel detail = SyncUi.label("", false);
    private final JLabel status = SyncUi.label("", false);
    private final JProgressBar progress = new JProgressBar();
    private final JButton cancel = SyncUi.secondaryButton("取消同步");
    private SyncExecutionResult result;

    private SyncProgressDialog(Window owner, SyncPlan plan, SyncRequest request,
                               SyncService service) {
        super(owner, "正在同步", ModalityType.APPLICATION_MODAL);
        this.plan = plan;
        this.request = request;
        this.service = service;
        setContentPane(createPage());
        setSize(720, 440);
        setMinimumSize(new Dimension(620, 400));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestCancel();
            }
        });
    }

    static SyncExecutionResult execute(Window owner, SyncPlan plan, SyncRequest request,
                                       SyncService service) {
        SyncProgressDialog dialog = new SyncProgressDialog(owner, plan, request, service);
        dialog.start();
        dialog.setVisible(true);
        return dialog.result;
    }

    private JPanel createPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(SyncUi.SURFACE);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SyncUi.SURFACE);
        header.setBorder(new EmptyBorder(20, 24, 18, 24));
        JLabel title = SyncUi.label("正在同步", true);
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        header.add(title, BorderLayout.WEST);
        JLabel direction = SyncUi.label(plan.getDirection().getDisplayName(), true);
        direction.setForeground(SyncUi.PRIMARY);
        header.add(direction, BorderLayout.EAST);
        page.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        body.setBackground(SyncUi.SURFACE);
        body.setBorder(new MatteBorder(1, 0, 1, 0, SyncUi.BORDER));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(18, 28, 0, 28);
        JPanel line = new JPanel(new BorderLayout());
        line.setOpaque(false);
        line.add(stageLabel, BorderLayout.WEST);
        line.add(countLabel, BorderLayout.EAST);
        body.add(line, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(10, 28, 0, 28);
        progress.setForeground(SyncUi.PRIMARY);
        progress.setStringPainted(false);
        body.add(progress, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(26, 28, 0, 28);
        JPanel current = new JPanel(new GridBagLayout());
        current.setBackground(SyncUi.HEADER);
        current.setBorder(new EmptyBorder(14, 16, 14, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        JLabel currentLabel = SyncUi.label("当前文件", true);
        currentLabel.setForeground(SyncUi.MUTED);
        current.add(currentLabel, c);
        c.gridy++;
        c.insets = new Insets(8, 0, 0, 0);
        current.add(currentPath, c);
        c.gridy++;
        c.insets = new Insets(5, 0, 0, 0);
        detail.setForeground(SyncUi.PRIMARY);
        current.add(detail, c);
        body.add(current, gbc);
        gbc.gridy++;
        gbc.weighty = 1;
        body.add(new JPanel(), gbc);
        page.add(body, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SyncUi.SURFACE);
        footer.setBorder(new EmptyBorder(14, 24, 14, 24));
        status.setForeground(SyncUi.MUTED);
        footer.add(status, BorderLayout.WEST);
        cancel.addActionListener(e -> requestCancel());
        footer.add(cancel, BorderLayout.EAST);
        page.add(footer, BorderLayout.SOUTH);
        return page;
    }

    private void start() {
        int total = request.getSelectedIndexes().size();
        progress.setMinimum(0);
        progress.setMaximum(Math.max(1, total));
        countLabel.setText("0 / " + total);
        status.setText("已完成 0 · 剩余 " + total + " · 失败 0");
        SwingWorker<SyncExecutionResult, SyncProgressUpdate> worker =
                new SwingWorker<SyncExecutionResult, SyncProgressUpdate>() {
                    @Override
                    protected SyncExecutionResult doInBackground() {
                        return service.execute(plan, request, update -> publish(update), cancelled);
                    }

                    @Override
                    protected void process(List<SyncProgressUpdate> chunks) {
                        if (!chunks.isEmpty()) {
                            update(chunks.get(chunks.size() - 1));
                        }
                    }

                    @Override
                    protected void done() {
                        try {
                            result = get();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(SyncProgressDialog.this,
                                    ex.getMessage(), "同步失败", JOptionPane.ERROR_MESSAGE);
                        }
                        dispose();
                    }
                };
        worker.execute();
    }

    private void update(SyncProgressUpdate update) {
        stageLabel.setText(update.getStage());
        currentPath.setText(update.getRelativePath().isEmpty() ? "准备同步" : update.getRelativePath());
        detail.setText(update.getMessage());
        progress.setValue(update.getCompleted());
        countLabel.setText(update.getCompleted() + " / " + update.getTotal());
        status.setText("已完成 " + update.getCompleted() + " · 剩余 "
                + Math.max(0, update.getTotal() - update.getCompleted()));
    }

    private void requestCancel() {
        if (!cancelled.get()) {
            cancelled.set(true);
            cancel.setEnabled(false);
            cancel.setText("正在完成当前文件");
            detail.setText("当前文件提交完成后停止");
        }
    }
}

final class SyncResultDialog extends JDialog {
    private final SyncExecutionResult result;
    private final SyncService service;
    private final Runnable finishedCallback;
    private final SyncResultTableModel model;
    private boolean callbackInvoked;

    private SyncResultDialog(Window owner, SyncExecutionResult result,
                             SyncService service, Runnable finishedCallback) {
        super(owner, result.hasFailures() ? "同步未完成" : "同步完成",
                ModalityType.APPLICATION_MODAL);
        this.result = result;
        this.service = service;
        this.finishedCallback = finishedCallback;
        this.model = new SyncResultTableModel(result);
        setContentPane(createPage());
        setSize(1060, 650);
        setMinimumSize(new Dimension(880, 560));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                notifyFinished();
            }
        });
    }

    static void showDialog(Window owner, SyncExecutionResult result,
                           SyncService service, Runnable finishedCallback) {
        new SyncResultDialog(owner, result, service, finishedCallback).setVisible(true);
    }

    static JDialog createForPreview(Window owner, SyncExecutionResult result,
                                    SyncService service) {
        return new SyncResultDialog(owner, result, service, null);
    }

    private JPanel createPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(SyncUi.SURFACE);
        page.add(createHeader(), BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFont(SyncUi.FONT);
        table.setRowHeight(34);
        table.setGridColor(SyncUi.BORDER);
        table.setShowVerticalLines(false);
        table.setDefaultRenderer(Object.class, new SyncResultCellRenderer(model));
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(360);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(new MatteBorder(1, 0, 1, 0, SyncUi.BORDER));
        page.add(scroll, BorderLayout.CENTER);
        page.add(createFooter(), BorderLayout.SOUTH);
        return page;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SyncUi.SURFACE);
        header.setBorder(new EmptyBorder(18, 24, 16, 24));
        JPanel title = new JPanel(new GridBagLayout());
        title.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        JLabel heading = SyncUi.label(result.hasFailures() || result.wasCancelled()
                ? "同步未完成" : "同步完成", true);
        heading.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        heading.setForeground(result.hasFailures() ? SyncUi.RED : SyncUi.TEXT);
        title.add(heading, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(5, 0, 0, 0);
        JLabel summary = SyncUi.label("已完成 " + result.count(SyncItemStatus.SUCCESS)
                + " · 失败 " + result.count(SyncItemStatus.FAILED)
                + " · 未执行 " + result.count(SyncItemStatus.NOT_EXECUTED)
                + " · 耗时 " + SyncUi.formatTime(result.getDurationMillis()), false);
        summary.setForeground(SyncUi.MUTED);
        title.add(summary, gbc);
        header.add(title, BorderLayout.WEST);
        JLabel transaction = SyncUi.label("事务：" + result.getPlan().getTransactionId(), false);
        transaction.setForeground(SyncUi.MUTED);
        header.add(transaction, BorderLayout.EAST);
        return header;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SyncUi.HEADER);
        footer.setBorder(new EmptyBorder(14, 24, 14, 24));
        JButton openBackup = SyncUi.secondaryButton("打开备份位置");
        openBackup.setEnabled(result.getTransactionDirectory() != null
                && java.nio.file.Files.isDirectory(result.getTransactionDirectory()));
        openBackup.addActionListener(e -> openBackupDirectory());
        footer.add(openBackup, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton close = SyncUi.secondaryButton(result.hasFailures()
                ? "保留已完成内容" : "返回对比结果");
        close.addActionListener(e -> finishAndClose());
        actions.add(close);
        if (result.hasRollbackCandidates()) {
            JButton rollback = SyncUi.primaryButton("回滚本次同步");
            rollback.addActionListener(e -> rollback());
            actions.add(rollback);
        }
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private void rollback() {
        int answer = JOptionPane.showConfirmDialog(this,
                "将恢复被覆盖的目标文件，并删除仍未被外部修改的本次新增文件。\n"
                        + "存在外部修改时会保留文件并报告冲突。确定继续吗？",
                "确认回滚本次同步", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        final JDialog progress = new JDialog(this, "正在回滚", true);
        JLabel message = SyncUi.label("正在恢复同步前状态...", true);
        message.setBorder(new EmptyBorder(24, 30, 24, 30));
        progress.add(message);
        progress.pack();
        progress.setLocationRelativeTo(this);
        SwingWorker<Void, SyncProgressUpdate> worker = new SwingWorker<Void, SyncProgressUpdate>() {
            @Override
            protected Void doInBackground() {
                service.rollback(result, update -> publish(update), new AtomicBoolean(false));
                return null;
            }

            @Override
            protected void process(List<SyncProgressUpdate> chunks) {
                if (!chunks.isEmpty()) {
                    SyncProgressUpdate update = chunks.get(chunks.size() - 1);
                    message.setText(update.getMessage() + "：" + update.getRelativePath());
                    progress.pack();
                }
            }

            @Override
            protected void done() {
                progress.dispose();
                model.fireTableDataChanged();
                String text = result.hasRollbackProblems()
                        ? "回滚存在冲突或失败，备份已保留，请查看结果。"
                        : "回滚完成。";
                JOptionPane.showMessageDialog(SyncResultDialog.this, text,
                        "回滚结果", result.hasRollbackProblems()
                                ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                if (!result.hasRollbackProblems()) {
                    finishAndClose();
                }
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private void openBackupDirectory() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(result.getTransactionDirectory().toFile());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "无法打开备份位置：" + ex.getMessage(),
                    "打开失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void finishAndClose() {
        dispose();
    }

    private void notifyFinished() {
        if (callbackInvoked) {
            return;
        }
        callbackInvoked = true;
        if (finishedCallback != null) {
            finishedCallback.run();
        }
    }
}

final class SyncResultTableModel extends AbstractTableModel {
    private final List<SyncItemResult> items;
    private final String[] columns = {"状态", "相对路径", "阶段", "结果与原因"};

    SyncResultTableModel(SyncExecutionResult result) {
        items = result.getItemResults();
    }

    @Override
    public int getRowCount() {
        return items.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        SyncItemResult item = items.get(rowIndex);
        if (columnIndex == 0) {
            return item.getStatus().getDisplayName();
        }
        if (columnIndex == 1) {
            return item.getEntry().getRelativePath();
        }
        if (columnIndex == 2) {
            return item.getStage();
        }
        return item.getMessage();
    }

    SyncItemResult itemAt(int row) {
        return items.get(row);
    }
}

final class SyncResultCellRenderer extends DefaultTableCellRenderer {
    private final SyncResultTableModel model;

    SyncResultCellRenderer(SyncResultTableModel model) {
        this.model = model;
        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean selected, boolean focused,
                                                   int row, int column) {
        super.getTableCellRendererComponent(table, value, selected, focused, row, column);
        SyncItemStatus status = model.itemAt(row).getStatus();
        setFont(column == 0 ? SyncUi.FONT_BOLD : SyncUi.FONT);
        setBorder(new EmptyBorder(0, 8, 0, 8));
        if (!selected) {
            setBackground(status == SyncItemStatus.FAILED
                    || status == SyncItemStatus.ROLLBACK_FAILED
                    || status == SyncItemStatus.ROLLBACK_CONFLICT
                    ? SyncUi.RED_BG : SyncUi.SURFACE);
        }
        if (column == 0) {
            setForeground(status == SyncItemStatus.SUCCESS
                    || status == SyncItemStatus.ROLLED_BACK ? SyncUi.GREEN
                    : status == SyncItemStatus.FAILED
                    || status == SyncItemStatus.ROLLBACK_FAILED
                    || status == SyncItemStatus.ROLLBACK_CONFLICT ? SyncUi.RED : SyncUi.MUTED);
        } else {
            setForeground(SyncUi.TEXT);
        }
        return this;
    }
}
