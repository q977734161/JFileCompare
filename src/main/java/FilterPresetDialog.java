import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

final class FilterPresetDialog {
    interface ApplyHandler {
        void apply(FilterRuleSet rules, String basePresetId);
    }

    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(218, 225, 232);
    private static final Color HEADER = new Color(247, 249, 251);
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color PRIMARY_DARK = new Color(29, 78, 216);
    private static final Color ERROR = new Color(214, 69, 69);
    private static final String SEARCH_PLACEHOLDER = "搜索预设名称或规则";
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private static final Font FONT_BOLD = new Font("Microsoft YaHei UI", Font.BOLD, 13);

    private final JDialog dialog;
    private final FilterPresetService service;
    private final ApplyHandler applyHandler;
    private final boolean pathsReady;
    private final DefaultListModel<PresetChoice> choiceModel = new DefaultListModel<PresetChoice>();
    private final JList<PresetChoice> presetList = new JList<PresetChoice>(choiceModel);
    private final JTextArea directoryArea = new JTextArea(3, 44);
    private final JTextArea extensionArea = new JTextArea(3, 44);
    private final JTextArea wildcardArea = new JTextArea(3, 44);
    private final JLabel titleLabel = new JLabel();
    private final JLabel sourceLabel = new JLabel();
    private final JLabel summaryLabel = new JLabel();
    private final JLabel validationLabel = new JLabel(" ");
    private final JButton restoreButton = new JButton("恢复预设");
    private final JButton updateButton = new JButton("更新预设");
    private final JButton saveAsButton = new JButton("另存为预设");
    private final JButton applyButton;
    private String basePresetId;
    private FilterRuleSet selectionBaseline = FilterRuleSet.empty();
    private boolean loadingDraft;
    private int acceptedSelection = -1;

    private FilterPresetDialog(JFrame owner, FilterPresetService service,
                               ActiveFilterState current, boolean pathsReady,
                               ApplyHandler applyHandler) {
        this.service = service;
        this.applyHandler = applyHandler;
        this.pathsReady = pathsReady;
        this.dialog = new JDialog(owner, "过滤和排除规则", true);
        this.applyButton = new JButton(pathsReady ? "应用并重新对比" : "保存规则");
        buildUi();
        reloadChoices(current.basePresetId());
        loadDraft(current.rules(), current.basePresetId());
        dialog.setMinimumSize(new Dimension(760, 540));
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(owner);
    }

    static void show(JFrame owner, FilterPresetService service, ActiveFilterState current,
                     boolean pathsReady, ApplyHandler applyHandler) {
        new FilterPresetDialog(owner, service, current, pathsReady, applyHandler).dialog.setVisible(true);
    }

    static void showManager(Window owner, FilterPresetService service, FilterRuleSet currentDraft,
                            Runnable onChanged) {
        new PresetManagerDialog(owner, service, currentDraft, onChanged).show();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createLineBorder(BORDER));
        root.add(createHeader(), BorderLayout.NORTH);
        root.add(createBody(), BorderLayout.CENTER);
        root.add(createFooter(), BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(applyButton);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    }

