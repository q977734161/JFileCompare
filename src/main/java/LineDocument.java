import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LineDocument {
    private final List<String> lines;
    private final List<String> lineEndings;
    private final String preferredLineSeparator;

    private LineDocument(List<String> lines, List<String> lineEndings,
                         String preferredLineSeparator) {
        if (lines.size() != lineEndings.size()) {
            throw new IllegalArgumentException("Each line must have one line-ending entry");
        }
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
        this.lineEndings = Collections.unmodifiableList(new ArrayList<String>(lineEndings));
        this.preferredLineSeparator = validSeparator(preferredLineSeparator)
                ? preferredLineSeparator : "\n";
    }

    static LineDocument parse(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            return empty("\n");
        }
        List<String> parsedLines = new ArrayList<String>();
        List<String> endings = new ArrayList<String>();
        int lineStart = 0;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n') {
                parsedLines.add(value.substring(lineStart, index));
                if (current == '\r' && index + 1 < value.length()
                        && value.charAt(index + 1) == '\n') {
                    endings.add("\r\n");
                    index += 2;
                } else {
                    endings.add(String.valueOf(current));
                    index++;
                }
                lineStart = index;
            } else {
                index++;
            }
        }
        if (lineStart < value.length()) {
            parsedLines.add(value.substring(lineStart));
            endings.add("");
        }
        return new LineDocument(parsedLines, endings, preferredSeparator(endings));
    }

    static LineDocument empty(String preferredSeparator) {
        return new LineDocument(Collections.<String>emptyList(),
                Collections.<String>emptyList(), preferredSeparator);
    }

    private static String preferredSeparator(List<String> endings) {
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        String first = null;
        for (String ending : endings) {
            if (ending.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = ending;
            }
            if ("\r\n".equals(ending)) {
                crlf++;
            } else if ("\n".equals(ending)) {
                lf++;
            } else if ("\r".equals(ending)) {
                cr++;
            }
        }
        int max = Math.max(crlf, Math.max(lf, cr));
        if (max == 0) {
            return "\n";
        }
        if (crlf == max && "\r\n".equals(first)) {
            return "\r\n";
        }
        if (lf == max && "\n".equals(first)) {
            return "\n";
        }
        if (cr == max && "\r".equals(first)) {
            return "\r";
        }
        if (crlf == max) {
            return "\r\n";
        }
        if (lf == max) {
            return "\n";
        }
        return "\r";
    }

    private static boolean validSeparator(String value) {
        return "\n".equals(value) || "\r\n".equals(value) || "\r".equals(value);
    }

    List<String> getLines() {
        return lines;
    }

    List<String> getLineEndings() {
        return lineEndings;
    }

    String getLineSeparator() {
        return preferredLineSeparator;
    }

    String getPreferredLineSeparator() {
        return preferredLineSeparator;
    }

    boolean hasTrailingNewline() {
        return !lineEndings.isEmpty() && !lineEndings.get(lineEndings.size() - 1).isEmpty();
    }

    boolean hasMixedLineEndings() {
        String found = null;
        for (String ending : lineEndings) {
            if (ending.isEmpty()) {
                continue;
            }
            if (found == null) {
                found = ending;
            } else if (!found.equals(ending)) {
                return true;
            }
        }
        return false;
    }

    int countLineEnding(String ending) {
        int count = 0;
        for (String value : lineEndings) {
            if (ending.equals(value)) {
                count++;
            }
        }
        return count;
    }

    String getLineEndingDisplayName() {
        if (hasMixedLineEndings()) {
            return "混合换行";
        }
        return separatorName(preferredLineSeparator);
    }

    String getLineEndingSummary() {
        return "CRLF " + countLineEnding("\r\n")
                + "，LF " + countLineEnding("\n")
                + "，CR " + countLineEnding("\r")
                + "，末尾" + (hasTrailingNewline() ? "有换行" : "无换行");
    }

    private static String separatorName(String separator) {
        if ("\r\n".equals(separator)) {
            return "CRLF";
        }
        if ("\r".equals(separator)) {
            return "CR";
        }
        return "LF";
    }

    LineDocument replaceLine(int index, String text) {
        return replaceLines(index, index + 1,
                Collections.singletonList(text == null ? "" : text), null);
    }

    LineDocument insertLine(int index, String text) {
        return replaceLines(index, index,
                Collections.singletonList(text == null ? "" : text), null);
    }

    LineDocument deleteLine(int index) {
        return replaceLines(index, index + 1, Collections.<String>emptyList(), null);
    }

    LineDocument withLines(List<String> newLines) {
        return replaceLines(0, lines.size(), newLines, null);
    }

    LineDocument withLines(List<String> newLines, String separator, boolean trailing) {
        List<String> endings = new ArrayList<String>();
        for (int i = 0; i < newLines.size(); i++) {
            boolean last = i == newLines.size() - 1;
            endings.add(last && !trailing ? "" : separator);
        }
        return new LineDocument(newLines, endings, separator);
    }

    LineDocument replaceLines(int start, int end, List<String> replacement,
                              LineDocument sourceDocument) {
        if (start < 0 || end < start || end > lines.size()) {
            throw new IndexOutOfBoundsException("Invalid line range " + start + ".." + end);
        }
        if (lines.isEmpty() && start == 0 && end == 0 && sourceDocument != null
                && replacement.equals(sourceDocument.getLines())) {
            return sourceDocument;
        }

        List<String> updatedLines = new ArrayList<String>();
        List<String> updatedEndings = new ArrayList<String>();
        updatedLines.addAll(lines.subList(0, start));
        updatedEndings.addAll(lineEndings.subList(0, start));

        for (int i = 0; i < replacement.size(); i++) {
            updatedLines.add(replacement.get(i));
            int oldIndex = start + i;
            if (oldIndex < end) {
                updatedEndings.add(lineEndings.get(oldIndex));
            } else {
                updatedEndings.add(preferredLineSeparator);
            }
        }

        updatedLines.addAll(lines.subList(end, lines.size()));
        updatedEndings.addAll(lineEndings.subList(end, lineEndings.size()));
        if (updatedLines.isEmpty()) {
            return empty(preferredLineSeparator);
        }

        for (int i = 0; i < updatedEndings.size() - 1; i++) {
            if (updatedEndings.get(i).isEmpty()) {
                updatedEndings.set(i, preferredLineSeparator);
            }
        }
        if (end == lines.size()) {
            String originalTrailing = lineEndings.isEmpty()
                    ? "" : lineEndings.get(lineEndings.size() - 1);
            updatedEndings.set(updatedEndings.size() - 1, originalTrailing);
        }
        return new LineDocument(updatedLines, updatedEndings, preferredLineSeparator);
    }

    LineDocument copyContentFrom(LineDocument source, boolean inheritSourceFormatting) {
        if (inheritSourceFormatting) {
            return source;
        }
        if (lines.isEmpty()) {
            return empty(preferredLineSeparator).withLines(source.getLines(),
                    preferredLineSeparator, source.hasTrailingNewline());
        }
        return replaceLines(0, lines.size(), source.getLines(), source);
    }

    String toText() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            builder.append(lines.get(i));
            builder.append(lineEndings.get(i));
        }
        return builder.toString();
    }
}
