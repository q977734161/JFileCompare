import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.List;

final class MyersDiffEngine implements DiffEngine {
    @Override
    public List<DiffHunk> diff(List<String> leftLines, List<String> rightLines) {
        Patch<String> patch = DiffUtils.diff(leftLines, rightLines);
        List<DiffHunk> hunks = new ArrayList<DiffHunk>();
        int id = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            DiffHunk.Type type = mapType(delta.getType());
            hunks.add(new DiffHunk(
                    id++,
                    type,
                    delta.getSource().getPosition(),
                    delta.getTarget().getPosition(),
                    delta.getSource().getLines(),
                    delta.getTarget().getLines()));
        }
        return hunks;
    }

    private DiffHunk.Type mapType(DeltaType type) {
        if (type == DeltaType.DELETE) {
            return DiffHunk.Type.LEFT_ONLY;
        }
        if (type == DeltaType.INSERT) {
            return DiffHunk.Type.RIGHT_ONLY;
        }
        return DiffHunk.Type.CHANGE;
    }
}
