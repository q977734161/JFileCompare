import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JDialog;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class EncodingDialogVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("encoding-confirm-preview");
        Path left = temp.resolve("left.properties");
        Path right = temp.resolve("right.properties");
        Files.write(left, "name=文件对比工具\r\nstatus=左侧内容\r\n"
                .getBytes(Charset.forName("GB18030")));
        Files.write(right, "name=文件对比工具\nstatus=右侧内容\n"
                .getBytes(StandardCharsets.UTF_8));

        final DiffEditorFrame[] frame = new DiffEditorFrame[1];
        SwingUtilities.invokeLater(() -> {
            try {
                frame[0] = new DiffEditorFrame((JFrame) null, "messages.properties",
                        left, right, null);
                frame[0].setVisible(true);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        Robot robot = new Robot();
        Thread.sleep(1200);
        BufferedImage dialogImage = robot.createScreenCapture(new Rectangle(
                Toolkit.getDefaultToolkit().getScreenSize()));
        Path dialogOutput = Paths.get("article-output", "assets",
                "ui-encoding-confirm-final.png");
        ImageIO.write(dialogImage, "png", dialogOutput.toFile());
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && window.isVisible()) {
                    JButton defaultButton = ((JDialog) window).getRootPane().getDefaultButton();
                    if (defaultButton != null) {
                        defaultButton.doClick();
                        return;
                    }
                }
            }
            throw new AssertionError("Encoding confirmation dialog was not visible");
        });

        for (int i = 0; i < 30 && frame[0] == null; i++) {
            Thread.sleep(100);
        }
        if (frame[0] == null) {
            throw new AssertionError("Editor did not open after confirming GB18030");
        }
        Thread.sleep(400);
        BufferedImage editorImage = new BufferedImage(frame[0].getWidth(), frame[0].getHeight(),
                BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = editorImage.createGraphics();
            frame[0].printAll(graphics);
            graphics.dispose();
            frame[0].dispose();
        });
        Path editorOutput = Paths.get("article-output", "assets",
                "ui-encoding-gb18030-final.png");
        ImageIO.write(editorImage, "png", editorOutput.toFile());
        System.out.println(dialogOutput.toAbsolutePath());
        System.out.println(editorOutput.toAbsolutePath());
        System.exit(0);
    }
}
