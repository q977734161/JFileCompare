import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncDialogsVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sync-dialog-preview");
        Path left = root.resolve("release-a");
        Path right = root.resolve("release-b");
        Files.createDirectories(left.resolve("src/main/resources"));
        Files.createDirectories(right.resolve("src/main/resources"));
        Files.write(left.resolve("config.properties"), bytes("mode=new"));
        Files.write(right.resolve("config.properties"), bytes("mode=old"));
        Files.write(left.resolve("src/main/resources/messages.properties"),
                bytes("name=文件对比工具\nstatus=完成"));
        Files.write(right.resolve("src/main/resources/messages.properties"),
                bytes("name=文件对比工具\nstatus=旧内容"));
        Files.write(left.resolve("deploy.md"), bytes("new deployment guide"));

        List<SyncComparisonEntry> compared = new ArrayList<SyncComparisonEntry>();
        add(compared, "config.properties", left, right);
        add(compared, "src/main/resources/messages.properties", left, right);
        add(compared, "deploy.md", left, right);
        SyncPlan plan = new SyncPlanBuilder().build(true,
                SyncDirection.LEFT_TO_RIGHT, left, right, compared,
                Collections.<String>emptySet(), Collections.<String>emptySet(), 18);
        SyncService service = new SyncService(root.resolve("backups"));

        final JDialog[] preview = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            preview[0] = SyncPreviewDialog.createForPreview(null, plan, service);
            preview[0].setModalityType(java.awt.Dialog.ModalityType.MODELESS);
            preview[0].setSize(1120, 700);
            preview[0].setVisible(true);
            preview[0].validate();
        });
        Thread.sleep(250);
        capture(preview[0], Paths.get("article-output", "assets",
                "ui-sync-preview-final.png"), 1120, 700);
        SwingUtilities.invokeAndWait(() -> preview[0].dispose());

        SyncExecutionResult result = service.execute(plan,
                new SyncRequest(plan.defaultSelection(), true), null,
                new AtomicBoolean(false));
        final JDialog[] resultDialog = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            resultDialog[0] = SyncResultDialog.createForPreview(null, result, service);
            resultDialog[0].setModalityType(java.awt.Dialog.ModalityType.MODELESS);
            resultDialog[0].setSize(1060, 650);
            resultDialog[0].setVisible(true);
            resultDialog[0].validate();
        });
        Thread.sleep(250);
        capture(resultDialog[0], Paths.get("article-output", "assets",
                "ui-sync-result-final.png"), 1060, 650);
        SwingUtilities.invokeAndWait(() -> resultDialog[0].dispose());
        System.out.println("SyncDialogsVisualSmokeTest passed");
        System.exit(0);
    }

    private static void add(List<SyncComparisonEntry> entries, String relative,
                            Path left, Path right) throws Exception {
        Path leftFile = left.resolve(relative);
        Path rightFile = right.resolve(relative);
        entries.add(new SyncComparisonEntry(relative, leftFile,
                SyncFileOperations.capture(leftFile), rightFile,
                SyncFileOperations.capture(rightFile)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void capture(JDialog dialog, Path output, int width, int height)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = image.createGraphics();
            dialog.printAll(graphics);
            graphics.dispose();
        });
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }
}