    private JComponent createHeader() {
        JLabel title = new JLabel("过滤和排除规则");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("规则会在目录发现和 Hash 之前生效");
        subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        subtitle.setForeground(MUTED);
        JPanel labels = new JPanel(new BorderLayout());
        labels.setOpaque(false);
        labels.add(title, BorderLayout.CENTER);
        labels.add(subtitle, BorderLayout.SOUTH);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(16, 22, 16, 22)));
        header.add(labels, BorderLayout.WEST);
        return header;
    }

    private JComponent createBody() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(SURFACE);
        body.add(createPresetSidebar(), BorderLayout.WEST);
        body.add(createEditor(), BorderLayout.CENTER);
        return body;
    }

    private JComponent createPresetSidebar() {
        presetList.setFont(FONT);
        presetList.setBackground(HEADER);
        presetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        presetList.setFixedCellHeight(48);
        presetList.setCellRenderer(new PresetChoiceRenderer());
        presetList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        presetList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || loadingDraft) {
                return;
            }
            int selected = presetList.getSelectedIndex();
            if (selected < 0 || selected == acceptedSelection) {
                return;
            }
            PresetChoice next = choiceModel.get(selected);
            if (draftHasUnappliedChanges() && !confirmDiscardDraft()) {
                loadingDraft = true;
                presetList.setSelectedIndex(acceptedSelection);
                loadingDraft = false;
                return;
            }
            acceptedSelection = selected;
            if (next.preset == null) {
                basePresetId = null;
                updateDraftState();
            } else {
                loadDraft(next.preset.rules(), next.preset.id());
            }
        });

        JScrollPane scroll = new JScrollPane(presetList);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(224, 500));
        JButton manage = new JButton("管理预设");
        styleSecondary(manage);
        manage.addActionListener(e -> showManager(dialog, service, parseDraftOrNull(), new Runnable() {
            @Override
            public void run() {
                reloadChoices(basePresetId);
                updateDraftState();
            }
        }));
        JPanel action = new JPanel(new BorderLayout());
        action.setBackground(HEADER);
        action.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        action.add(manage, BorderLayout.CENTER);
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(HEADER);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER));
        sidebar.add(scroll, BorderLayout.CENTER);
        sidebar.add(action, BorderLayout.SOUTH);
        return sidebar;
    }

    private JComponent createEditor() {
        JPanel editor = new JPanel(new GridBagLayout());
        editor.setBackground(SURFACE);
        editor.setBorder(BorderFactory.createEmptyBorder(12, 20, 10, 20));
        titleLabel.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 17));
        titleLabel.setForeground(TEXT);
        sourceLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        sourceLabel.setForeground(MUTED);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(titleLabel, BorderLayout.NORTH);
        heading.add(sourceLabel, BorderLayout.SOUTH);

        styleText(restoreButton);
        styleSecondary(updateButton);
        styleSecondary(saveAsButton);
        JPanel headingActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        headingActions.setOpaque(false);
        headingActions.add(restoreButton);
        headingActions.add(updateButton);
        headingActions.add(saveAsButton);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        editor.add(heading, gbc);
        gbc.gridx = 1;
        gbc.weightx = 0.0;
        editor.add(headingActions, gbc);

        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.gridy = 1;
        gbc.insets = new Insets(12, 0, 0, 0);
        editor.add(createRuleEditor("排除目录名", directoryArea,
                "命中目录名后跳过整个目录及其子内容"), gbc);
        gbc.gridy = 2;
        gbc.insets = new Insets(8, 0, 0, 0);
        editor.add(createRuleEditor("排除文件扩展名", extensionArea,
                "自动补前导点，匹配不区分大小写"), gbc);
        gbc.gridy = 3;
        editor.add(createRuleEditor("排除通配符", wildcardArea,
                "支持 * 和 ?，匹配相对路径或文件名"), gbc);

        summaryLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        summaryLabel.setForeground(MUTED);
        summaryLabel.setOpaque(true);
        summaryLabel.setBackground(HEADER);
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(11, 13, 11, 13));
        gbc.gridy = 4;
        gbc.insets = new Insets(10, 0, 0, 0);
        editor.add(summaryLabel, gbc);
        validationLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        validationLabel.setForeground(ERROR);
        gbc.gridy = 5;
        gbc.insets = new Insets(4, 0, 0, 0);
        editor.add(validationLabel, gbc);
        gbc.gridy = 6;
        gbc.weighty = 1.0;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        editor.add(spacer, gbc);

        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateDraftState(); }
            @Override public void removeUpdate(DocumentEvent e) { updateDraftState(); }
            @Override public void changedUpdate(DocumentEvent e) { updateDraftState(); }
        };
        directoryArea.getDocument().addDocumentListener(listener);
        extensionArea.getDocument().addDocumentListener(listener);
        wildcardArea.getDocument().addDocumentListener(listener);
        restoreButton.addActionListener(e -> restoreBasePreset());
        saveAsButton.addActionListener(e -> saveAsPreset());
        updateButton.addActionListener(e -> updateSelectedPreset());
        return editor;
    }

    private JComponent createRuleEditor(String label, JTextArea area, String hint) {
        JLabel title = new JLabel(label);
        title.setFont(FONT_BOLD);
        title.setForeground(TEXT);
        styleArea(area);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.setPreferredSize(new Dimension(500, 50));
        JLabel help = new JLabel(hint + "；支持逗号、分号或换行");
        help.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        help.setForeground(MUTED);
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        panel.add(title, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(help, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createFooter() {
        JButton clear = new JButton("清空规则");
        JButton cancel = new JButton("取消");
        styleText(clear);
        styleText(cancel);
        stylePrimary(applyButton);
        clear.addActionListener(e -> clearDraft());
        cancel.addActionListener(e -> dialog.dispose());
        applyButton.addActionListener(e -> applyDraft());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(cancel);
        right.add(applyButton);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        footer.add(clear, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void reloadChoices(String selectId) {
        loadingDraft = true;
        choiceModel.clear();
        for (FilterPreset preset : service.allPresets()) {
            choiceModel.addElement(new PresetChoice(preset));
        }
        choiceModel.addElement(new PresetChoice(null));
        int selected = choiceModel.size() - 1;
        if (selectId != null) {
            for (int i = 0; i < choiceModel.size(); i++) {
                FilterPreset preset = choiceModel.get(i).preset;
                if (preset != null && preset.id().equals(selectId)) {
                    selected = i;
                    break;
                }
            }
        }
        acceptedSelection = selected;
        presetList.setSelectedIndex(selected);
        loadingDraft = false;
    }

    private void loadDraft(FilterRuleSet rules, String presetId) {
        loadingDraft = true;
        basePresetId = presetId;
        selectionBaseline = rules;
        directoryArea.setText(rules.directoryText());
        extensionArea.setText(rules.extensionText());
        wildcardArea.setText(rules.wildcardText());
        loadingDraft = false;
        reloadSelectionForBase();
        updateDraftState();
    }

    private void reloadSelectionForBase() {
        int selected = choiceModel.size() - 1;
        if (basePresetId != null) {
            for (int i = 0; i < choiceModel.size(); i++) {
                FilterPreset preset = choiceModel.get(i).preset;
                if (preset != null && preset.id().equals(basePresetId)) {
                    selected = i;
                    break;
                }
            }
        }
        loadingDraft = true;
        acceptedSelection = selected;
        presetList.setSelectedIndex(selected);
        loadingDraft = false;
    }

    private void updateDraftState() {
        if (loadingDraft) {
            return;
        }
        FilterRuleSet rules = parseDraftOrNull();
        FilterPreset base = service.findPreset(basePresetId);
        boolean modified = rules != null && base != null && !base.rules().equals(rules);
        String name;
        if (rules == null) {
            name = base == null ? "自定义规则" : base.name();
        } else if (rules.isEmpty()) {
            name = "未设置";
        } else if (base == null) {
            name = "自定义规则";
        } else {
            name = base.name() + (modified ? "（已修改）" : "");
        }
        titleLabel.setText(name);
        sourceLabel.setText(modified ? "当前输入尚未应用，应用不会修改原预设"
                : base == null ? "当前规则没有绑定预设" : "当前规则来自所选预设");
        restoreButton.setVisible(base != null && modified);
        updateButton.setVisible(base != null && !base.isBuiltIn() && modified);
        if (rules == null) {
            summaryLabel.setText("请修正规则后再应用");
            applyButton.setEnabled(false);
        } else {
            summaryLabel.setText(rules.summaryText());
            validationLabel.setText(" ");
            applyButton.setEnabled(true);
        }
    }

    private FilterRuleSet parseDraftOrNull() {
        try {
            FilterRuleSet rules = FilterRuleSet.fromText(directoryArea.getText(),
                    extensionArea.getText(), wildcardArea.getText());
            validationLabel.setText(" ");
            return rules;
        } catch (IllegalArgumentException ex) {
            validationLabel.setText(ex.getMessage());
            return null;
        }
    }

    private boolean draftHasUnappliedChanges() {
        FilterRuleSet draft = parseDraftOrNull();
        return draft == null || !selectionBaseline.equals(draft);
    }

    private void clearDraft() {
        loadingDraft = true;
        basePresetId = null;
        directoryArea.setText("");
        extensionArea.setText("");
        wildcardArea.setText("");
        loadingDraft = false;
        reloadSelectionForBase();
        updateDraftState();
    }

    private boolean confirmDiscardDraft() {
        return JOptionPane.showConfirmDialog(dialog,
                "当前输入尚未应用，切换预设会覆盖这些修改。是否继续？",
                "切换预设", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    private void restoreBasePreset() {
        FilterPreset base = service.findPreset(basePresetId);
        if (base != null) {
            loadDraft(base.rules(), base.id());
        }
    }

    private void applyDraft() {
        FilterRuleSet rules = parseDraftOrNull();
        if (rules == null) {
            return;
        }
        applyHandler.apply(rules, basePresetId);
        dialog.dispose();
    }

    private void saveAsPreset() {
        final FilterRuleSet rules = parseDraftOrNull();
        if (rules == null) {
            return;
        }
        showSaveDialog(dialog, service, rules, new PresetSaved() {
            @Override
            public void saved(FilterPreset preset) {
                reloadChoices(preset.id());
                loadDraft(rules, preset.id());
            }
        });
    }

    private void updateSelectedPreset() {
        final FilterPreset preset = service.findPreset(basePresetId);
        final FilterRuleSet rules = parseDraftOrNull();
        if (preset == null || preset.isBuiltIn() || rules == null) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(dialog,
                "使用当前规则更新自定义预设“" + preset.name() + "”？",
                "更新预设", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        runPresetTask(dialog, new PresetTask() {
            @Override
            public FilterPreset run() throws Exception {
                service.updatePreset(preset.id(), rules);
                return service.findPreset(preset.id());
            }
        }, new PresetSaved() {
            @Override
            public void saved(FilterPreset updated) {
                reloadChoices(updated.id());
                loadDraft(updated.rules(), updated.id());
            }
        });
    }

    private static void showSaveDialog(Window owner, final FilterPresetService service,
                                       final FilterRuleSet rules, final PresetSaved callback) {
        final JDialog save = new JDialog(owner, "另存为自定义预设",
                JDialog.ModalityType.APPLICATION_MODAL);
        final JTextField name = new JTextField();
        styleField(name);
        JLabel hint = new JLabel("1 - 30 个字符，自定义预设名称不能重复");
        hint.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        hint.setForeground(MUTED);
        JLabel summary = new JLabel("目录 " + rules.directoryCount() + " 条 · 扩展名 "
                + rules.extensionCount() + " 条 · 通配符 " + rules.wildcardCount() + " 条");
        summary.setOpaque(true);
        summary.setBackground(HEADER);
        summary.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        summary.setFont(FONT_BOLD);
        summary.setForeground(TEXT);
        JLabel error = new JLabel(" ");
        error.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        error.setForeground(ERROR);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(SURFACE);
        form.setBorder(BorderFactory.createEmptyBorder(18, 24, 12, 24));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        JLabel label = new JLabel("预设名称");
        label.setFont(FONT_BOLD);
        form.add(label, gbc);
        gbc.gridy = 1;
        gbc.insets = new Insets(6, 0, 0, 0);
        form.add(name, gbc);
        gbc.gridy = 2;
        gbc.insets = new Insets(5, 0, 0, 0);
        form.add(hint, gbc);
        gbc.gridy = 3;
        gbc.insets = new Insets(18, 0, 0, 0);
        form.add(summary, gbc);
        gbc.gridy = 4;
        gbc.insets = new Insets(5, 0, 0, 0);
        form.add(error, gbc);

        JButton cancel = new JButton("取消");
        JButton saveButton = new JButton("保存预设");
        styleText(cancel);
        stylePrimary(saveButton);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        actions.setBackground(SURFACE);
        actions.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
        actions.add(cancel);
        actions.add(saveButton);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(SURFACE);
        JLabel title = new JLabel("另存为自定义预设");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 18));
        JLabel subtitle = new JLabel("保存用于以后复用，返回规则窗口后仍需点击应用");
        subtitle.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        subtitle.setForeground(MUTED);
        JPanel saveHeader = new JPanel(new BorderLayout(0, 3));
        saveHeader.setBackground(SURFACE);
        saveHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(16, 24, 16, 24)));
        saveHeader.add(title, BorderLayout.NORTH);
        saveHeader.add(subtitle, BorderLayout.SOUTH);
        root.add(saveHeader, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        save.setContentPane(root);
        save.getRootPane().setDefaultButton(saveButton);
        save.setSize(520, 390);
        save.setLocationRelativeTo(owner);
        cancel.addActionListener(e -> save.dispose());
        saveButton.addActionListener(e -> {
            final String presetName = name.getText().trim();
            final FilterPreset duplicate = service.findCustomByName(presetName);
            if (duplicate != null) {
                int answer = JOptionPane.showConfirmDialog(save,
                        "自定义预设“" + duplicate.name() + "”已存在，是否使用当前规则更新它？",
                        "更新已有预设", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
                runPresetTask(save, new PresetTask() {
                    @Override public FilterPreset run() throws Exception {
                        service.updatePreset(duplicate.id(), rules);
                        return service.findPreset(duplicate.id());
                    }
                }, new PresetSaved() {
                    @Override public void saved(FilterPreset preset) {
                        save.dispose();
                        callback.saved(preset);
                    }
                });
                return;
            }
            runPresetTask(save, new PresetTask() {
                @Override public FilterPreset run() throws Exception {
                    return service.createPreset(presetName, rules);
                }
            }, new PresetSaved() {
                @Override public void saved(FilterPreset preset) {
                    save.dispose();
                    callback.saved(preset);
                }
            }, error);
        });
        save.setVisible(true);
    }

    private static void runPresetTask(final Window owner, final PresetTask task,
                                      final PresetSaved callback) {
        runPresetTask(owner, task, callback, null);
    }

    private static void runPresetTask(final Window owner, final PresetTask task,
                                      final PresetSaved callback, final JLabel inlineError) {
        new SwingWorker<FilterPreset, Void>() {
            @Override protected FilterPreset doInBackground() throws Exception { return task.run(); }
            @Override protected void done() {
                try {
                    callback.saved(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    String message = rootMessage(ex);
                    if (inlineError != null) {
                        inlineError.setText(message);
                    } else {
                        JOptionPane.showMessageDialog(owner, message, "预设操作失败",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }.execute();
    }

    private static final class PresetManagerDialog {
        private final JDialog dialog;
        private final FilterPresetService service;
        private final FilterRuleSet currentDraft;
        private final Runnable onChanged;
        private final PresetTableModel model = new PresetTableModel();
        private final JTable table = new JTable(model);
        private final JTextField search = new JTextField();

        PresetManagerDialog(Window owner, FilterPresetService service,
                            FilterRuleSet currentDraft, Runnable onChanged) {
            this.service = service;
            this.currentDraft = currentDraft;
            this.onChanged = onChanged;
            this.dialog = new JDialog(owner, "过滤预设管理", JDialog.ModalityType.MODELESS);
            buildUi();
        }

        void show() {
            reload();
            dialog.setSize(900, 580);
            dialog.setMinimumSize(new Dimension(760, 500));
            dialog.setLocationRelativeTo(dialog.getOwner());
            dialog.setVisible(true);
        }

        private void buildUi() {
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(SURFACE);
            JLabel title = new JLabel("过滤预设管理");
            title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 20));
            title.setForeground(TEXT);
            title.setBorder(BorderFactory.createEmptyBorder(18, 22, 12, 22));
            root.add(title, BorderLayout.NORTH);

            styleField(search);
            installSearchPlaceholder(search);
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { reload(); }
                @Override public void removeUpdate(DocumentEvent e) { reload(); }
                @Override public void changedUpdate(DocumentEvent e) { reload(); }
            });
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setRowHeight(48);
            table.setShowGrid(false);
            table.setFont(FONT);
            table.getTableHeader().setFont(FONT_BOLD);
            table.getTableHeader().setBackground(HEADER);
            table.setDefaultRenderer(Object.class, new PresetTableRenderer());
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createLineBorder(BORDER));
            JPanel center = new JPanel(new BorderLayout(0, 10));
            center.setBackground(SURFACE);
            center.setBorder(BorderFactory.createEmptyBorder(0, 22, 12, 22));
            JLabel searchLabel = new JLabel("搜索");
            searchLabel.setFont(FONT_BOLD);
            searchLabel.setForeground(TEXT);
            searchLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
            JPanel searchBar = new JPanel(new BorderLayout());
            searchBar.setOpaque(false);
            searchBar.add(searchLabel, BorderLayout.WEST);
            searchBar.add(search, BorderLayout.CENTER);
            center.add(searchBar, BorderLayout.NORTH);
            center.add(scroll, BorderLayout.CENTER);
            root.add(center, BorderLayout.CENTER);

            JButton rename = new JButton("重命名");
            JButton update = new JButton("使用当前规则更新");
            JButton delete = new JButton("删除");
            JButton create = new JButton("新建预设");
            JButton close = new JButton("关闭");
            styleSecondary(rename);
            styleSecondary(update);
            styleText(delete);
            delete.setForeground(ERROR);
            styleSecondary(create);
            stylePrimary(close);
            rename.addActionListener(e -> renameSelected());
            update.addActionListener(e -> updateSelected());
            delete.addActionListener(e -> deleteSelected());
            create.addActionListener(e -> createPreset());
            close.addActionListener(e -> dialog.dispose());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(rename);
            left.add(update);
            left.add(delete);
            JPanel footer = new JPanel(new BorderLayout());
            footer.setBackground(SURFACE);
            footer.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                    BorderFactory.createEmptyBorder(10, 22, 10, 22)));
            footer.add(left, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            right.add(create);
            right.add(close);
            footer.add(right, BorderLayout.EAST);
            root.add(footer, BorderLayout.SOUTH);
            dialog.setContentPane(root);
        }

        private void reload() {
            if (model == null) {
                return;
            }
            String searchText = search.getText() == null ? "" : search.getText().trim();
            String query = SEARCH_PLACEHOLDER.equals(searchText) ? ""
                    : searchText.toLowerCase(Locale.ROOT);
            List<FilterPreset> values = new ArrayList<FilterPreset>();
            for (FilterPreset preset : service.allPresets()) {
                String searchable = (preset.name() + " " + preset.rules().directoryText() + " "
                        + preset.rules().extensionText() + " " + preset.rules().wildcardText())
                        .toLowerCase(Locale.ROOT);
                if (query.isEmpty() || searchable.contains(query)) {
                    values.add(preset);
                }
            }
            model.setPresets(values);
        }

        private FilterPreset selectedCustom() {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(dialog, "请先选择一个自定义预设。",
                        "过滤预设管理", JOptionPane.INFORMATION_MESSAGE);
                return null;
            }
            FilterPreset preset = model.get(row);
            if (preset.isBuiltIn()) {
                JOptionPane.showMessageDialog(dialog, "内置预设为只读，不能执行此操作。",
                        "过滤预设管理", JOptionPane.INFORMATION_MESSAGE);
                return null;
            }
            return preset;
        }

        private void renameSelected() {
            final FilterPreset preset = selectedCustom();
            if (preset == null) return;
            String name = JOptionPane.showInputDialog(dialog, "新的预设名称：", preset.name());
            if (name == null) return;
            final String nextName = name;
            runPresetTask(dialog, new PresetTask() {
                @Override public FilterPreset run() throws Exception {
                    service.renamePreset(preset.id(), nextName);
                    return service.findPreset(preset.id());
                }
            }, new PresetSaved() {
                @Override public void saved(FilterPreset ignored) { changed(); }
            });
        }

        private void createPreset() {
            if (currentDraft == null) {
                JOptionPane.showMessageDialog(dialog,
                        "当前规则存在校验错误，不能保存为预设。",
                        "新建预设", JOptionPane.WARNING_MESSAGE);
                return;
            }
            showSaveDialog(dialog, service, currentDraft, new PresetSaved() {
                @Override
                public void saved(FilterPreset ignored) {
                    changed();
                }
            });
        }

        private void updateSelected() {
            final FilterPreset preset = selectedCustom();
            if (preset == null || currentDraft == null) return;
            int answer = JOptionPane.showConfirmDialog(dialog,
                    "使用规则编辑窗口中的当前内容更新“" + preset.name() + "”？",
                    "更新预设", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            runPresetTask(dialog, new PresetTask() {
                @Override public FilterPreset run() throws Exception {
                    service.updatePreset(preset.id(), currentDraft);
                    return service.findPreset(preset.id());
                }
            }, new PresetSaved() {
                @Override public void saved(FilterPreset ignored) { changed(); }
            });
        }

        private void deleteSelected() {
            final FilterPreset preset = selectedCustom();
            if (preset == null) return;
            int answer = JOptionPane.showConfirmDialog(dialog,
                    "删除自定义预设“" + preset.name() + "”？\n已应用的规则快照不会被清空。",
                    "删除预设", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            runPresetTask(dialog, new PresetTask() {
                @Override public FilterPreset run() throws Exception {
                    service.deletePreset(preset.id());
                    return preset;
                }
            }, new PresetSaved() {
                @Override public void saved(FilterPreset ignored) { changed(); }
            });
        }

        private void changed() {
            reload();
            if (onChanged != null) onChanged.run();
        }
    }

    private static final class PresetTableModel extends AbstractTableModel {
        private final String[] columns = {"预设", "类型", "目录", "扩展名", "通配符", "更新时间"};
        private List<FilterPreset> presets = new ArrayList<FilterPreset>();

        void setPresets(List<FilterPreset> values) {
            presets = values;
            fireTableDataChanged();
        }

        FilterPreset get(int row) { return presets.get(row); }
        @Override public int getRowCount() { return presets.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            FilterPreset preset = presets.get(row);
            switch (column) {
                case 0: return preset.name();
                case 1: return preset.isBuiltIn() ? "内置 · 只读" : "自定义";
                case 2: return preset.rules().directoryCount();
                case 3: return preset.rules().extensionCount();
                case 4: return preset.rules().wildcardCount();
                default: return preset.isBuiltIn() ? "随应用发布"
                        : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                        .format(new Date(preset.updatedTime()));
            }
        }
    }

    private static final class PresetChoice {
        final FilterPreset preset;
        PresetChoice(FilterPreset preset) { this.preset = preset; }
    }

    private static final class PresetChoiceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            PresetChoice choice = (PresetChoice) value;
            label.setText(choice.preset == null ? "自定义规则"
                    : choice.preset.name() + "  ·  " + choice.preset.rules().totalCount() + " 条");
            label.setFont(choice.preset != null && choice.preset.isBuiltIn() ? FONT_BOLD : FONT);
            label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8));
            label.setBackground(selected ? new Color(239, 246, 255) : HEADER);
            label.setForeground(selected ? PRIMARY : TEXT);
            return label;
        }
    }

    private static final class PresetTableRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                        boolean selected, boolean focus,
                                                        int row, int column) {
            Component component = super.getTableCellRendererComponent(
                    table, value, selected, focus, row, column);
            component.setBackground(selected ? new Color(239, 246, 255) : SURFACE);
            component.setForeground(TEXT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            if (column >= 2 && column <= 4) {
                setHorizontalAlignment(SwingConstants.CENTER);
            } else {
                setHorizontalAlignment(SwingConstants.LEFT);
            }
            return component;
        }
    }

    private interface PresetTask { FilterPreset run() throws Exception; }
    private interface PresetSaved { void saved(FilterPreset preset); }

    private static void styleArea(JTextArea area) {
        area.setFont(FONT);
        area.setForeground(TEXT);
        area.setBackground(SURFACE);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
    }

    private static void styleField(JTextComponent field) {
        field.setFont(FONT);
        field.setForeground(TEXT);
        field.setBackground(SURFACE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    private static void stylePrimary(JButton button) {
        styleButton(button, PRIMARY, SURFACE, PRIMARY);
    }

    private static void styleSecondary(JButton button) {
        styleButton(button, SURFACE, TEXT, new Color(184, 196, 209));
    }

    private static void styleButton(JButton button, Color background, Color foreground, Color border) {
        button.setUI(new BasicButtonUI());
        button.setFont(FONT_BOLD);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static void styleText(JButton button) {
        button.setUI(new BasicButtonUI());
        button.setFont(FONT);
        button.setForeground(MUTED);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static void installSearchPlaceholder(final JTextField field) {
        field.setText(SEARCH_PLACEHOLDER);
        field.setForeground(MUTED);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                if (SEARCH_PLACEHOLDER.equals(field.getText())) {
                    field.setText("");
                    field.setForeground(TEXT);
                }
            }

            @Override
            public void focusLost(FocusEvent event) {
                if (field.getText().trim().isEmpty()) {
                    field.setText(SEARCH_PLACEHOLDER);
                    field.setForeground(MUTED);
                }
            }
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
