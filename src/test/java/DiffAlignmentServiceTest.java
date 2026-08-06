import java.util.List;

public class DiffAlignmentServiceTest {
    public static void main(String[] args) {
        DiffEngine engine = new MyersDiffEngine();
        DiffAlignmentService alignment = new DiffAlignmentService();

        LineDocument left = LineDocument.parse("a\nleft-1\nleft-2\nz");
        LineDocument right = LineDocument.parse("a\nright\nz");
        List<DiffHunk> hunks = engine.diff(left.getLines(), right.getLines());
        List<AlignedDiffRow> rows = alignment.align(left.getLines(), right.getLines(), hunks);

        if (rows.size() != 4) {
            throw new AssertionError("Expected four aligned rows but got " + rows.size());
        }
        if (rows.get(0).getStatus() != AlignedDiffRow.Status.SAME
                || rows.get(3).getStatus() != AlignedDiffRow.Status.SAME) {
            throw new AssertionError("Equal rows were not preserved");
        }
        if (rows.get(2).getRightLineIndex() != -1 || rows.get(2).getRightText() != null) {
            throw new AssertionError("Shorter right hunk needs a placeholder row");
        }

        LineDocument rightOnly = LineDocument.parse("a\ninserted\nz");
        LineDocument shortLeft = LineDocument.parse("a\nz");
        List<AlignedDiffRow> insertedRows = alignment.align(shortLeft.getLines(), rightOnly.getLines(),
                engine.diff(shortLeft.getLines(), rightOnly.getLines()));
        if (insertedRows.get(1).getLeftLineIndex() != -1
                || insertedRows.get(1).getStatus() != AlignedDiffRow.Status.RIGHT_ONLY) {
            throw new AssertionError("Right-only row needs a left placeholder");
        }
        System.out.println("DiffAlignmentServiceTest passed");
    }
}
