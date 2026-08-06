import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CompareProgressVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "scan-ui-test");
        Files.createDirectories(root);
        Path left = Files.createTempDirectory(root, "left-");
        Path right = Files.createTempDirectory(root, "right-");
        byte[] data = new byte[2 * 1024 * 1024];
        for (int i = 0; i < 80; i++) {
            data[0] = (byte) i;
            Files.write(left.resolve(String.format("file-%03d.bin", i)), data);
            data[1] = (byte) (i + (i == 35 ? 1 : 0));
            Files.write(right.resolve(String.format("file-%03d.bin", i)), data);
        }

        final FilterPresetService filters = new FilterPresetService(
                new FilterConfigRepository(root.resolve("filter-config.xml")));
        final CompareHistoryService history = new CompareHistoryService(
                new HistoryRepository(root.resolve("history.xml")));
        final PreferencesService preferences = new PreferencesService(
                new PreferencesRepository(root.resolve("preferences.xml")));

        final FileCompareTool[] frame = new FileCompareTool[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new FileCompareTool(filters, history, preferences);
            frame[0].setSize(960, 620);
            frame[0].setVisible(true);
            try {
                Method open = FileCompareTool.class.getDeclaredMethod("openCompareWorkspace",
                        Class.forName("FileCompareTool$CompareMode"));
                open.setAccessible(true);
                Object[] modes = Class.forName("FileCompareTool$CompareMode").getEnumConstants();
                open.invoke(frame[0], modes[1]);
                field(frame[0], "leftField", JTextField.class).setText(left.toString());
                field(frame[0], "rightField", JTextField.class).setText(right.toString());
                Method compare = FileCompareTool.class.getDeclaredMethod("compare");
                compare.setAccessible(true);
                compare.invoke(frame[0]);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        JPanel taskPanel = field(frame[0], "scanTaskPanel", JPanel.class);
        long waitUntil = System.currentTimeMillis() + 10000L;
        while (!taskPanel.isVisible() && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(25L);
        }
        if (!taskPanel.isVisible()) {
            throw new AssertionError("Scan task panel was not shown");
        }
        Thread.sleep(150L);
        capture(frame[0], Paths.get("article-output", "assets",
                "ui-scan-progress-final.png"), 960, 620);

        JButton cancel = field(frame[0], "cancelScanButton", JButton.class);
        SwingUtilities.invokeAndWait(cancel::doClick);
        Field busyField = FileCompareTool.class.getDeclaredField("busy");
        busyField.setAccessible(true);
        waitUntil = System.currentTimeMillis() + 10000L;
        while (busyField.getBoolean(frame[0]) && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(25L);
        }
        if (busyField.getBoolean(frame[0]) || !taskPanel.isVisible()
                || !"重新开始".equals(cancel.getText())) {
            throw new AssertionError("Cancelled state did not expose restart action");
        }
        capture(frame[0], Paths.get("article-output", "assets",
                "ui-scan-cancelled-final.png"), 960, 620);
        SwingUtilities.invokeAndWait(cancel::doClick);

        waitUntil = System.currentTimeMillis() + 30000L;
        while (busyField.getBoolean(frame[0]) && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(50L);
        }
        if (busyField.getBoolean(frame[0])) {
            throw new AssertionError("Comparison did not complete");
        }
        JTable leftTable = field(frame[0], "leftTable", JTable.class);
        if (leftTable.getRowCount() != 80) {
            throw new AssertionError("Expected 80 visible rows, got " + leftTable.getRowCount());
        }
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        System.out.println("CompareProgressVisualSmokeTest passed");
        System.exit(0);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void capture(FileCompareTool frame, Path output, int width, int height)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = image.createGraphics();
            frame.printAll(graphics);
            graphics.dispose();
        });
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }
}
