import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

enum CompareHistoryMode {
    FILE("文件"), DIRECTORY("文件夹");

    private final String displayName;

    CompareHistoryMode(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }
}

final class HistoryResultSummary {
    private final int sameCount;
    private final int differentCount;
    private final int leftOnlyCount;
    private final int rightOnlyCount;
    private final int excludedDirectoryCount;
    private final int excludedFileCount;

    HistoryResultSummary(int sameCount, int differentCount, int leftOnlyCount,
                         int rightOnlyCount, int excludedDirectoryCount,
                         int excludedFileCount) {
        this.sameCount = nonNegative(sameCount);
        this.differentCount = nonNegative(differentCount);
        this.leftOnlyCount = nonNegative(leftOnlyCount);
        this.rightOnlyCount = nonNegative(rightOnlyCount);
        this.excludedDirectoryCount = nonNegative(excludedDirectoryCount);
        this.excludedFileCount = nonNegative(excludedFileCount);
    }

    static HistoryResultSummary empty() {
        return new HistoryResultSummary(0, 0, 0, 0, 0, 0);
    }

    private static int nonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("历史结果数量不能为负数");
        }
        return value;
    }

    int sameCount() { return sameCount; }
    int differentCount() { return differentCount; }
    int leftOnlyCount() { return leftOnlyCount; }
    int rightOnlyCount() { return rightOnlyCount; }
    int excludedDirectoryCount() { return excludedDirectoryCount; }
    int excludedFileCount() { return excludedFileCount; }

    String compactText() {
        return "上次：相同 " + sameCount + " · 不同 " + differentCount
                + " · 仅单侧 " + (leftOnlyCount + rightOnlyCount);
    }
}

final class HistoryFilterSnapshot {
    private final String directoryText;
    private final String extensionText;
    private final String wildcardText;
    private final String presetId;

    HistoryFilterSnapshot(String directoryText, String extensionText, String wildcardText,
                          String presetId) {
        FilterRuleSet rules = FilterRuleSet.fromText(directoryText, extensionText, wildcardText);
        this.directoryText = rules.directoryText();
        this.extensionText = rules.extensionText();
        this.wildcardText = rules.wildcardText();
        this.presetId = cleanOptional(presetId, 128);
    }

    static HistoryFilterSnapshot empty() {
        return new HistoryFilterSnapshot("", "", "", null);
    }

    static HistoryFilterSnapshot fromRules(FilterRuleSet rules, String presetId) {
        FilterRuleSet value = rules == null ? FilterRuleSet.empty() : rules;
        return new HistoryFilterSnapshot(value.directoryText(), value.extensionText(),
                value.wildcardText(), presetId);
    }

    private static String cleanOptional(String value, int maximum) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String clean = value.trim();
        if (clean.length() > maximum) {
            throw new IllegalArgumentException("历史字段超过长度上限");
        }
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) {
                throw new IllegalArgumentException("历史字段包含控制字符");
            }
        }
        return clean;
    }

    String directoryText() { return directoryText; }
    String extensionText() { return extensionText; }
    String wildcardText() { return wildcardText; }
    String presetId() { return presetId; }

    FilterRuleSet rules() {
        return FilterRuleSet.fromText(directoryText, extensionText, wildcardText);
    }

    int totalRuleCount() {
        return rules().totalCount();
    }
}

final class CompareHistoryEntry {
    static final int MAX_ID_LENGTH = 128;
    static final int MAX_PATH_LENGTH = 4096;
    static final int MAX_NOTE_LENGTH = 80;

    private final String id;
    private final CompareHistoryMode mode;
    private final String leftPath;
    private final String rightPath;
    private final long createdTime;
    private final long lastSuccessTime;
    private final boolean pinned;
    private final String note;
    private final HistoryResultSummary summary;
    private final HistoryFilterSnapshot filter;

    CompareHistoryEntry(String id, CompareHistoryMode mode, String leftPath, String rightPath,
                        long createdTime, long lastSuccessTime, boolean pinned, String note,
                        HistoryResultSummary summary, HistoryFilterSnapshot filter) {
        this.id = required(id, "历史 ID", MAX_ID_LENGTH);
        this.mode = mode == null ? fail("历史模式不能为空") : mode;
        this.leftPath = validPath(leftPath, "左侧路径");
        this.rightPath = validPath(rightPath, "右侧路径");
        this.createdTime = Math.max(0L, createdTime);
        this.lastSuccessTime = Math.max(0L, lastSuccessTime);
        this.pinned = pinned;
        this.note = cleanNote(note);
        this.summary = summary == null ? HistoryResultSummary.empty() : summary;
        this.filter = filter == null ? HistoryFilterSnapshot.empty() : filter;
    }

