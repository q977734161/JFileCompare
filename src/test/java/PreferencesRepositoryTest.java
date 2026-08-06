import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PreferencesRepositoryTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "preferences-repository-test-data");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "repository-");
        try {
            defaultsWhenMissing(temp.resolve("missing.xml"));
            roundTrip(temp.resolve("round-trip.xml"), temp);
            invalidOptionalFieldFallsBack(temp.resolve("optional.xml"));
            rejectsDoctypeAndBacksUp(temp.resolve("malicious.xml"));
            rejectsOversizedFile(temp.resolve("oversized.xml"));
            resetDeletesPreferencesAndBackups(temp.resolve("reset.xml"));
        } finally {
            deleteTree(temp);
        }
        System.out.println("PreferencesRepositoryTest passed");
    }

    private static void defaultsWhenMissing(Path path) {
        PreferencesRepository.LoadResult loaded = new PreferencesRepository(path).load();
        assertEquals(AppPreferences.defaults(), loaded.preferences(), "missing defaults");
        assertEquals(null, loaded.warning(), "missing warning");
    }

    private static void roundTrip(Path path, Path temp) throws Exception {
        String chinese = temp.resolve("发布 & 配置测试").toString();
        AppPreferences expected = new AppPreferences(false, true, false, false,
                false, true, new WindowBounds(-1200, 80, 1100, 720), true, 0.37d,
                new WindowBounds(40, 55, 1280, 760), chinese, temp.toString());
        PreferencesRepository repository = new PreferencesRepository(path);
        repository.save(expected);
        PreferencesRepository.LoadResult loaded = repository.load();
        assertEquals(null, loaded.warning(), "round-trip warning");
        assertEquals(expected, loaded.preferences(), "round-trip value");
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertEquals(true, xml.contains("&amp;"), "escaped path");
        assertEquals(false, Files.exists(path.resolveSibling("round-trip.xml.tmp")),
                "temporary file removed");
    }

    private static void invalidOptionalFieldFallsBack(Path path) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<preferences version=\"1\"><behavior linked-scroll=\"false\" "
                + "confirm-hunk-deletion=\"true\" remember-chooser-locations=\"true\"/>"
                + "<restore main-window=\"true\" main-divider=\"true\" "
                + "editor-window=\"true\"/><main-window x=\"bad\" y=\"10\" "
                + "width=\"1000\" height=\"700\" maximized=\"false\" "
                + "divider-ratio=\"9\"/><editor-window/><chooser-locations>"
                + "<directory>bad&#x1;</directory></chooser-locations></preferences>";
        // XML 1.0 rejects control characters, so use an invalid platform path instead.
        xml = xml.replace("bad&#x1;", "bad\u0000path");
        try {
            Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
            PreferencesRepository.LoadResult loaded = new PreferencesRepository(path).load();
            assertEquals(true, loaded.warning() != null, "invalid XML warning");
        } finally {
            Files.deleteIfExists(path);
        }

        String validXml = "<?xml version=\"1.0\"?><preferences version=\"1\">"
                + "<behavior linked-scroll=\"false\" confirm-hunk-deletion=\"true\" "
                + "remember-chooser-locations=\"true\"/><restore main-window=\"true\" "
                + "main-divider=\"true\" editor-window=\"true\"/>"
                + "<main-window x=\"bad\" y=\"10\" width=\"1000\" height=\"700\" "
                + "maximized=\"false\" divider-ratio=\"9\"/><editor-window/>"
                + "<chooser-locations/></preferences>";
        Files.write(path, validXml.getBytes(StandardCharsets.UTF_8));
        AppPreferences value = new PreferencesRepository(path).load().preferences();
        assertEquals(false, value.linkedScrollDefault(), "valid sibling retained");
        assertEquals(null, value.mainWindowBounds(), "bad bounds fallback");
        assertEquals(AppPreferences.DEFAULT_DIVIDER_RATIO, value.mainDividerRatio(),
                "bad divider fallback");
    }

    private static void rejectsDoctypeAndBacksUp(Path path) throws Exception {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM "
                + "\"file:///c:/windows/win.ini\">]><preferences version=\"1\">"
                + "<chooser-locations><directory>&e;</directory></chooser-locations>"
                + "</preferences>";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        PreferencesRepository.LoadResult loaded = new PreferencesRepository(path).load();
        assertEquals(true, loaded.warning() != null, "doctype warning");
        assertEquals(AppPreferences.defaults(), loaded.preferences(), "doctype defaults");
        assertEquals(false, Files.exists(path), "malicious file moved");
        assertEquals(true, countBackups(path.getParent()) >= 1, "corrupt backup");
    }

    private static void rejectsOversizedFile(Path path) throws Exception {
        byte[] bytes = new byte[(int) PreferencesRepository.MAX_FILE_BYTES + 1];
        Files.write(path, bytes);
        PreferencesRepository.LoadResult loaded = new PreferencesRepository(path).load();
        assertEquals(true, loaded.warning() != null, "oversize warning");
        assertEquals(false, Files.exists(path), "oversize moved");
    }

    private static void resetDeletesPreferencesAndBackups(Path path) throws Exception {
        PreferencesRepository repository = new PreferencesRepository(path);
        repository.save(AppPreferences.defaults().withLinkedScrollDefault(false));
        Files.write(path.resolveSibling("preferences.corrupt-20000101-000000-000.xml"),
                "bad".getBytes(StandardCharsets.UTF_8));
        repository.reset();
        assertEquals(false, Files.exists(path), "reset config");
        assertEquals(0, countBackups(path.getParent()), "reset backups");
    }

    private static int countBackups(Path directory) throws Exception {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                "preferences.corrupt-*.xml")) {
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
