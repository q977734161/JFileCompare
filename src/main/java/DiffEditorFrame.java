import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;

final class DiffEditorFrame extends JFrame {
    private static final Color APP_BACKGROUND = new Color(237, 241, 245);
    private static final Color SURFACE = Color.WHITE;
    private static final Color HEADER = new Color(247, 249, 251);
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(218, 225, 232);
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color PRIMARY_DARK = new Color(29, 78, 216);
    private static final Color SAME_BACKGROUND = new Color(241, 249, 244);
    private static final Color DIFFERENT = new Color(200, 49, 49);
    private static final Color DIFFERENT_BACKGROUND = new Color(255, 237, 237);
    private static final Color PLACEHOLDER_BACKGROUND = new Color(243, 245, 247);
    private static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private static final Font UI_FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);
    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final int ROW_HEIGHT = 28;
    private static final int HISTORY_LIMIT = 100;

    private final Path leftPath;
    private final Path rightPath;
    private final Runnable savedCallback;
    private final PreferencesService preferencesService;
    private final DiffEngine diffEngine = new MyersDiffEngine();
    private final DiffAlignmentService alignmentService = new DiffAlignmentService();
    private final HunkApplyService applyService = new HunkApplyService();
    private final TextFileCodec textFileCodec = new TextFileCodec();
    private final SideHistory leftHistory = new SideHistory();
    private final SideHistory rightHistory = new SideHistory();

    private LineDocument leftDocument;
    private LineDocument rightDocument;
    private FileEncoding leftEncoding;
    private FileEncoding rightEncoding;
    private byte[] leftRawBytes;
    private byte[] rightRawBytes;
    private boolean leftExistsOnDisk;
    private boolean rightExistsOnDisk;
    private String savedLeftText;
    private String savedRightText;
    private List<DiffHunk> hunks = Collections.emptyList();
    private List<AlignedDiffRow> rows = Collections.emptyList();

    private final SideTableModel leftModel = new SideTableModel(true);
    private final SideTableModel rightModel = new SideTableModel(false);
    private final LineNumberTableModel leftLineNumberModel = new LineNumberTableModel(true);
    private final LineNumberTableModel rightLineNumberModel = new LineNumberTableModel(false);
    private final RailTableModel railModel = new RailTableModel();
    private final JTable leftTable = createSideTable(true, leftModel);
    private final JTable rightTable = createSideTable(false, rightModel);
    private final JTable leftLineNumberTable = createLineNumberTable(true, leftTable,
            leftLineNumberModel);
    private final JTable rightLineNumberTable = createLineNumberTable(false, rightTable,
            rightLineNumberModel);
    private final JTable railTable = createRailTable();
    private final JLabel leftModifiedLabel = createModifiedLabel();
    private final JLabel rightModifiedLabel = createModifiedLabel();
    private final JButton leftEncodingButton = createMetadataButton();
    private final JButton rightEncodingButton = createMetadataButton();
    private final JLabel leftLineEndingLabel = createLineEndingLabel();
    private final JLabel rightLineEndingLabel = createLineEndingLabel();
    private final JLabel statusLabel = new JLabel();
    private final JCheckBox linkedScroll = new JCheckBox("联动滚动", true);
    private final Timer recomputeTimer;

    private JScrollPane leftScroll;
    private JScrollPane rightScroll;
    private JScrollPane railScroll;
    private boolean syncingScroll;
    private boolean confirmDeletion;
    private boolean preferencesCaptureReady;
    private Rectangle lastNormalBounds;
    private int documentRevision;
    private int appliedRevision;
    private SwingWorker<ReloadBytes, Void> reloadWorker;

    DiffEditorFrame(JFrame owner, String relativePath, Path leftPath, Path rightPath,
                     Runnable savedCallback) throws IOException {
        this(owner, relativePath, leftPath, rightPath, savedCallback, null);
    }

    DiffEditorFrame(JFrame owner, String relativePath, Path leftPath, Path rightPath,
                    Runnable savedCallback, PreferencesService preferencesService)
            throws IOException {
        this(owner, relativePath, leftPath, rightPath, savedCallback, preferencesService,
                null, null);
    }

    DiffEditorFrame(JFrame owner, String relativePath, Path leftPath, Path rightPath,
                    Runnable savedCallback, PreferencesService preferencesService,
                    TextFileSnapshot preparedLeft, TextFileSnapshot preparedRight)
            throws IOException {
        super("文件内容对比 - " + relativePath);
        this.leftPath = leftPath;
        this.rightPath = rightPath;
        this.savedCallback = savedCallback;
        this.preferencesService = preferencesService;
        AppPreferences preferences = preferencesService == null
                ? AppPreferences.defaults() : preferencesService.current();
        confirmDeletion = preferences.confirmHunkDeletion();
        linkedScroll.setSelected(preferences.linkedScrollDefault());
        TextFileSnapshot leftSnapshot = preparedLeft == null
                ? openSnapshot(owner, leftPath) : preparedLeft;
        TextFileSnapshot rightSnapshot = preparedRight == null
                ? openSnapshot(owner, rightPath) : preparedRight;
        applyLoadedSnapshot(true, leftSnapshot);
        applyLoadedSnapshot(false, rightSnapshot);
        this.recomputeTimer = new Timer(300, e -> recomputeInBackground());
        this.recomputeTimer.setRepeats(false);

        boolean prepared = preparedLeft != null && preparedRight != null;
        if (!prepared) {
            DiffResult initial = calculateDiff(leftDocument, rightDocument);
            hunks = initial.hunks;
            rows = initial.rows;
        }

        setJMenuBar(createMenuBar());
        setContentPane(createPage(relativePath));
        installKeyboardActions();
        refreshModels();
        refreshFileMetadata();
        updateModifiedState();
        if (prepared) {
            leftTable.setEnabled(false);
            rightTable.setEnabled(false);
            railTable.setEnabled(false);
            statusLabel.setText("正在计算首次差异...");
        }
        setMinimumSize(new Dimension(1020, 620));
        WindowBounds savedBounds = preferences.restoreEditorWindow()
                ? preferences.editorWindowBounds() : null;
        Rectangle restored = WindowPlacement.fitToCurrentScreens(savedBounds,
                new Dimension(1020, 620), new Dimension(1360, 800));
        setBounds(restored);
        lastNormalBounds = new Rectangle(restored);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscardChanges()) {
                    dispose();
                }
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) {
                captureWindowPreference();
            }

            @Override public void componentResized(ComponentEvent event) {
                captureWindowPreference();
            }
        });
        linkedScroll.addActionListener(e -> {
            if (DiffEditorFrame.this.preferencesService != null) {
                DiffEditorFrame.this.preferencesService.updateLinkedScroll(
                        linkedScroll.isSelected());
            }
        });
        preferencesCaptureReady = true;
    }

    void calculateInitialDiffInBackground() {
        final LineDocument leftSnapshot = leftDocument;
        final LineDocument rightSnapshot = rightDocument;
        new SwingWorker<DiffResult, Void>() {
            @Override protected DiffResult doInBackground() {
                return calculateDiff(leftSnapshot, rightSnapshot);
            }

            @Override protected void done() {
                if (!isDisplayable()) return;
                try {
                    applyDiff(get(), documentRevision);
                    leftTable.setEnabled(true);
                    rightTable.setEnabled(true);
                } catch (Exception ex) {
                    statusLabel.setText("首次差异计算失败：" + rootMessage(ex));
                } finally {
                    railTable.setEnabled(true);
                }
            }
        }.execute();
    }

    @Override public void dispose() {
        if (reloadWorker != null) {
            reloadWorker.cancel(true);
        }
        captureWindowPreference();
        super.dispose();
    }

    private void captureWindowPreference() {
        if (!preferencesCaptureReady || preferencesService == null) return;
        if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0
                && getWidth() > 0 && getHeight() > 0) {
            lastNormalBounds = getBounds();
        }
        if (lastNormalBounds != null) {
            preferencesService.updateEditorWindow(WindowBounds.from(lastNormalBounds));
        }
    }

    private TextFileSnapshot openSnapshot(Component parent, Path path) throws IOException {
        if (!Files.exists(path)) {
            return textFileCodec.read(path, null);
        }
        byte[] bytes = Files.readAllBytes(path);
        EncodingDetection detection = textFileCodec.detect(bytes);
        FileEncoding encoding = detection.getSuggested();
        if (detection.isConfirmationRequired()) {
            encoding = chooseEncoding(parent, path, bytes, detection, encoding);
            if (encoding == null) {
                throw new TextFileOpenCancelledException();
            }
        }
        return textFileCodec.decode(path, true, bytes, encoding);
    }

    private void applyLoadedSnapshot(boolean leftSide, TextFileSnapshot snapshot) {
        if (leftSide) {
            leftDocument = snapshot.getDocument();
            leftEncoding = snapshot.getEncoding();
            leftRawBytes = snapshot.getRawBytes();
            leftExistsOnDisk = snapshot.existed();
            savedLeftText = leftDocument.toText();
        } else {
            rightDocument = snapshot.getDocument();
            rightEncoding = snapshot.getEncoding();
            rightRawBytes = snapshot.getRawBytes();
            rightExistsOnDisk = snapshot.existed();
            savedRightText = rightDocument.toText();
        }
    }

    private JPanel createPage(String relativePath) {
        leftScroll = createScroll(leftTable, true, leftLineNumberTable);
        rightScroll = createScroll(rightTable, true, rightLineNumberTable);
        railScroll = createScroll(railTable, false);
        installLinkedScrolling();

        JPanel editors = new JPanel(new GridBagLayout());
        editors.setBackground(SURFACE);
        editors.setBorder(new EmptyBorder(12, 16, 12, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        gbc.gridx = 0;
        gbc.weightx = 1;
        editors.add(createEditorSide("左侧文件", leftPath, !leftExistsOnDisk,
                leftModifiedLabel, leftScroll, true), gbc);
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.insets = new Insets(0, 8, 0, 8);
        editors.add(createRailPanel(), gbc);
        gbc.gridx = 2;
        gbc.weightx = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        editors.add(createEditorSide("右侧文件", rightPath, !rightExistsOnDisk,
                rightModifiedLabel, rightScroll, false), gbc);

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(SURFACE);
        shell.setBorder(BorderFactory.createLineBorder(BORDER));
        shell.add(createHeader(relativePath), BorderLayout.NORTH);
        shell.add(editors, BorderLayout.CENTER);
        shell.add(createFooter(), BorderLayout.SOUTH);

        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(APP_BACKGROUND);
        page.setBorder(new EmptyBorder(12, 12, 12, 12));
        page.add(shell, BorderLayout.CENTER);
        return page;
    }

    private JPanel createHeader(String relativePath) {
        JLabel mark = new JLabel("⇄", SwingConstants.CENTER);
        mark.setOpaque(true);
        mark.setBackground(PRIMARY);
        mark.setForeground(SURFACE);
        mark.setFont(new Font("SansSerif", Font.BOLD, 20));
        mark.setPreferredSize(new Dimension(34, 34));

        JLabel title = new JLabel("文件内容对比");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 19));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel(relativePath);
        subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        subtitle.setForeground(MUTED);

        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        titles.add(title, BorderLayout.CENTER);
        titles.add(subtitle, BorderLayout.SOUTH);
        JPanel identity = new JPanel(new BorderLayout(12, 0));
        identity.setOpaque(false);
        identity.add(mark, BorderLayout.WEST);
        identity.add(titles, BorderLayout.CENTER);

        linkedScroll.setOpaque(false);
        linkedScroll.setFocusPainted(false);
        linkedScroll.setFont(UI_FONT);
        linkedScroll.setForeground(TEXT);
        JButton reload = secondaryButton("重新加载");
        reload.setToolTipText("从磁盘重新读取两侧文件 (F5)");
        reload.addActionListener(e -> reloadFiles());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        actions.setOpaque(false);
        actions.add(linkedScroll);
        actions.add(reload);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SURFACE);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(10, 16, 10, 16)));
        header.add(identity, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel createEditorSide(String titleText, Path path, boolean missing,
                                    JLabel modifiedLabel, JScrollPane scroll,
                                    boolean leftSide) {
        JLabel title = new JLabel(titleText);
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 15));
        title.setForeground(TEXT);
        JLabel missingLabel = new JLabel("文件缺失");
        missingLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        missingLabel.setForeground(DIFFERENT);
        missingLabel.setVisible(missing);

        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titleLine.setOpaque(false);
        titleLine.add(title);
        titleLine.add(missingLabel);
        titleLine.add(modifiedLabel);

        JLabel pathLabel = new JLabel(path.toString());
        pathLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        pathLabel.setForeground(MUTED);
        pathLabel.setToolTipText(path.toString());

        JButton encodingButton = leftSide ? leftEncodingButton : rightEncodingButton;
        JLabel lineEndingLabel = leftSide ? leftLineEndingLabel : rightLineEndingLabel;
        encodingButton.addActionListener(e -> showEncodingActions(leftSide, encodingButton));
        JPanel metadata = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        metadata.setOpaque(false);
        metadata.add(encodingButton);
        metadata.add(lineEndingLabel);

        JPanel topLine = new JPanel(new BorderLayout());
        topLine.setOpaque(false);
        topLine.add(titleLine, BorderLayout.WEST);
        topLine.add(metadata, BorderLayout.EAST);

        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setBackground(SURFACE);
        heading.setPreferredSize(new Dimension(100, 54));
        heading.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(7, 10, 7, 10)));
        heading.add(topLine, BorderLayout.NORTH);
        heading.add(pathLabel, BorderLayout.SOUTH);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(SURFACE);
        side.setBorder(BorderFactory.createLineBorder(BORDER));
        side.add(heading, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    private JPanel createRailPanel() {
        JLabel title = new JLabel("差异操作", SwingConstants.CENTER);
        title.setFont(UI_FONT_BOLD);
        title.setForeground(MUTED);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setBackground(HEADER);
        heading.setPreferredSize(new Dimension(92, 54));
        heading.setBorder(new MatteBorder(0, 0, 1, 0, BORDER));
        heading.add(title, BorderLayout.CENTER);

        JPanel rail = new JPanel(new BorderLayout());
        rail.setPreferredSize(new Dimension(92, 100));
        rail.setMinimumSize(new Dimension(92, 100));
        rail.setBackground(HEADER);
        rail.setBorder(BorderFactory.createLineBorder(BORDER));
        rail.add(heading, BorderLayout.NORTH);
        rail.add(railScroll, BorderLayout.CENTER);
        return rail;
    }

    private JPanel createFooter() {
        statusLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        statusLabel.setForeground(MUTED);

        JButton allToLeft = secondaryButton("全部复制到左侧");
        JButton allToRight = secondaryButton("全部复制到右侧");
        JButton saveLeft = secondaryButton("保存左侧");
        JButton saveRight = secondaryButton("保存右侧");
        JButton saveAll = primaryButton("全部保存");
        allToLeft.addActionListener(e -> copyWholeFile(false));
        allToRight.addActionListener(e -> copyWholeFile(true));
        saveLeft.addActionListener(e -> saveSide(true, true));
        saveRight.addActionListener(e -> saveSide(false, true));
        saveAll.addActionListener(e -> saveAll());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(allToLeft);
        actions.add(allToRight);
        actions.add(saveLeft);
        actions.add(saveRight);
        actions.add(saveAll);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SURFACE);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(9, 16, 9, 16)));
        footer.add(statusLabel, BorderLayout.WEST);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu edit = new JMenu("编辑");
        edit.setFont(UI_FONT);
        JMenuItem undoLeft = new JMenuItem("撤销左侧修改");
        JMenuItem undoRight = new JMenuItem("撤销右侧修改");
        undoLeft.setFont(UI_FONT);
        undoRight.setFont(UI_FONT);
        undoLeft.addActionListener(e -> undo(true));
        undoRight.addActionListener(e -> undo(false));

        JCheckBoxMenuItem confirmItem = new JCheckBoxMenuItem("删除差异块前确认", confirmDeletion);
        confirmItem.setFont(UI_FONT);
        confirmItem.addActionListener(e -> {
            confirmDeletion = confirmItem.isSelected();
            if (preferencesService != null) {
                preferencesService.updateConfirmDeletion(confirmDeletion);
            }
        });
        edit.add(undoLeft);
        edit.add(undoRight);
        edit.addSeparator();
        edit.add(confirmItem);
        bar.add(edit);
        return bar;
    }

    private JTable createSideTable(boolean leftSide, SideTableModel model) {
        JTable table = new JTable(model);
        table.setTableHeader(null);
        table.setRowHeight(ROW_HEIGHT);
        table.setShowGrid(true);
        table.setGridColor(BORDER);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setFont(CODE_FONT);
        table.setForeground(TEXT);
        table.setBackground(SURFACE);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new SideCellRenderer(leftSide));

        JTextField editorField = new JTextField();
        editorField.setFont(CODE_FONT);
        editorField.setBorder(new EmptyBorder(0, 6, 0, 6));
        DefaultCellEditor editor = new DefaultCellEditor(editorField);
        editor.setClickCountToStart(2);
        table.setDefaultEditor(Object.class, editor);

        TableColumn contentColumn = table.getColumnModel().getColumn(0);
        contentColumn.setMinWidth(400);
        contentColumn.setPreferredWidth(1200);
        installTableActions(table, leftSide);
        return table;
    }

    private JTable createLineNumberTable(boolean leftSide, JTable contentTable,
                                         LineNumberTableModel model) {
        JTable table = new JTable(model);
        table.setTableHeader(null);
        table.setRowHeight(ROW_HEIGHT);
        table.setShowGrid(true);
        table.setGridColor(BORDER);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionModel(contentTable.getSelectionModel());
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        table.setFocusable(false);
        table.setBackground(SURFACE);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new LineNumberCellRenderer(leftSide));
        TableColumn lineColumn = table.getColumnModel().getColumn(0);
        lineColumn.setMinWidth(52);
        lineColumn.setMaxWidth(52);
        lineColumn.setPreferredWidth(52);
        table.setPreferredScrollableViewportSize(new Dimension(52, 0));
        return table;
    }

    private JTable createRailTable() {
        JTable table = new JTable(railModel);
        table.setTableHeader(null);
        table.setRowHeight(ROW_HEIGHT);
        table.setShowGrid(true);
        table.setGridColor(BORDER);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setBackground(HEADER);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new RailCellRenderer());
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = railTable.rowAtPoint(e.getPoint());
                DiffHunk hunk = hunkAtControlRow(row);
                if (hunk == null || !railTable.isEnabled()) {
                    return;
                }
                boolean copyToLeft = e.getX() < railTable.getWidth() / 2;
                applyHunk(hunk, copyToLeft
                        ? HunkApplyService.Direction.RIGHT_TO_LEFT
                        : HunkApplyService.Direction.LEFT_TO_RIGHT);
            }
        });
        table.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = railTable.rowAtPoint(e.getPoint());
                DiffHunk hunk = hunkAtControlRow(row);
                if (hunk == null) {
                    railTable.setToolTipText(null);
                } else if (e.getX() < railTable.getWidth() / 2) {
                    railTable.setToolTipText("用右侧差异块替换左侧");
                } else {
                    railTable.setToolTipText("用左侧差异块替换右侧");
                }
            }
        });
        return table;
    }

    private JScrollPane createScroll(JTable table, boolean horizontal) {
        return createScroll(table, horizontal, null);
    }

    private JScrollPane createScroll(JTable table, boolean horizontal, JTable rowHeader) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(table.getBackground());
        if (rowHeader != null) {
            scroll.setRowHeaderView(rowHeader);
            scroll.getRowHeader().setBackground(rowHeader.getBackground());
        }
        scroll.setHorizontalScrollBarPolicy(horizontal
                ? JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                : JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(horizontal
                ? JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                : JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private void installLinkedScrolling() {
        AdjustmentListener leftListener = new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                syncScrollValue(leftScroll, rightScroll);
            }
        };
        AdjustmentListener rightListener = new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                syncScrollValue(rightScroll, leftScroll);
            }
        };
        leftScroll.getVerticalScrollBar().addAdjustmentListener(leftListener);
        rightScroll.getVerticalScrollBar().addAdjustmentListener(rightListener);
    }

    private void syncScrollValue(JScrollPane source, JScrollPane target) {
        if (!linkedScroll.isSelected() || syncingScroll) {
            return;
        }
        syncingScroll = true;
        int value = source.getVerticalScrollBar().getValue();
        target.getVerticalScrollBar().setValue(value);
        railScroll.getVerticalScrollBar().setValue(value);
        syncingScroll = false;
    }

    private void installTableActions(final JTable table, final boolean leftSide) {
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("control Z"), "undo-side");
        table.getActionMap().put("undo-side", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (table.isEditing()) {
                    table.getCellEditor().stopCellEditing();
                }
                undo(leftSide);
            }
        });

        final JPopupMenu popup = new JPopupMenu();
        JMenuItem insertAbove = new JMenuItem("在上方插入一行");
        JMenuItem insertBelow = new JMenuItem("在下方插入一行");
        JMenuItem deleteLine = new JMenuItem("删除这一行");
        insertAbove.addActionListener(e -> insertLine(leftSide, table.getSelectedRow(), false));
        insertBelow.addActionListener(e -> insertLine(leftSide, table.getSelectedRow(), true));
        deleteLine.addActionListener(e -> deleteLine(leftSide, table.getSelectedRow()));
        popup.add(insertAbove);
        popup.add(insertBelow);
        popup.addSeparator();
        popup.add(deleteLine);
        table.addMouseListener(new MouseAdapter() {
            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                }
                popup.show(table, e.getX(), e.getY());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }
        });
    }

    private void installKeyboardActions() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("control S"), "save-all");
        getRootPane().getActionMap().put("save-all", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopEditing();
                saveAll();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("F5"), "reload");
        getRootPane().getActionMap().put("reload", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                reloadFiles();
            }
        });
    }

    static FileEncoding chooseEncodingForOpen(Component parent, Path path, byte[] bytes,
                                              EncodingDetection detection) {
        if (!detection.isConfirmationRequired()) {
            return detection.getSuggested();
        }
        return chooseEncoding(parent, path, bytes, detection, detection.getSuggested());
    }

    private static FileEncoding chooseEncoding(Component parent, Path path, byte[] bytes,
                                               EncodingDetection detection,
                                               FileEncoding initialEncoding) {
        final TextFileCodec codec = new TextFileCodec();
        final JComboBox<FileEncoding> encodingBox = new JComboBox<FileEncoding>(
                encodingOptions(detection.getCandidates(), true).toArray(new FileEncoding[0]));
        selectEncoding(encodingBox, initialEncoding);
        encodingBox.setFont(UI_FONT);
        final JTextArea preview = new JTextArea(12, 54);
        preview.setEditable(false);
        preview.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        preview.setLineWrap(false);
        preview.setMargin(new Insets(8, 10, 8, 10));

        Runnable refreshPreview = new Runnable() {
            @Override
            public void run() {
                FileEncoding selected = (FileEncoding) encodingBox.getSelectedItem();
                try {
                    preview.setText(codec.preview(bytes, selected, 6000));
                    preview.setCaretPosition(0);
                    preview.setForeground(TEXT);
                } catch (IOException ex) {
                    preview.setText("该编码无法完整解码当前文件。\n\n" + ex.getMessage());
                    preview.setForeground(DIFFERENT);
                }
            }
        };
        encodingBox.addActionListener(e -> refreshPreview.run());
        refreshPreview.run();

        JLabel message = new JLabel(detection.getMessage());
        message.setFont(UI_FONT_BOLD);
        message.setForeground(detection.isLikelyBinary()
                ? DIFFERENT : new Color(161, 98, 7));
        JLabel pathLabel = new JLabel(path.toString());
        pathLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        pathLabel.setForeground(MUTED);
        JPanel fields = new JPanel(new BorderLayout(8, 8));
        fields.add(message, BorderLayout.NORTH);
        fields.add(pathLabel, BorderLayout.CENTER);
        fields.add(encodingBox, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(fields, BorderLayout.NORTH);
        panel.add(new JScrollPane(preview), BorderLayout.CENTER);

        while (true) {
            int answer = JOptionPane.showConfirmDialog(parent, panel, "确认文件编码",
                    JOptionPane.OK_CANCEL_OPTION,
                    detection.isLikelyBinary()
                            ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return null;
            }
            FileEncoding selected = (FileEncoding) encodingBox.getSelectedItem();
            try {
                codec.preview(bytes, selected, 1);
                return selected.confirmedByUser();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent, ex.getMessage(), "无法使用该编码",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static List<FileEncoding> encodingOptions(List<FileEncoding> preferred,
                                                      boolean includeBomVariants) {
        List<FileEncoding> options = new ArrayList<FileEncoding>();
        for (FileEncoding encoding : preferred) {
            addEncodingOption(options, encoding);
        }
        addEncodingOption(options, FileEncoding.manual(StandardCharsets.UTF_8, false));
        if (includeBomVariants) {
            addEncodingOption(options, FileEncoding.manual(StandardCharsets.UTF_8, true));
        }
        addEncodingOption(options, FileEncoding.manual(Charset.forName("GB18030"), false));
        addEncodingOption(options, FileEncoding.manual(Charset.forName("GBK"), false));
        addEncodingOption(options, FileEncoding.manual(Charset.forName("GB2312"), false));
        addEncodingOption(options, FileEncoding.manual(Charset.forName("Big5"), false));
        addEncodingOption(options, FileEncoding.manual(StandardCharsets.UTF_16LE, false));
        addEncodingOption(options, FileEncoding.manual(StandardCharsets.UTF_16BE, false));
        if (includeBomVariants) {
            addEncodingOption(options, FileEncoding.manual(StandardCharsets.UTF_16LE, true));
            addEncodingOption(options, FileEncoding.manual(StandardCharsets.UTF_16BE, true));
        }
        return options;
    }

    private static void addEncodingOption(List<FileEncoding> values, FileEncoding candidate) {
        for (FileEncoding existing : values) {
            if (existing.sameFormat(candidate)) {
                return;
            }
        }
        values.add(candidate);
    }

    private static void selectEncoding(JComboBox<FileEncoding> box, FileEncoding encoding) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).sameFormat(encoding)) {
                box.setSelectedIndex(i);
                return;
            }
        }
    }

    private void showEncodingActions(final boolean leftSide, Component source) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem reopen = new JMenuItem("重新按其他编码打开...");
        JMenuItem convert = new JMenuItem("转换并保存编码...");
        reopen.setFont(UI_FONT);
        convert.setFont(UI_FONT);
        reopen.addActionListener(e -> reopenWithEncoding(leftSide));
        convert.addActionListener(e -> convertEncodingAndSave(leftSide));
        menu.add(reopen);
        menu.add(convert);
        menu.show(source, 0, source.getHeight());
    }

    private void reopenWithEncoding(boolean leftSide) {
        stopEditing();
        if (isSideModified(leftSide)) {
            int answer = JOptionPane.showConfirmDialog(this,
                    (leftSide ? "左侧" : "右侧")
                            + "还有未保存修改，重新按编码打开会放弃这些修改。\n确定继续吗？",
                    "放弃当前修改", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        Path path = leftSide ? leftPath : rightPath;
        if (!Files.exists(path)) {
            JOptionPane.showMessageDialog(this, "文件尚未保存，不能重新读取原始字节。",
                    "重新打开", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            EncodingDetection detected = textFileCodec.detect(bytes);
            FileEncoding selected = chooseEncoding(this, path, bytes, detected,
                    leftSide ? leftEncoding : rightEncoding);
            if (selected == null) {
                return;
            }
            TextFileSnapshot snapshot = textFileCodec.decode(path, true, bytes, selected);
            applyLoadedSnapshot(leftSide, snapshot);
            (leftSide ? leftHistory : rightHistory).clear();
            refreshFileMetadata();
            updateModifiedState();
            recomputeInBackground();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "重新打开失败：" + ex.getMessage(),
                    "重新打开失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void convertEncodingAndSave(boolean leftSide) {
        stopEditing();
        FileEncoding current = leftSide ? leftEncoding : rightEncoding;
        JComboBox<FileEncoding> choices = new JComboBox<FileEncoding>(
                encodingOptions(Collections.<FileEncoding>emptyList(), true)
                        .toArray(new FileEncoding[0]));
        choices.setFont(UI_FONT);
        selectEncoding(choices, current);
        LineDocument document = leftSide ? leftDocument : rightDocument;
        Path path = leftSide ? leftPath : rightPath;

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 14);
        panel.add(new JLabel("当前编码"), gbc);
        gbc.gridx = 1;
        panel.add(new JLabel(current.getDisplayName()), gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        panel.add(new JLabel("目标编码"), gbc);
        gbc.gridx = 1;
        panel.add(choices, gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        panel.add(new JLabel("换行格式"), gbc);
        gbc.gridx = 1;
        panel.add(new JLabel(document.getLineEndingDisplayName() + "（保持）"), gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        panel.add(new JLabel("文件"), gbc);
        gbc.gridx = 1;
        panel.add(new JLabel(path.toString()), gbc);

        int selectedAnswer = JOptionPane.showConfirmDialog(this, panel, "转换并保存编码",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (selectedAnswer != JOptionPane.OK_OPTION) {
            return;
        }
        FileEncoding target = (FileEncoding) choices.getSelectedItem();
        if (current.sameFormat(target) && !isSideModified(leftSide)) {
            JOptionPane.showMessageDialog(this, "文件已经使用 " + current.getDisplayName() + "。",
                    "无需转换", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            textFileCodec.encode(document, target);
        } catch (CharacterCodingException ex) {
            JOptionPane.showMessageDialog(this,
                    "当前内容包含 " + target.getDisplayName()
                            + " 无法表示的字符，文件没有被修改。",
                    "无法转换编码", JOptionPane.ERROR_MESSAGE);
            return;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "无法转换编码",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        String details = "将" + (leftSide ? "左侧" : "右侧") + "文件从 "
                + current.getDisplayName() + " 转换为 " + target.getDisplayName()
                + "。\n文字内容和 " + document.getLineEndingDisplayName()
                + " 换行保持不变，另一侧文件不会改变。";
        int confirm = JOptionPane.showConfirmDialog(this, details, "确认转换并保存",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            byte[] written = textFileCodec.write(path, document, target);
            if (leftSide) {
                leftEncoding = target.confirmedByUser();
                leftRawBytes = written;
                leftExistsOnDisk = true;
                savedLeftText = document.toText();
                leftHistory.clear();
            } else {
                rightEncoding = target.confirmedByUser();
                rightRawBytes = written;
                rightExistsOnDisk = true;
                savedRightText = document.toText();
                rightHistory.clear();
            }
            refreshFileMetadata();
            updateModifiedState();
            if (savedCallback != null) {
                savedCallback.run();
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "转换保存失败：" + ex.getMessage(),
                    "保存失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean isSideModified(boolean leftSide) {
        return leftSide ? !savedLeftText.equals(leftDocument.toText())
                : !savedRightText.equals(rightDocument.toText());
    }

    private void refreshFileMetadata() {
        updateMetadataControl(leftEncodingButton, leftLineEndingLabel,
                leftEncoding, leftDocument);
        updateMetadataControl(rightEncodingButton, rightLineEndingLabel,
                rightEncoding, rightDocument);
    }

    private void updateMetadataControl(JButton encodingButton, JLabel lineEndingLabel,
                                       FileEncoding encoding, LineDocument document) {
        encodingButton.setText(encoding.getDisplayName());
        encodingButton.setToolTipText(encoding.getConfidenceLabel() + "："
                + encoding.getDetectionSource() + "；点击可重新解码或转换编码");
        boolean uncertain = encoding.getConfidence() == FileEncoding.Confidence.HEURISTIC
                || encoding.getConfidence() == FileEncoding.Confidence.UNCONFIRMED;
        encodingButton.setForeground(uncertain ? new Color(161, 98, 7) : TEXT);
        lineEndingLabel.setText(document.getLineEndingDisplayName());
        lineEndingLabel.setToolTipText(document.getLineEndingSummary());
        lineEndingLabel.setForeground(document.hasMixedLineEndings()
                ? new Color(161, 98, 7) : MUTED);
    }

    private void updateLine(boolean leftSide, int lineIndex, String value) {
        LineDocument document = leftSide ? leftDocument : rightDocument;
        if (lineIndex < 0 || lineIndex >= document.getLines().size()) {
            return;
        }
        String normalized = value == null ? "" : value.replace("\r", "").replace("\n", "");
        if (normalized.equals(document.getLines().get(lineIndex))) {
            return;
        }
        pushHistory(leftSide, document);
        setDocument(leftSide, document.replaceLine(lineIndex, normalized));
        scheduleRecompute();
    }

    private void insertLine(boolean leftSide, int alignedRow, boolean below) {
        stopEditing();
        LineDocument document = leftSide ? leftDocument : rightDocument;
        int index = insertionIndex(leftSide, alignedRow, below);
        pushHistory(leftSide, document);
        int safeIndex = Math.max(0, Math.min(index, document.getLines().size()));
        setDocument(leftSide, document.insertLine(safeIndex, ""));
        recomputeInBackground();
    }

    private void deleteLine(boolean leftSide, int alignedRow) {
        stopEditing();
        if (alignedRow < 0 || alignedRow >= rows.size()) {
            return;
        }
        int index = leftSide ? rows.get(alignedRow).getLeftLineIndex()
                : rows.get(alignedRow).getRightLineIndex();
        LineDocument document = leftSide ? leftDocument : rightDocument;
        if (index < 0 || index >= document.getLines().size()) {
            return;
        }
        pushHistory(leftSide, document);
        setDocument(leftSide, document.deleteLine(index));
        recomputeInBackground();
    }

    private int insertionIndex(boolean leftSide, int alignedRow, boolean below) {
        LineDocument document = leftSide ? leftDocument : rightDocument;
        if (alignedRow < 0 || alignedRow >= rows.size()) {
            return document.getLines().size();
        }
        AlignedDiffRow row = rows.get(alignedRow);
        int index = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
        if (index >= 0) {
            return below ? index + 1 : index;
        }
        DiffHunk hunk = findHunk(row.getHunkId());
        if (hunk == null) {
            return document.getLines().size();
        }
        return leftSide ? hunk.getLeftStart() : hunk.getRightStart();
    }

    private void applyHunk(DiffHunk hunk, HunkApplyService.Direction direction) {
        stopEditing();
        boolean toLeft = direction == HunkApplyService.Direction.RIGHT_TO_LEFT;
        boolean deletes = toLeft ? hunk.deletesWhenAppliedToLeft()
                : hunk.deletesWhenAppliedToRight();
        if (deletes && !confirmDeletion(hunk, toLeft)) {
            return;
        }

        int scrollValue = leftScroll.getVerticalScrollBar().getValue();
        pushHistory(toLeft, toLeft ? leftDocument : rightDocument);
        boolean inheritLeft = toLeft && !leftExistsOnDisk && leftDocument.getLines().isEmpty()
                && !hunk.getRightLines().isEmpty();
        boolean inheritRight = !toLeft && !rightExistsOnDisk && rightDocument.getLines().isEmpty()
                && !hunk.getLeftLines().isEmpty();
        HunkApplyService.ApplyResult result = applyService.apply(
                leftDocument, rightDocument, hunk, direction);
        leftDocument = result.getLeft();
        rightDocument = result.getRight();
        if (inheritLeft) {
            leftEncoding = rightEncoding;
        }
        if (inheritRight) {
            rightEncoding = leftEncoding;
        }
        refreshFileMetadata();
        updateModifiedState();
        documentRevision++;
        applyDiff(calculateDiff(leftDocument, rightDocument), documentRevision);
        SwingUtilities.invokeLater(() -> {
            leftScroll.getVerticalScrollBar().setValue(scrollValue);
            rightScroll.getVerticalScrollBar().setValue(scrollValue);
            railScroll.getVerticalScrollBar().setValue(scrollValue);
        });
    }

    private boolean confirmDeletion(DiffHunk hunk, boolean deleteLeft) {
        if (!confirmDeletion) {
            return true;
        }
        int start = (deleteLeft ? hunk.getLeftStart() : hunk.getRightStart()) + 1;
        int end = deleteLeft ? hunk.getLeftEnd() : hunk.getRightEnd();
        JLabel message = new JLabel("该操作会删除" + (deleteLeft ? "左侧" : "右侧")
                + "第 " + start + (end > start ? " - " + end : "") + " 行的差异内容。");
        message.setFont(UI_FONT);
        JCheckBox noMore = new JCheckBox("下次不再提示删除确认");
        noMore.setFont(UI_FONT);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.add(message, BorderLayout.NORTH);
        panel.add(noMore, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(this, panel, "确认删除差异块",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION && noMore.isSelected()) {
            confirmDeletion = false;
            if (preferencesService != null) {
                preferencesService.updateConfirmDeletion(false);
            }
            syncConfirmMenuState();
        }
        return answer == JOptionPane.OK_OPTION;
    }

    private void syncConfirmMenuState() {
        JMenu menu = getJMenuBar().getMenu(0);
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item instanceof JCheckBoxMenuItem) {
                ((JCheckBoxMenuItem) item).setSelected(confirmDeletion);
            }
        }
    }

    private void copyWholeFile(boolean leftToRight) {
        stopEditing();
        if (leftToRight) {
            pushHistory(false, rightDocument);
            boolean inherit = !rightExistsOnDisk && rightDocument.getLines().isEmpty();
            rightDocument = rightDocument.copyContentFrom(leftDocument, inherit);
            if (inherit) {
                rightEncoding = leftEncoding;
            }
        } else {
            pushHistory(true, leftDocument);
            boolean inherit = !leftExistsOnDisk && leftDocument.getLines().isEmpty();
            leftDocument = leftDocument.copyContentFrom(rightDocument, inherit);
            if (inherit) {
                leftEncoding = rightEncoding;
            }
        }
        refreshFileMetadata();
        updateModifiedState();
        recomputeInBackground();
    }

    private void undo(boolean leftSide) {
        stopEditing();
        SideHistory history = leftSide ? leftHistory : rightHistory;
        SideState previous = history.pop();
        if (previous == null) {
            return;
        }
        if (leftSide) {
            leftEncoding = previous.encoding;
        } else {
            rightEncoding = previous.encoding;
        }
        setDocument(leftSide, previous.document);
        refreshFileMetadata();
        recomputeInBackground();
    }

    private void pushHistory(boolean leftSide, LineDocument current) {
        FileEncoding encoding = leftSide ? leftEncoding : rightEncoding;
        (leftSide ? leftHistory : rightHistory).push(new SideState(current, encoding));
    }

    private void setDocument(boolean leftSide, LineDocument document) {
        if (leftSide) {
            leftDocument = document;
        } else {
            rightDocument = document;
        }
        updateModifiedState();
    }

    private void scheduleRecompute() {
        documentRevision++;
        railTable.setEnabled(false);
        statusLabel.setText("正在重新对比...");
        recomputeTimer.restart();
    }

    private void recomputeInBackground() {
        recomputeTimer.stop();
        final int revision = ++documentRevision;
        final LineDocument leftSnapshot = leftDocument;
        final LineDocument rightSnapshot = rightDocument;
        railTable.setEnabled(false);
        statusLabel.setText("正在重新对比...");
        new SwingWorker<DiffResult, Void>() {
            @Override
            protected DiffResult doInBackground() {
                return calculateDiff(leftSnapshot, rightSnapshot);
            }

            @Override
            protected void done() {
                if (revision != documentRevision) {
                    return;
                }
                try {
                    applyDiff(get(), revision);
                } catch (Exception ex) {
                    railTable.setEnabled(true);
                    statusLabel.setText("重新对比失败：" + ex.getMessage());
                }
            }
        }.execute();
    }

    private DiffResult calculateDiff(LineDocument left, LineDocument right) {
        List<DiffHunk> calculatedHunks = diffEngine.diff(left.getLines(), right.getLines());
        List<AlignedDiffRow> calculatedRows = alignmentService.align(
                left.getLines(), right.getLines(), calculatedHunks);
        return new DiffResult(calculatedHunks, calculatedRows);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private void applyDiff(DiffResult result, int revision) {
        hunks = result.hunks;
        rows = result.rows;
        appliedRevision = revision;
        refreshModels();
        railTable.setEnabled(true);
        updateStatus();
    }

    private void refreshModels() {
        leftModel.fireTableDataChanged();
        rightModel.fireTableDataChanged();
        leftLineNumberModel.fireTableDataChanged();
        rightLineNumberModel.fireTableDataChanged();
        railModel.fireTableDataChanged();
        updateStatus();
    }

    private void updateStatus() {
        statusLabel.setText(hunks.isEmpty()
                ? "两侧内容一致"
                : "共 " + hunks.size() + " 处差异，双击内容可编辑");
    }

    private void updateModifiedState() {
        leftModifiedLabel.setVisible(!savedLeftText.equals(leftDocument.toText()));
        rightModifiedLabel.setVisible(!savedRightText.equals(rightDocument.toText()));
    }

    private void saveAll() {
        stopEditing();
        try {
            textFileCodec.encode(leftDocument, leftEncoding);
            textFileCodec.encode(rightDocument, rightEncoding);
        } catch (CharacterCodingException ex) {
            JOptionPane.showMessageDialog(this,
                    "至少一侧包含当前编码无法表示的字符，两侧文件均未保存。",
                    "全部保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "全部保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (saveSide(true, false) && saveSide(false, false) && savedCallback != null) {
            savedCallback.run();
        }
    }

    private boolean saveSide(boolean leftSide, boolean notifySaved) {
        stopEditing();
        Path path = leftSide ? leftPath : rightPath;
        LineDocument document = leftSide ? leftDocument : rightDocument;
        FileEncoding encoding = leftSide ? leftEncoding : rightEncoding;
        try {
            byte[] written = textFileCodec.write(path, document, encoding);
            if (leftSide) {
                savedLeftText = document.toText();
                leftRawBytes = written;
                leftExistsOnDisk = true;
                leftHistory.clear();
            } else {
                savedRightText = document.toText();
                rightRawBytes = written;
                rightExistsOnDisk = true;
                rightHistory.clear();
            }
            refreshFileMetadata();
            updateModifiedState();
            if (notifySaved && savedCallback != null) {
                savedCallback.run();
            }
            return true;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "保存文件失败：" + ex.getMessage(),
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void reloadFiles() {
        stopEditing();
        if (!confirmDiscardChanges()) {
            return;
        }
        if (reloadWorker != null) {
            reloadWorker.cancel(true);
        }
        setEditorEnabled(false);
        statusLabel.setText("正在检查并重新读取文件...");
        reloadWorker = new SwingWorker<ReloadBytes, Void>() {
            @Override protected ReloadBytes doInBackground() throws Exception {
                long leftSize = Files.exists(leftPath) ? Files.size(leftPath) : 0L;
                long rightSize = Files.exists(rightPath) ? Files.size(rightPath) : 0L;
                DiffEditorLauncher.SizeDecision decision =
                        DiffEditorLauncher.sizeDecision(leftSize, rightSize);
                return ReloadBytes.checked(leftSize, rightSize, decision);
            }

            @Override protected void done() {
                if (!isDisplayable()) return;
                boolean handedOff = false;
                try {
                    ReloadBytes loaded = get();
                    if (loaded.decision == DiffEditorLauncher.SizeDecision.REJECT) {
                        JOptionPane.showMessageDialog(DiffEditorFrame.this,
                                "至少一侧文件已超过 100 MB，不能重新载入可编辑差异视图。",
                                "文件过大", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    if (loaded.decision == DiffEditorLauncher.SizeDecision.CONFIRM) {
                        int answer = JOptionPane.showConfirmDialog(DiffEditorFrame.this,
                                "至少一侧文件已超过 20 MB，重新加载可能占用较多内存。\n"
                                        + "是否继续？", "重新加载大文件",
                                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (answer != JOptionPane.OK_OPTION) return;
                    }
                    readReloadedFiles(loaded);
                    handedOff = true;
                } catch (CancellationException ex) {
                    statusLabel.setText("已取消重新加载");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DiffEditorFrame.this,
                            "重新加载文件失败：" + rootMessage(ex),
                            "重新加载失败", JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (!handedOff) {
                        reloadWorker = null;
                        setEditorEnabled(true);
                    }
                }
            }
        };
        reloadWorker.execute();
    }

    private void readReloadedFiles(final ReloadBytes loaded) {
        statusLabel.setText("正在从磁盘重新读取文件...");
        reloadWorker = new SwingWorker<ReloadBytes, Void>() {
            @Override protected ReloadBytes doInBackground() throws Exception {
                loaded.left = readBytes(leftPath);
                if (isCancelled()) throw new CancellationException();
                loaded.right = readBytes(rightPath);
                if (isCancelled()) throw new CancellationException();
                loaded.leftDetection = textFileCodec.detect(loaded.left);
                loaded.rightDetection = textFileCodec.detect(loaded.right);
                return loaded;
            }

            @Override protected void done() {
                if (!isDisplayable()) return;
                boolean handedOff = false;
                try {
                    ReloadBytes read = get();
                    FileEncoding newLeftEncoding = chooseEncodingForOpen(
                            DiffEditorFrame.this, leftPath, read.left, read.leftDetection);
                    if (newLeftEncoding == null) return;
                    FileEncoding newRightEncoding = chooseEncodingForOpen(
                            DiffEditorFrame.this, rightPath, read.right, read.rightDetection);
                    if (newRightEncoding == null) return;
                    decodeReloadedFiles(read, newLeftEncoding, newRightEncoding);
                    handedOff = true;
                } catch (CancellationException ex) {
                    statusLabel.setText("已取消重新加载");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DiffEditorFrame.this,
                            "重新加载文件失败：" + rootMessage(ex),
                            "重新加载失败", JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (!handedOff) {
                        reloadWorker = null;
                        setEditorEnabled(true);
                    }
                }
            }
        };
        reloadWorker.execute();
    }

    private void decodeReloadedFiles(final ReloadBytes loaded,
                                     final FileEncoding newLeftEncoding,
                                     final FileEncoding newRightEncoding) {
        statusLabel.setText("正在解码重新加载的文件...");
        reloadWorker = new SwingWorker<ReloadBytes, Void>() {
            @Override protected ReloadBytes doInBackground() throws Exception {
                loaded.leftSnapshot = textFileCodec.decode(leftPath,
                        Files.exists(leftPath), loaded.left, newLeftEncoding);
                if (isCancelled()) throw new CancellationException();
                loaded.rightSnapshot = textFileCodec.decode(rightPath,
                        Files.exists(rightPath), loaded.right, newRightEncoding);
                return loaded;
            }

            @Override protected void done() {
                if (!isDisplayable()) return;
                try {
                    ReloadBytes decoded = get();
                    applyLoadedSnapshot(true, decoded.leftSnapshot);
                    applyLoadedSnapshot(false, decoded.rightSnapshot);
                    leftHistory.clear();
                    rightHistory.clear();
                    refreshFileMetadata();
                    updateModifiedState();
                    recomputeInBackground();
                } catch (CancellationException ex) {
                    statusLabel.setText("已取消重新加载");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DiffEditorFrame.this,
                            "重新加载文件失败：" + rootMessage(ex),
                            "重新加载失败", JOptionPane.ERROR_MESSAGE);
                } finally {
                    reloadWorker = null;
                    setEditorEnabled(true);
                }
            }
        };
        reloadWorker.execute();
    }

    private byte[] readBytes(Path path) throws IOException {
        return Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
    }

    private void setEditorEnabled(boolean enabled) {
        leftTable.setEnabled(enabled);
        rightTable.setEnabled(enabled);
        railTable.setEnabled(enabled);
    }

    private boolean confirmDiscardChanges() {
        if (savedLeftText.equals(leftDocument.toText())
                && savedRightText.equals(rightDocument.toText())) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "当前还有未保存的修改，确定放弃吗？", "未保存的修改",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.OK_OPTION;
    }

    private void stopEditing() {
        if (leftTable.isEditing()) {
            leftTable.getCellEditor().stopCellEditing();
        }
        if (rightTable.isEditing()) {
            rightTable.getCellEditor().stopCellEditing();
        }
    }

    private DiffHunk hunkAtControlRow(int row) {
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        int id = rows.get(row).getHunkId();
        if (id < 0 || (row > 0 && rows.get(row - 1).getHunkId() == id)) {
            return null;
        }
        return findHunk(id);
    }

    private DiffHunk findHunk(int id) {
        for (DiffHunk hunk : hunks) {
            if (hunk.getId() == id) {
                return hunk;
            }
        }
        return null;
    }

    private static JLabel createModifiedLabel() {
        JLabel label = new JLabel("已修改");
        label.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        label.setForeground(DIFFERENT);
        label.setVisible(false);
        return label;
    }

    private static JButton createMetadataButton() {
        JButton button = new JButton();
        button.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        button.setForeground(TEXT);
        button.setBackground(SURFACE);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setIcon(new DropdownIcon());
        button.setHorizontalTextPosition(SwingConstants.LEFT);
        button.setIconTextGap(8);
        button.setBorder(new CompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(4, 8, 4, 8)));
        return button;
    }

    private static JLabel createLineEndingLabel() {
        JLabel label = new JLabel("LF", SwingConstants.CENTER);
        label.setOpaque(true);
        label.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 11));
        label.setForeground(MUTED);
        label.setBackground(HEADER);
        label.setBorder(new CompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(4, 8, 4, 8)));
        return label;
    }

    private static final class DropdownIcon implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(MUTED);
            graphics.fillPolygon(new int[]{x, x + 8, x + 4},
                    new int[]{y + 2, y + 2, y + 6}, 3);
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 8;
        }
    }

    private static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(UI_FONT_BOLD);
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(7, 14, 7, 14));
        return button;
    }

    private static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(UI_FONT);
        button.setForeground(TEXT);
        button.setBackground(SURFACE);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new CompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 12, 6, 12)));
        return button;
    }

    private final class SideTableModel extends AbstractTableModel {
        private final boolean leftSide;

        private SideTableModel(boolean leftSide) {
            this.leftSide = leftSide;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AlignedDiffRow row = rows.get(rowIndex);
            int lineIndex = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
            String text = leftSide ? row.getLeftText() : row.getRightText();
            return text == null ? "此处无对应内容" : text;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            AlignedDiffRow row = rows.get(rowIndex);
            int lineIndex = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
            return columnIndex == 0 && lineIndex >= 0 && appliedRevision == documentRevision;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                return;
            }
            AlignedDiffRow row = rows.get(rowIndex);
            int lineIndex = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
            updateLine(leftSide, lineIndex, value == null ? "" : value.toString());
        }
    }

    private final class LineNumberTableModel extends AbstractTableModel {
        private final boolean leftSide;

        private LineNumberTableModel(boolean leftSide) {
            this.leftSide = leftSide;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AlignedDiffRow row = rows.get(rowIndex);
            int lineIndex = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
            return lineIndex >= 0 ? Integer.toString(lineIndex + 1) : "";
        }
    }

    private final class RailTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DiffHunk hunk = hunkAtControlRow(rowIndex);
            return hunk == null ? null : Integer.valueOf(hunk.getId());
        }
    }

    private final class SideCellRenderer extends DefaultTableCellRenderer {
        private final boolean leftSide;

        private SideCellRenderer(boolean leftSide) {
            this.leftSide = leftSide;
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focused,
                                                       int rowIndex, int columnIndex) {
            super.getTableCellRendererComponent(table, value, selected, focused,
                    rowIndex, columnIndex);
            AlignedDiffRow row = rows.get(rowIndex);
            int lineIndex = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
            boolean placeholder = lineIndex < 0;
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(placeholder
                    ? new Font("Microsoft YaHei UI", Font.ITALIC, 12) : CODE_FONT);
            setForeground(placeholder ? MUTED : TEXT);
            setBorder(new EmptyBorder(0, 7, 0, 7));
            if (selected && !placeholder) {
                setBackground(new Color(219, 234, 254));
            } else if (placeholder) {
                setBackground(PLACEHOLDER_BACKGROUND);
            } else if (row.getStatus() == AlignedDiffRow.Status.SAME) {
                setBackground(SAME_BACKGROUND);
            } else {
                setBackground(DIFFERENT_BACKGROUND);
            }
            return this;
        }
    }

    private final class LineNumberCellRenderer extends DefaultTableCellRenderer {
        private final boolean leftSide;

        private LineNumberCellRenderer(boolean leftSide) {
            this.leftSide = leftSide;
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focused,
                                                       int rowIndex, int columnIndex) {
            super.getTableCellRendererComponent(table, value, selected, false,
                    rowIndex, columnIndex);
            AlignedDiffRow row = rows.get(rowIndex);
            int lineIndex = leftSide ? row.getLeftLineIndex() : row.getRightLineIndex();
            boolean placeholder = lineIndex < 0;
            setHorizontalAlignment(SwingConstants.RIGHT);
            setFont(new Font("Consolas", Font.PLAIN, 12));
            setForeground(MUTED);
            setBorder(new EmptyBorder(0, 4, 0, 8));
            if (selected && !placeholder) {
                setBackground(new Color(219, 234, 254));
            } else if (placeholder) {
                setBackground(PLACEHOLDER_BACKGROUND);
            } else if (row.getStatus() == AlignedDiffRow.Status.SAME) {
                setBackground(SAME_BACKGROUND);
            } else {
                setBackground(DIFFERENT_BACKGROUND);
            }
            return this;
        }
    }

    private final class RailCellRenderer extends JPanel
            implements javax.swing.table.TableCellRenderer {
        private final JLabel leftArrow = createArrowLabel("←");
        private final JLabel rightArrow = createArrowLabel("→");

        private RailCellRenderer() {
            super(new GridBagLayout());
            setOpaque(true);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridy = 0;
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.gridx = 0;
            add(leftArrow, gbc);
            gbc.gridx = 1;
            add(rightArrow, gbc);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focused,
                                                       int rowIndex, int columnIndex) {
            setBackground(HEADER);
            DiffHunk hunk = hunkAtControlRow(rowIndex);
            boolean visible = hunk != null;
            leftArrow.setVisible(visible);
            rightArrow.setVisible(visible);
            if (visible) {
                styleArrow(leftArrow, hunk.deletesWhenAppliedToLeft());
                styleArrow(rightArrow, hunk.deletesWhenAppliedToRight());
            }
            return this;
        }

        private JLabel createArrowLabel(String text) {
            JLabel label = new JLabel(text, SwingConstants.CENTER);
            label.setOpaque(true);
            label.setFont(new Font("SansSerif", Font.BOLD, 15));
            label.setPreferredSize(new Dimension(34, 22));
            return label;
        }

        private void styleArrow(JLabel label, boolean deletion) {
            label.setForeground(deletion ? DIFFERENT : PRIMARY_DARK);
            label.setBackground(deletion ? DIFFERENT_BACKGROUND : new Color(239, 246, 255));
            label.setBorder(BorderFactory.createLineBorder(deletion
                    ? new Color(239, 168, 168) : new Color(147, 197, 253)));
        }
    }

    private static final class SideHistory {
        private final Deque<SideState> undo = new ArrayDeque<SideState>();

        private void push(SideState state) {
            undo.push(state);
            while (undo.size() > HISTORY_LIMIT) {
                undo.removeLast();
            }
        }

        private SideState pop() {
            return undo.isEmpty() ? null : undo.pop();
        }

        private void clear() {
            undo.clear();
        }
    }

    private static final class SideState {
        private final LineDocument document;
        private final FileEncoding encoding;

        private SideState(LineDocument document, FileEncoding encoding) {
            this.document = document;
            this.encoding = encoding;
        }
    }

    private static final class DiffResult {
        private final List<DiffHunk> hunks;
        private final List<AlignedDiffRow> rows;

        private DiffResult(List<DiffHunk> hunks, List<AlignedDiffRow> rows) {
            this.hunks = hunks;
            this.rows = rows;
        }
    }

    private static final class ReloadBytes {
        private byte[] left;
        private byte[] right;
        private final long leftSize;
        private final long rightSize;
        private EncodingDetection leftDetection;
        private EncodingDetection rightDetection;
        private final DiffEditorLauncher.SizeDecision decision;
        private TextFileSnapshot leftSnapshot;
        private TextFileSnapshot rightSnapshot;

        private ReloadBytes(byte[] left, byte[] right, long leftSize, long rightSize,
                            EncodingDetection leftDetection,
                            EncodingDetection rightDetection,
                            DiffEditorLauncher.SizeDecision decision) {
            this.left = left;
            this.right = right;
            this.leftSize = leftSize;
            this.rightSize = rightSize;
            this.leftDetection = leftDetection;
            this.rightDetection = rightDetection;
            this.decision = decision;
        }

        private static ReloadBytes checked(long leftSize, long rightSize,
                                           DiffEditorLauncher.SizeDecision decision) {
            return new ReloadBytes(new byte[0], new byte[0], leftSize, rightSize,
                    null, null, decision);
        }
    }
}
