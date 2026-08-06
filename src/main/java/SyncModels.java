import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

enum SyncDirection {
    LEFT_TO_RIGHT("左侧", "右侧"),
    RIGHT_TO_LEFT("右侧", "左侧");

    private final String sourceLabel;
    private final String targetLabel;

    SyncDirection(String sourceLabel, String targetLabel) {
        this.sourceLabel = sourceLabel;
        this.targetLabel = targetLabel;
    }

    String getSourceLabel() {
        return sourceLabel;
    }

    String getTargetLabel() {
        return targetLabel;
    }

    String getDisplayName() {
        return sourceLabel + "同步到" + targetLabel;
    }
}

enum SyncAction {
    ADD("新增"),
    OVERWRITE("覆盖"),
    CREATE_DIRECTORY("创建目录"),
    SKIP("跳过");

    private final String displayName;

    SyncAction(String displayName) {
        this.displayName = displayName;
    }

    String getDisplayName() {
        return displayName;
    }

    boolean isExecutable() {
        return this != SKIP;
    }
}

enum SyncItemStatus {
    PENDING("待执行"),
    SUCCESS("已完成"),
    FAILED("失败"),
    NOT_EXECUTED("未执行"),
    CANCELLED("已取消"),
    ROLLED_BACK("已回滚"),
    ROLLBACK_CONFLICT("回滚冲突"),
    ROLLBACK_FAILED("回滚失败");

    private final String displayName;

    SyncItemStatus(String displayName) {
        this.displayName = displayName;
    }

    String getDisplayName() {
        return displayName;
    }
}

final class FileFingerprint {
    private final boolean exists;
    private final boolean directory;
    private final long size;
    private final long modifiedTime;
    private final String hash;

    FileFingerprint(boolean exists, boolean directory, long size,
                    long modifiedTime, String hash) {
        this.exists = exists;
        this.directory = directory;
        this.size = size;
        this.modifiedTime = modifiedTime;
        this.hash = hash == null ? "" : hash;
    }

    static FileFingerprint missing() {
        return new FileFingerprint(false, false, 0L, 0L, "");
    }

    static FileFingerprint knownFile(long size, long modifiedTime, String hash) {
        return new FileFingerprint(true, false, size, modifiedTime, hash);
    }

    static FileFingerprint knownDirectory(long modifiedTime) {
        return new FileFingerprint(true, true, 0L, modifiedTime, "");
    }

    boolean exists() {
        return exists;
    }

    boolean isDirectory() {
        return directory;
    }

    long getSize() {
        return size;
    }

    long getModifiedTime() {
        return modifiedTime;
    }

    String getHash() {
        return hash;
    }

    boolean sameState(FileFingerprint other) {
        if (other == null || exists != other.exists || directory != other.directory) {
            return false;
        }
        if (!exists) {
            return true;
        }
        if (directory) {
            return true;
        }
        return size == other.size && modifiedTime == other.modifiedTime
                && hash.equals(other.hash);
    }

    boolean sameContent(FileFingerprint other) {
        return other != null && exists && other.exists
                && !directory && !other.directory
                && size == other.size && hash.equals(other.hash);
    }
}

final class SyncComparisonEntry {
    private final String relativePath;
    private final Path leftPath;
    private final FileFingerprint leftFingerprint;
    private final Path rightPath;
    private final FileFingerprint rightFingerprint;

    SyncComparisonEntry(String relativePath, Path leftPath, FileFingerprint leftFingerprint,
                        Path rightPath, FileFingerprint rightFingerprint) {
        this.relativePath = relativePath;
        this.leftPath = leftPath;
        this.leftFingerprint = leftFingerprint == null
                ? FileFingerprint.missing() : leftFingerprint;
        this.rightPath = rightPath;
        this.rightFingerprint = rightFingerprint == null
                ? FileFingerprint.missing() : rightFingerprint;
    }

    String getRelativePath() {
        return relativePath;
    }

    Path sourcePath(SyncDirection direction) {
        return direction == SyncDirection.LEFT_TO_RIGHT ? leftPath : rightPath;
    }

