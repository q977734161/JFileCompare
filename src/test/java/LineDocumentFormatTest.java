import java.util.List;

public class LineDocumentFormatTest {
    public static void main(String[] args) {
        LineDocument mixed = LineDocument.parse("a\r\nb\nc\rd");
        assertText("a\r\nb\nc\rd", mixed.toText(), "mixed line endings");
        if (!mixed.hasMixedLineEndings() || mixed.hasTrailingNewline()) {
            throw new AssertionError("Mixed endings and missing trailing newline were not detected");
        }

        assertText("a\r\nchanged\nc\rd", mixed.replaceLine(1, "changed").toText(),
                "line replacement");
        assertText("a\r\ninserted\r\nb\nc\rd", mixed.insertLine(1, "inserted").toText(),
                "line insertion");
        assertText("a\r\nb\nc", mixed.deleteLine(3).toText(), "delete final line");

        LineDocument noTrailing = LineDocument.parse("a\r\nb");
        assertText("a", noTrailing.deleteLine(1).toText(),
                "deleting final line must preserve no trailing newline");

        DiffEngine engine = new MyersDiffEngine();
        HunkApplyService service = new HunkApplyService();
        LineDocument source = LineDocument.parse("a\nnew-1\nnew-2\nz\n");
        LineDocument target = LineDocument.parse("a\r\nold\r\nz");
        List<DiffHunk> hunks = engine.diff(source.getLines(), target.getLines());
        HunkApplyService.ApplyResult changed = service.apply(source, target, hunks.get(0),
                HunkApplyService.Direction.LEFT_TO_RIGHT);
        assertText("a\r\nnew-1\r\nnew-2\r\nz", changed.getRight().toText(),
                "target formatting during hunk copy");

        LineDocument empty = LineDocument.parse("");
        DiffHunk wholeFile = engine.diff(source.getLines(), empty.getLines()).get(0);
        HunkApplyService.ApplyResult inherited = service.apply(source, empty, wholeFile,
                HunkApplyService.Direction.LEFT_TO_RIGHT);
        assertText(source.toText(), inherited.getRight().toText(),
                "empty target must inherit source formatting");
        LineDocument existingEmpty = LineDocument.empty("\r\n");
        assertText("a\r\nnew-1\r\nnew-2\r\nz\r\n",
                existingEmpty.copyContentFrom(source, false).toText(),
                "existing empty target must use its own preferred line ending");
        System.out.println("LineDocumentFormatTest passed");
    }

    private static void assertText(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected [" + printable(expected)
                    + "] but was [" + printable(actual) + "]");
        }
    }

    private static String printable(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
