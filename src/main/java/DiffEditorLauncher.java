import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

final class DiffEditorLauncher {
    enum SizeDecision { ALLOW, CONFIRM, REJECT }

    private DiffEditorLauncher() {
    }

    static void open(JFrame owner, String relativePath, Path leftPath, Path rightPath,
                     Runnable savedCallback, PreferencesService preferencesService) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("差异编辑器必须从 Swing EDT 打开");
        }
        try {
            long leftSize = existingSize(leftPath);
            long rightSize = existingSize(rightPath);
            SizeDecision decision = sizeDecision(leftSize, rightSize);
            if (decision == SizeDecision.REJECT) {
                JOptionPane.showMessageDialog(owner,
                        "至少一侧文件超过 100 MB，不能进入可编辑差异视图。\n"
                                + sizeSummary(leftPath, leftSize, rightPath, rightSize)
                                + "\n请使用外部大文件工具，或缩小文件后重试。",
                        "文件过大", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (decision == SizeDecision.CONFIRM) {
                int answer = JOptionPane.showConfirmDialog(owner,
                        "至少一侧文件超过 20 MB，加载和差异计算可能占用较多内存。\n"
                                + sizeSummary(leftPath, leftSize, rightPath, rightSize)
                                + "\n是否继续？",
                        "打开大文件", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) {
                    return;
                }
            }
            loadRaw(owner, relativePath, leftPath, rightPath, savedCallback,
                    preferencesService);
        } catch (SecurityException ex) {
            showError(owner, "无法检查文件大小：" + ex.getMessage());
        } catch (IOException ex) {
            showError(owner, "无法检查文件大小：" + ex.getMessage());
        }
    }

    static SizeDecision sizeDecision(long leftSize, long rightSize) {
        long maximum = Math.max(leftSize, rightSize);
        if (maximum > AppInfo.HARD_EDITABLE_FILE_BYTES) {
            return SizeDecision.REJECT;
        }
        if (maximum > AppInfo.SOFT_EDITABLE_FILE_BYTES) {
            return SizeDecision.CONFIRM;
        }
        return SizeDecision.ALLOW;
    }

    private static void loadRaw(final JFrame owner, final String relativePath,
                                final Path leftPath, final Path rightPath,
                                final Runnable savedCallback,
                                final PreferencesService preferencesService) {
        final LoadingDialog loading = new LoadingDialog(owner);
        final SwingWorker<RawPair, Void> worker = new SwingWorker<RawPair, Void>() {
            @Override protected RawPair doInBackground() throws Exception {
                RawFile left = read(leftPath);
                if (isCancelled()) throw new CancellationException();
                RawFile right = read(rightPath);
                if (isCancelled()) throw new CancellationException();
                return new RawPair(left, right);
            }

            @Override protected void done() {
                if (isCancelled()) {
                    loading.dispose();
                    return;
                }
                try {
                    RawPair raw = get();
                    FileEncoding leftEncoding = DiffEditorFrame.chooseEncodingForOpen(
                            owner, raw.left.path, raw.left.bytes, raw.left.detection);
                    if (leftEncoding == null) {
                        loading.dispose();
                        return;
                    }
                    FileEncoding rightEncoding = DiffEditorFrame.chooseEncodingForOpen(
                            owner, raw.right.path, raw.right.bytes, raw.right.detection);
                    if (rightEncoding == null) {
                        loading.dispose();
                        return;
                    }
                    loading.setStage("正在解码文件...");
                    decode(owner, relativePath, raw, leftEncoding, rightEncoding,
                            savedCallback, preferencesService, loading);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    loading.dispose();
                } catch (CancellationException ex) {
                    loading.dispose();
                } catch (ExecutionException ex) {
                    loading.dispose();
                    showError(owner, "无法读取文件：" + rootMessage(ex));
                }
            }
        };
        loading.setCancelAction(() -> worker.cancel(true));
        worker.execute();
        loading.setVisible(true);
    }

    private static void decode(final JFrame owner, final String relativePath,
                               final RawPair raw, final FileEncoding leftEncoding,
                               final FileEncoding rightEncoding, final Runnable savedCallback,
                               final PreferencesService preferencesService,
                               final LoadingDialog loading) {
        final SwingWorker<SnapshotPair, Void> worker =
                new SwingWorker<SnapshotPair, Void>() {
            @Override protected SnapshotPair doInBackground() throws Exception {
                TextFileCodec codec = new TextFileCodec();
                TextFileSnapshot left = codec.decode(raw.left.path, raw.left.existed,
                        raw.left.bytes, leftEncoding);
                if (isCancelled()) throw new CancellationException();
                TextFileSnapshot right = codec.decode(raw.right.path, raw.right.existed,
                        raw.right.bytes, rightEncoding);
                if (isCancelled()) throw new CancellationException();
                return new SnapshotPair(left, right);
            }

            @Override protected void done() {
                loading.dispose();
                if (isCancelled()) return;
                try {
                    SnapshotPair snapshots = get();
                    DiffEditorFrame editor = new DiffEditorFrame(owner, relativePath,
                            raw.left.path, raw.right.path, savedCallback, preferencesService,
                            snapshots.left, snapshots.right);
                    editor.setVisible(true);
                    editor.calculateInitialDiffInBackground();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (CancellationException ex) {
                    return;
                } catch (ExecutionException ex) {
                    showError(owner, "无法解码文件：" + rootMessage(ex));
                } catch (IOException ex) {
                    showError(owner, "无法创建差异编辑器：" + ex.getMessage());
                }
            }
        };
        loading.setCancelAction(() -> worker.cancel(true));
        worker.execute();
    }

    private static RawFile read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new RawFile(path, false, new byte[0],
                    new TextFileCodec().detect(new byte[0]));
        }
        byte[] bytes = Files.readAllBytes(path);
        return new RawFile(path, true, bytes, new TextFileCodec().detect(bytes));
    }

    private static long existingSize(Path path) throws IOException {
        return Files.exists(path) ? Files.size(path) : 0L;
    }

    private static String sizeSummary(Path leftPath, long leftSize,
                                      Path rightPath, long rightSize) {
        return "左侧：" + leftPath.getFileName() + "（" + formatSize(leftSize) + "）\n"
                + "右侧：" + rightPath.getFileName() + "（" + formatSize(rightSize) + "）";
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB",
                    bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024d);
        }
        return bytes + " B";
    }

    private static void showError(JFrame owner, String message) {
        JOptionPane.showMessageDialog(owner, message, "打开文件失败",
                JOptionPane.ERROR_MESSAGE);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static final class RawFile {
        private final Path path;
        private final boolean existed;
        private final byte[] bytes;
        private final EncodingDetection detection;

        private RawFile(Path path, boolean existed, byte[] bytes,
                        EncodingDetection detection) {
            this.path = path;
            this.existed = existed;
            this.bytes = bytes;
            this.detection = detection;
        }
    }

    private static final class RawPair {
        private final RawFile left;
        private final RawFile right;

        private RawPair(RawFile left, RawFile right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class SnapshotPair {
        private final TextFileSnapshot left;
        private final TextFileSnapshot right;

        private SnapshotPair(TextFileSnapshot left, TextFileSnapshot right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class LoadingDialog extends JDialog {
        private final JLabel stage = new JLabel("正在读取和检测文件编码...",
                SwingConstants.CENTER);
        private Runnable cancelAction;

        private LoadingDialog(JFrame owner) {
            super(owner, "正在打开文件", Dialog.ModalityType.MODELESS);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            stage.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
            stage.setForeground(new Color(31, 41, 55));
            JProgressBar progress = new JProgressBar();
            progress.setIndeterminate(true);
            JButton cancel = new JButton("取消");
            cancel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
            cancel.addActionListener(e -> cancel());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            actions.setOpaque(false);
            actions.add(cancel);
            JPanel content = new JPanel(new BorderLayout(0, 14));
            content.setBackground(Color.WHITE);
            content.setBorder(new EmptyBorder(18, 20, 14, 20));
            content.add(stage, BorderLayout.NORTH);
            content.add(progress, BorderLayout.CENTER);
            content.add(actions, BorderLayout.SOUTH);
            content.setPreferredSize(new Dimension(400, 120));
            setContentPane(content);
            pack();
            setResizable(false);
            setLocationRelativeTo(owner);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) {
                    cancel();
                }
            });
        }

        private void setCancelAction(Runnable action) {
            cancelAction = action;
        }

        private void setStage(String text) {
            stage.setText(text);
        }

        private void cancel() {
            if (cancelAction != null) cancelAction.run();
            dispose();
        }
    }
}
