import java.util.List;

public class HunkApplyServiceTest {
    public static void main(String[] args) {
        DiffEngine engine = new MyersDiffEngine();
        HunkApplyService service = new HunkApplyService();

        assertApply(engine, service, "a\nold\nz\n", "a\nnew\nz\n", true,
                "a\nold\nz\n", "a\nold\nz\n");
        assertApply(engine, service, "a\nold\nz\n", "a\nnew\nz\n", false,
                "a\nnew\nz\n", "a\nnew\nz\n");
        assertApply(engine, service, "a\nextra\nz\n", "a\nz\n", true,
                "a\nextra\nz\n", "a\nextra\nz\n");
        assertApply(engine, service, "a\nextra\nz\n", "a\nz\n", false,
                "a\nz\n", "a\nz\n");
        assertApply(engine, service, "a\nz\n", "a\nextra\nz\n", true,
                "a\nz\n", "a\nz\n");
        assertApply(engine, service, "a\nz\n", "a\nextra\nz\n", false,
                "a\nextra\nz\n", "a\nextra\nz\n");

        LineDocument crlf = LineDocument.parse("left\r\nvalue\r\n");
        if (!"left\r\nvalue\r\n".equals(crlf.toText())) {
            throw new AssertionError("CRLF and trailing newline must be preserved");
        }
        LineDocument noTrailing = LineDocument.parse("left\nvalue");
        if (!"left\nvalue".equals(noTrailing.toText())) {
            throw new AssertionError("Missing trailing newline must be preserved");
        }
        System.out.println("HunkApplyServiceTest passed");
    }

    private static void assertApply(DiffEngine engine, HunkApplyService service,
                                    String leftText, String rightText, boolean leftToRight,
                                    String expectedLeft, String expectedRight) {
        LineDocument left = LineDocument.parse(leftText);
        LineDocument right = LineDocument.parse(rightText);
        List<DiffHunk> hunks = engine.diff(left.getLines(), right.getLines());
        if (hunks.size() != 1) {
            throw new AssertionError("Expected one hunk but got " + hunks.size());
        }
        HunkApplyService.ApplyResult result = service.apply(left, right, hunks.get(0),
                leftToRight ? HunkApplyService.Direction.LEFT_TO_RIGHT
                        : HunkApplyService.Direction.RIGHT_TO_LEFT);
        if (!expectedLeft.equals(result.getLeft().toText())
                || !expectedRight.equals(result.getRight().toText())) {
            throw new AssertionError("Unexpected apply result: [" + result.getLeft().toText()
                    + "] / [" + result.getRight().toText() + "]");
        }
    }
}
