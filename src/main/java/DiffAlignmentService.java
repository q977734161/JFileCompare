import java.util.ArrayList;
import java.util.List;

final class DiffAlignmentService {
    List<AlignedDiffRow> align(List<String> leftLines, List<String> rightLines,
                               List<DiffHunk> hunks) {
        List<AlignedDiffRow> rows = new ArrayList<AlignedDiffRow>();
        int leftCursor = 0;
        int rightCursor = 0;
        for (DiffHunk hunk : hunks) {
            while (leftCursor < hunk.getLeftStart() && rightCursor < hunk.getRightStart()) {
                rows.add(new AlignedDiffRow(-1, AlignedDiffRow.Status.SAME,
                        leftCursor, rightCursor, leftLines.get(leftCursor), rightLines.get(rightCursor)));
                leftCursor++;
                rightCursor++;
            }
            int rowCount = Math.max(hunk.getLeftLines().size(), hunk.getRightLines().size());
            AlignedDiffRow.Status status = statusFor(hunk.getType());
            for (int i = 0; i < rowCount; i++) {
                int leftIndex = i < hunk.getLeftLines().size() ? hunk.getLeftStart() + i : -1;
                int rightIndex = i < hunk.getRightLines().size() ? hunk.getRightStart() + i : -1;
                String leftText = leftIndex >= 0 ? leftLines.get(leftIndex) : null;
                String rightText = rightIndex >= 0 ? rightLines.get(rightIndex) : null;
                rows.add(new AlignedDiffRow(hunk.getId(), status,
                        leftIndex, rightIndex, leftText, rightText));
            }
            leftCursor = hunk.getLeftEnd();
            rightCursor = hunk.getRightEnd();
        }
        while (leftCursor < leftLines.size() && rightCursor < rightLines.size()) {
            rows.add(new AlignedDiffRow(-1, AlignedDiffRow.Status.SAME,
                    leftCursor, rightCursor, leftLines.get(leftCursor), rightLines.get(rightCursor)));
            leftCursor++;
            rightCursor++;
        }
        return rows;
    }

    private AlignedDiffRow.Status statusFor(DiffHunk.Type type) {
        if (type == DiffHunk.Type.LEFT_ONLY) {
            return AlignedDiffRow.Status.LEFT_ONLY;
        }
        if (type == DiffHunk.Type.RIGHT_ONLY) {
            return AlignedDiffRow.Status.RIGHT_ONLY;
        }
        return AlignedDiffRow.Status.CHANGE;
    }
}
