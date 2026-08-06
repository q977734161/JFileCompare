import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class DiffEditorFixedLineNumberSmokeTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("file-compare-fixed-line-number");
        Path left = temp.resolve("left.txt");
        Path right = temp.resolve("right.txt");
        String longLine = "    private String description = \"这是一段较长的内容，用来验证进入编辑状态和横向滚动后行号仍然固定可见\";";
        String leftText = "public class Demo {\r\n" + longLine + "\r\n"
                + "    private String side = \"left\";\r\n}\r\n";
        String rightText = "public class Demo {\n" + longLine + "\n"
                + "    private String side = \"right\";\n}\n";
        Files.write(left, leftText.getBytes(StandardCharsets.UTF_8));
        Files.write(right, rightText.getBytes(StandardCharsets.UTF_8));

        final DiffEditorFrame[] frame = new DiffEditorFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                frame[0] = new DiffEditorFrame((JFrame) null, "src/Demo.java", left, right, null);
                frame[0].setSize(1360, 800);
                frame[0].setVisible(true);
                frame[0].validate();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            List<JScrollPane> editorScrolls = new ArrayList<JScrollPane>();
            collectEditorScrolls(frame[0], editorScrolls);
            if (editorScrolls.size() != 2) {
                throw new AssertionError("Expected two editor scroll panes with fixed row headers");
            }
            for (JScrollPane scroll : editorScrolls) {
                JTable content = (JTable) scroll.getViewport().getView();
                JTable lineNumbers = (JTable) scroll.getRowHeader().getView();
                if (content.getColumnCount() != 1 || lineNumbers.getColumnCount() != 1) {
                    throw new AssertionError("Content and line numbers must use separate tables");
                }
                if (content.getSelectionModel() != lineNumbers.getSelectionModel()) {
                    throw new AssertionError("Content and line-number selection must stay synchronized");
                }
                scroll.getHorizontalScrollBar().setValue(220);
            }
            JTable leftContent = (JTable) editorScrolls.get(0).getViewport().getView();
            leftContent.changeSelection(1, 0, false, false);
            if (!leftContent.editCellAt(1, 0)) {
                throw new AssertionError("Expected content cell to enter edit mode");
            }
            leftContent.getEditorComponent().requestFocusInWindow();
        });

        Thread.sleep(250);
        BufferedImage image = new BufferedImage(1360, 800, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = image.createGraphics();
            frame[0].printAll(graphics);
            graphics.dispose();
            frame[0].dispose();
        });
        Path output = Paths.get("article-output", "assets", "ui-fixed-line-number-edit.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println(output.toAbsolutePath());
        System.exit(0);
    }

    private static void collectEditorScrolls(Component component, List<JScrollPane> result) {
        if (component instanceof JScrollPane) {
            JScrollPane scroll = (JScrollPane) component;
            if (scroll.getRowHeader() != null
                    && scroll.getRowHeader().getView() instanceof JTable
                    && scroll.getViewport().getView() instanceof JTable) {
                result.add(scroll);
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collectEditorScrolls(child, result);
            }
        }
    }
}
