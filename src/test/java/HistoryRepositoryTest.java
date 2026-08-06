import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HistoryRepositoryTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "history-repository-test-data");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "repository-");
        try {
            roundTripAndMerge(temp.resolve("history.xml"), temp);
            skipsMalformedAndDuplicateEntries(temp.resolve("optional.xml"), temp);
            rejectsDoctypeAndBacksUp(temp.resolve("malicious.xml"));
            clearDeletesConfigAndBackups(temp.resolve("clear.xml"), temp);
        } finally {
            deleteTree(temp);
        }
        System.out.println("HistoryRepositoryTest passed");
    }

    private static void roundTripAndMerge(Path path, Path temp) throws Exception {
        final CompareHistoryEntry firstEntry = entry("one", CompareHistoryMode.DIRECTORY,
                temp.resolve("left-a"), temp.resolve("right-a"), "发布 & 配置 <1>", true, 20L);
        final HistoryRepository repository = new HistoryRepository(path);
        repository.update(latest -> Collections.singletonList(firstEntry));
        HistoryRepository.LoadResult loaded = repository.load();
        assertEquals(null, loaded.warning(), "round-trip warning");
        assertEquals(1, loaded.entries().size(), "round-trip size");
        assertEquals("发布 & 配置 <1>", loaded.entries().get(0).note(), "escaped note");
        assertEquals(3, loaded.entries().get(0).filter().totalRuleCount(), "filter rules");

        final CompareHistoryEntry secondEntry = entry("two", CompareHistoryMode.FILE,
                temp.resolve("left-b.txt"), temp.resolve("right-b.txt"), "第二条", false, 30L);
        new HistoryRepository(path).update(latest -> {
            List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(latest);
            values.add(secondEntry);
            return values;
        });
        assertEquals(2, repository.load().entries().size(), "multi-instance merge");
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertEquals(false, xml.contains("SHA-256"), "does not persist hash");
        assertEquals(false, Files.exists(path.resolveSibling("history.xml.tmp")),
                "temporary file removed");
    }

    private static void skipsMalformedAndDuplicateEntries(Path path, Path temp) throws Exception {
        String left = escape(temp.resolve("same-left").toAbsolutePath().toString());
        String right = escape(temp.resolve("same-right").toAbsolutePath().toString());
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<compare-history version=\"1\">"
                + xmlEntry("one", "DIRECTORY", left, right, "有效")
                + xmlEntry("one", "DIRECTORY", escape(temp.resolve("x").toString()),
                        escape(temp.resolve("y").toString()), "重复 ID")
                + xmlEntry("two", "DIRECTORY", left, right, "重复任务")
                + "<entry id=\"bad\" mode=\"DIRECTORY\"><left-path>only-left</left-path></entry>"
                + xmlEntry("three", "FILE", escape(temp.resolve("a.txt").toString()),
                        escape(temp.resolve("b.txt").toString()), "第二个有效")
                + "</compare-history>";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        HistoryRepository.LoadResult loaded = new HistoryRepository(path).load();
        assertEquals(null, loaded.warning(), "optional malformed warning");
        assertEquals(2, loaded.entries().size(), "valid optional entries");
        assertEquals("one", loaded.entries().get(0).id(), "first valid id");
        assertEquals("three", loaded.entries().get(1).id(), "second valid id");
    }

    private static void rejectsDoctypeAndBacksUp(Path path) throws Exception {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM "
                + "\"file:///c:/windows/win.ini\">]><compare-history version=\"1\">"
                + "<entry id=\"x\" mode=\"FILE\"><left-path>&e;</left-path>"
                + "<right-path>x</right-path></entry></compare-history>";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        HistoryRepository.LoadResult loaded = new HistoryRepository(path).load();
        assertEquals(true, loaded.warning() != null, "doctype warning");
        assertEquals(0, loaded.entries().size(), "doctype fallback");
        assertEquals(false, Files.exists(path), "malicious file moved");
        assertEquals(1, countBackups(path.getParent()), "corrupt backup");
    }

    private static void clearDeletesConfigAndBackups(Path path, Path temp) throws Exception {
        HistoryRepository repository = new HistoryRepository(path);
        repository.update(latest -> Collections.singletonList(entry("clear",
                CompareHistoryMode.DIRECTORY, temp.resolve("l"), temp.resolve("r"), "", false, 1L)));
        Files.write(path.resolveSibling("history.corrupt-20000101-000000-000.xml"),
                "bad".getBytes(StandardCharsets.UTF_8));
        repository.clear();
        assertEquals(false, Files.exists(path), "clear config");
        assertEquals(0, countBackups(path.getParent()), "clear corrupt backups");
    }

    private static CompareHistoryEntry entry(String id, CompareHistoryMode mode,
                                             Path left, Path right, String note,
                                             boolean pinned, long time) {
        return new CompareHistoryEntry(id, mode, left.toAbsolutePath().toString(),
                right.toAbsolutePath().toString(), time, time, pinned, note,
                new HistoryResultSummary(1, 2, 3, 4, 5, 6),
                new HistoryFilterSnapshot(".git", ".log", "*.bak", "preset-1"));
    }

    private static String xmlEntry(String id, String mode, String left, String right,
                                   String note) {
        return "<entry id=\"" + id + "\" mode=\"" + mode + "\" pinned=\"false\">"
                + "<left-path>" + left + "</left-path><right-path>" + right + "</right-path>"
                + "<created-time>1</created-time><last-success-time>2</last-success-time>"
                + "<note>" + note + "</note><summary same=\"1\" different=\"0\" "
                + "left-only=\"0\" right-only=\"0\" excluded-directories=\"0\" "
                + "excluded-files=\"0\"/><filter><directories/><extensions/>"
                + "<wildcards/></filter></entry>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int countBackups(Path directory) throws Exception {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                "history.corrupt-*.xml")) {
            for (Path ignored : stream) count++;
        }
        return count;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        List<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        for (int i = paths.size() - 1; i >= 0; i--) Files.deleteIfExists(paths.get(i));
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected " + expected + " but was " + actual);
        }
    }
}
