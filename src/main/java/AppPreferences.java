import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

final class WindowBounds {
    static final int MAX_ABSOLUTE_COORDINATE = 1000000;
    static final int MAX_DIMENSION = 100000;

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    WindowBounds(int x, int y, int width, int height) {
        if (Math.abs((long) x) > MAX_ABSOLUTE_COORDINATE
                || Math.abs((long) y) > MAX_ABSOLUTE_COORDINATE
                || width <= 0 || height <= 0
                || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("窗口边界无效");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    static WindowBounds from(Rectangle rectangle) {
        return new WindowBounds(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
    }

    int x() { return x; }
    int y() { return y; }
    int width() { return width; }
    int height() { return height; }

    Rectangle toRectangle() {
        return new Rectangle(x, y, width, height);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof WindowBounds)) return false;
        WindowBounds value = (WindowBounds) other;
        return x == value.x && y == value.y && width == value.width && height == value.height;
    }

    @Override public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + width;
        result = 31 * result + height;
        return result;
    }
}

final class AppPreferences {
    static final double MIN_DIVIDER_RATIO = 0.20d;
    static final double MAX_DIVIDER_RATIO = 0.80d;
    static final double DEFAULT_DIVIDER_RATIO = 0.50d;
    static final int MAX_PATH_LENGTH = 4096;

    private final boolean restoreMainWindow;
    private final boolean restoreMainDivider;
    private final boolean restoreEditorWindow;
    private final boolean linkedScrollDefault;
    private final boolean confirmHunkDeletion;
    private final boolean rememberChooserLocations;
    private final WindowBounds mainWindowBounds;
    private final boolean mainWindowMaximized;
    private final double mainDividerRatio;
    private final WindowBounds editorWindowBounds;
    private final String recentDirectoryLocation;
    private final String recentFileLocation;

    AppPreferences(boolean restoreMainWindow, boolean restoreMainDivider,
                   boolean restoreEditorWindow, boolean linkedScrollDefault,
                   boolean confirmHunkDeletion, boolean rememberChooserLocations,
                   WindowBounds mainWindowBounds, boolean mainWindowMaximized,
                   double mainDividerRatio, WindowBounds editorWindowBounds,
                   String recentDirectoryLocation, String recentFileLocation) {
        if (Double.isNaN(mainDividerRatio) || Double.isInfinite(mainDividerRatio)
                || mainDividerRatio < MIN_DIVIDER_RATIO
                || mainDividerRatio > MAX_DIVIDER_RATIO) {
            throw new IllegalArgumentException("分栏比例无效");
        }
        this.restoreMainWindow = restoreMainWindow;
        this.restoreMainDivider = restoreMainDivider;
        this.restoreEditorWindow = restoreEditorWindow;
        this.linkedScrollDefault = linkedScrollDefault;
        this.confirmHunkDeletion = confirmHunkDeletion;
        this.rememberChooserLocations = rememberChooserLocations;
        this.mainWindowBounds = mainWindowBounds;
        this.mainWindowMaximized = mainWindowMaximized;
        this.mainDividerRatio = mainDividerRatio;
        this.editorWindowBounds = editorWindowBounds;
        this.recentDirectoryLocation = rememberChooserLocations
                ? validOptionalPath(recentDirectoryLocation) : null;
        this.recentFileLocation = rememberChooserLocations
                ? validOptionalPath(recentFileLocation) : null;
    }

    static AppPreferences defaults() {
        return new AppPreferences(true, true, true, true, true, true,
                null, false, DEFAULT_DIVIDER_RATIO, null, null, null);
    }

