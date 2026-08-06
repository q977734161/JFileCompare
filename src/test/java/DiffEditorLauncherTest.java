public class DiffEditorLauncherTest {
    public static void main(String[] args) {
        assertDecision(DiffEditorLauncher.SizeDecision.ALLOW, 0L, 1024L);
        assertDecision(DiffEditorLauncher.SizeDecision.ALLOW,
                AppInfo.SOFT_EDITABLE_FILE_BYTES, 0L);
        assertDecision(DiffEditorLauncher.SizeDecision.CONFIRM,
                AppInfo.SOFT_EDITABLE_FILE_BYTES + 1L, 0L);
        assertDecision(DiffEditorLauncher.SizeDecision.CONFIRM,
                0L, AppInfo.HARD_EDITABLE_FILE_BYTES);
        assertDecision(DiffEditorLauncher.SizeDecision.REJECT,
                AppInfo.HARD_EDITABLE_FILE_BYTES + 1L, 0L);
        System.out.println("DiffEditorLauncherTest passed");
    }

    private static void assertDecision(DiffEditorLauncher.SizeDecision expected,
                                       long left, long right) {
        DiffEditorLauncher.SizeDecision actual =
                DiffEditorLauncher.sizeDecision(left, right);
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}