    Path targetPath(SyncDirection direction) {
        return direction == SyncDirection.LEFT_TO_RIGHT ? rightPath : leftPath;
    }

    FileFingerprint sourceFingerprint(SyncDirection direction) {
        return direction == SyncDirection.LEFT_TO_RIGHT
                ? leftFingerprint : rightFingerprint;
    }

    FileFingerprint targetFingerprint(SyncDirection direction) {
        return direction == SyncDirection.LEFT_TO_RIGHT
                ? rightFingerprint : leftFingerprint;
    }
}

final class SyncPlanEntry {
    private final int index;
    private final String relativePath;
    private final SyncAction action;
    private final Path sourcePath;
    private final Path targetPath;
    private final FileFingerprint sourceFingerprint;
    private final FileFingerprint targetFingerprint;
    private final String skipReason;

    SyncPlanEntry(int index, String relativePath, SyncAction action,
                  Path sourcePath, Path targetPath,
                  FileFingerprint sourceFingerprint,
                  FileFingerprint targetFingerprint, String skipReason) {
        this.index = index;
        this.relativePath = relativePath;
        this.action = action;
        this.sourcePath = sourcePath;
        this.targetPath = targetPath;
        this.sourceFingerprint = sourceFingerprint == null
                ? FileFingerprint.missing() : sourceFingerprint;
        this.targetFingerprint = targetFingerprint == null
                ? FileFingerprint.missing() : targetFingerprint;
        this.skipReason = skipReason == null ? "" : skipReason;
    }

    int getIndex() {
        return index;
    }

    String getRelativePath() {
        return relativePath;
    }

    SyncAction getAction() {
        return action;
    }

    Path getSourcePath() {
        return sourcePath;
    }

    Path getTargetPath() {
        return targetPath;
    }

    FileFingerprint getSourceFingerprint() {
        return sourceFingerprint;
    }

    FileFingerprint getTargetFingerprint() {
        return targetFingerprint;
    }

    String getSkipReason() {
        return skipReason;
    }

    boolean isExecutable() {
        return action.isExecutable();
    }

    long getCopySize() {
        return sourceFingerprint.getSize();
    }

    long getBackupSize() {
        return action == SyncAction.OVERWRITE ? targetFingerprint.getSize() : 0L;
    }
}

final class SyncPlan {
    private final String transactionId;
    private final boolean directoryMode;
    private final SyncDirection direction;
    private final Path sourceRoot;
    private final Path targetRoot;
    private final List<SyncPlanEntry> entries;
    private final int excludedCount;

    SyncPlan(boolean directoryMode, SyncDirection direction, Path sourceRoot,
             Path targetRoot, List<SyncPlanEntry> entries, int excludedCount) {
        this.transactionId = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        this.directoryMode = directoryMode;
        this.direction = direction;
        this.sourceRoot = sourceRoot;
        this.targetRoot = targetRoot;
        this.entries = Collections.unmodifiableList(new ArrayList<SyncPlanEntry>(entries));
        this.excludedCount = excludedCount;
    }

    String getTransactionId() {
        return transactionId;
    }

    boolean isDirectoryMode() {
        return directoryMode;
    }

    SyncDirection getDirection() {
        return direction;
    }

    Path getSourceRoot() {
        return sourceRoot;
    }

    Path getTargetRoot() {
        return targetRoot;
    }

    List<SyncPlanEntry> getEntries() {
        return entries;
    }

    int getExcludedCount() {
        return excludedCount;
    }

    int count(SyncAction action) {
        int count = 0;
        for (SyncPlanEntry entry : entries) {
            if (entry.getAction() == action) {
                count++;
            }
        }
        return count;
    }

    Set<Integer> defaultSelection() {
        Set<Integer> selected = new LinkedHashSet<Integer>();
        for (SyncPlanEntry entry : entries) {
            if (entry.isExecutable()) {
                selected.add(Integer.valueOf(entry.getIndex()));
            }
        }
        return selected;
    }
}