    private static String validOptionalPath(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String clean = value.trim();
        if (clean.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("选择器路径超过长度上限");
        }
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) {
                throw new IllegalArgumentException("选择器路径包含控制字符");
            }
        }
        try {
            Paths.get(clean);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("选择器路径无效", ex);
        }
        return clean;
    }

    boolean restoreMainWindow() { return restoreMainWindow; }
    boolean restoreMainDivider() { return restoreMainDivider; }
    boolean restoreEditorWindow() { return restoreEditorWindow; }
    boolean linkedScrollDefault() { return linkedScrollDefault; }
    boolean confirmHunkDeletion() { return confirmHunkDeletion; }
    boolean rememberChooserLocations() { return rememberChooserLocations; }
    WindowBounds mainWindowBounds() { return mainWindowBounds; }
    boolean mainWindowMaximized() { return mainWindowMaximized; }
    double mainDividerRatio() { return mainDividerRatio; }
    WindowBounds editorWindowBounds() { return editorWindowBounds; }
    String recentDirectoryLocation() { return recentDirectoryLocation; }
    String recentFileLocation() { return recentFileLocation; }

    AppPreferences withOptions(boolean restoreMain, boolean restoreDivider,
                               boolean restoreEditor, boolean linkedScroll,
                               boolean confirmDeletion, boolean rememberLocations) {
        return new AppPreferences(restoreMain, restoreDivider, restoreEditor,
                linkedScroll, confirmDeletion, rememberLocations, mainWindowBounds,
                mainWindowMaximized, mainDividerRatio, editorWindowBounds,
                recentDirectoryLocation, recentFileLocation);
    }

    AppPreferences withMainWindow(WindowBounds bounds, boolean maximized) {
        return new AppPreferences(restoreMainWindow, restoreMainDivider,
                restoreEditorWindow, linkedScrollDefault, confirmHunkDeletion,
                rememberChooserLocations, bounds, maximized, mainDividerRatio,
                editorWindowBounds, recentDirectoryLocation, recentFileLocation);
    }

    AppPreferences withMainDivider(double ratio) {
        double safe = Math.max(MIN_DIVIDER_RATIO, Math.min(MAX_DIVIDER_RATIO, ratio));
        return new AppPreferences(restoreMainWindow, restoreMainDivider,
                restoreEditorWindow, linkedScrollDefault, confirmHunkDeletion,
                rememberChooserLocations, mainWindowBounds, mainWindowMaximized,
                safe, editorWindowBounds, recentDirectoryLocation, recentFileLocation);
    }

    AppPreferences withEditorWindow(WindowBounds bounds) {
        return new AppPreferences(restoreMainWindow, restoreMainDivider,
                restoreEditorWindow, linkedScrollDefault, confirmHunkDeletion,
                rememberChooserLocations, mainWindowBounds, mainWindowMaximized,
                mainDividerRatio, bounds, recentDirectoryLocation, recentFileLocation);
    }

    AppPreferences withLinkedScrollDefault(boolean value) {
        return new AppPreferences(restoreMainWindow, restoreMainDivider,
                restoreEditorWindow, value, confirmHunkDeletion, rememberChooserLocations,
                mainWindowBounds, mainWindowMaximized, mainDividerRatio, editorWindowBounds,
                recentDirectoryLocation, recentFileLocation);
    }

    AppPreferences withConfirmHunkDeletion(boolean value) {
        return new AppPreferences(restoreMainWindow, restoreMainDivider,
                restoreEditorWindow, linkedScrollDefault, value, rememberChooserLocations,
                mainWindowBounds, mainWindowMaximized, mainDividerRatio, editorWindowBounds,
                recentDirectoryLocation, recentFileLocation);
    }

    AppPreferences withChooserLocation(boolean directory, String path) {
        String nextDirectory = directory ? path : recentDirectoryLocation;
        String nextFile = directory ? recentFileLocation : path;
        return new AppPreferences(restoreMainWindow, restoreMainDivider,
                restoreEditorWindow, linkedScrollDefault, confirmHunkDeletion,
                rememberChooserLocations, mainWindowBounds, mainWindowMaximized,
                mainDividerRatio, editorWindowBounds, nextDirectory, nextFile);
    }

    AppPreferences clearChooserLocation(boolean directory) {
        return withChooserLocation(directory, null);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AppPreferences)) return false;
        AppPreferences value = (AppPreferences) other;
        return restoreMainWindow == value.restoreMainWindow
                && restoreMainDivider == value.restoreMainDivider
                && restoreEditorWindow == value.restoreEditorWindow
                && linkedScrollDefault == value.linkedScrollDefault
                && confirmHunkDeletion == value.confirmHunkDeletion
                && rememberChooserLocations == value.rememberChooserLocations
                && equal(mainWindowBounds, value.mainWindowBounds)
                && mainWindowMaximized == value.mainWindowMaximized
                && Double.compare(mainDividerRatio, value.mainDividerRatio) == 0
                && equal(editorWindowBounds, value.editorWindowBounds)
                && equal(recentDirectoryLocation, value.recentDirectoryLocation)
                && equal(recentFileLocation, value.recentFileLocation);
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    @Override public int hashCode() {
        int result = restoreMainWindow ? 1 : 0;
        result = 31 * result + (restoreMainDivider ? 1 : 0);
        result = 31 * result + (restoreEditorWindow ? 1 : 0);
        result = 31 * result + (linkedScrollDefault ? 1 : 0);
        result = 31 * result + (confirmHunkDeletion ? 1 : 0);
        result = 31 * result + (rememberChooserLocations ? 1 : 0);
        result = 31 * result + (mainWindowBounds == null ? 0 : mainWindowBounds.hashCode());
        long ratio = Double.doubleToLongBits(mainDividerRatio);
        result = 31 * result + (int) (ratio ^ (ratio >>> 32));
        return result;
    }
}

