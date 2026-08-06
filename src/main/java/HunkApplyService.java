import java.util.ArrayList;
import java.util.List;

final class HunkApplyService {
    enum Direction {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT
    }

    ApplyResult apply(LineDocument left, LineDocument right, DiffHunk hunk, Direction direction) {
        if (direction == Direction.LEFT_TO_RIGHT) {
            LineDocument updatedRight = replace(
                    right, hunk.getRightStart(), hunk.getRightEnd(), hunk.getLeftLines(), left, hunk);
            return new ApplyResult(left, updatedRight);
        }
        LineDocument updatedLeft = replace(
                left, hunk.getLeftStart(), hunk.getLeftEnd(), hunk.getRightLines(), right, hunk);
        return new ApplyResult(updatedLeft, right);
    }

    private LineDocument replace(LineDocument target, int start, int end,
                                 List<String> replacement, LineDocument source, DiffHunk hunk) {
        return target.replaceLines(start, end, replacement, source);
    }

    static final class ApplyResult {
        private final LineDocument left;
        private final LineDocument right;

        private ApplyResult(LineDocument left, LineDocument right) {
            this.left = left;
            this.right = right;
        }

        LineDocument getLeft() {
            return left;
        }

        LineDocument getRight() {
            return right;
        }
    }
}
