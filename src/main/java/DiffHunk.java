import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DiffHunk {
    enum Type {
        CHANGE,
        LEFT_ONLY,
        RIGHT_ONLY
    }

    private final int id;
    private final Type type;
    private final int leftStart;
    private final int rightStart;
    private final List<String> leftLines;
    private final List<String> rightLines;

    DiffHunk(int id, Type type, int leftStart, int rightStart,
             List<String> leftLines, List<String> rightLines) {
        this.id = id;
        this.type = type;
        this.leftStart = leftStart;
        this.rightStart = rightStart;
        this.leftLines = immutableCopy(leftLines);
        this.rightLines = immutableCopy(rightLines);
    }

    private static List<String> immutableCopy(List<String> lines) {
        return Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    int getId() {
        return id;
    }

    Type getType() {
        return type;
    }

    int getLeftStart() {
        return leftStart;
    }

    int getLeftEnd() {
        return leftStart + leftLines.size();
    }

    int getRightStart() {
        return rightStart;
    }

    int getRightEnd() {
        return rightStart + rightLines.size();
    }

    List<String> getLeftLines() {
        return leftLines;
    }

    List<String> getRightLines() {
        return rightLines;
    }

    boolean deletesWhenAppliedToLeft() {
        return rightLines.size() < leftLines.size();
    }

    boolean deletesWhenAppliedToRight() {
        return leftLines.size() < rightLines.size();
    }
}