final class WindowPlacement {
    private WindowPlacement() {
    }

    static Rectangle fit(WindowBounds saved, Rectangle[] screens, Rectangle primary,
                         Dimension minimum, Dimension defaultSize) {
        Rectangle fallback = primary == null ? new Rectangle(0, 0,
                Math.max(minimum.width, defaultSize.width),
                Math.max(minimum.height, defaultSize.height)) : new Rectangle(primary);
        Rectangle target = chooseScreen(saved, screens, fallback);
        int requestedWidth = saved == null ? defaultSize.width : saved.width();
        int requestedHeight = saved == null ? defaultSize.height : saved.height();
        int width = Math.min(target.width, Math.max(minimum.width, requestedWidth));
        int height = Math.min(target.height, Math.max(minimum.height, requestedHeight));
        int x = saved == null ? target.x + (target.width - width) / 2 : saved.x();
        int y = saved == null ? target.y + (target.height - height) / 2 : saved.y();
        x = clamp(x, target.x, target.x + target.width - width);
        y = clamp(y, target.y, target.y + target.height - height);
        return new Rectangle(x, y, width, height);
    }

    static Rectangle fitToCurrentScreens(WindowBounds saved, Dimension minimum,
                                         Dimension defaultSize) {
        if (GraphicsEnvironment.isHeadless()) {
            return fit(saved, new Rectangle[]{new Rectangle(0, 0, defaultSize.width,
                    defaultSize.height)}, new Rectangle(0, 0, defaultSize.width,
                    defaultSize.height), minimum, defaultSize);
        }
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = environment.getScreenDevices();
        Rectangle[] screens = new Rectangle[devices.length];
        for (int i = 0; i < devices.length; i++) {
            screens[i] = usableBounds(devices[i].getDefaultConfiguration());
        }
        Rectangle primary = usableBounds(environment.getDefaultScreenDevice()
                .getDefaultConfiguration());
        return fit(saved, screens, primary, minimum, defaultSize);
    }

    private static Rectangle usableBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private static Rectangle chooseScreen(WindowBounds saved, Rectangle[] screens,
                                          Rectangle fallback) {
        if (saved == null || screens == null || screens.length == 0) return fallback;
        Rectangle value = saved.toRectangle();
        Rectangle best = null;
        long bestArea = 0L;
        for (Rectangle screen : screens) {
            if (screen == null || screen.width <= 0 || screen.height <= 0) continue;
            Rectangle intersection = value.intersection(screen);
            long area = intersection.isEmpty() ? 0L
                    : (long) intersection.width * intersection.height;
            if (area > bestArea) {
                bestArea = area;
                best = screen;
            }
        }
        return best == null ? fallback : new Rectangle(best);
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
