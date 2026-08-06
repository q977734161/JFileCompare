import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class HistoryVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "history-ui-test");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "history-");
        final HistoryRepository repository = new HistoryRepository(temp.resolve("history.xml"));
        repository.update(latest -> seedEntries(temp));
        final CompareHistoryService history = new CompareHistoryService(repository);
        final FilterPresetService filters = new FilterPresetService(
                new FilterConfigRepository(temp.resolve("filter-config.xml")));
        final PreferencesService preferences = new PreferencesService(
                new PreferencesRepository(temp.resolve("preferences.xml")));
        final FileCompareTool[] frame = new FileCompareTool[1];

        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new FileCompareTool(filters, history, preferences);
            frame[0].setSize(1180, 760);
            frame[0].setVisible(true);
        });
        Thread.sleep(250L);
        if (findComponent(frame[0], JLabel.class, "最近对比") == null) {
            throw new AssertionError("Recent history section is missing");
        }
        capture(frame[0], Paths.get("article-output", "assets", "ui-history-home-final.png"));

        final HistoryUiHandler handler = new HistoryUiHandler() {
            @Override public void openHistory(CompareHistoryEntry entry) { }
            @Override public void toggleHistoryPinned(CompareHistoryEntry entry) { }
            @Override public void editHistoryNote(CompareHistoryEntry entry) { }
            @Override public void deleteHistory(CompareHistoryEntry entry) { }
            @Override public void historyChanged(List<CompareHistoryEntry> entries) { }
            @Override public boolean historyOpenBlocked() { return false; }
        };
        SwingUtilities.invokeAndWait(() -> HistoryManagerDialog.show(frame[0], history, handler));
        JDialog manager = waitForDialog("对比历史", 5000L);
        JTable table = findComponent(manager, JTable.class, null);
        if (table == null || table.getRowCount() != 4) {
            throw new AssertionError("Expected 4 history rows, got "
                    + (table == null ? "no table" : table.getRowCount()));
        }
        assertComponent(manager, JButton.class, "重新对比");
        assertComponent(manager, JButton.class, "重新定位左侧");
        assertComponent(manager, JButton.class, "清空全部");
        Thread.sleep(400L);
        capture(manager, Paths.get("article-output", "assets", "ui-history-manager-final.png"));

        SwingUtilities.invokeAndWait(manager::dispose);
        CompareHistoryEntry first = history.entries().get(0);
        long previousSuccess = first.lastSuccessTime();
        Method openHistory = FileCompareTool.class.getDeclaredMethod("openHistoryEntry",
                CompareHistoryEntry.class);
        openHistory.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                openHistory.invoke(frame[0], first);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            CompareHistoryEntry updated = history.find(first.id());
            if (updated != null && updated.lastSuccessTime() > previousSuccess) {
                break;
            }
            Thread.sleep(50L);
        }
        if (history.find(first.id()).lastSuccessTime() <= previousSuccess) {
            throw new AssertionError("Opening history did not rescan and update success time");
        }
        SwingUtilities.invokeAndWait(frame[0]::dispose);
        deleteTree(temp);
        System.out.println("HistoryVisualSmokeTest passed");
        System.exit(0);
    }

    private static List<CompareHistoryEntry> seedEntries(Path temp) {
        try {
            Path projectA = Files.createDirectories(temp.resolve("project-a"));
            Path projectB = Files.createDirectories(temp.resolve("project-b"));
            Path releaseA = Files.createDirectories(temp.resolve("release-a"));
            Path releaseB = Files.createDirectories(temp.resolve("release-b"));
            Path fileA = temp.resolve("application-dev.yml");
            Path fileB = temp.resolve("application-prod.yml");
            Files.write(fileA, "dev".getBytes(StandardCharsets.UTF_8));
            Files.write(fileB, "prod".getBytes(StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>();
            values.add(entry("1", CompareHistoryMode.DIRECTORY, projectA, projectB,
                    "Java 主项目", true, now, 428, 12));
            values.add(entry("2", CompareHistoryMode.DIRECTORY, releaseA, releaseB,
                    "发布目录", true, now - 60000L, 186, 4));
            values.add(entry("3", CompareHistoryMode.FILE, fileA, fileB,
                    "环境配置", false, now - 120000L, 0, 1));
            values.add(entry("4", CompareHistoryMode.DIRECTORY,
                    temp.resolve("missing-left"), releaseB,
                    "已移动的备份目录", false, now - 180000L, 72, 9));
            return values;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static CompareHistoryEntry entry(String id, CompareHistoryMode mode,
                                             Path left, Path right, String note,
                                             boolean pinned, long time,
                                             int same, int different) {
        HistoryFilterSnapshot filter = mode == CompareHistoryMode.DIRECTORY
                ? new HistoryFilterSnapshot(".git,target", ".log,.tmp", "*.bak", null)
                : HistoryFilterSnapshot.empty();
        return new CompareHistoryEntry(id, mode, left.toAbsolutePath().toString(),
                right.toAbsolutePath().toString(), time - 100000L, time, pinned, note,
                new HistoryResultSummary(same, different, 2, 1, 3, 8), filter);
    }

    private static JDialog waitForDialog(String title, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            final JDialog[] result = new JDialog[1];
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog && window.isShowing()
                            && title.equals(((JDialog) window).getTitle())) {
                        result[0] = (JDialog) window;
                    }
                }
            });
            if (result[0] != null) return result[0];
            Thread.sleep(25L);
        }
        throw new AssertionError("Dialog not shown: " + title);
    }

    private static <T extends Component> void assertComponent(Container root, Class<T> type,
                                                               String text) {
        if (findComponent(root, type, text) == null) {
            throw new AssertionError("Missing " + type.getSimpleName() + ": " + text);
        }
    }

    private static <T extends Component> T findComponent(Container root, Class<T> type,
                                                         String text) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && matches(component, text)) return type.cast(component);
            if (component instanceof Container) {
                T nested = findComponent((Container) component, type, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean matches(Component component, String text) {
        if (text == null) return true;
        if (component instanceof JButton) return text.equals(((JButton) component).getText());
        if (component instanceof JLabel) return text.equals(((JLabel) component).getText());
        return false;
    }

    private static void capture(final Window window, Path output) throws Exception {
        final BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = image.createGraphics();
            window.printAll(graphics);
            graphics.dispose();
        });
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        ArrayList<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        for (int i = paths.size() - 1; i >= 0; i--) Files.deleteIfExists(paths.get(i));
    }
}
