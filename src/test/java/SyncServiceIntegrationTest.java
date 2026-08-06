import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncServiceIntegrationTest {
    public static void main(String[] args) throws Exception {
        testSuccessfulSyncAndRollback();
        testPreflightRejectsChangedTarget();
        testRollbackConflictProtection();
        testPartialSelection();
        testPreflightFailureMarksWholeBatchUnexecuted();
        testCancelBeforeFirstItem();
        testOverwriteWithoutBackupCannotRollback();
        System.out.println("SyncServiceIntegrationTest passed");
    }

    private static void testSuccessfulSyncAndRollback() throws Exception {
        Fixture fixture = Fixture.create("success");
        Files.createDirectories(fixture.left.resolve("nested"));
        Files.write(fixture.left.resolve("overwrite.txt"), bytes("new content"));
        Files.write(fixture.right.resolve("overwrite.txt"), bytes("old content"));
        Files.write(fixture.left.resolve("nested/add.txt"), bytes("added"));
        SyncPlan plan = fixture.plan("overwrite.txt", "nested/add.txt");
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(plan.defaultSelection(), true), null,
                new AtomicBoolean(false));
        assertEquals("new content", read(fixture.right.resolve("overwrite.txt")),
                "overwrite result");
        assertEquals("added", read(fixture.right.resolve("nested/add.txt")),
                "add result");
        if (result.count(SyncItemStatus.SUCCESS) != 2 || !result.hasRollbackCandidates()) {
            throw new AssertionError("Expected two successful rollback candidates");
        }
        fixture.service.rollback(result, null, new AtomicBoolean(false));
        assertEquals("old content", read(fixture.right.resolve("overwrite.txt")),
                "restored overwrite");
        if (Files.exists(fixture.right.resolve("nested/add.txt"))) {
            throw new AssertionError("Added file must be removed by rollback");
        }
        if (result.count(SyncItemStatus.ROLLED_BACK) != 2) {
            throw new AssertionError("Expected both items to be rolled back");
        }
    }

    private static void testPreflightRejectsChangedTarget() throws Exception {
        Fixture fixture = Fixture.create("preflight");
        Files.write(fixture.left.resolve("file.txt"), bytes("new"));
        Files.write(fixture.right.resolve("file.txt"), bytes("old"));
        SyncPlan plan = fixture.plan("file.txt");
        Files.write(fixture.right.resolve("file.txt"), bytes("external change"));
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(plan.defaultSelection(), true), null,
                new AtomicBoolean(false));
        assertEquals("external change", read(fixture.right.resolve("file.txt")),
                "preflight must preserve changed target");
        if (result.count(SyncItemStatus.FAILED) != 1) {
            throw new AssertionError("Changed target must fail preflight");
        }
    }

    private static void testRollbackConflictProtection() throws Exception {
        Fixture fixture = Fixture.create("conflict");
        Files.write(fixture.left.resolve("file.txt"), bytes("synced"));
        Files.write(fixture.right.resolve("file.txt"), bytes("original"));
        SyncPlan plan = fixture.plan("file.txt");
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(plan.defaultSelection(), true), null,
                new AtomicBoolean(false));
        Files.write(fixture.right.resolve("file.txt"), bytes("changed after sync"));
        fixture.service.rollback(result, null, new AtomicBoolean(false));
        assertEquals("changed after sync", read(fixture.right.resolve("file.txt")),
                "rollback conflict must preserve external change");
        if (result.count(SyncItemStatus.ROLLBACK_CONFLICT) != 1) {
            throw new AssertionError("Expected rollback conflict");
        }
    }

    private static void testPartialSelection() throws Exception {
        Fixture fixture = Fixture.create("partial");
        Files.write(fixture.left.resolve("one.txt"), bytes("one"));
        Files.write(fixture.left.resolve("two.txt"), bytes("two"));
        SyncPlan plan = fixture.plan("one.txt", "two.txt");
        Set<Integer> selected = new LinkedHashSet<Integer>();
        for (SyncPlanEntry entry : plan.getEntries()) {
            if ("two.txt".equals(entry.getRelativePath())) {
                selected.add(Integer.valueOf(entry.getIndex()));
            }
        }
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(selected, true), null, new AtomicBoolean(false));
        if (Files.exists(fixture.right.resolve("one.txt"))) {
            throw new AssertionError("Unselected file must not be copied");
        }
        assertEquals("two", read(fixture.right.resolve("two.txt")),
                "selected file");
        if (result.count(SyncItemStatus.SUCCESS) != 1) {
            throw new AssertionError("Expected one successful selected item");
        }
    }

    private static void testPreflightFailureMarksWholeBatchUnexecuted() throws Exception {
        Fixture fixture = Fixture.create("preflight-batch");
        Files.write(fixture.left.resolve("one.txt"), bytes("one-new"));
        Files.write(fixture.right.resolve("one.txt"), bytes("one-old"));
        Files.write(fixture.left.resolve("two.txt"), bytes("two-new"));
        Files.write(fixture.right.resolve("two.txt"), bytes("two-old"));
        SyncPlan plan = fixture.plan("one.txt", "two.txt");
        Files.write(fixture.right.resolve("one.txt"), bytes("one-external"));
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(plan.defaultSelection(), true), null,
                new AtomicBoolean(false));
        if (result.count(SyncItemStatus.FAILED) != 1
                || result.count(SyncItemStatus.NOT_EXECUTED) != 1) {
            throw new AssertionError("Preflight failure must stop the whole batch before writes");
        }
        assertEquals("two-old", read(fixture.right.resolve("two.txt")),
                "second file must not be written after preflight failure");
    }

    private static void testCancelBeforeFirstItem() throws Exception {
        Fixture fixture = Fixture.create("cancel");
        Files.write(fixture.left.resolve("one.txt"), bytes("one"));
        Files.write(fixture.left.resolve("two.txt"), bytes("two"));
        SyncPlan plan = fixture.plan("one.txt", "two.txt");
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(plan.defaultSelection(), true), null,
                new AtomicBoolean(true));
        if (result.count(SyncItemStatus.CANCELLED) != 1
                || result.count(SyncItemStatus.NOT_EXECUTED) != 1) {
            throw new AssertionError("Cancellation must distinguish stopped and unexecuted items");
        }
        if (Files.exists(fixture.right.resolve("one.txt"))
                || Files.exists(fixture.right.resolve("two.txt"))) {
            throw new AssertionError("Cancelled batch must not write files");
        }
    }

    private static void testOverwriteWithoutBackupCannotRollback() throws Exception {
        Fixture fixture = Fixture.create("no-backup");
        Files.write(fixture.left.resolve("file.txt"), bytes("new"));
        Files.write(fixture.right.resolve("file.txt"), bytes("old"));
        SyncPlan plan = fixture.plan("file.txt");
        SyncExecutionResult result = fixture.service.execute(plan,
                new SyncRequest(plan.defaultSelection(), false), null,
                new AtomicBoolean(false));
        assertEquals("new", read(fixture.right.resolve("file.txt")),
                "overwrite without backup");
        if (result.hasRollbackCandidates()) {
            throw new AssertionError("Overwrite without backup must not offer false recovery");
        }
    }

    private static final class Fixture {
        private final Path left;
        private final Path right;
        private final SyncService service;

        private Fixture(Path left, Path right, SyncService service) {
            this.left = left;
            this.right = right;
            this.service = service;
        }

        private static Fixture create(String name) throws Exception {
            Path root = Files.createTempDirectory("sync-service-" + name);
            Path left = root.resolve("left");
            Path right = root.resolve("right");
            Files.createDirectories(left);
            Files.createDirectories(right);
            return new Fixture(left, right, new SyncService(root.resolve("backups")));
        }

        private SyncPlan plan(String... paths) throws Exception {
            List<SyncComparisonEntry> compared = new ArrayList<SyncComparisonEntry>();
            for (String relative : paths) {
                Path leftFile = left.resolve(relative);
                Path rightFile = right.resolve(relative);
                compared.add(new SyncComparisonEntry(relative, leftFile,
                        SyncFileOperations.capture(leftFile), rightFile,
                        SyncFileOperations.capture(rightFile)));
            }
            return new SyncPlanBuilder().build(true, SyncDirection.LEFT_TO_RIGHT,
                    left, right, compared,
                    Collections.<String>emptySet(), Collections.<String>emptySet(), 0);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected [" + expected
                    + "] but was [" + actual + "]");
        }
    }
}
