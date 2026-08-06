import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FilterRuleSet implements CompareScanService.ScanFilter {
    static final int MAX_TEXT_LENGTH = 8192;
    static final int MAX_RULE_LENGTH = 256;
    static final int MAX_DIRECTORY_RULES = 200;
    static final int MAX_EXTENSION_RULES = 200;
    static final int MAX_WILDCARD_RULES = 100;
    static final int MAX_TOTAL_RULES = 500;

    private final List<String> directoryNames;
    private final List<String> extensions;
    private final List<String> wildcardValues;
    private final List<Pattern> wildcardPatterns;
    private final String directoryText;
    private final String extensionText;
    private final String wildcardText;
    private final String canonicalKey;

    private FilterRuleSet(List<String> directoryNames, List<String> extensions,
                          List<String> wildcardValues, List<Pattern> wildcardPatterns,
                          String directoryText, String extensionText, String wildcardText) {
        this.directoryNames = Collections.unmodifiableList(directoryNames);
        this.extensions = Collections.unmodifiableList(extensions);
        this.wildcardValues = Collections.unmodifiableList(wildcardValues);
        this.wildcardPatterns = Collections.unmodifiableList(wildcardPatterns);
        this.directoryText = directoryText;
        this.extensionText = extensionText;
        this.wildcardText = wildcardText;
        this.canonicalKey = canonical(directoryNames) + "\n" + canonical(extensions)
                + "\n" + canonical(wildcardValues);
    }

    static FilterRuleSet empty() {
        return fromText("", "", "");
    }

    static FilterRuleSet fromText(String directoryText, String extensionText,
                                  String wildcardText) {
        validateTextLength("目录名规则", directoryText);
        validateTextLength("扩展名规则", extensionText);
        validateTextLength("通配符规则", wildcardText);

        List<String> directories = splitValues("目录名规则", directoryText);
        List<String> rawExtensions = splitValues("扩展名规则", extensionText);
        Set<String> normalizedExtensions = new LinkedHashSet<String>();
        for (String value : rawExtensions) {
            normalizedExtensions.add(value.startsWith(".") ? value : "." + value);
        }
        List<String> extensions = new ArrayList<String>(normalizedExtensions);
        List<String> wildcards = splitValues("通配符规则", wildcardText);

        validateCount("目录名规则", directories.size(), MAX_DIRECTORY_RULES);
        validateCount("扩展名规则", extensions.size(), MAX_EXTENSION_RULES);
        validateCount("通配符规则", wildcards.size(), MAX_WILDCARD_RULES);
        int total = directories.size() + extensions.size() + wildcards.size();
        if (total > MAX_TOTAL_RULES) {
            throw new IllegalArgumentException("过滤规则总数不能超过 " + MAX_TOTAL_RULES + " 条");
        }

        List<Pattern> patterns = new ArrayList<Pattern>();
        for (String wildcard : wildcards) {
            patterns.add(Pattern.compile(wildcardToRegex(wildcard), Pattern.CASE_INSENSITIVE));
        }
        return new FilterRuleSet(directories, extensions, wildcards, patterns,
                cleanText(directoryText), cleanText(extensionText), cleanText(wildcardText));
    }

    private static void validateTextLength(String label, String text) {
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(label + "最多输入 " + MAX_TEXT_LENGTH + " 个字符");
        }
    }

    private static void validateCount(String label, int count, int maximum) {
        if (count > maximum) {
            throw new IllegalArgumentException(label + "不能超过 " + maximum + " 条");
        }
    }

    private static List<String> splitValues(String label, String text) {
        Set<String> values = new LinkedHashSet<String>();
        if (text == null) {
            return new ArrayList<String>();
        }
        String[] tokens = text.split("[,;\\r\\n]+", -1);
        for (String token : tokens) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_RULE_LENGTH) {
                throw new IllegalArgumentException(label + "中的单条规则不能超过 "
                        + MAX_RULE_LENGTH + " 个字符：" + abbreviate(value));
            }
            values.add(value.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<String>(values);
    }

    private static String abbreviate(String value) {
        return value.length() <= 32 ? value : value.substring(0, 29) + "...";
    }

    private static String cleanText(String text) {
        return text == null ? "" : text.trim();
    }

    private static String canonical(List<String> values) {
        List<String> sorted = new ArrayList<String>(values);
        Collections.sort(sorted);
        StringBuilder result = new StringBuilder();
        for (String value : sorted) {
            if (result.length() > 0) {
                result.append('\u001f');
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String wildcardToRegex(String wildcard) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < wildcard.length(); i++) {
            char value = wildcard.charAt(i);
            if (value == '*') {
                regex.append(".*");
            } else if (value == '?') {
                regex.append('.');
            } else {
                if ("\\.[]{}()+-^$|".indexOf(value) >= 0) {
                    regex.append('\\');
                }
                regex.append(value);
            }
        }
        return regex.append('$').toString();
    }

    @Override
    public boolean matchesDirectory(String relativePath) {
        String normalizedPath = normalizePath(relativePath);
        String[] parts = normalizedPath.split("/");
        for (String part : parts) {
            if (directoryNames.contains(part.toLowerCase(Locale.ROOT))
                    || matchesWildcard(part) || matchesWildcard(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean matchesFile(String relativePath) {
        String normalizedPath = normalizePath(relativePath);
        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
        String fileName = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        for (String extension : extensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return matchesWildcard(normalizedPath) || matchesWildcard(fileName);
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private boolean matchesWildcard(String value) {
        for (Pattern pattern : wildcardPatterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty() {
        return directoryNames.isEmpty() && extensions.isEmpty() && wildcardPatterns.isEmpty();
    }

    int directoryCount() {
        return directoryNames.size();
    }

    int extensionCount() {
        return extensions.size();
    }

    int wildcardCount() {
        return wildcardPatterns.size();
    }

    int totalCount() {
        return directoryCount() + extensionCount() + wildcardCount();
    }

    String directoryText() {
        return directoryText;
    }

    String extensionText() {
        return extensionText;
    }

    String wildcardText() {
        return wildcardText;
    }

    String summaryText() {
        return "当前将排除：" + directoryCount() + " 个目录规则 · "
                + extensionCount() + " 个扩展名规则 · " + wildcardCount() + " 个通配符规则";
    }

    String canonicalKey() {
        return canonicalKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FilterRuleSet
                && canonicalKey.equals(((FilterRuleSet) other).canonicalKey);
    }

    @Override
    public int hashCode() {
        return canonicalKey.hashCode();
    }
}
