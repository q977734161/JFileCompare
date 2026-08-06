import java.util.List;

interface DiffEngine {
    List<DiffHunk> diff(List<String> leftLines, List<String> rightLines);
}
