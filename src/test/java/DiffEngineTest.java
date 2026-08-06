import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DiffEngineTest {
    public static void main(String[] args) {
        DiffEngine engine = new MyersDiffEngine();

        assertHunk(engine.diff(lines("a", "old", "z"), lines("a", "new", "z")),
                DiffHunk.Type.CHANGE, 1, 1, lines("old"), lines("new"));
        assertHunk(engine.diff(lines("a", "extra", "z"), lines("a", "z")),
                DiffHunk.Type.LEFT_ONLY, 1, 1, lines("extra"), Collections.<String>emptyList());
        assertHunk(engine.diff(lines("a", "z"), lines("a", "extra", "z")),
                DiffHunk.Type.RIGHT_ONLY, 1, 1, Collections.<String>emptyList(), lines("extra"));

        List<DiffHunk> repeated = engine.diff(
                lines("same", "repeat", "left", "repeat", "end"),
                lines("same", "repeat", "right", "repeat", "end"));
        assertHunk(repeated, DiffHunk.Type.CHANGE, 2, 2, lines("left"), lines("right"));

        DiffHunk uneven = engine.diff(lines("left-1", "left-2"), lines("right")).get(0);
        if (!uneven.deletesWhenAppliedToLeft() || uneven.deletesWhenAppliedToRight()) {
            throw new AssertionError("Uneven change must mark only the shrinking direction as deletion");
        }

        if (!engine.diff(Collections.<String>emptyList(), Collections.<String>emptyList()).isEmpty()) {
            throw new AssertionError("Two empty files must have no hunks");
        }
        System.out.println("DiffEngineTest passed");
    }

    private static List<String> lines(String... values) {
        return Arrays.asList(values);
    }

    private static void assertHunk(List<DiffHunk> hunks, DiffHunk.Type type,
                                   int leftStart, int rightStart,
                                   List<String> leftLines, List<String> rightLines) {
        if (hunks.size() != 1) {
            throw new AssertionError("Expected one hunk but got " + hunks.size());
        }
        DiffHunk hunk = hunks.get(0);
        if (hunk.getType() != type || hunk.getLeftStart() != leftStart
                || hunk.getRightStart() != rightStart
                || !hunk.getLeftLines().equals(leftLines)
                || !hunk.getRightLines().equals(rightLines)) {
            throw new AssertionError("Unexpected hunk: " + hunk.getType()
                    + " at " + hunk.getLeftStart() + "/" + hunk.getRightStart());
        }
    }
}
