import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DiffEditorVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        final Path temp = Files.createTempDirectory("file-compare-editor-preview");
        final Path left = temp.resolve("left.txt");
        final Path right = temp.resolve("right.txt");
        byte[] leftText = ("public class Demo {\r\n"
                + "    private String mode = \"left\";\r\n"
                + "    // common line\r\n"
                + "    private String leftOnly = \"remove me\";\r\n"
                + "    public void run() {\r\n"
                + "        System.out.println(mode);\r\n"
                + "    }\r\n"
                + "}\r\n").getBytes(StandardCharsets.UTF_16LE);
        Files.write(left, withBom(new byte[]{(byte) 0xFF, (byte) 0xFE}, leftText));
        byte[] rightText = ("public class Demo {\n"
                + "    private String mode = \"right\";\r\n"
                + "    // common line\n"
                + "    public void run() {\n"
                + "        System.out.println(mode);\n"
                + "    }\n"
                + "    private String rightOnly = \"keep or copy\";\n"
                + "}\n").getBytes(StandardCharsets.UTF_8);
        Files.write(right, withBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, rightText));

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
        Thread.sleep(500);
        final BufferedImage image = new BufferedImage(1360, 800, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = image.createGraphics();
            frame[0].printAll(graphics);
            graphics.dispose();
            frame[0].dispose();
        });
        Path output = Paths.get("article-output", "assets", "ui-encoding-editor-final.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println(output.toAbsolutePath());
        System.exit(0);
    }

    private static byte[] withBom(byte[] bom, byte[] content) {
        byte[] result = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(content, 0, result, bom.length, content.length);
        return result;
    }
}