final class SyncRequest {
    private final Set<Integer> selectedIndexes;
    private final boolean backupEnabled;

    SyncRequest(Set<Integer> selectedIndexes, boolean backupEnabled) {
        this.selectedIndexes = Collections.unmodifiableSet(
                new LinkedHashSet<Integer>(selectedIndexes));
        this.backupEnabled = backupEnabled;
    }

    Set<Integer> getSelectedIndexes() {
        return selectedIndexes;
    }

    boolean isSelected(SyncPlanEntry entry) {
        return selectedIndexes.contains(Integer.valueOf(entry.getIndex()));
    }

    boolean isBackupEnabled() {
        return backupEnabled;
    }
}

final class SyncItemResult {
    private final SyncPlanEntry entry;
    private SyncItemStatus status;
    private String stage;
    private String message;
    private Path backupPath;
    private String writtenHash;
    private boolean atomicMove = true;

    SyncItemResult(SyncPlanEntry entry, SyncItemStatus status) {
        this.entry = entry;
        this.status = status;
        this.stage = "";
        this.message = "";
    }

    SyncPlanEntry getEntry() {
        return entry;
    }

    SyncItemStatus getStatus() {
        return status;
    }

    void setStatus(SyncItemStatus status) {
        this.status = status;
    }

    String getStage() {
        return stage;
    }

    void setStage(String stage) {
        this.stage = stage == null ? "" : stage;
    }

    String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message == null ? "" : message;
    }

    Path getBackupPath() {
        return backupPath;
    }

    void setBackupPath(Path backupPath) {
        this.backupPath = backupPath;
    }

    String getWrittenHash() {
        return writtenHash;
    }

    void setWrittenHash(String writtenHash) {
        this.writtenHash = writtenHash;
    }

    boolean isAtomicMove() {
        return atomicMove;
    }

    void setAtomicMove(boolean atomicMove) {
        this.atomicMove = atomicMove;
    }
}

final class SyncExecutionResult {
    private final SyncPlan plan;
    private final SyncRequest request;
    private final Path transactionDirectory;
    private final List<SyncItemResult> itemResults;
    private final Set<Path> createdDirectories = new LinkedHashSet<Path>();
    private final long startedAt;
    private long finishedAt;

    SyncExecutionResult(SyncPlan plan, SyncRequest request, Path transactionDirectory,
                        List<SyncItemResult> itemResults) {
        this.plan = plan;
        this.request = request;
        this.transactionDirectory = transactionDirectory;
        this.itemResults = itemResults;
        this.startedAt = System.currentTimeMillis();
    }

    SyncPlan getPlan() {
        return plan;
    }

    SyncRequest getRequest() {
        return request;
    }

    Path getTransactionDirectory() {
        return transactionDirectory;
    }

    List<SyncItemResult> getItemResults() {
        return Collections.unmodifiableList(itemResults);
    }

    void addCreatedDirectory(Path directory) {
        createdDirectories.add(directory);
    }

    void removeCreatedDirectory(Path directory) {
        createdDirectories.remove(directory);
    }

    Set<Path> getCreatedDirectories() {
        return Collections.unmodifiableSet(createdDirectories);
    }

    void finish() {
        finishedAt = System.currentTimeMillis();
    }

    long getDurationMillis() {
        long end = finishedAt == 0L ? System.currentTimeMillis() : finishedAt;
        return Math.max(0L, end - startedAt);
    }

