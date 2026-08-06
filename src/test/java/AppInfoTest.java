import java.nio.file.Path;

public class AppInfoTest {
    public static void main(String[] args) {
        assertEquals("0.9.0-rc1", AppInfo.VERSION, "default version");
        assertTrue(AppInfo.SOFT_EDITABLE_FILE_BYTES == 20L * 1024L * 1024L,
                "soft file limit");
        assertTrue(AppInfo.HARD_EDITABLE_FILE_BYTES == 100L * 1024L * 1024L,
                "hard file limit");
        Path data = AppInfo.dataDirectory();
        assertTrue(data.isAbsolute(), "data directory must be absolute");
        String diagnostics = AppInfo.diagnosticInfo();
        assertTrue(diagnostics.contains("版本："), "diagnostics version");
        assertTrue(!diagnostics.contains(data.toString()),
                "diagnostics must not expose the user data path");
        System.out.println("AppInfoTest passed");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}

