import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JToggleButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class PreferencesVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "preferences-ui-test");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "settings-");
        PreferencesService service = new PreferencesService(
                new PreferencesRepository(temp.resolve("preferences.xml")));
        AppPreferences configured = AppPreferences.defaults()
                .withOptions(true, true, true, false, false, true)
                .withMainWindow(new WindowBounds(90, 70, 1080, 700), false)
                .withMainDivider(0.36d)
                .withEditorWindow(new WindowBounds(130, 90, 1120, 680));
        service.replace(configured);
        service.updateChooserLocation(true, temp.resolve("workspace"));
        service.updateChooserLocation(false, temp.resolve("files"));
        service.flush();

        FilterPresetService filters = new FilterPresetService(
                new FilterConfigRepository(temp.resolve("filter-config.xml")));
        CompareHistoryService history = new CompareHistoryService(
                new HistoryRepository(temp.resolve("history.xml")));
        final FileCompareTool[] owner = new FileCompareTool[1];
        SwingUtilities.invokeAndWait(() -> {
            owner[0] = new FileCompareTool(filters, history, service);
            owner[0].setVisible(true);
        });
        Thread.sleep(180L);
        java.awt.Rectangle expectedMain = WindowPlacement.fitToCurrentScreens(
                configured.mainWindowBounds(), new java.awt.Dimension(920, 620),
                new java.awt.Dimension(1180, 760));
        assertEquals(expectedMain, owner[0].getBounds(), "main bounds restore");
        Field splitField = FileCompareTool.class.getDeclaredField("resultSplitPane");
        splitField.setAccessible(true);
        javax.swing.JSplitPane split = (javax.swing.JSplitPane) splitField.get(owner[0]);
        int available = split.getWidth() - split.getDividerSize();
        double ratio = available <= 0 ? 0d : (double) split.getDividerLocation() / available;
        if (Math.abs(ratio - 0.36d) > 0.03d) {
            throw new AssertionError("main divider restore expected 0.36 but was " + ratio);
        }
        showPreferences(owner[0], service);
        JDialog dialog = waitForDialog("偏好设置", 5000L);
        assertComponent(dialog, JButton.class, "保存设置");
        assertComponent(dialog, JButton.class, "恢复默认值");
        capture(dialog, Paths.get("article-output", "assets",
                "ui-preferences-dialog-final.png"));

        JToggleButton remember = findByName(dialog, JToggleButton.class,
                "记住文件选择器上次位置");
        if (remember == null) throw new AssertionError("Remember-location switch missing");
        SwingUtilities.invokeAndWait(remember::doClick);
        JScrollPane scroll = findAny(dialog, JScrollPane.class);
        SwingUtilities.invokeAndWait(() -> scroll.getVerticalScrollBar().setValue(
                scroll.getVerticalScrollBar().getMaximum()));
        Thread.sleep(120L);
        capture(dialog, Paths.get("article-output", "assets",
                "ui-preferences-path-disabled-final.png"));
        SwingUtilities.invokeAndWait(remember::doClick);

        JButton reset = findComponent(dialog, JButton.class, "恢复默认值");
        SwingUtilities.invokeLater(reset::doClick);
        JDialog confirmation = waitForDialog("恢复默认设置", 5000L);
        capture(confirmation, Paths.get("article-output", "assets",
                "ui-preferences-reset-final.png"));
        SwingUtilities.invokeAndWait(confirmation::dispose);
        SwingUtilities.invokeAndWait(dialog::dispose);

        Path left = temp.resolve("left.txt");
        Path right = temp.resolve("right.txt");
        Files.write(left, "left\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(right, "right\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final DiffEditorFrame[] editor = new DiffEditorFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                editor[0] = new DiffEditorFrame(owner[0], "settings.txt", left, right,
                        null, service);
                editor[0].setVisible(true);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        java.awt.Rectangle expectedEditor = WindowPlacement.fitToCurrentScreens(
                configured.editorWindowBounds(), new java.awt.Dimension(1020, 620),
                new java.awt.Dimension(1360, 800));
        assertEquals(expectedEditor, editor[0].getBounds(), "editor bounds restore");
        Field linkedField = DiffEditorFrame.class.getDeclaredField("linkedScroll");
        linkedField.setAccessible(true);
        if (((javax.swing.JCheckBox) linkedField.get(editor[0])).isSelected()) {
            throw new AssertionError("linked-scroll preference was not restored");
        }
        SwingUtilities.invokeAndWait(editor[0]::dispose);
        SwingUtilities.invokeAndWait(owner[0]::dispose);
        deleteTree(temp);
        System.out.println("PreferencesVisualSmokeTest passed");
        System.exit(0);
    }

    private static void showPreferences(JFrame owner, PreferencesService service) {
        SwingUtilities.invokeLater(() -> PreferencesDialog.showDialog(owner, service, null));
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
            if (type.isInstance(component) && component instanceof JButton
                    && text.equals(((JButton) component).getText())) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T nested = findComponent((Container) component, type, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static <T extends Component> T findByName(Container root, Class<T> type,
                                                      String name) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T nested = findByName((Container) component, type, name);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static <T extends Component> T findAny(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container) {
                T nested = findAny((Container) component, type);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void capture(Window window, Path path) throws Exception {
        final BufferedImage[] image = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage value = new BufferedImage(window.getWidth(), window.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = value.createGraphics();
            window.paint(graphics);
            graphics.dispose();
            image[0] = value;
        });
        Files.createDirectories(path.getParent());
        ImageIO.write(image[0], "png", path.toFile());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        List<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        for (int i = paths.size() - 1; i >= 0; i--) Files.deleteIfExists(paths.get(i));
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected " + expected + " but was " + actual);
        }
    }
}
