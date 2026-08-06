import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class AboutDialog extends JDialog {
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(218, 225, 232);
    private static final Color HEADER = new Color(247, 249, 251);
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private static final Font UI_FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);
    private final JLabel actionStatus = new JLabel(" ");

    AboutDialog(java.awt.Frame owner) {
        super(owner, "关于", Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setContentPane(createContent());
        pack();
        setMinimumSize(new Dimension(560, 360));
        setLocationRelativeTo(owner);
    }

    private JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createLineBorder(BORDER));
        root.add(createTitleBar(), BorderLayout.NORTH);
        root.add(createBody(), BorderLayout.CENTER);
        root.add(createActions(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel createTitleBar() {
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(SURFACE);
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(10, 16, 10, 16)));
        JLabel title = new JLabel("关于");
        title.setFont(UI_FONT_BOLD);
        title.setForeground(TEXT);
        titleBar.add(title, BorderLayout.WEST);
        return titleBar;
    }

    private JPanel createBody() {
        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setBackground(SURFACE);
        body.setBorder(new EmptyBorder(22, 24, 18, 24));
        body.add(createProductMark(), BorderLayout.WEST);

        JPanel details = new JPanel(new BorderLayout(0, 14));
        details.setOpaque(false);
        JPanel identity = new JPanel(new BorderLayout(0, 5));
        identity.setOpaque(false);
        JLabel name = new JLabel(AppInfo.NAME);
        name.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        name.setForeground(TEXT);
        identity.add(name, BorderLayout.NORTH);
        JPanel versionLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        versionLine.setOpaque(false);
        JLabel version = new JLabel("版本 " + AppInfo.version());
        version.setFont(UI_FONT);
        version.setForeground(TEXT);
        JLabel channel = new JLabel(AppInfo.channel());
        channel.setOpaque(true);
        channel.setBackground(new Color(255, 247, 230));
        channel.setForeground(new Color(154, 91, 0));
        channel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(240, 177, 74)),
                new EmptyBorder(2, 7, 2, 7)));
        channel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        versionLine.add(version);
        versionLine.add(channel);
        identity.add(versionLine, BorderLayout.SOUTH);
        details.add(identity, BorderLayout.NORTH);
        details.add(createMetadata(), BorderLayout.CENTER);
        body.add(details, BorderLayout.CENTER);
        return body;
    }

    private JLabel createProductMark() {
        JLabel mark = new JLabel("F", SwingConstants.CENTER);
        mark.setOpaque(true);
        mark.setBackground(PRIMARY);
        mark.setForeground(Color.WHITE);
        mark.setFont(new Font("SansSerif", Font.BOLD, 28));
        mark.setPreferredSize(new Dimension(58, 58));
        mark.setBorder(BorderFactory.createLineBorder(PRIMARY));
        return mark;
    }

    private JPanel createMetadata() {
        JPanel metadata = new JPanel(new GridBagLayout());
        metadata.setOpaque(false);
        addMetadataRow(metadata, 0, "构建", AppInfo.buildDate() + " · " + AppInfo.commit());
        addMetadataRow(metadata, 1, "运行环境", AppInfo.runtimeSummary());
        addMetadataRow(metadata, 2, "数据目录", AppInfo.dataDirectory().toString());
        addMetadataRow(metadata, 3, "许可", "项目许可与第三方开源软件声明");
        return metadata;
    }

    private void addMetadataRow(JPanel panel, int row, String labelText, String valueText) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.NORTHWEST;
        label.insets = new Insets(0, 0, 8, 14);
        JLabel name = new JLabel(labelText);
        name.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        name.setForeground(MUTED);
        panel.add(name, label);

        GridBagConstraints value = new GridBagConstraints();
        value.gridx = 1;
        value.gridy = row;
        value.weightx = 1;
        value.fill = GridBagConstraints.HORIZONTAL;
        value.anchor = GridBagConstraints.NORTHWEST;
        value.insets = new Insets(0, 0, 8, 0);
        JLabel content = new JLabel(valueText);
        content.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        content.setForeground(TEXT);
        content.setToolTipText(valueText);
        panel.add(content, value);
    }

    private JPanel createActions() {
        JPanel actions = new JPanel(new BorderLayout());
        actions.setBackground(HEADER);
        actions.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(8, 12, 8, 12)));
        actionStatus.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        actionStatus.setForeground(new Color(36, 138, 75));
        actions.add(actionStatus, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton copy = actionButton("复制版本信息", false);
        copy.addActionListener(e -> copyDiagnostics());
        JButton data = actionButton("打开数据目录", false);
        data.addActionListener(e -> openDataDirectory());
        JButton notes = actionButton("查看更新说明", false);
        notes.addActionListener(e -> openDistributionFile("CHANGELOG.md", "更新说明"));
        JButton licenses = actionButton("查看许可", false);
        licenses.addActionListener(e -> openDistributionFile(
                "THIRD_PARTY_NOTICES.md", "第三方许可声明"));
        JButton close = actionButton("关闭", true);
        close.addActionListener(e -> dispose());
        buttons.add(copy);
        buttons.add(data);
        buttons.add(notes);
        buttons.add(licenses);
        buttons.add(close);
        actions.add(buttons, BorderLayout.EAST);
        getRootPane().setDefaultButton(close);
        return actions;
    }

    private JButton actionButton(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFont(UI_FONT);
        button.setFocusPainted(false);
        button.setPreferredSize(new Dimension(primary ? 72 : 104, 34));
        if (primary) {
            button.setBackground(PRIMARY);
            button.setForeground(Color.WHITE);
            button.setBorder(BorderFactory.createLineBorder(PRIMARY));
        } else {
            button.setBackground(SURFACE);
            button.setForeground(TEXT);
            button.setBorder(BorderFactory.createLineBorder(BORDER));
        }
        return button;
    }

    private void copyDiagnostics() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(AppInfo.diagnosticInfo()), null);
            actionStatus.setText("版本信息已复制");
            Timer clear = new Timer(1800, e -> actionStatus.setText(" "));
            clear.setRepeats(false);
            clear.start();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "无法访问系统剪贴板：" + ex.getMessage(),
                    "复制失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openDataDirectory() {
        Path directory = AppInfo.dataDirectory();
        try {
            Files.createDirectories(directory);
            AppInfo.openInDesktop(directory);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "无法打开数据目录",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openDistributionFile(String name, String title) {
        Path file = AppInfo.distributionFile(name);
        if (!Files.exists(file)) {
            JOptionPane.showMessageDialog(this, "随包文件不存在：" + name, title,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            AppInfo.openInDesktop(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), title,
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