    int count(SyncItemStatus status) {
        int count = 0;
        for (SyncItemResult item : itemResults) {
            if (item.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    boolean hasFailures() {
        return count(SyncItemStatus.FAILED) > 0
                || count(SyncItemStatus.ROLLBACK_FAILED) > 0
                || count(SyncItemStatus.ROLLBACK_CONFLICT) > 0;
    }

    boolean hasRollbackProblems() {
        return count(SyncItemStatus.ROLLBACK_FAILED) > 0
                || count(SyncItemStatus.ROLLBACK_CONFLICT) > 0;
    }

    boolean wasCancelled() {
        return count(SyncItemStatus.CANCELLED) > 0;
    }

    boolean hasRollbackCandidates() {
        for (SyncItemResult item : itemResults) {
            if (item.getStatus() == SyncItemStatus.SUCCESS
                    && (item.getEntry().getAction() == SyncAction.ADD
                    || item.getEntry().getAction() == SyncAction.OVERWRITE
                    || item.getEntry().getAction() == SyncAction.CREATE_DIRECTORY)) {
                if (item.getEntry().getAction() != SyncAction.OVERWRITE
                        || item.getBackupPath() != null) {
                    return true;
                }
            }
        }
        return false;
    }
}

final class SyncPlanBuilder {
    SyncPlan build(boolean directoryMode, SyncDirection direction,
                   Path leftRoot, Path rightRoot,
                   List<SyncComparisonEntry> comparedEntries,
                   Set<String> leftDirectories, Set<String> rightDirectories,
                   int excludedCount) {
        Path sourceRoot = direction == SyncDirection.LEFT_TO_RIGHT ? leftRoot : rightRoot;
        Path targetRoot = direction == SyncDirection.LEFT_TO_RIGHT ? rightRoot : leftRoot;
        List<SyncPlanEntry> planned = new ArrayList<SyncPlanEntry>();
        int index = 0;

        if (directoryMode) {
            Set<String> sourceDirectories = direction == SyncDirection.LEFT_TO_RIGHT
                    ? leftDirectories : rightDirectories;
            Set<String> targetDirectories = direction == SyncDirection.LEFT_TO_RIGHT
                    ? rightDirectories : leftDirectories;
            Set<String> nonEmptyDirectories = sourceParentDirectories(comparedEntries, direction);
            List<String> sorted = new ArrayList<String>(sourceDirectories);
            Collections.sort(sorted);
            for (String relative : sorted) {
                if (!targetDirectories.contains(relative)
                        && !nonEmptyDirectories.contains(relative)) {
                    planned.add(new SyncPlanEntry(index++, relative,
                            SyncAction.CREATE_DIRECTORY,
                            sourceRoot.resolve(relative), targetRoot.resolve(relative),
                            FileFingerprint.knownDirectory(0L), FileFingerprint.missing(), ""));
                }
            }
        }

        for (SyncComparisonEntry compared : comparedEntries) {
            FileFingerprint source = compared.sourceFingerprint(direction);
            FileFingerprint target = compared.targetFingerprint(direction);
            SyncAction action;
            String reason = "";
            if (!source.exists()) {
                action = SyncAction.SKIP;
                reason = "来源侧不存在，不删除目标文件";
            } else if (source.sameContent(target)) {
                action = SyncAction.SKIP;
                reason = "内容已经一致";
            } else if (!target.exists()) {
                action = SyncAction.ADD;
            } else {
                action = SyncAction.OVERWRITE;
            }
            Path sourcePath = compared.sourcePath(direction);
            Path targetPath = compared.targetPath(direction);
            if (targetPath == null) {
                targetPath = directoryMode
                        ? targetRoot.resolve(compared.getRelativePath()) : targetRoot;
            }
            planned.add(new SyncPlanEntry(index++, compared.getRelativePath(), action,
                    sourcePath, targetPath, source, target, reason));
        }
        return new SyncPlan(directoryMode, direction, sourceRoot, targetRoot,
                planned, excludedCount);
    }

    private Set<String> sourceParentDirectories(List<SyncComparisonEntry> entries,
                                                SyncDirection direction) {
        Set<String> parents = new HashSet<String>();
        for (SyncComparisonEntry entry : entries) {
            if (!entry.sourceFingerprint(direction).exists()) {
                continue;
            }
            String path = entry.getRelativePath().replace('\\', '/');
            int slash = path.lastIndexOf('/');
            while (slash > 0) {
                path = path.substring(0, slash);
                parents.add(path);
                slash = path.lastIndexOf('/');
            }
        }
        return parents;
    }
}
