import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class FilterPresetVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "filter-preset-ui-test");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "config-");
        final FilterPresetService service = new FilterPresetService(
                new FilterConfigRepository(temp.resolve("filter-config.xml")));
        service.createPreset("发布目录",
                FilterRuleSet.fromText(".git,temp", ".log,.tmp", "*_old.*"));
        FilterRuleSet modifiedJava = FilterRuleSet.fromText(
                ".git,.idea,.gradle,target,build,out,logs", ".class,.log,.tmp",
                "*.iml,hs_err_pid*.log");
        final ActiveFilterState active = service.activate(modifiedJava,
                "builtin:java-source:v1");
        final CompareHistoryService history = new CompareHistoryService(
                new HistoryRepository(temp.resolve("history.xml")));
        final PreferencesService preferences = new PreferencesService(
                new PreferencesRepository(temp.resolve("preferences.xml")));

        final FileCompareTool[] frame = new FileCompareTool[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame[0] = new FileCompareTool(service, history, preferences);
                frame[0].setSize(1180, 760);
                frame[0].setVisible(true);
            }
        });

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                FilterPresetDialog.show(frame[0], service, active, true,
                        new FilterPresetDialog.ApplyHandler() {
                            @Override
                            public void apply(FilterRuleSet rules, String basePresetId) {
                            }
                        });
            }
        });
        JDialog editor = waitForDialog("过滤和排除规则", 5000L);
        JButton apply = findComponent(editor, JButton.class, "应用并重新对比");
        if (apply == null || !apply.isEnabled()) {
            throw new AssertionError("Apply button is missing or disabled");
        }
        JButton saveAs = findComponent(editor, JButton.class, "另存为预设");
        if (saveAs == null) {
            throw new AssertionError("Missing save-as-preset button");
        }
        assertComponent(editor, JButton.class, "管理预设");
        assertComponent(editor, JLabel.class, "Java 源码（已修改）");
        capture(editor, Paths.get("article-output", "assets",
                "ui-filter-preset-editor-final.png"));
        SwingUtilities.invokeLater(saveAs::doClick);
        JDialog saveDialog = waitForDialog("另存为自定义预设", 5000L);
        assertComponent(saveDialog, JButton.class, "保存预设");
        capture(saveDialog, Paths.get("article-output", "assets",
                "ui-filter-preset-save-final.png"));
        SwingUtilities.invokeAndWait(saveDialog::dispose);
        SwingUtilities.invokeAndWait(editor::dispose);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                FilterPresetDialog.showManager(frame[0], service, active.rules(), null);
            }
        });
        JDialog manager = waitForDialog("过滤预设管理", 5000L);
        assertComponent(manager, JButton.class, "新建预设");
        assertComponent(manager, JButton.class, "使用当前规则更新");
        assertComponent(manager, JLabel.class, "搜索");
        JTable table = findComponent(manager, JTable.class, null);
        if (table == null || table.getRowCount() != 4) {
            throw new AssertionError("Expected 4 preset rows, got "
                    + (table == null ? "no table" : table.getRowCount()));
        }
        capture(manager, Paths.get("article-output", "assets",
                "ui-filter-preset-manager-final.png"));
        SwingUtilities.invokeAndWait(manager::dispose);
        final Method open = FileCompareTool.class.getDeclaredMethod("openCompareWorkspace",
                Class.forName("FileCompareTool$CompareMode"));
        open.setAccessible(true);
        final Object[] modes = Class.forName("FileCompareTool$CompareMode").getEnumConstants();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    open.invoke(frame[0], modes[0]);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
        Field menuField = FileCompareTool.class.getDeclaredField("filterMenuItem");
        menuField.setAccessible(true);
        if (((JMenuItem) menuField.get(frame[0])).isEnabled()) {
            throw new AssertionError("Filter menu must be disabled in file mode");
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame[0].dispose();
            }
        });
        deleteTree(temp);
        System.out.println("FilterPresetVisualSmokeTest passed");
        System.exit(0);
    }

    private static JDialog waitForDialog(String title, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final JDialog[] found = new JDialog[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof JDialog && window.isShowing()) {
                            JDialog dialog = (JDialog) window;
                            if (title.equals(dialog.getTitle())) {
                                found[0] = dialog;
                                break;
                            }
                        }
                    }
                }
            });
            if (found[0] != null) {
                return found[0];
            }
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
                                                         String expectedText) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && matchesText(component, expectedText)) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T nested = findComponent((Container) component, type, expectedText);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static boolean matchesText(Component component, String expected) {
        if (expected == null) {
            return true;
        }
        if (component instanceof JButton) {
            return expected.equals(((JButton) component).getText());
        }
        if (component instanceof JLabel) {
            return expected.equals(((JLabel) component).getText());
        }
        return false;
    }

    private static void capture(final Window window, Path output) throws Exception {
        final BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                Graphics2D graphics = image.createGraphics();
                window.printAll(graphics);
                graphics.dispose();
            }
        });
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        ArrayList<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(paths.get(i));
        }
    }
}
