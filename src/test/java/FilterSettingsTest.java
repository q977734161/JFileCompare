public class FilterSettingsTest {
    public static void main(String[] args) {
        FilterRuleSet settings = FilterRuleSet.fromText(
                ".git, node_modules, target", ".log, tmp", "*.bak, *_old.*, temp-*");

        assertEquals(true, settings.matchesDirectory("src/.git"), "git directory");
        assertEquals(true, settings.matchesDirectory("build\\node_modules"), "windows path");
        assertEquals(false, settings.matchesDirectory("src/main"), "normal directory");
        assertEquals(true, settings.matchesFile("logs/app.LOG"), "extension case");
        assertEquals(true, settings.matchesFile("cache/data.tmp"), "extension dot normalization");
        assertEquals(true, settings.matchesFile("backup/config_old.json"), "wildcard file");
        assertEquals(true, settings.matchesFile("temp-cache/index.txt"), "wildcard path");
        assertEquals(false, settings.matchesFile("src/Main.java"), "normal source");

        FilterRuleSet equivalent = FilterRuleSet.fromText(
                "TARGET; .GIT; node_modules; .git", "TMP;.LOG;.log", "temp-*;*_old.*;*.bak");
        assertEquals(settings, equivalent, "semantic equality ignores order, case, duplicates");
        assertEquals(3, settings.directoryCount(), "directory count");
        assertEquals(2, settings.extensionCount(), "extension count");
        assertEquals(3, settings.wildcardCount(), "wildcard count");

        expectFailure(repeat('a', FilterRuleSet.MAX_RULE_LENGTH + 1), "", "", "单条规则");
        expectFailure(repeat('a', FilterRuleSet.MAX_TEXT_LENGTH + 1), "", "", "最多输入");
        System.out.println("FilterSettingsTest passed");
    }

    private static void expectFailure(String directories, String extensions, String wildcards,
                                      String expectedMessage) {
        try {
            FilterRuleSet.fromText(directories, extensions, wildcards);
            throw new AssertionError("expected failure containing " + expectedMessage);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(expectedMessage)) {
                throw new AssertionError("unexpected message: " + expected.getMessage());
            }
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected " + expected + " but was " + actual);
        }
    }
}