    private static <T> T fail(String message) {
        throw new IllegalArgumentException(message);
    }

    private static String required(String value, String label, int maximum) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String clean = value.trim();
        if (clean.length() > maximum || containsControl(clean)) {
            throw new IllegalArgumentException(label + "无效");
        }
        return clean;
    }

    private static String validPath(String value, String label) {
        String clean = required(value, label, MAX_PATH_LENGTH);
        try {
            Paths.get(clean);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException(label + "无效", ex);
        }
        return clean;
    }

    static String cleanNote(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("备注不能超过 " + MAX_NOTE_LENGTH + " 个字符");
        }
        if (containsControl(clean)) {
            throw new IllegalArgumentException("备注不能包含控制字符");
        }
        return clean;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    String id() { return id; }
    CompareHistoryMode mode() { return mode; }
    String leftPath() { return leftPath; }
    String rightPath() { return rightPath; }
    long createdTime() { return createdTime; }
    long lastSuccessTime() { return lastSuccessTime; }
    boolean pinned() { return pinned; }
    String note() { return note; }
    HistoryResultSummary summary() { return summary; }
    HistoryFilterSnapshot filter() { return filter; }

    String normalizedKey() {
        return HistoryKeyFactory.key(mode, leftPath, rightPath);
    }

    String displayName() {
        if (!note.isEmpty()) {
            return note;
        }
        return fileName(leftPath) + "  →  " + fileName(rightPath);
    }

    CompareHistoryEntry successful(String nextLeft, String nextRight, long successTime,
                                   HistoryResultSummary nextSummary,
                                   HistoryFilterSnapshot nextFilter) {
        return new CompareHistoryEntry(id, mode, nextLeft, nextRight, createdTime,
                successTime, pinned, note, nextSummary, nextFilter);
    }

    CompareHistoryEntry withPinned(boolean value) {
        return new CompareHistoryEntry(id, mode, leftPath, rightPath, createdTime,
                lastSuccessTime, value, note, summary, filter);
    }

    CompareHistoryEntry withNote(String value) {
        return new CompareHistoryEntry(id, mode, leftPath, rightPath, createdTime,
                lastSuccessTime, pinned, value, summary, filter);
    }

    CompareHistoryEntry withPaths(String nextLeft, String nextRight) {
        return new CompareHistoryEntry(id, mode, nextLeft, nextRight, createdTime,
                lastSuccessTime, pinned, note, summary, filter);
    }

    private static String fileName(String value) {
        try {
            Path path = Paths.get(value);
            Path name = path.getFileName();
            return name == null ? value : name.toString();
        } catch (InvalidPathException ex) {
            return value;
        }
    }
}

final class HistoryKeyFactory {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private HistoryKeyFactory() {
    }

    static String key(CompareHistoryMode mode, String leftPath, String rightPath) {
        return mode.name() + "\u001f" + normalize(leftPath) + "\u001f" + normalize(rightPath);
    }

    private static String normalize(String value) {
        String normalized = Paths.get(value).toAbsolutePath().normalize().toString();
        return WINDOWS ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }
}

enum HistoryPathStatus {
    AVAILABLE("可用"), LEFT_MISSING("左侧不可用"), RIGHT_MISSING("右侧不可用"),
    BOTH_MISSING("两侧不可用"), TYPE_MISMATCH("路径类型不匹配");

    private final String displayName;

    HistoryPathStatus(String displayName) {
        this.displayName = displayName;
    }

    String displayName() { return displayName; }
    boolean available() { return this == AVAILABLE; }
}

final class HistoryPathValidator {
    private HistoryPathValidator() {
    }

    static HistoryPathStatus validate(CompareHistoryEntry entry) {
        Path left = Paths.get(entry.leftPath());
        Path right = Paths.get(entry.rightPath());
        boolean leftExists = Files.exists(left);
        boolean rightExists = Files.exists(right);
        if (!leftExists && !rightExists) {
            return HistoryPathStatus.BOTH_MISSING;
        }
        if (!leftExists) {
            return HistoryPathStatus.LEFT_MISSING;
        }
        if (!rightExists) {
            return HistoryPathStatus.RIGHT_MISSING;
        }
        boolean directory = entry.mode() == CompareHistoryMode.DIRECTORY;
        boolean leftType = directory ? Files.isDirectory(left) : Files.isRegularFile(left);
        boolean rightType = directory ? Files.isDirectory(right) : Files.isRegularFile(right);
        return leftType && rightType ? HistoryPathStatus.AVAILABLE
                : HistoryPathStatus.TYPE_MISMATCH;
    }
}
