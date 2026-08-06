import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CompareHistoryServiceTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "history-service-test-data");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "service-");
        try {
            upsertsAndKeepsDirection(temp.resolve("upsert.xml"), temp);
            enforcesCapacityAndPinnedLimit(temp.resolve("capacity.xml"), temp);
            relocatesAndMerges(temp.resolve("relocate.xml"), temp);
            validatesPathStatus(temp.resolve("paths"));
        } finally {
            deleteTree(temp);
        }
        System.out.println("CompareHistoryServiceTest passed");
    }

    private static void upsertsAndKeepsDirection(Path config, Path temp) throws Exception {
        CompareHistoryService service = new CompareHistoryService(new HistoryRepository(config));
        try {
            Path left = temp.resolve("left");
            Path right = temp.resolve("right");
            record(service, CompareHistoryMode.DIRECTORY, left, right, 1);
            String id = service.entries().get(0).id();
            service.updateNote(id, "常用发布");
            service.togglePinned(id);
            record(service, CompareHistoryMode.DIRECTORY, left, right, 2);
            assertEquals(1, service.entries().size(), "same task upsert");
            assertEquals("常用发布", service.entries().get(0).note(), "keep note");
            assertEquals(true, service.entries().get(0).pinned(), "keep pinned");
            assertEquals(2, service.entries().get(0).summary().differentCount(),
                    "update summary");
            record(service, CompareHistoryMode.DIRECTORY, right, left, 3);
            record(service, CompareHistoryMode.FILE, left, right, 4);
            assertEquals(3, service.entries().size(), "direction and mode separate");
        } finally {
            service.close();
        }
    }

    private static void enforcesCapacityAndPinnedLimit(Path config, Path temp) throws Exception {
        CompareHistoryService service = new CompareHistoryService(new HistoryRepository(config));
        try {
            for (int i = 0; i < 25; i++) {
                record(service, CompareHistoryMode.DIRECTORY, temp.resolve("left-" + i),
                        temp.resolve("right-" + i), i);
            }
            assertEquals(CompareHistoryService.MAX_ENTRIES, service.entries().size(),
                    "history capacity");
            for (int i = 0; i < CompareHistoryService.MAX_PINNED; i++) {
                service.togglePinned(service.entries().get(i).id());
            }
            boolean rejected = false;
            try {
                service.togglePinned(service.entries().get(CompareHistoryService.MAX_PINNED).id());
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertEquals(true, rejected, "pinned capacity");
        } finally {
            service.close();
        }
    }

    private static void relocatesAndMerges(Path config, Path temp) throws Exception {
        CompareHistoryService service = new CompareHistoryService(new HistoryRepository(config));
        try {
            Path leftA = temp.resolve("left-a");
            Path rightA = temp.resolve("right-a");
            Path leftB = temp.resolve("left-b");
            Path rightB = temp.resolve("right-b");
            record(service, CompareHistoryMode.DIRECTORY, leftA, rightA, 1);
            record(service, CompareHistoryMode.DIRECTORY, leftB, rightB, 2);
            List<CompareHistoryEntry> entries = service.entries();
            CompareHistoryEntry a = find(entries, leftA);
            service.updateNote(a.id(), "迁移来源");
            service.relocate(a.id(), leftB.toString(), rightB.toString());
            assertEquals(1, service.entries().size(), "relocate duplicate merge");
            assertEquals("迁移来源", service.entries().get(0).note(), "merge note");
        } finally {
            service.close();
        }
    }

    private static void validatesPathStatus(Path root) throws Exception {
        Files.createDirectories(root);
        Path left = root.resolve("left");
        Path right = root.resolve("right");
        Files.createDirectories(left);
        Files.createDirectories(right);
        CompareHistoryEntry entry = new CompareHistoryEntry("path", CompareHistoryMode.DIRECTORY,
                left.toString(), right.toString(), 1L, 1L, false, "",
                HistoryResultSummary.empty(), HistoryFilterSnapshot.empty());
        assertEquals(HistoryPathStatus.AVAILABLE, HistoryPathValidator.validate(entry),
                "available paths");
        Files.delete(right);
        assertEquals(HistoryPathStatus.RIGHT_MISSING, HistoryPathValidator.validate(entry),
                "right missing");
        Files.delete(left);
        assertEquals(HistoryPathStatus.BOTH_MISSING, HistoryPathValidator.validate(entry),
                "both missing");
    }

    private static void record(CompareHistoryService service, CompareHistoryMode mode,
                               Path left, Path right, int different) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] error = new String[1];
        service.recordSuccessAsync(mode, left, right,
                HistoryFilterSnapshot.fromRules(FilterRuleSet.fromText(".git", ".log", ""), null),
                new HistoryResultSummary(1, different, 0, 0, 0, 0),
                (entries, message) -> { error[0] = message; done.countDown(); });
        if (!done.await(5L, TimeUnit.SECONDS)) {
            throw new AssertionError("history save timed out");
        }
        if (error[0] != null) {
            throw new AssertionError("history save failed: " + error[0]);
        }
    }

    private static CompareHistoryEntry find(List<CompareHistoryEntry> values, Path left) {
        String path = left.toAbsolutePath().normalize().toString();
        for (CompareHistoryEntry entry : values) {
            if (entry.leftPath().equals(path)) return entry;
        }
        throw new AssertionError("entry not found: " + path);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        java.util.ArrayList<Path> paths = new java.util.ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        for (int i = paths.size() - 1; i >= 0; i--) Files.deleteIfExists(paths.get(i));
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected " + expected + " but was " + actual);
        }
    }
}
