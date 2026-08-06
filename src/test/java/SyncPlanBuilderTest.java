import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class SyncPlanBuilderTest {
    public static void main(String[] args) {
        Path left = Paths.get("left");
        Path right = Paths.get("right");
        FileFingerprint same = FileFingerprint.knownFile(4L, 1L, "same");
        FileFingerprint sameNewer = FileFingerprint.knownFile(4L, 99L, "same");
        FileFingerprint differentLeft = FileFingerprint.knownFile(5L, 2L, "left");
        FileFingerprint differentRight = FileFingerprint.knownFile(6L, 3L, "right");
        List<SyncComparisonEntry> entries = Arrays.asList(
                entry("same.txt", left, same, right, sameNewer),
                entry("different.txt", left, differentLeft, right, differentRight),
                entry("left-only.txt", left, differentLeft, right,
                        FileFingerprint.missing()),
                entry("right-only.txt", left, FileFingerprint.missing(), right,
                        differentRight),
                entry("nested/file.txt", left, differentLeft, right,
                        FileFingerprint.missing()));
        LinkedHashSet<String> leftDirectories = new LinkedHashSet<String>(
                Arrays.asList("nested", "empty-folder"));
        LinkedHashSet<String> rightDirectories = new LinkedHashSet<String>();

        SyncPlan forward = new SyncPlanBuilder().build(true,
                SyncDirection.LEFT_TO_RIGHT, left, right, entries,
                leftDirectories, rightDirectories, 3);
        assertAction(forward, "same.txt", SyncAction.SKIP);
        assertAction(forward, "different.txt", SyncAction.OVERWRITE);
        assertAction(forward, "left-only.txt", SyncAction.ADD);
        assertAction(forward, "right-only.txt", SyncAction.SKIP);
        assertAction(forward, "empty-folder", SyncAction.CREATE_DIRECTORY);
        if (find(forward, "nested", SyncAction.CREATE_DIRECTORY) != null) {
            throw new AssertionError("A parent directory must be created with its selected file");
        }
        if (forward.defaultSelection().size() != 4) {
            throw new AssertionError("Expected overwrite, two adds and empty directory selected");
        }

        SyncPlan reverse = new SyncPlanBuilder().build(true,
                SyncDirection.RIGHT_TO_LEFT, left, right, entries,
                leftDirectories, rightDirectories, 3);
        assertAction(reverse, "left-only.txt", SyncAction.SKIP);
        assertAction(reverse, "right-only.txt", SyncAction.ADD);
        assertAction(reverse, "different.txt", SyncAction.OVERWRITE);
        System.out.println("SyncPlanBuilderTest passed");
    }

    private static SyncComparisonEntry entry(String path, Path leftRoot,
                                             FileFingerprint left,
                                             Path rightRoot, FileFingerprint right) {
        return new SyncComparisonEntry(path, leftRoot.resolve(path), left,
                rightRoot.resolve(path), right);
    }

    private static void assertAction(SyncPlan plan, String path, SyncAction expected) {
        SyncPlanEntry entry = find(plan, path, expected);
        if (entry == null) {
            throw new AssertionError("Expected " + path + " to be " + expected);
        }
    }

    private static SyncPlanEntry find(SyncPlan plan, String path, SyncAction action) {
        for (SyncPlanEntry entry : plan.getEntries()) {
            if (path.equals(entry.getRelativePath()) && entry.getAction() == action) {
                return entry;
            }
        }
        return null;
    }
}
