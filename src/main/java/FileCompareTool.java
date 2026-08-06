import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class FileCompareTool extends JFrame {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String START_CARD = "start";
    private static final String WORKSPACE_CARD = "workspace";

    private static final Color APP_BACKGROUND = new Color(237, 241, 245);
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT_COLOR = new Color(31, 41, 55);
    private static final Color MUTED_COLOR = new Color(100, 116, 139);
    private static final Color BORDER_COLOR = new Color(218, 225, 232);
    private static final Color HEADER_COLOR = new Color(247, 249, 251);
    private static final Color PRIMARY_COLOR = new Color(37, 99, 235);
    private static final Color PRIMARY_DARK_COLOR = new Color(29, 78, 216);
    private static final Color SAME_COLOR = new Color(36, 138, 75);
    private static final Color SAME_BACKGROUND = new Color(235, 247, 239);
    private static final Color DIFFERENT_COLOR = new Color(214, 69, 69);
    private static final Color DIFFERENT_BACKGROUND = new Color(253, 237, 237);
    private static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
    private static final Font UI_FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 14);
    private static final Font CODE_FONT = new Font("Consolas", Font.PLAIN, 14);

    private final JToggleButton fileModeButton = new JToggleButton("文件对比");
    private final JToggleButton directoryModeButton = new JToggleButton("目录对比", true);
    private final JTextField leftField = new JTextField();
    private final JTextField rightField = new JTextField();
    private final JButton browseLeftButton = new JButton("选择目录");
    private final JButton browseRightButton = new JButton("选择目录");
    private final JButton filterButton = new JButton();
    private final JMenuItem filterMenuItem = new JMenuItem("过滤和排除规则");
    private final JMenu historyMenu = new JMenu("历史");
    private final JButton refreshButton = new JButton();
    private final JButton cancelScanButton = new JButton("取消");
    private final JButton scanDetailsButton = new JButton("查看详情");
    private final JButton syncLeftToRightButton = new JButton("同步到右侧  →");
    private final JButton syncRightToLeftButton = new JButton("←  同步到左侧");
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextArea logArea = new JTextArea(5, 80);
    private final JLabel sameSummaryLabel = new JLabel("相同 0");
    private final JLabel differentSummaryLabel = new JLabel("不同 0");
    private final JLabel missingSummaryLabel = new JLabel("缺失 0");
    private final JLabel statusLabel = new JLabel("请选择左右路径开始对比");
    private final JPanel scanTaskPanel = new JPanel(new GridBagLayout());
    private final JLabel scanStageLabel = new JLabel("正在发现文件");
    private final JLabel scanStatsLabel = new JLabel(" ");
    private final JLabel scanPathLabel = new JLabel(" ");
    private final JProgressBar scanProgressBar = new JProgressBar(0, 1000);
    private final JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 18, 8));
    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pageCards = new JPanel(pageLayout);
    private RecentHistoryPanel recentHistoryPanel;
    private SideTableModel leftTableModel = new SideTableModel();
    private SideTableModel rightTableModel = new SideTableModel();
    private final JTable leftTable = new JTable(leftTableModel);
    private final JTable rightTable = new JTable(rightTableModel);
    private final JScrollPane leftResultScroll = new JScrollPane(leftTable);
    private final JScrollPane rightResultScroll = new JScrollPane(rightTable);
    private final Set<String> expandedPaths = new LinkedHashSet<String>();
    private final List<CompareNode> visibleNodes = new ArrayList<CompareNode>();

    private CompareResult lastResult;
    private CompareNode treeRoot;
    private boolean busy;
    private boolean workspaceActive;
    private String pendingSelectionPath;
    private FilterRuleSet filterSettings;
    private String filterBasePresetId;
    private final FilterPresetService filterPresetService;
    private final CompareHistoryService historyService;
    private final PreferencesService preferencesService;
    private String startupFilterWarning;
    private String startupHistoryWarning;
    private String startupPreferencesWarning;
    private JSplitPane resultSplitPane;
    private Rectangle lastNormalMainBounds;
    private boolean preferencesCaptureReady;
    private boolean servicesClosed;
    private final SyncService syncService = new SyncService();
    private final CompareScanService compareScanService = new CompareScanService();
    private final AtomicLong compareTaskSequence = new AtomicLong();
    private SwingWorker<CompareTaskOutput, CompareScanService.ScanProgress> activeCompareWorker;
    private Timer activePublishTimer;
    private CompareScanService.CancellationToken activeCancellation;
    private long activeCompareTaskId;
    private CompareScanService.ScanProgress lastTaskProgress;
    private CompareScanService.ScanMetrics lastScanMetrics;

    public FileCompareTool() {
        this(new FilterPresetService(), new CompareHistoryService(), new PreferencesService());
    }

    FileCompareTool(FilterPresetService presetService) {
        this(presetService, new CompareHistoryService(), new PreferencesService());
    }

    FileCompareTool(FilterPresetService presetService, CompareHistoryService historyService) {
        this(presetService, historyService, new PreferencesService());
    }

    FileCompareTool(FilterPresetService presetService, CompareHistoryService historyService,
                    PreferencesService preferencesService) {
        super("文件对比工具");
        filterPresetService = presetService;
        this.historyService = historyService;
        this.preferencesService = preferencesService;
        ActiveFilterState activeFilters = filterPresetService.active();
        filterSettings = activeFilters.rules();
        filterBasePresetId = activeFilters.basePresetId();
        startupFilterWarning = filterPresetService.loadWarning();
        startupHistoryWarning = historyService.loadWarning();
        startupPreferencesWarning = preferencesService.loadWarning();
        configureLookAndFeel();
        buildUi();
        wireEvents();
        setMinimumSize(new Dimension(920, 620));
        restoreMainWindowPreferences();
        preferencesService.setErrorListener(message -> SwingUtilities.invokeLater(() -> {
            String text = "偏好设置保存失败：" + message;
            appendLog(text);
            statusLabel.setText(text);
        }));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                dispose();
            }
        });
        installPreferenceCapture();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new FileCompareTool().setVisible(true);
            }
        });
    }

    @Override
    public void dispose() {
        if (servicesClosed) {
            super.dispose();
            return;
        }
        servicesClosed = true;
        if (activeCancellation != null) {
            activeCancellation.cancel();
        }
        activeCompareTaskId = 0L;
        if (activePublishTimer != null) {
            activePublishTimer.stop();
        }
        captureMainPreferences();
        filterPresetService.close();
        historyService.close();
        preferencesService.close();
        super.dispose();
    }

    private void restoreMainWindowPreferences() {
        AppPreferences preferences = preferencesService.current();
        WindowBounds saved = preferences.restoreMainWindow()
                ? preferences.mainWindowBounds() : null;
        Rectangle bounds = WindowPlacement.fitToCurrentScreens(saved,
                new Dimension(920, 620), new Dimension(1180, 760));
        setBounds(bounds);
        lastNormalMainBounds = new Rectangle(bounds);
        if (preferences.restoreMainWindow() && preferences.mainWindowMaximized()) {
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        }
        SwingUtilities.invokeLater(() -> {
            if (resultSplitPane != null && preferences.restoreMainDivider()) {
                resultSplitPane.setDividerLocation(preferences.mainDividerRatio());
            }
        });
    }

    private void installPreferenceCapture() {
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) {
                captureMainPreferences();
            }

            @Override public void componentResized(ComponentEvent event) {
                captureMainPreferences();
            }
        });
        addWindowStateListener(event -> captureMainPreferences());
        if (resultSplitPane != null) {
            resultSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                    event -> captureDividerPreference());
        }
        preferencesCaptureReady = true;
    }

    private void captureMainPreferences() {
        if (!preferencesCaptureReady) return;
        boolean maximized = (getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0;
        if (!maximized && getWidth() > 0 && getHeight() > 0) {
            lastNormalMainBounds = getBounds();
        }
        Rectangle normal = lastNormalMainBounds;
        if (normal != null) {
            preferencesService.updateMainWindow(WindowBounds.from(normal), maximized);
        }
        captureDividerPreference();
    }

    private void captureDividerPreference() {
        if (!preferencesCaptureReady || resultSplitPane == null) return;
        int available = resultSplitPane.getWidth() - resultSplitPane.getDividerSize();
        if (available <= 0) return;
        double ratio = (double) resultSplitPane.getDividerLocation() / available;
        preferencesService.updateMainDivider(ratio);
    }

    private void showPreferencesDialog() {
        PreferencesDialog.showDialog(this, preferencesService,
                () -> applyDefaultWindowPreferences());
    }

    private void applyDefaultWindowPreferences() {
        preferencesCaptureReady = false;
        setExtendedState(JFrame.NORMAL);
        Rectangle bounds = WindowPlacement.fitToCurrentScreens(null,
                new Dimension(920, 620), new Dimension(1180, 760));
        setBounds(bounds);
        lastNormalMainBounds = new Rectangle(bounds);
        if (resultSplitPane != null) {
            resultSplitPane.setDividerLocation(AppPreferences.DEFAULT_DIVIDER_RATIO);
        }
        SwingUtilities.invokeLater(() -> preferencesCaptureReady = true);
        statusLabel.setText("已恢复默认偏好设置，过滤规则和对比历史未改变");
    }

    private void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Default look and feel is acceptable if the system one cannot load.
        }
        UIManager.put("Label.font", UI_FONT);
        UIManager.put("Button.font", UI_FONT_BOLD);
        UIManager.put("RadioButton.font", UI_FONT_BOLD);
        UIManager.put("TextField.font", UI_FONT);
        UIManager.put("Table.font", UI_FONT);
        UIManager.put("TableHeader.font", UI_FONT_BOLD);
        UIManager.put("OptionPane.messageFont", UI_FONT);
        UIManager.put("OptionPane.buttonFont", UI_FONT_BOLD);
    }

    private void buildUi() {
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(fileModeButton);
        modeGroup.add(directoryModeButton);

        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(APP_BACKGROUND);
        page.setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(SURFACE);
        shell.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));

        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(SURFACE);
        JPanel workArea = new JPanel(new BorderLayout());
        workArea.setBackground(SURFACE);
        workArea.add(createScanTaskPanel(), BorderLayout.NORTH);
        workArea.add(createResultPanel(), BorderLayout.CENTER);
        workspace.add(workArea, BorderLayout.CENTER);
        workspace.add(createFooterPanel(), BorderLayout.SOUTH);

        pageCards.setBackground(SURFACE);
        pageCards.add(createStartPanel(), START_CARD);
        pageCards.add(workspace, WORKSPACE_CARD);

        shell.add(createHeaderPanel(), BorderLayout.NORTH);
        shell.add(pageCards, BorderLayout.CENTER);
        page.add(shell, BorderLayout.CENTER);
        setContentPane(page);
        setJMenuBar(createMenuBar());
        installShortcuts();

        logArea.setEditable(false);
        logArea.setFont(CODE_FONT);
        logArea.setBackground(HEADER_COLOR);
        logArea.setForeground(TEXT_COLOR);
        logArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        summaryPanel.setVisible(false);
        scanTaskPanel.setVisible(false);
        pageLayout.show(pageCards, START_CARD);
        refreshHistoryUi(historyService.entries());
        if (startupHistoryWarning != null) {
            appendLog(startupHistoryWarning);
        }
        if (startupPreferencesWarning != null) {
            appendLog(startupPreferencesWarning);
        }
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(SURFACE);
        header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
                new EmptyBorder(14, 20, 14, 20)));

        JLabel appMark = new JLabel("⇄", SwingConstants.CENTER);
        appMark.setOpaque(true);
        appMark.setBackground(PRIMARY_COLOR);
        appMark.setForeground(SURFACE);
        appMark.setFont(new Font("SansSerif", Font.BOLD, 20));
        appMark.setPreferredSize(new Dimension(34, 34));

        JLabel title = new JLabel("文件对比工具");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 21));
        title.setForeground(TEXT_COLOR);
        JLabel subtitle = new JLabel("轻量文件校验与同步");
        subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        subtitle.setForeground(MUTED_COLOR);
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        titlePanel.add(title, BorderLayout.CENTER);
        titlePanel.add(subtitle, BorderLayout.SOUTH);

        JPanel identity = new JPanel(new BorderLayout(12, 0));
        identity.setOpaque(false);
        identity.add(appMark, BorderLayout.WEST);
        identity.add(titlePanel, BorderLayout.CENTER);

        styleSummaryLabel(sameSummaryLabel, SAME_COLOR);
        styleSummaryLabel(differentSummaryLabel, DIFFERENT_COLOR);
        styleSummaryLabel(missingSummaryLabel, DIFFERENT_COLOR);
        summaryPanel.setOpaque(false);
        summaryPanel.add(sameSummaryLabel);
        summaryPanel.add(differentSummaryLabel);
        summaryPanel.add(missingSummaryLabel);
        styleFilterButton();
        summaryPanel.add(filterButton);
        styleRefreshButton();
        summaryPanel.add(refreshButton);

        header.add(identity, BorderLayout.WEST);
        header.add(summaryPanel, BorderLayout.EAST);
        return header;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(SURFACE);
        menuBar.setBorder(new MatteBorder(0, 0, 1, 0, BORDER_COLOR));
        menuBar.setFont(UI_FONT);

        JMenu fileMenu = new JMenu("文件");
        fileMenu.setFont(UI_FONT);
        JMenuItem directoryCompareItem = new JMenuItem("新建文件夹对比");
        JMenuItem fileCompareItem = new JMenuItem("新建文件对比");
        JMenuItem homeItem = new JMenuItem("返回首页");
        JMenuItem exitItem = new JMenuItem("退出");
        styleMenuItem(directoryCompareItem);
        styleMenuItem(fileCompareItem);
        styleMenuItem(homeItem);
        styleMenuItem(exitItem);
        directoryCompareItem.addActionListener(e -> openCompareWorkspace(CompareMode.DIRECTORY));
        fileCompareItem.addActionListener(e -> openCompareWorkspace(CompareMode.FILE));
        homeItem.addActionListener(e -> showStartPage());
        exitItem.addActionListener(e -> dispose());
        fileMenu.add(directoryCompareItem);
        fileMenu.add(fileCompareItem);
        fileMenu.addSeparator();
        fileMenu.add(homeItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        JMenu toolMenu = new JMenu("工具");
        toolMenu.setFont(UI_FONT);
        JMenuItem reloadItem = new JMenuItem("重新加载    F5");
        JMenuItem filterPresetItem = new JMenuItem("管理过滤预设");
        JMenuItem scanDetailsItem = new JMenuItem("查看本次扫描详情");
        JMenuItem logItem = new JMenuItem("查看运行日志");
        JMenuItem preferencesItem = new JMenuItem("偏好设置");
        styleMenuItem(reloadItem);
        styleMenuItem(filterMenuItem);
        styleMenuItem(filterPresetItem);
        styleMenuItem(scanDetailsItem);
        styleMenuItem(logItem);
        styleMenuItem(preferencesItem);
        reloadItem.addActionListener(e -> reloadComparison());
        filterMenuItem.addActionListener(e -> showFilterDialog());
        filterPresetItem.addActionListener(e -> showFilterPresetManager());
        scanDetailsItem.addActionListener(e -> showScanDetails());
        logItem.addActionListener(e -> showLogDialog());
        preferencesItem.addActionListener(e -> showPreferencesDialog());
        toolMenu.add(reloadItem);
        toolMenu.add(filterMenuItem);
        toolMenu.add(filterPresetItem);
        toolMenu.add(scanDetailsItem);
        toolMenu.addSeparator();
        toolMenu.add(preferencesItem);
        toolMenu.add(logItem);

        historyMenu.setFont(UI_FONT);
        rebuildHistoryMenu(historyService.entries());

        JMenu viewMenu = new JMenu("查看");
        viewMenu.setFont(UI_FONT);
        JMenuItem expandAllItem = new JMenuItem("全部展开");
        JMenuItem collapseAllItem = new JMenuItem("全部折叠");
        styleMenuItem(expandAllItem);
        styleMenuItem(collapseAllItem);
        expandAllItem.addActionListener(e -> expandAllDirectories());
        collapseAllItem.addActionListener(e -> collapseAllDirectories());
        viewMenu.add(expandAllItem);
        viewMenu.add(collapseAllItem);

        JMenu helpMenu = new JMenu("帮助");
        helpMenu.setFont(UI_FONT);
        JMenuItem releaseNotesItem = new JMenuItem("更新说明");
        JMenuItem aboutItem = new JMenuItem("关于");
        styleMenuItem(releaseNotesItem);
        styleMenuItem(aboutItem);
        releaseNotesItem.addActionListener(e -> openReleaseNotes());
        aboutItem.addActionListener(e -> new AboutDialog(this).setVisible(true));
        helpMenu.add(releaseNotesItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(historyMenu);
        menuBar.add(viewMenu);
        menuBar.add(toolMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    private void installShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("F5"), "reloadComparison");
        getRootPane().getActionMap().put("reloadComparison", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                reloadComparison();
            }
        });
    }

    private void styleMenuItem(JMenuItem item) {
        item.setFont(UI_FONT);
        item.setBackground(SURFACE);
        item.setForeground(TEXT_COLOR);
    }

    private JPanel createStartPanel() {
        JPanel start = new JPanel(new GridBagLayout());
        start.setBackground(SURFACE);
        start.setBorder(new EmptyBorder(12, 24, 12, 24));

        JLabel title = new JLabel("选择对比方式", SwingConstants.CENTER);
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 24));
        title.setForeground(TEXT_COLOR);
        JLabel subtitle = new JLabel("新建文件或文件夹对比", SwingConstants.CENTER);
        subtitle.setFont(UI_FONT);
        subtitle.setForeground(MUTED_COLOR);

        JButton directoryButton = createModeChoiceButton("文件夹对比", true);
        JButton fileButton = createModeChoiceButton("文件对比", false);
        directoryButton.addActionListener(e -> openCompareWorkspace(CompareMode.DIRECTORY));
        fileButton.addActionListener(e -> openCompareWorkspace(CompareMode.FILE));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 0, 8, 0);
        start.add(title, gbc);
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 10, 0);
        start.add(subtitle, gbc);
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.insets = new Insets(0, 0, 0, 14);
        start.add(directoryButton, gbc);
        gbc.gridx = 1;
        gbc.insets = new Insets(0, 14, 0, 0);
        start.add(fileButton, gbc);
        recentHistoryPanel = new RecentHistoryPanel(createHistoryUiHandler());
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.insets = new Insets(0, 0, 0, 0);
        start.add(recentHistoryPanel, gbc);
        return start;
    }

    private JButton createModeChoiceButton(String text, boolean directory) {
        final JButton button = new JButton(text, new ModeIcon(directory));
        button.setUI(new BasicButtonUI());
        button.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 18));
        button.setForeground(TEXT_COLOR);
        button.setBackground(SURFACE);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setIconTextGap(8);
        button.setPreferredSize(new Dimension(220, 128));
        button.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(10, 24, 10, 24)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(248, 251, 255));
                button.setBorder(new CompoundBorder(
                        BorderFactory.createLineBorder(PRIMARY_COLOR),
                        new EmptyBorder(10, 24, 10, 24)));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(SURFACE);
                button.setBorder(new CompoundBorder(
                        BorderFactory.createLineBorder(BORDER_COLOR),
                        new EmptyBorder(10, 24, 10, 24)));
            }
        });
        return button;
    }

    private void openCompareWorkspace(CompareMode mode) {
        if (busy) {
            return;
        }
        workspaceActive = true;
        boolean fileMode = mode == CompareMode.FILE;
        fileModeButton.setSelected(fileMode);
        directoryModeButton.setSelected(!fileMode);
        browseLeftButton.setToolTipText(fileMode ? "选择文件" : "选择目录");
        browseRightButton.setToolTipText(fileMode ? "选择文件" : "选择目录");
        filterButton.setEnabled(!fileMode);
        filterMenuItem.setEnabled(!fileMode);
        filterButton.setToolTipText(fileMode ? "过滤规则仅用于目录对比" : filterTooltipText());
        leftField.setText("");
        rightField.setText("");
        clearResults();
        if (!fileMode) {
            if (startupFilterWarning != null) {
                statusLabel.setText(startupFilterWarning);
                appendLog(startupFilterWarning);
                startupFilterWarning = null;
            } else if (!filterSettings.isEmpty()) {
                statusLabel.setText("已恢复过滤规则："
                        + filterPresetService.displayName(filterPresetService.active())
                        + "；请选择左右目录");
            }
        }
        summaryPanel.setVisible(true);
        pageLayout.show(pageCards, WORKSPACE_CARD);
        SwingUtilities.invokeLater(() -> leftField.requestFocusInWindow());
    }

    private void showStartPage() {
        if (busy) {
            return;
        }
        workspaceActive = false;
        summaryPanel.setVisible(false);
        refreshHistoryUi(historyService.entries());
        pageLayout.show(pageCards, START_CARD);
    }

    private HistoryUiHandler createHistoryUiHandler() {
        return new HistoryUiHandler() {
            @Override public void openHistory(CompareHistoryEntry entry) {
                openHistoryEntry(entry);
            }

            @Override public void toggleHistoryPinned(final CompareHistoryEntry entry) {
                runHistoryMutation("更新固定状态", new HistoryMutation() {
                    @Override public List<CompareHistoryEntry> run() throws IOException {
                        return historyService.togglePinned(entry.id());
                    }
                });
            }

            @Override public void editHistoryNote(final CompareHistoryEntry entry) {
                String note = JOptionPane.showInputDialog(FileCompareTool.this,
                        "备注最多 " + CompareHistoryEntry.MAX_NOTE_LENGTH + " 个字符：",
                        entry.note());
                if (note == null) {
                    return;
                }
                final String value = note;
                runHistoryMutation("保存备注", new HistoryMutation() {
                    @Override public List<CompareHistoryEntry> run() throws IOException {
                        return historyService.updateNote(entry.id(), value);
                    }
                });
            }

            @Override public void deleteHistory(final CompareHistoryEntry entry) {
                if (JOptionPane.showConfirmDialog(FileCompareTool.this,
                        "删除这条对比历史？", "删除历史", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                    return;
                }
                runHistoryMutation("删除历史", new HistoryMutation() {
                    @Override public List<CompareHistoryEntry> run() throws IOException {
                        return historyService.delete(entry.id());
                    }
                });
            }

            @Override public void historyChanged(List<CompareHistoryEntry> entries) {
                refreshHistoryUi(entries);
            }

            @Override public boolean historyOpenBlocked() {
                return busy;
            }
        };
    }

    private void refreshHistoryUi(List<CompareHistoryEntry> entries) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final List<CompareHistoryEntry> values =
                    new ArrayList<CompareHistoryEntry>(entries);
            SwingUtilities.invokeLater(() -> refreshHistoryUi(values));
            return;
        }
        if (recentHistoryPanel != null) {
            recentHistoryPanel.setEntries(entries);
        }
        rebuildHistoryMenu(entries);
    }

    private void rebuildHistoryMenu(List<CompareHistoryEntry> entries) {
        historyMenu.removeAll();
        int count = Math.min(5, entries.size());
        if (count == 0) {
            JMenuItem empty = new JMenuItem("暂无对比历史");
            styleMenuItem(empty);
            empty.setEnabled(false);
            historyMenu.add(empty);
        } else {
            for (int i = 0; i < count; i++) {
                final CompareHistoryEntry entry = entries.get(i);
                JMenuItem item = new JMenuItem((entry.pinned() ? "固定 · " : "")
                        + entry.mode().displayName() + " · "
                        + RecentHistoryPanel.middle(entry.displayName(), 32));
                styleMenuItem(item);
                item.setToolTipText(entry.leftPath() + "  →  " + entry.rightPath());
                item.addActionListener(e -> openHistoryEntry(entry));
                historyMenu.add(item);
            }
        }
        historyMenu.addSeparator();
        JMenuItem manager = new JMenuItem("查看全部历史");
        JMenuItem clear = new JMenuItem("清空全部历史");
        styleMenuItem(manager);
        styleMenuItem(clear);
        manager.addActionListener(e -> HistoryManagerDialog.show(this, historyService,
                createHistoryUiHandler()));
        clear.addActionListener(e -> clearHistory());
        clear.setEnabled(!entries.isEmpty());
        historyMenu.add(manager);
        historyMenu.add(clear);
    }

    private void clearHistory() {
        if (historyService.entries().isEmpty()) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "清空全部对比历史？此操作不会删除实际文件。", "清空历史",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        runHistoryMutation("清空历史", new HistoryMutation() {
            @Override public List<CompareHistoryEntry> run() throws IOException {
                historyService.clear();
                return Collections.emptyList();
            }
        });
    }

    private void runHistoryMutation(final String action, final HistoryMutation mutation) {
        new SwingWorker<List<CompareHistoryEntry>, Void>() {
            @Override protected List<CompareHistoryEntry> doInBackground() throws Exception {
                return mutation.run();
            }

            @Override protected void done() {
                try {
                    List<CompareHistoryEntry> values = get();
                    refreshHistoryUi(values);
                    statusLabel.setText(action + "完成");
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null
                            ? ex.getCause() : ex;
                    showError(action + "失败：" + rootMessage(cause));
                }
            }
        }.execute();
    }

    private void openHistoryEntry(final CompareHistoryEntry entry) {
        if (busy) {
            JOptionPane.showMessageDialog(this, "请先取消当前扫描并等待任务结束。",
                    "扫描进行中", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        statusLabel.setText("正在检查历史路径...");
        new SwingWorker<HistoryPathStatus, Void>() {
            @Override protected HistoryPathStatus doInBackground() {
                return HistoryPathValidator.validate(entry);
            }

            @Override protected void done() {
                try {
                    HistoryPathStatus status = get();
                    if (!status.available()) {
                        JOptionPane.showMessageDialog(FileCompareTool.this,
                                status.displayName() + "，请在历史管理中重新定位。",
                                "历史任务不可用", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    restoreHistoryEntry(entry);
                } catch (Exception ex) {
                    showError("历史路径检查失败：" + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void restoreHistoryEntry(CompareHistoryEntry entry) {
        CompareMode mode = entry.mode() == CompareHistoryMode.FILE
                ? CompareMode.FILE : CompareMode.DIRECTORY;
        openCompareWorkspace(mode);
        if (mode == CompareMode.DIRECTORY) {
            FilterRuleSet restored = entry.filter().rules();
            String presetId = entry.filter().presetId();
            FilterPreset preset = filterPresetService.findPreset(presetId);
            if (preset == null || !preset.rules().equals(restored)) {
                presetId = null;
            }
            ActiveFilterState active = filterPresetService.activate(restored, presetId);
            filterSettings = active.rules();
            filterBasePresetId = active.basePresetId();
            filterPresetService.persistActiveAsync(new FilterPresetService.SaveCallback() {
                @Override public void completed(String errorMessage) {
                    if (errorMessage != null) {
                        appendLog("历史规则已恢复，但未保存到本地：" + errorMessage);
                    }
                }
            });
            updateFilterButtonState();
        }
        leftField.setText(entry.leftPath());
        rightField.setText(entry.rightPath());
        expandedPaths.clear();
        appendLog("从历史恢复任务，重新读取当前磁盘内容。");
        compare();
    }

    private interface HistoryMutation {
        List<CompareHistoryEntry> run() throws IOException;
    }

    private JPanel createResultPanel() {
        configureResultTable(leftTable);
        configureResultTable(rightTable);
        syncTableSelections();
        leftResultScroll.setBorder(null);
        rightResultScroll.setBorder(null);
        syncScrollPanes(leftResultScroll, rightResultScroll);

        JPanel leftSide = createResultSide(leftField, browseLeftButton, leftResultScroll);
        JPanel rightSide = createResultSide(rightField, browseRightButton, rightResultScroll);
        resultSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSide, rightSide);
        resultSplitPane.setResizeWeight(0.5);
        resultSplitPane.setDividerSize(12);
        resultSplitPane.setBorder(null);
        resultSplitPane.setBackground(SURFACE);

        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.setBackground(SURFACE);
        resultPanel.setBorder(new EmptyBorder(14, 20, 12, 20));
        resultPanel.add(resultSplitPane, BorderLayout.CENTER);
        return resultPanel;
    }

    private JPanel createScanTaskPanel() {
        scanTaskPanel.setBackground(new Color(248, 250, 252));
        scanTaskPanel.setBorder(new CompoundBorder(
                new MatteBorder(0, 20, 1, 20, BORDER_COLOR),
                new EmptyBorder(12, 16, 11, 16)));

        scanStageLabel.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 15));
        scanStageLabel.setForeground(TEXT_COLOR);
        scanStatsLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        scanStatsLabel.setForeground(MUTED_COLOR);
        scanPathLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        scanPathLabel.setForeground(MUTED_COLOR);
        scanPathLabel.setToolTipText("");
        scanProgressBar.setForeground(PRIMARY_COLOR);
        scanProgressBar.setBackground(new Color(226, 232, 240));
        scanProgressBar.setPreferredSize(new Dimension(400, 10));
        scanProgressBar.setStringPainted(false);
        styleSecondaryButton(cancelScanButton);
        cancelScanButton.setPreferredSize(new Dimension(104, 34));
        cancelScanButton.setMinimumSize(new Dimension(104, 34));
        cancelScanButton.addActionListener(e -> {
            if (busy) {
                cancelActiveComparison();
            } else {
                compare();
            }
        });

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        scanTaskPanel.add(scanStageLabel, gbc);
        gbc.gridy = 1;
        gbc.insets = new Insets(3, 0, 0, 0);
        scanTaskPanel.add(scanStatsLabel, gbc);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridheight = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 18, 0, 0);
        scanTaskPanel.add(cancelScanButton, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridheight = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 0, 0);
        scanTaskPanel.add(scanProgressBar, gbc);
        gbc.gridy = 3;
        gbc.insets = new Insets(7, 0, 0, 0);
        scanTaskPanel.add(scanPathLabel, gbc);
        return scanTaskPanel;
    }

    private JPanel createResultSide(JTextField pathField, JButton browseButton, JScrollPane scrollPane) {
        stylePathField(pathField);
        browseButton.setText("");
        browseButton.setIcon(new FolderIcon(true));
        browseButton.setToolTipText(fileModeButton.isSelected() ? "选择文件" : "选择目录");
        browseButton.setUI(new BasicButtonUI());
        browseButton.setBackground(new Color(250, 251, 252));
        browseButton.setOpaque(true);
        browseButton.setContentAreaFilled(true);
        browseButton.setFocusPainted(false);
        browseButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        browseButton.setPreferredSize(new Dimension(44, 40));
        browseButton.setBorder(new CompoundBorder(
                new MatteBorder(0, 1, 0, 0, BORDER_COLOR),
                new EmptyBorder(6, 10, 6, 10)));

        JLabel pathIcon = new JLabel(new FolderIcon(false));
        pathIcon.setBorder(new EmptyBorder(0, 10, 0, 2));
        JPanel pathControl = new JPanel(new BorderLayout());
        pathControl.setBackground(new Color(250, 251, 252));
        pathControl.setBorder(BorderFactory.createLineBorder(new Color(207, 216, 226)));
        pathControl.add(pathIcon, BorderLayout.WEST);
        pathControl.add(pathField, BorderLayout.CENTER);
        pathControl.add(browseButton, BorderLayout.EAST);

        JPanel pathBar = new JPanel(new BorderLayout());
        pathBar.setBackground(SURFACE);
        pathBar.setBorder(new EmptyBorder(10, 10, 10, 10));
        pathBar.add(pathControl, BorderLayout.CENTER);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(SURFACE);
        side.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        side.add(pathBar, BorderLayout.NORTH);
        side.add(scrollPane, BorderLayout.CENTER);
        return side;
    }

    private JPanel createFooterPanel() {
        styleSecondaryButton(syncLeftToRightButton);
        styleSecondaryButton(syncRightToLeftButton);
        compactFooterButton(syncLeftToRightButton);
        compactFooterButton(syncRightToLeftButton);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(syncLeftToRightButton);
        actions.add(syncRightToLeftButton);

        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(90, 6));
        progressBar.setForeground(PRIMARY_COLOR);
        progressBar.setBackground(HEADER_COLOR);
        progressBar.setVisible(false);
        statusLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        statusLabel.setForeground(MUTED_COLOR);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 7));
        status.setOpaque(false);
        status.add(progressBar);
        status.add(statusLabel);
        styleTextButton(scanDetailsButton);
        scanDetailsButton.setVisible(false);
        scanDetailsButton.addActionListener(e -> showScanDetails());
        status.add(scanDetailsButton);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SURFACE);
        footer.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, BORDER_COLOR),
                new EmptyBorder(7, 20, 7, 20)));
        footer.add(actions, BorderLayout.WEST);
        footer.add(status, BorderLayout.EAST);
        return footer;
    }

    private void styleSummaryLabel(JLabel label, Color color) {
        label.setText("●  " + label.getText());
        label.setFont(UI_FONT);
        label.setForeground(color);
    }

    private void styleRefreshButton() {
        refreshButton.setUI(new BasicButtonUI());
        refreshButton.setIcon(new RefreshIcon());
        refreshButton.setToolTipText("重新加载 (F5)");
        refreshButton.setPreferredSize(new Dimension(34, 34));
        refreshButton.setBackground(SURFACE);
        refreshButton.setOpaque(true);
        refreshButton.setContentAreaFilled(true);
        refreshButton.setFocusPainted(false);
        refreshButton.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        refreshButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshButton.setEnabled(false);
    }

    private void styleFilterButton() {
        filterButton.setUI(new BasicButtonUI());
        filterButton.setIcon(new FilterIcon());
        filterButton.setToolTipText(filterTooltipText());
        filterButton.setPreferredSize(new Dimension(34, 34));
        filterButton.setBackground(SURFACE);
        filterButton.setOpaque(true);
        filterButton.setContentAreaFilled(true);
        filterButton.setFocusPainted(false);
        filterButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateFilterButtonState();
    }

    private void updateFilterButtonState() {
        boolean active = !filterSettings.isEmpty();
        filterButton.setForeground(active ? PRIMARY_COLOR : MUTED_COLOR);
        filterButton.setBackground(active ? new Color(239, 246, 255) : SURFACE);
        filterButton.setBorder(BorderFactory.createLineBorder(active ? PRIMARY_COLOR : BORDER_COLOR));
        filterButton.setToolTipText(filterTooltipText());
    }

    private String filterTooltipText() {
        if (filterSettings.isEmpty()) {
            return "过滤和排除规则（未设置）";
        }
        return "过滤和排除规则："
                + filterPresetService.displayName(new ActiveFilterState(filterSettings,
                filterPresetService.active().source(), filterBasePresetId));
    }

    private void stylePathField(JTextField field) {
        field.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        field.setForeground(TEXT_COLOR);
        field.setBackground(new Color(250, 251, 252));
        field.setPreferredSize(new Dimension(200, 40));
        field.setBorder(new EmptyBorder(0, 8, 0, 8));
    }

    private void compactFooterButton(JButton button) {
        button.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        button.setPreferredSize(new Dimension(128, 32));
        button.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(new Color(184, 196, 209)),
                new EmptyBorder(4, 9, 4, 9)));
    }

    private void stylePrimaryButton(JButton button) {
        configureButton(button, PRIMARY_COLOR, SURFACE,
                BorderFactory.createLineBorder(PRIMARY_COLOR), PRIMARY_DARK_COLOR);
    }

    private void styleSecondaryButton(JButton button) {
        configureButton(button, SURFACE, TEXT_COLOR,
                BorderFactory.createLineBorder(new Color(184, 196, 209)), HEADER_COLOR);
    }

    private void styleTextButton(JButton button) {
        button.setUI(new BasicButtonUI());
        button.setFont(UI_FONT);
        button.setForeground(MUTED_COLOR);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorder(new EmptyBorder(6, 8, 6, 8));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void configureButton(final JButton button, final Color background, Color foreground,
                                 Border border, final Color hoverBackground) {
        button.setUI(new BasicButtonUI());
        button.setFont(UI_FONT_BOLD);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorder(new CompoundBorder(border, new EmptyBorder(7, 13, 7, 13)));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(hoverBackground);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(background);
            }
        });
    }

    private void wireEvents() {
        browseLeftButton.addActionListener(e -> choosePath(leftField));
        browseRightButton.addActionListener(e -> choosePath(rightField));
        filterButton.addActionListener(e -> showFilterDialog());
        refreshButton.addActionListener(e -> reloadComparison());
        leftField.addActionListener(e -> maybeAutoCompare());
        rightField.addActionListener(e -> maybeAutoCompare());
        syncLeftToRightButton.addActionListener(e -> sync(true));
        syncRightToLeftButton.addActionListener(e -> sync(false));
        leftTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleResultClick(leftTable, e);
            }
        });
        rightTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleResultClick(rightTable, e);
            }
        });
    }

    private void configureResultTable(JTable table) {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setFillsViewportHeight(true);
        table.setRowHeight(31);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(SURFACE);
        table.setForeground(TEXT_COLOR);
        table.setSelectionBackground(new Color(219, 234, 254));
        table.setSelectionForeground(TEXT_COLOR);
        table.setFont(UI_FONT);
        table.setDefaultRenderer(Object.class, new StatusCellRenderer());
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        table.getTableHeader().setBackground(HEADER_COLOR);
        table.getTableHeader().setForeground(MUTED_COLOR);
        table.getTableHeader().setFont(UI_FONT_BOLD);
        table.getTableHeader().setBorder(new MatteBorder(0, 0, 1, 0, BORDER_COLOR));
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
    }

    private void showLogDialog() {
        JTextArea text = new JTextArea(logArea.getText(), 16, 76);
        text.setEditable(false);
        text.setFont(CODE_FONT);
        text.setBackground(HEADER_COLOR);
        text.setForeground(TEXT_COLOR);
        text.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        JOptionPane.showMessageDialog(this, scroll, "运行日志", JOptionPane.PLAIN_MESSAGE);
    }

    private void showFilterDialog() {
        if (fileModeButton.isSelected()) {
            showError("过滤规则仅适用于目录对比。");
            return;
        }
        FilterPresetDialog.show(this, filterPresetService,
                new ActiveFilterState(filterSettings, filterPresetService.active().source(),
                        filterBasePresetId),
                hasCompleteDirectoryPaths(), new FilterPresetDialog.ApplyHandler() {
                    @Override
                    public void apply(FilterRuleSet rules, String basePresetId) {
                        applyFilterRules(rules, basePresetId);
                    }
                });
    }

    private void showFilterPresetManager() {
        FilterPresetDialog.showManager(this, filterPresetService, filterSettings, new Runnable() {
            @Override
            public void run() {
                ActiveFilterState active = filterPresetService.active();
                filterSettings = active.rules();
                filterBasePresetId = active.basePresetId();
                updateFilterButtonState();
            }
        });
    }

    private void applyFilterRules(FilterRuleSet rules, String basePresetId) {
        boolean changed = !filterSettings.equals(rules);
        ActiveFilterState active = filterPresetService.activate(rules, basePresetId);
        filterSettings = active.rules();
        filterBasePresetId = active.basePresetId();
        updateFilterButtonState();
        filterPresetService.persistActiveAsync(new FilterPresetService.SaveCallback() {
            @Override
            public void completed(String errorMessage) {
                if (errorMessage != null) {
                    String message = "规则本次已生效，但未能保存到本地：" + errorMessage;
                    appendLog(message);
                    statusLabel.setText(message);
                }
            }
        });

        if (changed && hasCompleteDirectoryPaths()) {
            expandedPaths.clear();
            maybeAutoCompare();
        } else if (!changed) {
            statusLabel.setText("过滤规则未变化，已保存当前配置");
        } else {
            statusLabel.setText(filterSettings.isEmpty()
                    ? "已清除过滤规则"
                    : "过滤规则已保存，选择左右目录后生效");
        }
    }

    private boolean hasCompleteDirectoryPaths() {
        if (fileModeButton.isSelected()) {
            return false;
        }
        try {
            Path left = parsePath(leftField.getText());
            Path right = parsePath(rightField.getText());
            return left != null && right != null && Files.isDirectory(left) && Files.isDirectory(right);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void syncScrollPanes(final JScrollPane left, final JScrollPane right) {
        syncScrollPanes(left, right, null);
    }

    private void syncScrollPanes(final JScrollPane left, final JScrollPane right,
                                 final JCheckBox enabledToggle) {
        final boolean[] syncing = new boolean[]{false};
        left.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (syncing[0] || (enabledToggle != null && !enabledToggle.isSelected())) {
                return;
            }
            syncing[0] = true;
            right.getVerticalScrollBar().setValue(e.getValue());
            syncing[0] = false;
        });
        right.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (syncing[0] || (enabledToggle != null && !enabledToggle.isSelected())) {
                return;
            }
            syncing[0] = true;
            left.getVerticalScrollBar().setValue(e.getValue());
            syncing[0] = false;
        });
    }

    private void syncTableSelections() {
        final boolean[] syncing = new boolean[]{false};
        leftTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing[0]) {
                return;
            }
            int row = leftTable.getSelectedRow();
            if (row >= 0 && row < rightTable.getRowCount()) {
                syncing[0] = true;
                rightTable.setRowSelectionInterval(row, row);
                syncing[0] = false;
            }
        });
        rightTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing[0]) {
                return;
            }
            int row = rightTable.getSelectedRow();
            if (row >= 0 && row < leftTable.getRowCount()) {
                syncing[0] = true;
                leftTable.setRowSelectionInterval(row, row);
                syncing[0] = false;
            }
        });
    }

    private void choosePath(JTextField targetField) {
        JFileChooser chooser = new JFileChooser();
        boolean fileMode = fileModeButton.isSelected();
        chooser.setFileSelectionMode(fileMode
                ? JFileChooser.FILES_ONLY
                : JFileChooser.DIRECTORIES_ONLY);
        String existing = targetField.getText().trim();
        if (!existing.isEmpty()) {
            chooser.setSelectedFile(Paths.get(existing).toFile());
        } else {
            Path start = preferencesService.chooserStart(!fileMode);
            if (start != null) chooser.setCurrentDirectory(start.toFile());
        }
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String selectedPath = chooser.getSelectedFile().getAbsolutePath();
            if (!selectedPath.equals(targetField.getText().trim())) {
                expandedPaths.clear();
            }
            targetField.setText(selectedPath);
            Path selected = chooser.getSelectedFile().toPath();
            preferencesService.updateChooserLocation(!fileMode,
                    fileMode ? selected.getParent() : selected);
            maybeAutoCompare();
        }
    }

    private void maybeAutoCompare() {
        Path left = parsePath(leftField.getText());
        Path right = parsePath(rightField.getText());
        if (left == null || right == null) {
            statusLabel.setText("请选择另一侧路径");
            return;
        }
        if (lastResult != null
                && (!left.equals(lastResult.leftRoot) || !right.equals(lastResult.rightRoot))) {
            expandedPaths.clear();
        }
        compare();
    }

    private void compare() {
        if (busy) {
            return;
        }
        final Path left = parsePath(leftField.getText());
        final Path right = parsePath(rightField.getText());
        if (left == null || right == null) {
            showError("请先选择左侧和右侧路径。");
            return;
        }
        final CompareMode mode = fileModeButton.isSelected() ? CompareMode.FILE : CompareMode.DIRECTORY;
        final FilterRuleSet filters = filterSettings;
        String validationError = validateCompareInput(left, right, mode);
        if (validationError != null) {
            showError(validationError);
            return;
        }

        pendingSelectionPath = selectedNodePath();
        final long taskId = compareTaskSequence.incrementAndGet();
        final CompareScanService.CancellationToken cancellation =
                new CompareScanService.CancellationToken();
        activeCompareTaskId = taskId;
        activeCancellation = cancellation;
        lastTaskProgress = null;
        setScanBusy(true, lastResult == null ? "正在扫描文件..." : "正在重新扫描，上次结果保持只读");
        appendLog("开始扫描，使用 " + HASH_ALGORITHM + " 校验文件内容。");

        final CompareScanService.ScanFilter scanFilter = new CompareScanService.ScanFilter() {
            @Override
            public boolean matchesDirectory(String relativePath) {
                return filters.matchesDirectory(relativePath);
            }

            @Override
            public boolean matchesFile(String relativePath) {
                return filters.matchesFile(relativePath);
            }
        };
        SwingWorker<CompareTaskOutput, CompareScanService.ScanProgress> worker =
                new SwingWorker<CompareTaskOutput, CompareScanService.ScanProgress>() {
            @Override
            protected CompareTaskOutput doInBackground() throws Exception {
                CompareScanService.ScanRequest request = new CompareScanService.ScanRequest(
                        taskId, mode == CompareMode.DIRECTORY, left, right, scanFilter);
                CompareScanService.ScanResult scanned = compareScanService.execute(
                        request, cancellation, new CompareScanService.ProgressListener() {
                            @Override
                            public void onProgress(CompareScanService.ScanProgress progress) {
                                publish(progress);
                            }
                        });
                cancellation.throwIfCancelled();
                long treeStarted = System.nanoTime();
                CompareResult result = toCompareResult(scanned, mode);
                CompareNode root = buildComparisonTree(result);
                long treeMillis = (System.nanoTime() - treeStarted) / 1000000L;
                scanned.metrics.buildMillis += treeMillis;
                scanned.metrics.totalMillis += treeMillis;
                return new CompareTaskOutput(taskId, result, root, scanned.metrics);
            }

            @Override
            protected void process(List<CompareScanService.ScanProgress> chunks) {
                if (taskId != activeCompareTaskId || chunks.isEmpty()) {
                    return;
                }
                updateScanProgress(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                if (taskId != activeCompareTaskId) {
                    return;
                }
                try {
                    CompareTaskOutput output = get();
                    if (cancellation.isCancelled()) {
                        finishCancelledScan(taskId, "构建结果");
                        return;
                    }
                    beginResultPublication(output);
                } catch (CancellationException ex) {
                    finishCancelledScan(taskId, scanStageLabel.getText());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    finishCancelledScan(taskId, scanStageLabel.getText());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof CancellationException) {
                        finishCancelledScan(taskId, scanStageLabel.getText());
                    } else {
                        finishFailedScan(taskId, cause);
                    }
                }
            }
        };
        activeCompareWorker = worker;
        worker.execute();
    }

    private CompareResult toCompareResult(CompareScanService.ScanResult scanned,
                                          CompareMode mode) {
        CompareResult result = new CompareResult(mode, scanned.leftRoot, scanned.rightRoot);
        result.leftDirectories.addAll(scanned.leftDirectories);
        result.rightDirectories.addAll(scanned.rightDirectories);
        for (CompareScanService.ScannedPair pair : scanned.pairs) {
            FileInfo left = pair.left == null ? null : new FileInfo(pair.left.path,
                    pair.left.relativePath, pair.left.size, pair.left.modifiedTime, pair.left.hash);
            FileInfo right = pair.right == null ? null : new FileInfo(pair.right.path,
                    pair.right.relativePath, pair.right.size, pair.right.modifiedTime, pair.right.hash);
            result.entries.add(CompareEntry.forPair(pair.relativePath, left, right));
        }
        result.excludedDirectoryCount = scanned.excludedDirectoryCount;
        result.excludedFileCount = scanned.excludedFileCount;
        result.recount();
        return result;
    }

    private void sync(final boolean leftToRight) {
        if (lastResult == null) {
            showError("请先执行文件对比。");
            return;
        }
        SyncPlan plan = createSyncPlan(leftToRight);
        SyncRequest request = SyncPreviewDialog.showDialog(this, plan, syncService);
        if (request == null) {
            return;
        }
        setBusy(true, "正在同步文件...");
        SyncExecutionResult result;
        try {
            result = SyncProgressDialog.execute(this, plan, request, syncService);
        } finally {
            setBusy(false, "同步操作结束");
        }
        if (result == null) {
            showError("同步任务未返回执行结果。");
            return;
        }
        appendSyncResult(result);
        SyncResultDialog.showDialog(this, result, syncService, new Runnable() {
            @Override
            public void run() {
                compare();
            }
        });
    }

    private SyncPlan createSyncPlan(boolean leftToRight) {
        SyncDirection direction = leftToRight
                ? SyncDirection.LEFT_TO_RIGHT : SyncDirection.RIGHT_TO_LEFT;
        List<SyncComparisonEntry> entries = new ArrayList<SyncComparisonEntry>();
        for (CompareEntry entry : lastResult.entries) {
            Path leftPath = entry.left == null ? null : entry.left.path;
            Path rightPath = entry.right == null ? null : entry.right.path;
            if (lastResult.mode == CompareMode.FILE) {
                leftPath = lastResult.leftRoot;
                rightPath = lastResult.rightRoot;
            } else {
                if (leftPath == null) {
                    leftPath = lastResult.leftRoot.resolve(entry.relativePath);
                }
                if (rightPath == null) {
                    rightPath = lastResult.rightRoot.resolve(entry.relativePath);
                }
            }
            entries.add(new SyncComparisonEntry(entry.relativePath,
                    leftPath, toFingerprint(entry.left),
                    rightPath, toFingerprint(entry.right)));
        }
        return new SyncPlanBuilder().build(lastResult.mode == CompareMode.DIRECTORY,
                direction, lastResult.leftRoot, lastResult.rightRoot, entries,
                lastResult.leftDirectories, lastResult.rightDirectories,
                lastResult.excludedDirectoryCount + lastResult.excludedFileCount);
    }

    private FileFingerprint toFingerprint(FileInfo file) {
        return file == null ? FileFingerprint.missing()
                : FileFingerprint.knownFile(file.size, file.modifiedTime, file.hash);
    }

    private void appendSyncResult(SyncExecutionResult result) {
        appendLog("同步事务 " + result.getPlan().getTransactionId()
                + "：已完成 " + result.count(SyncItemStatus.SUCCESS)
                + "，失败 " + result.count(SyncItemStatus.FAILED)
                + "，未执行 " + result.count(SyncItemStatus.NOT_EXECUTED)
                + "，已取消 " + result.count(SyncItemStatus.CANCELLED) + "。");
        for (SyncItemResult item : result.getItemResults()) {
            if (item.getStatus() != SyncItemStatus.SUCCESS) {
                appendLog(item.getStatus().getDisplayName() + "："
                        + item.getEntry().getRelativePath() + " - " + item.getMessage());
            }
        }
    }

    private void renderResult(CompareResult result) {
        treeRoot = buildComparisonTree(result);
        Set<String> availableDirectories = new LinkedHashSet<String>();
        collectDirectoryPaths(treeRoot, availableDirectories);
        expandedPaths.retainAll(availableDirectories);
        refreshVisibleRows(pendingSelectionPath);
        pendingSelectionPath = null;
        updateSummary(
                result.sameCount,
                result.differentCount,
                result.leftOnlyCount + result.rightOnlyCount);
    }

    private void beginResultPublication(final CompareTaskOutput output) {
        if (output.taskId != activeCompareTaskId) {
            return;
        }
        final Set<String> nextExpanded = new LinkedHashSet<String>(expandedPaths);
        Set<String> availableDirectories = new LinkedHashSet<String>();
        collectDirectoryPaths(output.treeRoot, availableDirectories);
        nextExpanded.retainAll(availableDirectories);
        final List<CompareNode> nextVisible = new ArrayList<CompareNode>();
        collectVisibleNodes(output.treeRoot, nextExpanded, nextVisible);
        final SideTableModel nextLeftModel = new SideTableModel();
        final SideTableModel nextRightModel = new SideTableModel();

        scanStageLabel.setText("正在更新界面");
        scanStatsLabel.setText("已准备 0 / " + nextVisible.size() + " 行");
        scanPathLabel.setText("上次结果保持显示，新结果完整后一次切换");
        scanPathLabel.setToolTipText(scanPathLabel.getText());
        scanProgressBar.setIndeterminate(false);
        scanProgressBar.setValue(0);
        final long publishStarted = System.nanoTime();
        final int[] nextRow = new int[]{0};
        activePublishTimer = new Timer(1, null);
        activePublishTimer.addActionListener(e -> {
            if (output.taskId != activeCompareTaskId || activeCancellation == null
                    || activeCancellation.isCancelled()) {
                activePublishTimer.stop();
                finishCancelledScan(output.taskId, "更新界面");
                return;
            }
            int end = Math.min(nextVisible.size(), nextRow[0] + 200);
            nextLeftModel.addNodes(nextVisible, nextRow[0], end, true);
            nextRightModel.addNodes(nextVisible, nextRow[0], end, false);
            nextRow[0] = end;
            int value = nextVisible.isEmpty() ? 1000
                    : (int) Math.min(1000L, end * 1000L / nextVisible.size());
            scanProgressBar.setValue(value);
            scanStatsLabel.setText("已准备 " + end + " / " + nextVisible.size() + " 行");
            if (end >= nextVisible.size()) {
                activePublishTimer.stop();
                long publishMillis = (System.nanoTime() - publishStarted) / 1000000L;
                output.metrics.publishMillis = publishMillis;
                output.metrics.totalMillis += publishMillis;
                commitPublishedResult(output, nextExpanded, nextVisible,
                        nextLeftModel, nextRightModel);
            }
        });
        activePublishTimer.start();
    }

    private void commitPublishedResult(CompareTaskOutput output,
                                       Set<String> nextExpanded,
                                       List<CompareNode> nextVisible,
                                       SideTableModel nextLeftModel,
                                       SideTableModel nextRightModel) {
        if (output.taskId != activeCompareTaskId) {
            return;
        }
        lastResult = output.result;
        lastScanMetrics = output.metrics;
        treeRoot = output.treeRoot;
        expandedPaths.clear();
        expandedPaths.addAll(nextExpanded);
        visibleNodes.clear();
        visibleNodes.addAll(nextVisible);
        leftTableModel = nextLeftModel;
        rightTableModel = nextRightModel;
        leftTable.setModel(leftTableModel);
        rightTable.setModel(rightTableModel);
        configureResultTable(leftTable);
        configureResultTable(rightTable);
        if (pendingSelectionPath != null) {
            selectVisiblePath(pendingSelectionPath);
        }
        pendingSelectionPath = null;
        updateSummary(lastResult.sameCount, lastResult.differentCount,
                lastResult.leftOnlyCount + lastResult.rightOnlyCount);
        appendLog("对比完成。相同：" + lastResult.sameCount
                + "，不同：" + lastResult.differentCount
                + "，仅左侧：" + lastResult.leftOnlyCount
                + "，仅右侧：" + lastResult.rightOnlyCount + "。");
        appendLog("扫描耗时：发现 " + formatDuration(output.metrics.discoveryMillis)
                + "，Hash " + formatDuration(output.metrics.hashMillis)
                + "，构建 " + formatDuration(output.metrics.buildMillis)
                + "，界面 " + formatDuration(output.metrics.publishMillis) + "。");
        if (lastResult.excludedDirectoryCount > 0 || lastResult.excludedFileCount > 0) {
            appendLog("已排除目录：" + lastResult.excludedDirectoryCount
                    + " 个，文件：" + lastResult.excludedFileCount + " 个。");
        }
        finishScanControls("对比完成，共 " + lastResult.entries.size() + " 个文件"
                + excludedSummary(lastResult));
        scanDetailsButton.setVisible(true);
        recordSuccessfulHistory(lastResult);
    }

    private void recordSuccessfulHistory(CompareResult result) {
        CompareHistoryMode mode = result.mode == CompareMode.FILE
                ? CompareHistoryMode.FILE : CompareHistoryMode.DIRECTORY;
        HistoryFilterSnapshot filter = mode == CompareHistoryMode.DIRECTORY
                ? HistoryFilterSnapshot.fromRules(filterSettings, filterBasePresetId)
                : HistoryFilterSnapshot.empty();
        HistoryResultSummary summary = new HistoryResultSummary(result.sameCount,
                result.differentCount, result.leftOnlyCount, result.rightOnlyCount,
                result.excludedDirectoryCount, result.excludedFileCount);
        historyService.recordSuccessAsync(mode, result.leftRoot, result.rightRoot,
                filter, summary, new CompareHistoryService.SaveCallback() {
                    @Override public void completed(final List<CompareHistoryEntry> entries,
                                                    final String errorMessage) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override public void run() {
                                if (errorMessage == null) {
                                    refreshHistoryUi(entries);
                                } else {
                                    appendLog("对比已完成，但历史未保存：" + errorMessage);
                                    statusLabel.setText("对比完成，历史未保存到本地");
                                }
                            }
                        });
                    }
                });
    }

    private void selectVisiblePath(String selectedPath) {
        for (int i = 0; i < visibleNodes.size(); i++) {
            if (selectedPath.equals(visibleNodes.get(i).relativePath)) {
                leftTable.setRowSelectionInterval(i, i);
                rightTable.setRowSelectionInterval(i, i);
                leftTable.scrollRectToVisible(leftTable.getCellRect(i, 1, true));
                rightTable.scrollRectToVisible(rightTable.getCellRect(i, 1, true));
                return;
            }
        }
    }

    private void cancelActiveComparison() {
        if (!busy || activeCancellation == null) {
            return;
        }
        cancelScanButton.setEnabled(false);
        cancelScanButton.setText("正在取消");
        scanStageLabel.setText("正在取消扫描");
        activeCancellation.cancel();
    }

    private void finishCancelledScan(long taskId, String stage) {
        if (taskId != activeCompareTaskId) {
            return;
        }
        appendLog("扫描已取消，停止于" + stage + "阶段；上一份有效结果未改变。");
        finishScanControls(lastResult == null ? "扫描已取消" : "扫描已取消，上次结果已保留");
        showTerminalScanState("扫描已取消", "停止于" + stage + "阶段 · "
                + progressSummary(lastTaskProgress), "上一份有效结果未发生变化");
    }

    private void finishFailedScan(long taskId, Throwable error) {
        if (taskId != activeCompareTaskId) {
            return;
        }
        String message = rootMessage(error);
        appendLog("扫描失败：" + message);
        finishScanControls(lastResult == null ? "扫描失败" : "扫描失败，上次结果已保留");
        showTerminalScanState("扫描失败", progressSummary(lastTaskProgress),
                middleEllipsis(message, 110));
        JOptionPane.showMessageDialog(this, message, "扫描失败", JOptionPane.ERROR_MESSAGE);
    }

    private void finishScanControls(String message) {
        activeCompareWorker = null;
        activeCancellation = null;
        activePublishTimer = null;
        activeCompareTaskId = 0L;
        setScanBusy(false, message);
    }

    private void showTerminalScanState(String stage, String stats, String detail) {
        scanStageLabel.setText(stage);
        scanStatsLabel.setText(stats);
        scanPathLabel.setText(detail);
        scanPathLabel.setToolTipText(detail);
        scanProgressBar.setIndeterminate(false);
        cancelScanButton.setText("重新开始");
        cancelScanButton.setEnabled(true);
        scanTaskPanel.setVisible(true);
        revalidate();
        repaint();
    }

    private void updateScanProgress(CompareScanService.ScanProgress progress) {
        if (progress.taskId != activeCompareTaskId) {
            return;
        }
        lastTaskProgress = progress;
        scanStageLabel.setText("正在" + progress.stage.displayName);
        scanProgressBar.setIndeterminate(progress.indeterminate);
        String stats;
        int value = 0;
        if (progress.stage == CompareScanService.ScanStage.DISCOVERING) {
            stats = "左侧 " + progress.discoveredLeft + " · 右侧 "
                    + progress.discoveredRight + " · " + formatBytes(progress.discoveredBytes)
                    + " · 已排除 "
                    + (progress.excludedDirectories + progress.excludedFiles);
        } else if (progress.stage == CompareScanService.ScanStage.HASHING) {
            stats = progress.completedFiles + " / " + progress.totalFiles + " 个文件 · "
                    + formatBytes(progress.completedBytes) + " / "
                    + formatBytes(progress.totalBytes) + " · Hash 并发 " + progress.workerCount;
            value = ratio(progress.completedBytes, progress.totalBytes);
        } else if (progress.stage == CompareScanService.ScanStage.BUILDING) {
            stats = "已构建 " + progress.builtItems + " / " + progress.totalBuildItems + " 项";
            value = ratio(progress.builtItems, progress.totalBuildItems);
        } else {
            stats = progress.stage.displayName;
        }
        scanStatsLabel.setText(stats + " · 已用时 " + formatDuration(progress.elapsedMillis));
        scanProgressBar.setValue(value);
        String path = progress.currentPath == null ? "" : progress.currentPath;
        scanPathLabel.setText(middleEllipsis(path, 110));
        scanPathLabel.setToolTipText(path);
    }

    private String progressSummary(CompareScanService.ScanProgress progress) {
        if (progress == null) {
            return "任务尚未产生进度";
        }
        if (progress.stage == CompareScanService.ScanStage.DISCOVERING) {
            return "左侧 " + progress.discoveredLeft + " · 右侧 "
                    + progress.discoveredRight + " · " + formatBytes(progress.discoveredBytes)
                    + " · 已用时 " + formatDuration(progress.elapsedMillis);
        }
        return "已处理 " + progress.completedFiles + " / " + progress.totalFiles
                + " 个文件 · " + formatBytes(progress.completedBytes) + " / "
                + formatBytes(progress.totalBytes) + " · 已用时 "
                + formatDuration(progress.elapsedMillis);
    }

    private CompareNode buildComparisonTree(CompareResult result) {
        CompareNode root = CompareNode.directory("", "", -1);
        root.leftExists = true;
        root.rightExists = true;
        Map<String, CompareNode> directories = new LinkedHashMap<String, CompareNode>();
        directories.put("", root);

        Set<String> directoryPaths = new LinkedHashSet<String>();
        directoryPaths.addAll(result.leftDirectories);
        directoryPaths.addAll(result.rightDirectories);
        for (CompareEntry entry : result.entries) {
            String parent = parentPath(entry.relativePath);
            while (!parent.isEmpty()) {
                directoryPaths.add(parent);
                parent = parentPath(parent);
            }
        }

        List<String> sortedDirectories = new ArrayList<String>(directoryPaths);
        Collections.sort(sortedDirectories, new Comparator<String>() {
            @Override
            public int compare(String first, String second) {
                int depthCompare = Integer.compare(pathDepth(first), pathDepth(second));
                return depthCompare != 0 ? depthCompare : first.compareToIgnoreCase(second);
            }
        });
        for (String path : sortedDirectories) {
            CompareNode node = CompareNode.directory(fileName(path), path, pathDepth(path));
            node.leftExists = result.leftDirectories.contains(path);
            node.rightExists = result.rightDirectories.contains(path);
            CompareNode parent = directories.get(parentPath(path));
            if (parent == null) {
                parent = root;
            }
            parent.children.add(node);
            directories.put(path, node);
        }

        for (CompareEntry entry : result.entries) {
            String parentPath = parentPath(entry.relativePath);
            CompareNode parent = directories.get(parentPath);
            if (parent == null) {
                parent = root;
            }
            String name = result.mode == CompareMode.FILE
                    ? selectedFileName(entry)
                    : fileName(entry.relativePath);
            parent.children.add(CompareNode.file(name, entry.relativePath, pathDepth(entry.relativePath), entry));
        }
        finalizeTree(root);
        return root;
    }

    private String selectedFileName(CompareEntry entry) {
        Path path = entry.left != null ? entry.left.path : entry.right.path;
        return path.getFileName() == null ? entry.relativePath : path.getFileName().toString();
    }

    private void finalizeTree(CompareNode node) {
        if (!node.directory) {
            node.status = node.entry.status;
            node.leftFileCount = node.entry.left == null ? 0 : 1;
            node.rightFileCount = node.entry.right == null ? 0 : 1;
            return;
        }

        Collections.sort(node.children, new Comparator<CompareNode>() {
            @Override
            public int compare(CompareNode first, CompareNode second) {
                if (first.directory != second.directory) {
                    return first.directory ? -1 : 1;
                }
                return first.name.compareToIgnoreCase(second.name);
            }
        });
        boolean allSame = true;
        for (CompareNode child : node.children) {
            finalizeTree(child);
            node.leftFileCount += child.leftFileCount;
            node.rightFileCount += child.rightFileCount;
            if (child.status != EntryStatus.SAME) {
                allSame = false;
            }
        }
        if (!node.leftExists && node.rightExists) {
            node.status = EntryStatus.RIGHT_ONLY;
        } else if (node.leftExists && !node.rightExists) {
            node.status = EntryStatus.LEFT_ONLY;
        } else {
            node.status = allSame ? EntryStatus.SAME : EntryStatus.DIFFERENT;
        }
    }

    private void collectDirectoryPaths(CompareNode node, Set<String> paths) {
        for (CompareNode child : node.children) {
            if (child.directory) {
                paths.add(child.relativePath);
                collectDirectoryPaths(child, paths);
            }
        }
    }

    private void refreshVisibleRows(String selectedPath) {
        visibleNodes.clear();
        if (treeRoot != null) {
            appendVisibleNodes(treeRoot);
        }
        leftTableModel.setRowCount(0);
        rightTableModel.setRowCount(0);
        for (CompareNode node : visibleNodes) {
            leftTableModel.addNode(node, true);
            rightTableModel.addNode(node, false);
        }
        if (selectedPath != null) {
            for (int i = 0; i < visibleNodes.size(); i++) {
                if (selectedPath.equals(visibleNodes.get(i).relativePath)) {
                    leftTable.setRowSelectionInterval(i, i);
                    rightTable.setRowSelectionInterval(i, i);
                    leftTable.scrollRectToVisible(leftTable.getCellRect(i, 1, true));
                    rightTable.scrollRectToVisible(rightTable.getCellRect(i, 1, true));
                    break;
                }
            }
        }
    }

    private String selectedNodePath() {
        int row = leftTable.getSelectedRow();
        return row >= 0 && row < visibleNodes.size() ? visibleNodes.get(row).relativePath : null;
    }

    private void appendVisibleNodes(CompareNode parent) {
        for (CompareNode child : parent.children) {
            visibleNodes.add(child);
            if (child.directory && expandedPaths.contains(child.relativePath)) {
                appendVisibleNodes(child);
            }
        }
    }

    private void collectVisibleNodes(CompareNode parent, Set<String> expanded,
                                     List<CompareNode> target) {
        for (CompareNode child : parent.children) {
            target.add(child);
            if (child.directory && expanded.contains(child.relativePath)) {
                collectVisibleNodes(child, expanded, target);
            }
        }
    }

    private void handleResultClick(JTable table, MouseEvent event) {
        int row = table.rowAtPoint(event.getPoint());
        int column = table.columnAtPoint(event.getPoint());
        if (row < 0 || row >= visibleNodes.size()) {
            return;
        }
        CompareNode node = visibleNodes.get(row);
        if (node.directory && column == 1 && event.getClickCount() == 1) {
            toggleDirectory(node);
        } else if (!busy && !node.directory && event.getClickCount() == 2) {
            openSelectedEntryDiff(table);
        }
    }

    private void toggleDirectory(CompareNode node) {
        if (expandedPaths.contains(node.relativePath)) {
            expandedPaths.remove(node.relativePath);
        } else {
            expandedPaths.add(node.relativePath);
        }
        refreshVisibleRows(node.relativePath);
    }

    private void expandAllDirectories() {
        if (treeRoot == null) {
            return;
        }
        expandedPaths.clear();
        collectDirectoryPaths(treeRoot, expandedPaths);
        refreshVisibleRows(null);
        statusLabel.setText("已展开全部目录");
    }

    private void collapseAllDirectories() {
        expandedPaths.clear();
        refreshVisibleRows(null);
        statusLabel.setText("已折叠全部目录");
    }

    private void reloadComparison() {
        if (!workspaceActive || busy
                || parsePath(leftField.getText()) == null || parsePath(rightField.getText()) == null) {
            return;
        }
        appendLog("重新扫描左右路径。");
        compare();
    }

    private String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private int pathDepth(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private void openSelectedEntryDiff(JTable table) {
        if (lastResult == null) {
            return;
        }
        int row = table.getSelectedRow();
        if (row < 0 || row >= visibleNodes.size()) {
            return;
        }
        CompareNode node = visibleNodes.get(row);
        if (node.directory || node.entry == null) {
            return;
        }
        CompareEntry entry = node.entry;
        if (entry.left == null && entry.right == null) {
            return;
        }
        try {
            openCompareEditor(entry);
        } catch (IOException ex) {
            showError("无法打开文件：" + ex.getMessage());
        }
    }

    private void openCompareEditor(final CompareEntry entry) throws IOException {
        Path leftPath = editorPath(entry, true);
        Path rightPath = editorPath(entry, false);
        DiffEditorLauncher.open(this, entry.relativePath, leftPath, rightPath,
                () -> compare(), preferencesService);
    }

    private void openReleaseNotes() {
        Path notes = AppInfo.distributionFile("CHANGELOG.md");
        if (!Files.exists(notes)) {
            JOptionPane.showMessageDialog(this, "随包更新说明不存在：CHANGELOG.md",
                    "更新说明", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            AppInfo.openInDesktop(notes);
        } catch (IOException ex) {
            showError("无法打开更新说明：" + ex.getMessage());
        }
    }

    private void openLegacyCompareEditor(final CompareEntry entry) throws IOException {
        if ((entry.left != null && !isLikelyTextFile(entry.left.path))
                || (entry.right != null && !isLikelyTextFile(entry.right.path))) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "其中一个文件可能是二进制文件，以 UTF-8 文本方式编辑可能损坏文件。\n仍然打开吗？",
                    "打开文件",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }

        final Path leftPath = editorPath(entry, true);
        final Path rightPath = editorPath(entry, false);
        final JFrame editor = new JFrame("文件内容对比 - " + entry.relativePath);
        final JTextArea leftArea = createEditorArea(entry.left == null ? "" : readText(leftPath));
        final JTextArea rightArea = createEditorArea(entry.right == null ? "" : readText(rightPath));
        final JLabel leftModifiedLabel = createModifiedLabel();
        final JLabel rightModifiedLabel = createModifiedLabel();
        final boolean[] modified = new boolean[]{false, false};

        final JScrollPane leftScroll = createEditorScroll(leftArea);
        final JScrollPane rightScroll = createEditorScroll(rightArea);
        final JCheckBox linkedScroll = new JCheckBox("联动滚动", true);
        linkedScroll.setOpaque(false);
        linkedScroll.setFont(UI_FONT);
        linkedScroll.setForeground(TEXT_COLOR);
        linkedScroll.setFocusPainted(false);
        syncScrollPanes(leftScroll, rightScroll, linkedScroll);
        highlightDifferentLines(leftArea, rightArea);
        attachHighlightRefresh(leftArea, rightArea);
        attachModifiedTracking(leftArea, leftModifiedLabel, modified, 0);
        attachModifiedTracking(rightArea, rightModifiedLabel, modified, 1);

        JButton copyLeftToRightButton = createTransferButton("→", "复制左侧选中内容到右侧");
        JButton copyRightToLeftButton = createTransferButton("←", "复制右侧选中内容到左侧");
        JButton copyAllLeftToRightButton = createTransferButton(">>", "用左侧全部内容覆盖右侧");
        JButton copyAllRightToLeftButton = createTransferButton("<<", "用右侧全部内容覆盖左侧");

        copyLeftToRightButton.addActionListener(e -> {
            copySelectedText(leftArea, rightArea);
            highlightDifferentLines(leftArea, rightArea);
        });
        copyRightToLeftButton.addActionListener(e -> {
            copySelectedText(rightArea, leftArea);
            highlightDifferentLines(leftArea, rightArea);
        });
        copyAllLeftToRightButton.addActionListener(e -> {
            rightArea.setText(leftArea.getText());
            rightArea.setCaretPosition(0);
            highlightDifferentLines(leftArea, rightArea);
        });
        copyAllRightToLeftButton.addActionListener(e -> {
            leftArea.setText(rightArea.getText());
            leftArea.setCaretPosition(0);
            highlightDifferentLines(leftArea, rightArea);
        });

        JPanel leftEditor = createEditorSide("左侧文件", leftPath, entry.left == null,
                leftModifiedLabel, leftScroll);
        JPanel rightEditor = createEditorSide("右侧文件", rightPath, entry.right == null,
                rightModifiedLabel, rightScroll);
        JPanel transferRail = createTransferRail(copyLeftToRightButton, copyRightToLeftButton,
                copyAllLeftToRightButton, copyAllRightToLeftButton);

        JPanel editors = new JPanel(new GridBagLayout());
        editors.setBackground(SURFACE);
        editors.setBorder(new EmptyBorder(16, 20, 16, 20));
        GridBagConstraints editorConstraints = new GridBagConstraints();
        editorConstraints.gridy = 0;
        editorConstraints.fill = GridBagConstraints.BOTH;
        editorConstraints.weighty = 1;
        editorConstraints.gridx = 0;
        editorConstraints.weightx = 1;
        editors.add(leftEditor, editorConstraints);
        editorConstraints.gridx = 1;
        editorConstraints.weightx = 0;
        editorConstraints.insets = new Insets(0, 10, 0, 10);
        editors.add(transferRail, editorConstraints);
        editorConstraints.gridx = 2;
        editorConstraints.weightx = 1;
        editorConstraints.insets = new Insets(0, 0, 0, 0);
        editors.add(rightEditor, editorConstraints);

        JButton compareAgainButton = new JButton("重新标记差异");
        styleSecondaryButton(compareAgainButton);
        compareAgainButton.addActionListener(e -> highlightDifferentLines(leftArea, rightArea));

        JPanel editorHeader = createEditorHeader(entry.relativePath, linkedScroll, compareAgainButton);
        JPanel editorFooter = createEditorFooter(editor, leftPath, rightPath, leftArea, rightArea,
                leftModifiedLabel, rightModifiedLabel, modified);

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(SURFACE);
        shell.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        shell.add(editorHeader, BorderLayout.NORTH);
        shell.add(editors, BorderLayout.CENTER);
        shell.add(editorFooter, BorderLayout.SOUTH);

        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(APP_BACKGROUND);
        page.setBorder(new EmptyBorder(12, 12, 12, 12));
        page.add(shell, BorderLayout.CENTER);
        editor.setContentPane(page);
        editor.setSize(1280, 760);
        editor.setMinimumSize(new Dimension(960, 600));
        editor.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        editor.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscardChanges(editor, modified)) {
                    editor.dispose();
                }
            }
        });
        editor.setLocationRelativeTo(this);
        editor.setVisible(true);
    }

    private JTextArea createEditorArea(String content) {
        JTextArea area = new JTextArea();
        area.setText(content);
        area.setCaretPosition(0);
        area.setFont(CODE_FONT);
        area.setForeground(TEXT_COLOR);
        area.setBackground(SURFACE);
        area.setSelectionColor(new Color(191, 219, 254));
        area.setSelectedTextColor(TEXT_COLOR);
        area.setCaretColor(PRIMARY_COLOR);
        area.setMargin(new Insets(8, 12, 8, 12));
        area.setTabSize(4);
        return area;
    }

    private JScrollPane createEditorScroll(JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(SURFACE);
        scroll.setRowHeaderView(new LineNumberView(area));
        return scroll;
    }

    private JLabel createModifiedLabel() {
        JLabel label = new JLabel("●  已修改");
        label.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        label.setForeground(DIFFERENT_COLOR);
        label.setVisible(false);
        return label;
    }

    private void attachModifiedTracking(JTextArea area, final JLabel label,
                                        final boolean[] modified, final int index) {
        area.getDocument().addDocumentListener(new DocumentListener() {
            private void markModified() {
                modified[index] = true;
                label.setVisible(true);
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                markModified();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markModified();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markModified();
            }
        });
    }

    private JButton createTransferButton(String text, String tooltip) {
        JButton button = new JButton(text);
        styleSecondaryButton(button);
        button.setFont(new Font("SansSerif", Font.BOLD, 17));
        button.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(new Color(184, 196, 209)),
                new EmptyBorder(5, 8, 5, 8)));
        button.setPreferredSize(new Dimension(64, 40));
        button.setMinimumSize(new Dimension(64, 40));
        button.setToolTipText(tooltip);
        return button;
    }

    private JPanel createEditorSide(String titleText, Path path, boolean missing,
                                    JLabel modifiedLabel, JScrollPane scroll) {
        JLabel title = new JLabel(titleText);
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 16));
        title.setForeground(TEXT_COLOR);
        JLabel missingLabel = new JLabel("●  文件缺失");
        missingLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        missingLabel.setForeground(DIFFERENT_COLOR);
        missingLabel.setVisible(missing);

        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        titleLine.setOpaque(false);
        titleLine.add(title);
        titleLine.add(missingLabel);
        titleLine.add(modifiedLabel);

        JLabel pathLabel = new JLabel(path.toString());
        pathLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        pathLabel.setForeground(MUTED_COLOR);
        pathLabel.setToolTipText(path.toString());

        JPanel heading = new JPanel(new BorderLayout(0, 5));
        heading.setBackground(SURFACE);
        heading.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
                new EmptyBorder(10, 14, 10, 14)));
        heading.add(titleLine, BorderLayout.NORTH);
        heading.add(pathLabel, BorderLayout.SOUTH);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(SURFACE);
        side.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        side.add(heading, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    private JPanel createTransferRail(JButton selectedToRight, JButton selectedToLeft,
                                      JButton allToRight, JButton allToLeft) {
        JLabel label = new JLabel("复制", SwingConstants.CENTER);
        label.setFont(UI_FONT_BOLD);
        label.setForeground(MUTED_COLOR);

        JPanel rail = new JPanel(new GridBagLayout());
        rail.setBackground(HEADER_COLOR);
        rail.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        rail.setPreferredSize(new Dimension(86, 100));
        rail.setMinimumSize(new Dimension(86, 100));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weighty = 0;
        gbc.insets = new Insets(12, 8, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        rail.add(label, gbc);

        gbc.gridy = 1;
        gbc.weighty = 0.5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.SOUTH;
        rail.add(selectedToRight, gbc);
        gbc.gridy = 2;
        gbc.weighty = 0;
        gbc.insets = new Insets(5, 8, 5, 8);
        rail.add(selectedToLeft, gbc);
        gbc.gridy = 3;
        gbc.insets = new Insets(18, 8, 5, 8);
        rail.add(allToRight, gbc);
        gbc.gridy = 4;
        gbc.insets = new Insets(5, 8, 5, 8);
        rail.add(allToLeft, gbc);
        gbc.gridy = 5;
        gbc.weighty = 0.5;
        gbc.anchor = GridBagConstraints.NORTH;
        rail.add(new JLabel(), gbc);
        return rail;
    }

    private JPanel createEditorHeader(String relativePath, JCheckBox linkedScroll, JButton compareAgainButton) {
        JLabel mark = new JLabel("⇄", SwingConstants.CENTER);
        mark.setOpaque(true);
        mark.setBackground(PRIMARY_COLOR);
        mark.setForeground(SURFACE);
        mark.setFont(new Font("SansSerif", Font.BOLD, 20));
        mark.setPreferredSize(new Dimension(34, 34));

        JLabel title = new JLabel("文件内容对比");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        title.setForeground(TEXT_COLOR);
        JLabel subtitle = new JLabel(relativePath);
        subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        subtitle.setForeground(MUTED_COLOR);
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        titlePanel.add(title, BorderLayout.CENTER);
        titlePanel.add(subtitle, BorderLayout.SOUTH);

        JPanel identity = new JPanel(new BorderLayout(12, 0));
        identity.setOpaque(false);
        identity.add(mark, BorderLayout.WEST);
        identity.add(titlePanel, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        controls.setOpaque(false);
        controls.add(linkedScroll);
        controls.add(compareAgainButton);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SURFACE);
        header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
                new EmptyBorder(12, 20, 12, 20)));
        header.add(identity, BorderLayout.WEST);
        header.add(controls, BorderLayout.EAST);
        return header;
    }

    private JPanel createEditorFooter(final JFrame editor, final Path leftPath, final Path rightPath,
                                      final JTextArea leftArea, final JTextArea rightArea,
                                      final JLabel leftModifiedLabel, final JLabel rightModifiedLabel,
                                      final boolean[] modified) {
        JLabel note = new JLabel("红色行表示内容不同，修改后请单独或全部保存");
        note.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        note.setForeground(MUTED_COLOR);

        JButton reloadButton = new JButton("重新加载");
        JButton saveLeftButton = new JButton("保存左侧");
        JButton saveRightButton = new JButton("保存右侧");
        JButton saveAllButton = new JButton("全部保存");
        styleTextButton(reloadButton);
        styleSecondaryButton(saveLeftButton);
        styleSecondaryButton(saveRightButton);
        stylePrimaryButton(saveAllButton);

        reloadButton.addActionListener(e -> {
            if (!confirmDiscardChanges(editor, modified)) {
                return;
            }
            try {
                leftArea.setText(Files.exists(leftPath) ? readText(leftPath) : "");
                rightArea.setText(Files.exists(rightPath) ? readText(rightPath) : "");
                leftArea.setCaretPosition(0);
                rightArea.setCaretPosition(0);
                resetModifiedState(modified, leftModifiedLabel, rightModifiedLabel);
                highlightDifferentLines(leftArea, rightArea);
            } catch (IOException ex) {
                showError("重新加载文件失败：" + ex.getMessage());
            }
        });
        saveLeftButton.addActionListener(e -> {
            if (saveEditorSide(leftPath, leftArea, leftModifiedLabel, modified, 0)) {
                compare();
            }
        });
        saveRightButton.addActionListener(e -> {
            if (saveEditorSide(rightPath, rightArea, rightModifiedLabel, modified, 1)) {
                compare();
            }
        });
        saveAllButton.addActionListener(e -> {
            boolean leftSaved = saveEditorSide(leftPath, leftArea, leftModifiedLabel, modified, 0);
            boolean rightSaved = leftSaved
                    && saveEditorSide(rightPath, rightArea, rightModifiedLabel, modified, 1);
            if (rightSaved) {
                compare();
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        actions.add(reloadButton);
        actions.add(saveLeftButton);
        actions.add(saveRightButton);
        actions.add(saveAllButton);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SURFACE);
        footer.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, BORDER_COLOR),
                new EmptyBorder(10, 20, 10, 20)));
        footer.add(note, BorderLayout.WEST);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private boolean saveEditorSide(Path path, JTextArea area, JLabel modifiedLabel,
                                   boolean[] modified, int index) {
        try {
            writeText(path, area.getText());
            modified[index] = false;
            modifiedLabel.setVisible(false);
            appendLog("已保存：" + path);
            return true;
        } catch (IOException ex) {
            showError("保存文件失败：" + ex.getMessage());
            return false;
        }
    }

    private void resetModifiedState(boolean[] modified, JLabel leftModifiedLabel, JLabel rightModifiedLabel) {
        modified[0] = false;
        modified[1] = false;
        leftModifiedLabel.setVisible(false);
        rightModifiedLabel.setVisible(false);
    }

    private boolean confirmDiscardChanges(Component parent, boolean[] modified) {
        if (!modified[0] && !modified[1]) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(
                parent,
                "当前还有未保存的修改，确定放弃吗？",
                "未保存的修改",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.OK_OPTION;
    }

    private String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void writeText(Path file, String text) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private Path editorPath(CompareEntry entry, boolean leftSide) {
        FileInfo existing = leftSide ? entry.left : entry.right;
        if (existing != null) {
            return existing.path;
        }
        if (lastResult.mode == CompareMode.FILE) {
            return leftSide ? lastResult.leftRoot : lastResult.rightRoot;
        }
        Path root = leftSide ? lastResult.leftRoot : lastResult.rightRoot;
        return root.resolve(entry.relativePath);
    }

    private void copySelectedText(JTextArea source, JTextArea target) {
        String selectedText = source.getSelectedText();
        if (selectedText == null || selectedText.length() == 0) {
            JOptionPane.showMessageDialog(
                    this,
                    "请先在来源一侧选择需要复制的文本。",
                    "复制选中内容",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        target.replaceSelection(selectedText);
    }

    private void attachHighlightRefresh(final JTextArea leftArea, final JTextArea rightArea) {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }

            private void refresh() {
                SwingUtilities.invokeLater(() -> highlightDifferentLines(leftArea, rightArea));
            }
        };
        leftArea.getDocument().addDocumentListener(listener);
        rightArea.getDocument().addDocumentListener(listener);
    }

    private void highlightDifferentLines(JTextArea leftArea, JTextArea rightArea) {
        try {
            leftArea.getHighlighter().removeAllHighlights();
            rightArea.getHighlighter().removeAllHighlights();
            Highlighter.HighlightPainter differentPainter =
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 220, 220));
            String[] leftLines = splitLines(leftArea.getText());
            String[] rightLines = splitLines(rightArea.getText());
            int maxLines = Math.max(leftLines.length, rightLines.length);
            for (int i = 0; i < maxLines; i++) {
                String leftLine = i < leftLines.length ? leftLines[i] : null;
                String rightLine = i < rightLines.length ? rightLines[i] : null;
                if (leftLine == null || rightLine == null || !leftLine.equals(rightLine)) {
                    highlightLine(leftArea, i, differentPainter);
                    highlightLine(rightArea, i, differentPainter);
                }
            }
        } catch (BadLocationException ex) {
            appendLog("无法标记差异：" + ex.getMessage());
        }
    }

    private String[] splitLines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private void highlightLine(JTextArea area, int line, Highlighter.HighlightPainter painter) throws BadLocationException {
        if (line >= area.getLineCount()) {
            return;
        }
        int start = area.getLineStartOffset(line);
        int end = area.getLineEndOffset(line);
        area.getHighlighter().addHighlight(start, end, painter);
    }

    private boolean isLikelyTextFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private String validateCompareInput(Path left, Path right, CompareMode mode) {
        if (mode == CompareMode.FILE) {
            if (!Files.isRegularFile(left)) {
                return "左侧路径不是有效文件：" + left;
            }
            if (!Files.isRegularFile(right)) {
                return "右侧路径不是有效文件：" + right;
            }
            return null;
        }
        if (!Files.isDirectory(left)) {
            return "左侧路径不是有效目录：" + left;
        }
        if (!Files.isDirectory(right)) {
            return "右侧路径不是有效目录：" + right;
        }
        return null;
    }

    private Path parsePath(String text) {
        String value = text == null ? "" : text.trim();
        return value.isEmpty() ? null : Paths.get(value);
    }

    private void clearResults() {
        if (activeCancellation != null) {
            activeCancellation.cancel();
        }
        if (activePublishTimer != null) {
            activePublishTimer.stop();
        }
        lastResult = null;
        lastScanMetrics = null;
        treeRoot = null;
        visibleNodes.clear();
        expandedPaths.clear();
        leftTableModel.setRowCount(0);
        rightTableModel.setRowCount(0);
        logArea.setText("");
        updateSummary(0, 0, 0);
        statusLabel.setText("请选择左右路径开始对比");
        syncLeftToRightButton.setEnabled(false);
        syncRightToLeftButton.setEnabled(false);
        refreshButton.setEnabled(false);
        scanDetailsButton.setVisible(false);
        scanTaskPanel.setVisible(false);
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        syncLeftToRightButton.setEnabled(!busy && lastResult != null);
        syncRightToLeftButton.setEnabled(!busy && lastResult != null);
        browseLeftButton.setEnabled(!busy);
        browseRightButton.setEnabled(!busy);
        leftField.setEnabled(!busy);
        rightField.setEnabled(!busy);
        fileModeButton.setEnabled(!busy);
        directoryModeButton.setEnabled(!busy);
        filterButton.setEnabled(!busy && !fileModeButton.isSelected());
        filterMenuItem.setEnabled(!busy && !fileModeButton.isSelected());
        refreshButton.setEnabled(!busy && lastResult != null);
        progressBar.setIndeterminate(busy);
        progressBar.setVisible(busy);
        statusLabel.setText(message);
    }

    private void setScanBusy(boolean busy, String message) {
        this.busy = busy;
        syncLeftToRightButton.setEnabled(!busy && lastResult != null);
        syncRightToLeftButton.setEnabled(!busy && lastResult != null);
        browseLeftButton.setEnabled(!busy);
        browseRightButton.setEnabled(!busy);
        leftField.setEnabled(!busy);
        rightField.setEnabled(!busy);
        fileModeButton.setEnabled(!busy);
        directoryModeButton.setEnabled(!busy);
        filterButton.setEnabled(!busy && !fileModeButton.isSelected());
        filterMenuItem.setEnabled(!busy && !fileModeButton.isSelected());
        refreshButton.setEnabled(!busy && lastResult != null);
        progressBar.setIndeterminate(busy);
        progressBar.setVisible(false);
        scanTaskPanel.setVisible(busy);
        cancelScanButton.setEnabled(busy);
        cancelScanButton.setText("取消");
        statusLabel.setText(message);
        if (!busy) {
            scanTaskPanel.setVisible(false);
        }
        revalidate();
        repaint();
    }

    private void updateSummary(int same, int different, int missing) {
        sameSummaryLabel.setText("●  相同 " + same);
        differentSummaryLabel.setText("●  不同 " + different);
        missingSummaryLabel.setText("●  缺失 " + missing);
    }

    private String excludedSummary(CompareResult result) {
        int total = result.excludedDirectoryCount + result.excludedFileCount;
        return total == 0 ? "" : "，已排除 " + total + " 项";
    }

    private void appendLog(String text) {
        logArea.append(text + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showError(String message) {
        appendLog("错误：" + message);
        JOptionPane.showMessageDialog(this, message, "操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private void showScanDetails() {
        if (lastScanMetrics == null || lastResult == null) {
            JOptionPane.showMessageDialog(this, "当前还没有可查看的扫描详情。",
                    "本次扫描详情", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        CompareScanService.ScanMetrics metrics = lastScanMetrics;
        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(SURFACE);
        content.setBorder(new EmptyBorder(8, 8, 8, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 8, 5, 18);
        addScanDetail(content, gbc, "文件数量", String.valueOf(metrics.totalFiles));
        int leftFiles = 0;
        int rightFiles = 0;
        for (CompareEntry entry : lastResult.entries) {
            leftFiles += entry.left == null ? 0 : 1;
            rightFiles += entry.right == null ? 0 : 1;
        }
        addScanDetail(content, gbc, "左侧文件", String.valueOf(leftFiles));
        addScanDetail(content, gbc, "右侧文件", String.valueOf(rightFiles));
        addScanDetail(content, gbc, "总字节", formatBytes(metrics.totalBytes));
        addScanDetail(content, gbc, "Hash 并发", String.valueOf(metrics.workerCount));
        addScanDetail(content, gbc, "Hash 重试", String.valueOf(metrics.hashRetries));
        addScanDetail(content, gbc, "发现文件", formatDuration(metrics.discoveryMillis));
        addScanDetail(content, gbc, "计算 Hash", formatDuration(metrics.hashMillis));
        addScanDetail(content, gbc, "构建结果", formatDuration(metrics.buildMillis));
        addScanDetail(content, gbc, "更新界面", formatDuration(metrics.publishMillis));
        addScanDetail(content, gbc, "总耗时", formatDuration(metrics.totalMillis));
        addScanDetail(content, gbc, "平均吞吐",
                String.format(Locale.ROOT, "%.1f MB/s", metrics.throughputMegabytesPerSecond()));
        addScanDetail(content, gbc, "结果", "相同 " + lastResult.sameCount
                + " · 不同 " + lastResult.differentCount + " · 仅左侧 "
                + lastResult.leftOnlyCount + " · 仅右侧 " + lastResult.rightOnlyCount);
        addScanDetail(content, gbc, "已排除", (lastResult.excludedDirectoryCount
                + lastResult.excludedFileCount) + " 项");
        JOptionPane.showMessageDialog(this, content, "本次扫描详情",
                JOptionPane.PLAIN_MESSAGE);
    }

    private void addScanDetail(JPanel panel, GridBagConstraints gbc,
                               String label, String value) {
        gbc.gridx = 0;
        JLabel name = new JLabel(label);
        name.setFont(UI_FONT);
        name.setForeground(MUTED_COLOR);
        panel.add(name, gbc);
        gbc.gridx = 1;
        JLabel detail = new JLabel(value);
        detail.setFont(UI_FONT_BOLD);
        detail.setForeground(TEXT_COLOR);
        panel.add(detail, gbc);
        gbc.gridy++;
    }

    private static int ratio(long value, long total) {
        return total <= 0L ? 1000 : (int) Math.min(1000L, Math.max(0L, value) * 1000L / total);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", value);
        }
        value /= 1024.0;
        if (value < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MB", value);
        }
        return String.format(Locale.ROOT, "%.2f GB", value / 1024.0);
    }

    private static String formatDuration(long millis) {
        if (millis < 1000L) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.1f 秒", millis / 1000.0);
    }

    private static String middleEllipsis(String value, int maximumCharacters) {
        if (value == null || value.length() <= maximumCharacters) {
            return value == null ? "" : value;
        }
        int side = (maximumCharacters - 3) / 2;
        return value.substring(0, side) + "..." + value.substring(value.length() - side);
    }

    private enum CompareMode {
        FILE,
        DIRECTORY
    }

    private enum EntryStatus {
        SAME("相同"),
        DIFFERENT("不同"),
        LEFT_ONLY("仅左侧"),
        RIGHT_ONLY("仅右侧");

        private final String label;

        EntryStatus(String label) {
            this.label = label;
        }
    }

    @SuppressWarnings("unchecked")
    private static class SideTableModel extends DefaultTableModel {
        private SideTableModel() {
            super(new Object[]{"状态", "名称", "大小 / 包含"}, 0);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        private void addNode(CompareNode node, boolean leftSide) {
            String status = sideStatus(node, leftSide);
            String size;
            if (node.directory) {
                boolean exists = leftSide ? node.leftExists : node.rightExists;
                int count = leftSide ? node.leftFileCount : node.rightFileCount;
                size = exists ? count + " 个文件" : "-";
            } else {
                FileInfo file = leftSide ? node.entry.left : node.entry.right;
                size = file == null ? "-" : formatSize(file.size);
            }
            addRow(new Object[]{status, node.name, size});
        }

        private void addNodes(List<CompareNode> nodes, int fromIndex, int toIndex,
                              boolean leftSide) {
            if (fromIndex >= toIndex) {
                return;
            }
            int first = getRowCount();
            for (int i = fromIndex; i < toIndex; i++) {
                CompareNode node = nodes.get(i);
                String status = sideStatus(node, leftSide);
                String size;
                if (node.directory) {
                    boolean exists = leftSide ? node.leftExists : node.rightExists;
                    int count = leftSide ? node.leftFileCount : node.rightFileCount;
                    size = exists ? count + " 个文件" : "-";
                } else {
                    FileInfo file = leftSide ? node.entry.left : node.entry.right;
                    size = file == null ? "-" : formatSize(file.size);
                }
                dataVector.add(new java.util.Vector<Object>(java.util.Arrays.asList(
                        status, node.name, size)));
            }
            fireTableRowsInserted(first, getRowCount() - 1);
        }

        private String sideStatus(CompareNode node, boolean leftSide) {
            if (node.status == EntryStatus.SAME) {
                return EntryStatus.SAME.label;
            }
            if (node.status == EntryStatus.DIFFERENT) {
                return node.directory ? "有差异" : EntryStatus.DIFFERENT.label;
            }
            if (node.status == EntryStatus.LEFT_ONLY) {
                return leftSide ? EntryStatus.LEFT_ONLY.label : "缺失";
            }
            return leftSide ? "缺失" : EntryStatus.RIGHT_ONLY.label;
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            double kilobytes = bytes / 1024.0;
            if (kilobytes < 1024) {
                return String.format("%.1f KB", kilobytes);
            }
            return String.format("%.1f MB", kilobytes / 1024.0);
        }
    }

    private class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            EntryStatus status = null;
            CompareNode node = null;
            if (row >= 0 && row < visibleNodes.size()) {
                node = visibleNodes.get(row);
                status = node.status;
            }
            if (status == EntryStatus.SAME) {
                component.setBackground(isSelected ? new Color(209, 238, 218) : SAME_BACKGROUND);
            } else if (status != null) {
                component.setBackground(isSelected ? new Color(250, 218, 218) : DIFFERENT_BACKGROUND);
            } else {
                component.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            }
            if (column == 0 && status != null) {
                component.setForeground(status == EntryStatus.SAME ? SAME_COLOR : DIFFERENT_COLOR);
                component.setFont(UI_FONT_BOLD);
            } else {
                component.setForeground(column == 2 ? MUTED_COLOR : TEXT_COLOR);
                component.setFont(UI_FONT);
            }
            if (component instanceof JLabel) {
                JLabel label = (JLabel) component;
                label.setIcon(null);
                label.setToolTipText(null);
                label.setHorizontalAlignment(column == 2 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                int leftPadding = column == 0 ? 10 : 8;
                if (column == 1 && node != null) {
                    leftPadding += node.depth * 18;
                    label.setIcon(new TreeItemIcon(node.directory,
                            node.directory && expandedPaths.contains(node.relativePath)));
                    label.setIconTextGap(7);
                    label.setToolTipText(node.relativePath);
                    label.setFont(node.directory ? UI_FONT_BOLD : UI_FONT);
                }
                label.setBorder(new CompoundBorder(
                        new MatteBorder(0, column == 0 ? 4 : 0, 1, 0,
                                column == 0 && status != EntryStatus.SAME ? DIFFERENT_COLOR
                                        : column == 0 ? SAME_COLOR : BORDER_COLOR),
                        new EmptyBorder(0, leftPadding, 0, 8)));
            }
            return component;
        }
    }

    private static class RefreshIcon implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isEnabled() ? component.getForeground() : new Color(190, 198, 208));
            g.setStroke(new java.awt.BasicStroke(2f));
            g.drawArc(x + 2, y + 2, 17, 17, 35, 285);
            g.drawLine(x + 18, y + 1, x + 20, y + 8);
            g.drawLine(x + 18, y + 1, x + 12, y + 4);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 22;
        }

        @Override
        public int getIconHeight() {
            return 22;
        }
    }

    private static class FilterIcon implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isEnabled() ? MUTED_COLOR : new Color(190, 198, 208));
            g.fillPolygon(new int[]{x + 2, x + 20, x + 13},
                    new int[]{y + 3, y + 3, y + 11}, 3);
            g.fillRect(x + 11, y + 10, 4, 9);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 22;
        }

        @Override
        public int getIconHeight() {
            return 22;
        }
    }

    private static class TreeItemIcon implements Icon {
        private final boolean directory;
        private final boolean expanded;

        private TreeItemIcon(boolean directory, boolean expanded) {
            this.directory = directory;
            this.expanded = expanded;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            if (directory) {
                g.setColor(MUTED_COLOR);
                if (expanded) {
                    g.fillPolygon(new int[]{x, x + 10, x + 5},
                            new int[]{y + 6, y + 6, y + 13}, 3);
                } else {
                    g.fillPolygon(new int[]{x + 2, x + 2, x + 9},
                            new int[]{y + 4, y + 14, y + 9}, 3);
                }
                g.setColor(new Color(244, 196, 82));
                g.fillRoundRect(x + 15, y + 6, 20, 14, 3, 3);
                g.fillRoundRect(x + 17, y + 2, 10, 8, 2, 2);
                g.setColor(new Color(191, 144, 45));
                g.drawRoundRect(x + 15, y + 6, 20, 14, 3, 3);
            } else {
                g.setColor(SURFACE);
                g.fillRect(x + 3, y + 1, 15, 19);
                g.setColor(new Color(148, 163, 184));
                g.drawRect(x + 3, y + 1, 15, 19);
                g.drawLine(x + 6, y + 7, x + 15, y + 7);
                g.drawLine(x + 6, y + 11, x + 15, y + 11);
            }
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return directory ? 38 : 22;
        }

        @Override
        public int getIconHeight() {
            return 22;
        }
    }

    private static class FolderIcon implements Icon {
        private final boolean action;

        private FolderIcon(boolean action) {
            this.action = action;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = action ? new Color(244, 196, 82) : new Color(226, 232, 240);
            Color stroke = action ? new Color(191, 144, 45) : MUTED_COLOR;
            g.setColor(fill);
            g.fillRoundRect(x + 1, y + 6, 20, 14, 3, 3);
            g.fillRoundRect(x + 3, y + 2, 10, 8, 2, 2);
            g.setColor(stroke);
            g.drawRoundRect(x + 1, y + 6, 20, 14, 3, 3);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 22;
        }

        @Override
        public int getIconHeight() {
            return 22;
        }
    }

    private static class ModeIcon implements Icon {
        private final boolean directory;
        private final int width = 82;
        private final int height = 82;

        private ModeIcon(boolean directory) {
            this.directory = directory;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            if (directory) {
                paintFolder(g, x, y);
            } else {
                paintFiles(g, x, y);
            }
            g.dispose();
        }

        private void paintFolder(java.awt.Graphics2D g, int x, int y) {
            g.setColor(new Color(226, 232, 240));
            g.fillRoundRect(x + 8, y + 25, 68, 48, 5, 5);
            g.setColor(new Color(244, 196, 82));
            g.fillRoundRect(x + 6, y + 22, 68, 48, 5, 5);
            g.fillRoundRect(x + 10, y + 14, 29, 17, 4, 4);
            g.setColor(new Color(191, 144, 45));
            g.drawRoundRect(x + 6, y + 22, 68, 48, 5, 5);
            g.drawLine(x + 10, y + 14, x + 36, y + 14);
            g.drawLine(x + 10, y + 14, x + 7, y + 25);
            g.drawLine(x + 36, y + 14, x + 43, y + 22);
        }

        private void paintFiles(java.awt.Graphics2D g, int x, int y) {
            g.setColor(new Color(226, 232, 240));
            g.fillRect(x + 24, y + 12, 43, 56);
            g.setColor(new Color(248, 250, 252));
            g.fillRect(x + 16, y + 20, 43, 56);
            g.setColor(PRIMARY_COLOR);
            g.drawRect(x + 16, y + 20, 43, 56);
            g.drawRect(x + 24, y + 12, 43, 56);
            g.setColor(new Color(148, 163, 184));
            for (int i = 0; i < 4; i++) {
                g.drawLine(x + 25, y + 34 + i * 9, x + 50, y + 34 + i * 9);
            }
            for (int i = 0; i < 3; i++) {
                g.drawLine(x + 33, y + 26 + i * 9, x + 58, y + 26 + i * 9);
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }

    private static class LineNumberView extends JComponent implements DocumentListener {
        private final JTextArea textArea;
        private final int width = 46;

        private LineNumberView(JTextArea textArea) {
            this.textArea = textArea;
            setFont(new Font("Consolas", Font.PLAIN, 13));
            textArea.getDocument().addDocumentListener(this);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(width, Math.max(textArea.getPreferredSize().height, textArea.getHeight()));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Rectangle clip = g.getClipBounds();
            g.setColor(HEADER_COLOR);
            g.fillRect(0, clip.y, getWidth(), clip.height);
            g.setColor(BORDER_COLOR);
            g.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);
            g.setFont(getFont());
            g.setColor(MUTED_COLOR);

            javax.swing.text.Element root = textArea.getDocument().getDefaultRootElement();
            int startOffset = textArea.viewToModel(new java.awt.Point(0, clip.y));
            int endOffset = textArea.viewToModel(new java.awt.Point(0, clip.y + clip.height));
            int startLine = Math.max(0, root.getElementIndex(startOffset));
            int endLine = Math.min(root.getElementCount() - 1, root.getElementIndex(endOffset));
            for (int line = startLine; line <= endLine; line++) {
                try {
                    Rectangle location = textArea.modelToView(root.getElement(line).getStartOffset());
                    if (location != null) {
                        String value = String.valueOf(line + 1);
                        int x = width - 10 - g.getFontMetrics().stringWidth(value);
                        int y = location.y + location.height - g.getFontMetrics().getDescent();
                        g.drawString(value, x, y);
                    }
                } catch (BadLocationException ignored) {
                    // The document changed between line calculation and painting.
                }
            }
        }

        private void refresh() {
            revalidate();
            repaint();
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            refresh();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            refresh();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            refresh();
        }
    }

    private static class FileInfo {
        private final Path path;
        private final String relativePath;
        private final long size;
        private final long modifiedTime;
        private final String hash;

        private FileInfo(Path path, String relativePath, long size,
                         long modifiedTime, String hash) {
            this.path = path;
            this.relativePath = relativePath;
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.hash = hash;
        }
    }

    private static class CompareEntry {
        private final String relativePath;
        private final FileInfo left;
        private final FileInfo right;
        private final EntryStatus status;

        private CompareEntry(String relativePath, FileInfo left, FileInfo right, EntryStatus status) {
            this.relativePath = relativePath;
            this.left = left;
            this.right = right;
            this.status = status;
        }

        private static CompareEntry forPair(String relativePath, FileInfo left, FileInfo right) {
            EntryStatus status;
            if (left == null) {
                status = EntryStatus.RIGHT_ONLY;
            } else if (right == null) {
                status = EntryStatus.LEFT_ONLY;
            } else if (left.hash.equals(right.hash)) {
                status = EntryStatus.SAME;
            } else {
                status = EntryStatus.DIFFERENT;
            }
            return new CompareEntry(relativePath, left, right, status);
        }
    }

    private static class CompareNode {
        private final String name;
        private final String relativePath;
        private final int depth;
        private final boolean directory;
        private final List<CompareNode> children = new ArrayList<CompareNode>();
        private CompareEntry entry;
        private boolean leftExists;
        private boolean rightExists;
        private int leftFileCount;
        private int rightFileCount;
        private EntryStatus status;

        private CompareNode(String name, String relativePath, int depth, boolean directory) {
            this.name = name;
            this.relativePath = relativePath;
            this.depth = depth;
            this.directory = directory;
        }

        private static CompareNode directory(String name, String relativePath, int depth) {
            return new CompareNode(name, relativePath, depth, true);
        }

        private static CompareNode file(String name, String relativePath, int depth, CompareEntry entry) {
            CompareNode node = new CompareNode(name, relativePath, depth, false);
            node.entry = entry;
            node.leftExists = entry.left != null;
            node.rightExists = entry.right != null;
            return node;
        }
    }

    private static class CompareResult {
        private final CompareMode mode;
        private final Path leftRoot;
        private final Path rightRoot;
        private final List<CompareEntry> entries = new ArrayList<CompareEntry>();
        private final Set<String> leftDirectories = new LinkedHashSet<String>();
        private final Set<String> rightDirectories = new LinkedHashSet<String>();
        private int sameCount;
        private int differentCount;
        private int leftOnlyCount;
        private int rightOnlyCount;
        private int excludedDirectoryCount;
        private int excludedFileCount;

        private CompareResult(CompareMode mode, Path leftRoot, Path rightRoot) {
            this.mode = mode;
            this.leftRoot = leftRoot;
            this.rightRoot = rightRoot;
        }

        private void recount() {
            sameCount = 0;
            differentCount = 0;
            leftOnlyCount = 0;
            rightOnlyCount = 0;
            for (CompareEntry entry : entries) {
                if (entry.status == EntryStatus.SAME) {
                    sameCount++;
                } else if (entry.status == EntryStatus.DIFFERENT) {
                    differentCount++;
                } else if (entry.status == EntryStatus.LEFT_ONLY) {
                    leftOnlyCount++;
                } else if (entry.status == EntryStatus.RIGHT_ONLY) {
                    rightOnlyCount++;
                }
            }
        }
    }

    private static class CompareTaskOutput {
        private final long taskId;
        private final CompareResult result;
        private final CompareNode treeRoot;
        private final CompareScanService.ScanMetrics metrics;

        private CompareTaskOutput(long taskId, CompareResult result, CompareNode treeRoot,
                                  CompareScanService.ScanMetrics metrics) {
            this.taskId = taskId;
            this.result = result;
            this.treeRoot = treeRoot;
            this.metrics = metrics;
        }
    }
}
