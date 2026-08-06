import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PreferencesServiceTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "preferences-service-test-data");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "service-");
        try {
            coalescesAndFlushesLatest(temp.resolve("coalesce.xml"));
            resetCancelsPendingWrite(temp.resolve("reset.xml"));
            chooserFallsBackToExistingAncestor(temp.resolve("chooser.xml"), temp);
            disablingMemoryClearsPaths(temp.resolve("privacy.xml"), temp);
        } finally {
            deleteTree(temp);
        }
        System.out.println("PreferencesServiceTest passed");
    }

    private static void coalescesAndFlushesLatest(Path path) throws Exception {
        PreferencesService service = new PreferencesService(new PreferencesRepository(path));
        service.updateLinkedScroll(false);
        service.updateConfirmDeletion(false);
        service.updateMainDivider(0.63d);
        service.flush();
        service.close();
        AppPreferences loaded = new PreferencesRepository(path).load().preferences();
        assertEquals(false, loaded.linkedScrollDefault(), "latest linked scroll");
        assertEquals(false, loaded.confirmHunkDeletion(), "latest confirmation");
        assertEquals(0.63d, loaded.mainDividerRatio(), "latest divider");
    }

    private static void resetCancelsPendingWrite(Path path) throws Exception {
        PreferencesService service = new PreferencesService(new PreferencesRepository(path));
        try {
            service.updateLinkedScroll(false);
            service.reset();
            Thread.sleep(PreferencesService.SAVE_DELAY_MILLIS + 250L);
            assertEquals(false, Files.exists(path), "stale task did not recreate file");
            assertEquals(AppPreferences.defaults(), service.current(), "reset in memory");
        } finally {
            service.close();
        }
    }

    private static void chooserFallsBackToExistingAncestor(Path path, Path temp)
            throws Exception {
        Path existing = Files.createDirectories(temp.resolve("chooser-root"));
        PreferencesService service = new PreferencesService(new PreferencesRepository(path));
        try {
            service.updateChooserLocation(true, existing.resolve("gone").resolve("nested"));
            assertEquals(existing.toAbsolutePath().normalize(), service.chooserStart(true),
                    "nearest existing ancestor");
        } finally {
            service.close();
        }
    }

    private static void disablingMemoryClearsPaths(Path path, Path temp) throws Exception {
        PreferencesService service = new PreferencesService(new PreferencesRepository(path));
        service.updateChooserLocation(true, temp);
        service.updateChooserLocation(false, temp);
        AppPreferences current = service.current();
        service.replace(current.withOptions(current.restoreMainWindow(),
                current.restoreMainDivider(), current.restoreEditorWindow(),
                current.linkedScrollDefault(), current.confirmHunkDeletion(), false));
        service.flush();
        service.close();
        AppPreferences loaded = new PreferencesRepository(path).load().preferences();
        assertEquals(false, loaded.rememberChooserLocations(), "memory disabled");
        assertEquals(null, loaded.recentDirectoryLocation(), "directory cleared");
        assertEquals(null, loaded.recentFileLocation(), "file cleared");
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
