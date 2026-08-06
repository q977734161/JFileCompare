import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.io.IOException;

final class PreferencesDialog extends JDialog {
    private static final Color BACKGROUND = new Color(244, 247, 250);
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(218, 225, 232);
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color DANGER = new Color(190, 45, 45);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private static final Font BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);

    private final PreferencesService service;
    private final Runnable resetCallback;
    private final ToggleSwitch restoreMain = new ToggleSwitch("恢复主窗口位置和大小");
    private final ToggleSwitch restoreDivider = new ToggleSwitch("恢复主界面左右分栏比例");
    private final ToggleSwitch restoreEditor = new ToggleSwitch("恢复文件编辑窗口位置和大小");
    private final ToggleSwitch linkedScroll = new ToggleSwitch("新编辑窗口默认联动滚动");
    private final ToggleSwitch confirmDeletion = new ToggleSwitch("删除差异块前确认");
    private final ToggleSwitch rememberLocations = new ToggleSwitch("记住文件选择器上次位置");
    private final JTextField directoryPath = pathField("recentDirectoryPath");
    private final JTextField filePath = pathField("recentFilePath");
    private final JButton clearDirectory = secondaryButton("清除");
    private final JButton clearFile = secondaryButton("清除");
    private final JButton resetButton = secondaryButton("恢复默认值");
    private final JButton cancelButton = secondaryButton("取消");
    private final JButton saveButton = primaryButton("保存设置");

    static void showDialog(JFrame owner, PreferencesService service, Runnable resetCallback) {
        PreferencesDialog dialog = new PreferencesDialog(owner, service, resetCallback);
        dialog.setVisible(true);
    }

    PreferencesDialog(JFrame owner, PreferencesService service, Runnable resetCallback) {
        super(owner, "偏好设置", true);
        this.service = service;
        this.resetCallback = resetCallback;
        setName("preferencesDialog");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(760, 580));
        setSize(900, 650);
        setContentPane(createPage());
        wireEvents();
        load(service.current());
        setLocationRelativeTo(owner);
    }

    private JPanel createPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(BACKGROUND);

        JPanel heading = new JPanel(new BorderLayout());
        heading.setBackground(SURFACE);
        heading.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(18, 24, 16, 24)));
        JLabel title = new JLabel("偏好设置");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("窗口布局、编辑习惯和路径隐私");
        subtitle.setFont(FONT);
        subtitle.setForeground(MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);

        JPanel content = new VerticalScrollablePanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setBackground(BACKGROUND);
        content.setBorder(new EmptyBorder(16, 20, 16, 20));
        content.add(section("窗口与布局",
                row("主窗口", "下次启动时恢复窗口位置、大小和最大化状态", restoreMain),
                row("左右分栏", "恢复目录对比结果的左右宽度比例", restoreDivider),
                row("编辑窗口", "新打开的内容对比窗口使用上次位置和大小", restoreEditor)));
        content.add(javax.swing.Box.createVerticalStrut(12));
        content.add(section("编辑行为",
                row("联动滚动", "新打开的内容对比窗口默认同步滚动两侧文件", linkedScroll),
                row("删除确认", "差异块操作会删除内容时先进行确认", confirmDeletion)));
        content.add(javax.swing.Box.createVerticalStrut(12));
        content.add(createPathSection());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SURFACE);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(12, 20, 12, 20)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(cancelButton);
        right.add(saveButton);
        footer.add(resetButton, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);

        page.add(heading, BorderLayout.NORTH);
        page.add(scroll, BorderLayout.CENTER);
        page.add(footer, BorderLayout.SOUTH);
        return page;
    }

    private JPanel section(String title, JPanel... rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createLineBorder(BORDER));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(13, 16, 10, 16);
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 15));
        heading.setForeground(TEXT);
        panel.add(heading, c);
        for (JPanel row : rows) {
            c.gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            panel.add(row, c);
        }
        return panel;
    }

    private JPanel row(String title, String description, ToggleSwitch toggle) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setBackground(SURFACE);
        row.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(10, 16, 10, 16)));
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        JLabel name = new JLabel(title);
        name.setFont(BOLD);
        name.setForeground(TEXT);
        JLabel note = new JLabel(description);
        note.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        note.setForeground(MUTED);
        text.add(name, BorderLayout.NORTH);
        text.add(note, BorderLayout.SOUTH);
        row.add(text, BorderLayout.CENTER);
        row.add(toggle, BorderLayout.EAST);
        return row;
    }

    private JPanel createPathSection() {
        JPanel section = section("路径与隐私",
                row("记住选择位置", "文件和目录选择器从上次使用的位置打开", rememberLocations));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        section.add(pathRow("目录选择位置", directoryPath, clearDirectory), c);
        c.gridy = 3;
        section.add(pathRow("文件选择位置", filePath, clearFile), c);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 270));
        return section;
    }

    private JPanel pathRow(String title, JTextField field, JButton clear) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(SURFACE);
        row.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(9, 16, 9, 16)));
        JLabel label = new JLabel(title);
        label.setPreferredSize(new Dimension(105, 30));
        label.setFont(FONT);
        label.setForeground(TEXT);
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.add(clear, BorderLayout.EAST);
        return row;
    }

    private void wireEvents() {
        rememberLocations.addActionListener(e -> updatePathState());
        clearDirectory.addActionListener(e -> directoryPath.setText(""));
        clearFile.addActionListener(e -> filePath.setText(""));
        cancelButton.addActionListener(e -> dispose());
        saveButton.addActionListener(e -> saveAndClose());
        resetButton.addActionListener(e -> resetPreferences());
    }

    private void load(AppPreferences value) {
        restoreMain.setSelected(value.restoreMainWindow());
        restoreDivider.setSelected(value.restoreMainDivider());
        restoreEditor.setSelected(value.restoreEditorWindow());
        linkedScroll.setSelected(value.linkedScrollDefault());
        confirmDeletion.setSelected(value.confirmHunkDeletion());
        rememberLocations.setSelected(value.rememberChooserLocations());
        directoryPath.setText(value.recentDirectoryLocation() == null
                ? "未记录" : value.recentDirectoryLocation());
        filePath.setText(value.recentFileLocation() == null
                ? "未记录" : value.recentFileLocation());
        directoryPath.setCaretPosition(0);
        filePath.setCaretPosition(0);
        directoryPath.putClientProperty("emptyValue", value.recentDirectoryLocation() == null);
        filePath.putClientProperty("emptyValue", value.recentFileLocation() == null);
        updatePathState();
    }

    private void saveAndClose() {
        AppPreferences current = service.current();
        boolean remember = rememberLocations.isSelected();
        AppPreferences next = current.withOptions(restoreMain.isSelected(),
                restoreDivider.isSelected(), restoreEditor.isSelected(),
                linkedScroll.isSelected(), confirmDeletion.isSelected(), remember);
        if (remember) {
            next = next.withChooserLocation(true, pathValue(directoryPath));
            next = next.withChooserLocation(false, pathValue(filePath));
        }
        service.replace(next);
        dispose();
    }

    private String pathValue(JTextField field) {
        String value = field.getText().trim();
        return value.isEmpty() || "未记录".equals(value) ? null : value;
    }

    private void resetPreferences() {
        int answer = JOptionPane.showConfirmDialog(this,
                "将恢复窗口、编辑行为和选择器路径的默认设置。\n过滤规则和对比历史不会被删除。",
                "恢复默认设置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        setBusy(true);
        new SwingWorker<Void, Void>() {
            private IOException error;
            @Override protected Void doInBackground() {
                try {
                    service.reset();
                } catch (IOException ex) {
                    error = ex;
                }
                return null;
            }
            @Override protected void done() {
                setBusy(false);
                if (error != null) {
                    JOptionPane.showMessageDialog(PreferencesDialog.this,
                            "恢复默认设置失败：" + error.getMessage(), "操作失败",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                load(service.current());
                if (resetCallback != null) resetCallback.run();
            }
        }.execute();
    }

    private void updatePathState() {
        boolean enabled = rememberLocations.isSelected();
        directoryPath.setEnabled(enabled);
        filePath.setEnabled(enabled);
        clearDirectory.setEnabled(enabled);
        clearFile.setEnabled(enabled);
    }

    private void setBusy(boolean busy) {
        resetButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
                : Cursor.getDefaultCursor());
    }

    private static JTextField pathField(String name) {
        JTextField field = new JTextField();
        field.setName(name);
        field.setEditable(false);
        field.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        field.setForeground(MUTED);
        field.setBackground(new Color(248, 250, 252));
        field.setBorder(new CompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(5, 8, 5, 8)));
        return field;
    }

    private static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(BOLD);
        button.setForeground(TEXT);
        button.setBackground(SURFACE);
        button.setFocusPainted(false);
        button.setBorder(new CompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 15, 6, 15)));
        return button;
    }

    private static JButton primaryButton(String text) {
        JButton button = secondaryButton(text);
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setBorder(new EmptyBorder(7, 18, 7, 18));
        return button;
    }

    static final class ToggleSwitch extends JToggleButton {
        ToggleSwitch(String accessibleName) {
            setName(accessibleName);
            getAccessibleContext().setAccessibleName(accessibleName);
            setPreferredSize(new Dimension(44, 24));
            setMinimumSize(new Dimension(44, 24));
            setMaximumSize(new Dimension(44, 24));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(isEnabled() ? (isSelected() ? PRIMARY : new Color(184, 194, 204))
                    : new Color(222, 228, 234));
            g.fillRoundRect(1, 2, getWidth() - 2, getHeight() - 4, 20, 20);
            g.setColor(Color.WHITE);
            int diameter = getHeight() - 8;
            int x = isSelected() ? getWidth() - diameter - 4 : 4;
            g.fillOval(x, 4, diameter, diameter);
            if (isFocusOwner()) {
                g.setColor(PRIMARY);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 1, getWidth() - 1, getHeight() - 3, 21, 21);
            }
            g.dispose();
        }
    }

    private static final class VerticalScrollablePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visibleRect,
                                                        int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visibleRect,
                                                         int orientation, int direction) {
            return Math.max(16, visibleRect.height - 32);
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
