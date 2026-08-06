import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AboutDialogVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        final AboutDialog[] dialog = new AboutDialog[1];
        final BufferedImage[] image = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            dialog[0] = new AboutDialog(null);
            dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
            assertButton(dialog[0], "复制版本信息");
            assertButton(dialog[0], "打开数据目录");
            assertButton(dialog[0], "查看更新说明");
            assertButton(dialog[0], "查看许可");
            assertButton(dialog[0], "关闭");
            dialog[0].setVisible(true);
        });
        Thread.sleep(250L);
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage value = new BufferedImage(dialog[0].getWidth(), dialog[0].getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = value.createGraphics();
            dialog[0].printAll(graphics);
            graphics.dispose();
            image[0] = value;
            dialog[0].dispose();
        });
        Path output = Paths.get("article-output", "assets", "ui-release-about-final.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image[0], "png", output.toFile());
        System.out.println("AboutDialogVisualSmokeTest passed: " + output);
        System.exit(0);
    }

    private static void assertButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return;
            }
            if (component instanceof Container) {
                try {
                    assertButton((Container) component, text);
                    return;
                } catch (AssertionError ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new AssertionError("Missing AboutDialog button: " + text);
    }
}
