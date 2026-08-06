import java.awt.Dimension;
import java.awt.Rectangle;

public class WindowPlacementTest {
    public static void main(String[] args) {
        Rectangle primary = new Rectangle(0, 0, 1920, 1040);
        Rectangle left = new Rectangle(-1280, 0, 1280, 984);
        Rectangle[] screens = {primary, left};
        assertEquals(new Rectangle(100, 80, 1180, 760), WindowPlacement.fit(
                new WindowBounds(100, 80, 1180, 760), screens, primary,
                new Dimension(920, 620), new Dimension(1180, 760)), "normal");
        assertEquals(new Rectangle(740, 280, 1180, 760), WindowPlacement.fit(
                new WindowBounds(8000, 6000, 1180, 760), screens, primary,
                new Dimension(920, 620), new Dimension(1180, 760)), "offscreen fallback");
        assertEquals(new Rectangle(0, 0, 1920, 1040), WindowPlacement.fit(
                new WindowBounds(0, 0, 8000, 6000), screens, primary,
                new Dimension(920, 620), new Dimension(1180, 760)), "oversized clamp");
        assertEquals(new Rectangle(20, 30, 920, 620), WindowPlacement.fit(
                new WindowBounds(20, 30, 100, 100), screens, primary,
                new Dimension(920, 620), new Dimension(1180, 760)), "minimum size");
        assertEquals(new Rectangle(-1200, 40, 1100, 800), WindowPlacement.fit(
                new WindowBounds(-1200, 40, 1100, 800), screens, primary,
                new Dimension(920, 620), new Dimension(1180, 760)), "negative monitor");
        assertEquals(0.20d, AppPreferences.defaults().withMainDivider(0.01d)
                .mainDividerRatio(), "divider minimum");
        assertEquals(0.80d, AppPreferences.defaults().withMainDivider(0.99d)
                .mainDividerRatio(), "divider maximum");
        System.out.println("WindowPlacementTest passed");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected " + expected + " but was " + actual);
        }
    }
}
