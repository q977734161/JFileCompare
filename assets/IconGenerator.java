import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

public class IconGenerator {
    public static void main(String[] args) throws Exception {
        int size = args.length == 0 ? 256 : Integer.parseInt(args[0]);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(37, 99, 235));
        int inset = Math.max(2, size / 32);
        int arc = Math.max(8, size / 5);
        graphics.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, arc, arc);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("SansSerif", Font.BOLD, Math.round(size * 0.58f)));
        FontMetrics metrics = graphics.getFontMetrics();
        String text = "F";
        int x = (size - metrics.stringWidth(text)) / 2;
        int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(text, x, y);
        graphics.dispose();
        File output = new File(args.length > 1 ? args[1] : "assets/app-icon.png");
        if (output.getParentFile() != null && !output.getParentFile().isDirectory()
                && !output.getParentFile().mkdirs()) {
            throw new IllegalStateException("Cannot create icon directory: " + output.getParent());
        }
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("Current Java runtime has no PNG writer");
        }
        if (!output.isFile() || output.length() == 0L) {
            throw new IllegalStateException("PNG file was not created: " + output.getAbsolutePath());
        }
        System.out.println(output.getAbsolutePath());
    }
}
