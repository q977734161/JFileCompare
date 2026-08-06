import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

interface SyncProgressListener {
    void onProgress(SyncProgressUpdate update);
}

final class SyncProgressUpdate {
    private final String stage;
    private final String relativePath;
    private final String message;
    private final int completed;
    private final int total;

    SyncProgressUpdate(String stage, String relativePath, String message,
                       int completed, int total) {
        this.stage = stage;
        this.relativePath = relativePath == null ? "" : relativePath;
        this.message = message == null ? "" : message;
        this.completed = completed;
        this.total = total;
    }

    String getStage() {
        return stage;
    }

    String getRelativePath() {
        return relativePath;
    }

    String getMessage() {
        return message;
    }

    int getCompleted() {
        return completed;
    }

    int getTotal() {
        return total;
    }
}

final class SyncService {
    private static final String MANIFEST_NAME = "manifest.properties";
    private static final int RETAINED_COMPLETED_TRANSACTIONS = 10;

    private final Path backupRoot;

    SyncService() {
        this(defaultBackupRoot());
    }

    SyncService(Path backupRoot) {
        this.backupRoot = backupRoot;
    }

    Path getBackupRoot() {
        return backupRoot;
    }

    SyncExecutionResult execute(SyncPlan plan, SyncRequest request,
                                SyncProgressListener listener,
                                AtomicBoolean cancelled) {
        List<SyncItemResult> results = selectedResults(plan, request);
        Path transactionDirectory = backupRoot.resolve(plan.getTransactionId());
        SyncExecutionResult execution = new SyncExecutionResult(
                plan, request, transactionDirectory, results);
        if (results.isEmpty()) {
            execution.finish();
            return execution;
        }

        notify(listener, "复核", "", "正在复核所选文件", 0, results.size());
        SyncItemResult preflightFailure = preflight(plan, request, results);
        if (preflightFailure != null) {
            markAllPending(results, SyncItemStatus.NOT_EXECUTED,
                    "预检未通过，整批未开始写入");
            execution.finish();
            return execution;
        }

        try {
            Files.createDirectories(transactionDirectory);
            if (request.isBackupEnabled() && plan.count(SyncAction.OVERWRITE) > 0) {
                Files.createDirectories(transactionDirectory.resolve("files"));
            }
            writeManifest(execution, "RUNNING");
        } catch (IOException ex) {
            SyncItemResult first = results.get(0);
            fail(first, "事务", "无法创建事务目录：" + rootMessage(ex));
            markAllPending(results, SyncItemStatus.NOT_EXECUTED,
                    "事务目录创建失败，整批未开始写入");
            execution.finish();
            return execution;
        }

        int completed = 0;
        SyncItemResult stoppedAt = null;
        for (SyncItemResult item : results) {
            if (cancelled != null && cancelled.get()) {
                item.setStatus(SyncItemStatus.CANCELLED);
                item.setStage("取消");
                item.setMessage("用户取消同步");
                stoppedAt = item;
                break;
            }
            try {
                executeItem(execution, item, listener, completed, results.size());
                completed++;
                writeManifest(execution, "RUNNING");
            } catch (Exception ex) {
                fail(item, item.getStage(), rootMessage(ex));
                stoppedAt = item;
                try {
                    writeManifest(execution, "FAILED");
                } catch (IOException ignored) {
                    item.setMessage(item.getMessage() + "；事务清单更新失败");
                }
                break;
            }
        }

        if (stoppedAt != null) {
            markRemaining(results, stoppedAt, SyncItemStatus.NOT_EXECUTED,
                    stoppedAt.getStatus() == SyncItemStatus.CANCELLED
                            ? "同步已取消" : "任务在前一项失败后停止");
        }
        execution.finish();
        try {
            writeManifest(execution, stoppedAt == null ? "COMPLETED" : "INCOMPLETE");
            if (stoppedAt == null) {
                cleanupOldTransactions();
            }
        } catch (IOException ex) {
            if (!results.isEmpty() && stoppedAt == null) {
                results.get(results.size() - 1).setMessage(
                        results.get(results.size() - 1).getMessage() + "；事务清单保存失败");
            }
        }
        return execution;
    }

    void rollback(SyncExecutionResult execution, SyncProgressListener listener,
                  AtomicBoolean cancelled) {
        List<SyncItemResult> reversed = new ArrayList<SyncItemResult>(
                execution.getItemResults());
        Collections.reverse(reversed);
        int total = 0;
        for (SyncItemResult item : reversed) {
            if (item.getStatus() == SyncItemStatus.SUCCESS) {
                total++;
            }
        }
        int completed = 0;
        for (SyncItemResult item : reversed) {
            if (item.getStatus() != SyncItemStatus.SUCCESS) {
                continue;
            }
            if (cancelled != null && cancelled.get()) {
                break;
            }
            notify(listener, "回滚", item.getEntry().getRelativePath(),
                    "正在恢复同步前状态", completed, total);
            try {
                rollbackItem(execution, item);
            } catch (Exception ex) {
                item.setStatus(SyncItemStatus.ROLLBACK_FAILED);
                item.setStage("回滚");
                item.setMessage(rootMessage(ex));
            }
            completed++;
            try {
                writeManifest(execution, "ROLLING_BACK");
            } catch (IOException ignored) {
                // The item result still reports the filesystem outcome.
            }
        }
        removeCreatedDirectories(execution);
        execution.finish();
        try {
            writeManifest(execution, execution.hasRollbackProblems()
                    ? "ROLLBACK_INCOMPLETE" : "ROLLED_BACK");
        } catch (IOException ignored) {
            // Keep the backup directory for manual recovery.
        }
    }

    private List<SyncItemResult> selectedResults(SyncPlan plan, SyncRequest request) {
        List<SyncItemResult> results = new ArrayList<SyncItemResult>();
        for (SyncPlanEntry entry : plan.getEntries()) {
            if (entry.isExecutable() && request.isSelected(entry)) {
                results.add(new SyncItemResult(entry, SyncItemStatus.PENDING));
            }
        }
        return results;
    }

    private SyncItemResult preflight(SyncPlan plan, SyncRequest request,
                                     List<SyncItemResult> results) {
        long backupBytes = 0L;
        long maxCopyBytes = 0L;
        for (SyncItemResult item : results) {
            SyncPlanEntry entry = item.getEntry();
            try {
                validateSafeRelativePath(entry.getRelativePath());
                FileFingerprint source = SyncFileOperations.capture(entry.getSourcePath());
                if (!entry.getSourceFingerprint().sameState(source)) {
                    return fail(item, "复核", "来源文件在预览后发生变化，请重新对比");
                }
                FileFingerprint target = SyncFileOperations.capture(entry.getTargetPath());
                if (!entry.getTargetFingerprint().sameState(target)) {
                    return fail(item, "复核", "目标文件在预览后发生变化，请重新对比");
                }
                if (source.exists() && target.exists()
                        && Files.isSameFile(entry.getSourcePath(), entry.getTargetPath())) {
                    return fail(item, "复核", "来源和目标指向同一个文件");
                }
                ensureWritableTarget(entry.getTargetPath());
                backupBytes += request.isBackupEnabled() ? entry.getBackupSize() : 0L;
                maxCopyBytes = Math.max(maxCopyBytes, entry.getCopySize());
            } catch (Exception ex) {
                return fail(item, "复核", rootMessage(ex));
            }
        }
        try {
            checkDiskSpace(plan.getTargetRoot(), maxCopyBytes, "目标磁盘空间不足");
            if (request.isBackupEnabled() && backupBytes > 0L) {
                Files.createDirectories(backupRoot);
                checkDiskSpace(backupRoot, backupBytes, "备份磁盘空间不足");
            }
        } catch (Exception ex) {
            return fail(results.get(0), "空间检查", rootMessage(ex));
        }
        return null;
    }

    private void executeItem(SyncExecutionResult execution, SyncItemResult item,
                             SyncProgressListener listener, int completed, int total)
            throws IOException {
        SyncPlanEntry entry = item.getEntry();
        if (entry.getAction() == SyncAction.CREATE_DIRECTORY) {
            item.setStage("创建目录");
            notify(listener, item.getStage(), entry.getRelativePath(),
                    "正在创建空目录", completed, total);
            if (!Files.exists(entry.getTargetPath())) {
                Files.createDirectories(entry.getTargetPath());
                execution.addCreatedDirectory(entry.getTargetPath());
            }
            item.setStatus(SyncItemStatus.SUCCESS);
            item.setMessage("目录已创建");
            return;
        }

        FileFingerprint sourceNow = SyncFileOperations.capture(entry.getSourcePath());
        FileFingerprint targetNow = SyncFileOperations.capture(entry.getTargetPath());
        if (!entry.getSourceFingerprint().sameState(sourceNow)
                || !entry.getTargetFingerprint().sameState(targetNow)) {
            throw new IOException("文件在执行前再次发生变化，请重新对比");
        }

        if (entry.getAction() == SyncAction.OVERWRITE
                && execution.getRequest().isBackupEnabled()) {
            item.setStage("备份");
            notify(listener, item.getStage(), entry.getRelativePath(),
                    "正在备份目标文件", completed, total);
            Path backupPath = backupPath(execution, entry);
            copyToStableFile(entry.getTargetPath(), backupPath,
                    entry.getTargetFingerprint().getHash());
            item.setBackupPath(backupPath);
        }

        item.setStage("复制");
        notify(listener, item.getStage(), entry.getRelativePath(),
                "正在写入同目录临时文件", completed, total);
        Set<Path> createdParents = SyncFileOperations.createMissingParents(
                entry.getTargetPath().getParent(), execution.getPlan().getTargetRoot());
        for (Path directory : createdParents) {
            execution.addCreatedDirectory(directory);
        }
        Path temporary = createTargetTemporary(entry.getTargetPath());
        try {
            Files.copy(entry.getSourcePath(), temporary, StandardCopyOption.REPLACE_EXISTING);
            copyLastModifiedTime(entry.getSourcePath(), temporary);
            item.setStage("校验");
            notify(listener, item.getStage(), entry.getRelativePath(),
                    "正在校验临时文件 SHA-256", completed, total);
            String temporaryHash = SyncFileOperations.sha256(temporary);
            if (!entry.getSourceFingerprint().getHash().equals(temporaryHash)) {
                throw new IOException("临时文件 Hash 与来源文件不一致");
            }
            item.setStage("提交");
            notify(listener, item.getStage(), entry.getRelativePath(),
                    "正在提交目标文件", completed, total);
            boolean atomic = moveReplacing(temporary, entry.getTargetPath());
            item.setAtomicMove(atomic);
            item.setWrittenHash(temporaryHash);
            item.setStatus(SyncItemStatus.SUCCESS);
            item.setMessage(entry.getAction() == SyncAction.OVERWRITE
                    ? (atomic ? "已覆盖" : "已覆盖，文件系统不支持原子移动")
                    : (atomic ? "已新增" : "已新增，文件系统不支持原子移动"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void rollbackItem(SyncExecutionResult execution, SyncItemResult item)
            throws IOException {
        SyncPlanEntry entry = item.getEntry();
        if (entry.getAction() == SyncAction.CREATE_DIRECTORY) {
            if (Files.isDirectory(entry.getTargetPath()) && isDirectoryEmpty(entry.getTargetPath())) {
                Files.delete(entry.getTargetPath());
                execution.removeCreatedDirectory(entry.getTargetPath());
            }
            item.setStatus(SyncItemStatus.ROLLED_BACK);
            item.setStage("回滚");
            item.setMessage("目录已恢复");
            return;
        }

        FileFingerprint current = SyncFileOperations.capture(entry.getTargetPath());
        if (!current.exists() || !current.getHash().equals(item.getWrittenHash())) {
            item.setStatus(SyncItemStatus.ROLLBACK_CONFLICT);
            item.setStage("回滚");
            item.setMessage("目标文件在同步后被修改，未强制回滚");
            return;
        }
        if (entry.getAction() == SyncAction.ADD) {
            Files.delete(entry.getTargetPath());
            item.setStatus(SyncItemStatus.ROLLED_BACK);
            item.setStage("回滚");
            item.setMessage("已删除本次新增文件");
            return;
        }
        if (item.getBackupPath() == null || !Files.isRegularFile(item.getBackupPath())) {
            item.setStatus(SyncItemStatus.ROLLBACK_FAILED);
            item.setStage("回滚");
            item.setMessage("找不到覆盖前备份文件");
            return;
        }
        String backupHash = SyncFileOperations.sha256(item.getBackupPath());
        Path temporary = createTargetTemporary(entry.getTargetPath());
        try {
            Files.copy(item.getBackupPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
            copyLastModifiedTime(item.getBackupPath(), temporary);
            if (!backupHash.equals(SyncFileOperations.sha256(temporary))) {
                throw new IOException("恢复临时文件 Hash 校验失败");
            }
            moveReplacing(temporary, entry.getTargetPath());
            item.setStatus(SyncItemStatus.ROLLED_BACK);
            item.setStage("回滚");
            item.setMessage("已恢复覆盖前文件");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void removeCreatedDirectories(SyncExecutionResult execution) {
        List<Path> directories = new ArrayList<Path>(execution.getCreatedDirectories());
        Collections.sort(directories, new Comparator<Path>() {
            @Override
            public int compare(Path first, Path second) {
                return Integer.compare(second.getNameCount(), first.getNameCount());
            }
        });
        for (Path directory : directories) {
            try {
                if (Files.isDirectory(directory) && isDirectoryEmpty(directory)) {
                    Files.delete(directory);
                }
            } catch (IOException ignored) {
                // A non-empty or locked directory is left in place.
            }
        }
    }

    private Path backupPath(SyncExecutionResult execution, SyncPlanEntry entry)
            throws IOException {
        Path relative = Paths.get(entry.getRelativePath()).normalize();
        if (relative.isAbsolute() || startsWithParent(relative)) {
            throw new IOException("非法相对路径：" + entry.getRelativePath());
        }
        return execution.getTransactionDirectory().resolve("files").resolve(relative);
    }

    private void copyToStableFile(Path source, Path target, String expectedHash)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".backup-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            copyLastModifiedTime(source, temporary);
            if (!expectedHash.equals(SyncFileOperations.sha256(temporary))) {
                throw new IOException("备份文件 Hash 校验失败");
            }
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path createTargetTemporary(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            parent = target.toAbsolutePath().getParent();
        }
        if (parent == null) {
            throw new IOException("无法确定目标文件目录：" + target);
        }
        Files.createDirectories(parent);
        String fileName = target.getFileName() == null ? "sync" : target.getFileName().toString();
        return Files.createTempFile(parent, "." + fileName + ".", ".fctmp");
    }

    private boolean moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return false;
        }
    }

    private void copyLastModifiedTime(Path source, Path target) {
        try {
            FileTime modified = Files.getLastModifiedTime(source);
            Files.setLastModifiedTime(target, modified);
        } catch (IOException ignored) {
            // Content integrity is more important than optional timestamp preservation.
        }
    }

    private void ensureWritableTarget(Path target) throws IOException {
        Path current = Files.exists(target) ? target : target.getParent();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current == null || !Files.isWritable(current)) {
            throw new IOException("目标位置不可写：" + target);
        }
    }

    private void checkDiskSpace(Path path, long required, String message) throws IOException {
        if (required <= 0L) {
            return;
        }
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return;
        }
        FileStore store = Files.getFileStore(existing);
        if (store.getUsableSpace() < required) {
            throw new IOException(message + "，至少需要 " + SyncText.formatSize(required));
        }
    }

    private void validateSafeRelativePath(String value) throws IOException {
        Path path = Paths.get(value).normalize();
        if (path.isAbsolute() || startsWithParent(path)) {
            throw new IOException("同步路径超出目标目录：" + value);
        }
    }

    private boolean startsWithParent(Path path) {
        return path.getNameCount() > 0 && "..".equals(path.getName(0).toString());
    }

    private void writeManifest(SyncExecutionResult execution, String state)
            throws IOException {
        Path directory = execution.getTransactionDirectory();
        Files.createDirectories(directory);
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("transactionId", execution.getPlan().getTransactionId());
        values.setProperty("state", state);
        values.setProperty("direction", execution.getPlan().getDirection().name());
        values.setProperty("sourceRoot", execution.getPlan().getSourceRoot().toString());
        values.setProperty("targetRoot", execution.getPlan().getTargetRoot().toString());
        values.setProperty("backupEnabled",
                Boolean.toString(execution.getRequest().isBackupEnabled()));
        values.setProperty("itemCount", Integer.toString(execution.getItemResults().size()));
        int index = 0;
        for (SyncItemResult item : execution.getItemResults()) {
            String prefix = "item." + index + ".";
            values.setProperty(prefix + "path", item.getEntry().getRelativePath());
            values.setProperty(prefix + "action", item.getEntry().getAction().name());
            values.setProperty(prefix + "status", item.getStatus().name());
            values.setProperty(prefix + "stage", item.getStage());
            values.setProperty(prefix + "message", item.getMessage());
            values.setProperty(prefix + "target", item.getEntry().getTargetPath().toString());
            values.setProperty(prefix + "writtenHash",
                    item.getWrittenHash() == null ? "" : item.getWrittenHash());
            values.setProperty(prefix + "backup",
                    item.getBackupPath() == null ? "" : item.getBackupPath().toString());
            index++;
        }
        Path target = directory.resolve(MANIFEST_NAME);
        Path temporary = Files.createTempFile(directory, ".manifest-", ".tmp");
        try {
            OutputStream output = Files.newOutputStream(temporary);
            try {
                values.store(output, "File Compare Tool sync transaction");
            } finally {
                output.close();
            }
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void cleanupOldTransactions() {
        try {
            if (!Files.isDirectory(backupRoot)) {
                return;
            }
            List<Path> completed = new ArrayList<Path>();
            DirectoryStream<Path> stream = Files.newDirectoryStream(backupRoot);
            try {
                for (Path directory : stream) {
                    Path manifest = directory.resolve(MANIFEST_NAME);
                    if (!Files.isRegularFile(manifest)) {
                        continue;
                    }
                    Properties values = new Properties();
                    InputStream input = Files.newInputStream(manifest);
                    try {
                        values.load(input);
                    } finally {
                        input.close();
                    }
                    String state = values.getProperty("state", "");
                    if ("COMPLETED".equals(state) || "ROLLED_BACK".equals(state)) {
                        completed.add(directory);
                    }
                }
            } finally {
                stream.close();
            }
            Collections.sort(completed, new Comparator<Path>() {
                @Override
                public int compare(Path first, Path second) {
                    try {
                        return Files.getLastModifiedTime(second).compareTo(
                                Files.getLastModifiedTime(first));
                    } catch (IOException ex) {
                        return second.toString().compareTo(first.toString());
                    }
                }
            });
            for (int i = RETAINED_COMPLETED_TRANSACTIONS; i < completed.size(); i++) {
                deleteTree(completed.get(i));
            }
        } catch (IOException ignored) {
            // Retention cleanup must not turn a successful sync into a failure.
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                                                           BasicFileAttributes attrs) {
                paths.add(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                paths.add(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(paths, new Comparator<Path>() {
            @Override
            public int compare(Path first, Path second) {
                return Integer.compare(second.getNameCount(), first.getNameCount());
            }
        });
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        try {
            return !stream.iterator().hasNext();
        } finally {
            stream.close();
        }
    }

    private SyncItemResult fail(SyncItemResult item, String stage, String message) {
        item.setStatus(SyncItemStatus.FAILED);
        item.setStage(stage);
        item.setMessage(message);
        return item;
    }

    private void markRemaining(List<SyncItemResult> results, SyncItemResult stoppedAt,
                               SyncItemStatus status, String message) {
        boolean after = false;
        for (SyncItemResult item : results) {
            if (after && item.getStatus() == SyncItemStatus.PENDING) {
                item.setStatus(status);
                item.setStage("");
                item.setMessage(message);
            }
            if (item == stoppedAt) {
                after = true;
            }
        }
    }

    private void markAllPending(List<SyncItemResult> results, SyncItemStatus status,
                                String message) {
        for (SyncItemResult item : results) {
            if (item.getStatus() == SyncItemStatus.PENDING) {
                item.setStatus(status);
                item.setStage("");
                item.setMessage(message);
            }
        }
    }

    private void notify(SyncProgressListener listener, String stage, String relativePath,
                        String message, int completed, int total) {
        if (listener != null) {
            listener.onProgress(new SyncProgressUpdate(stage, relativePath, message,
                    completed, total));
        }
    }

    private static Path defaultBackupRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData, "FileCompareTool", "backups");
        }
        return Paths.get(System.getProperty("user.home"), ".file-compare-tool", "backups");
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }
}

final class SyncFileOperations {
    private SyncFileOperations() {
    }

    static FileFingerprint capture(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return FileFingerprint.missing();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            return FileFingerprint.knownDirectory(attributes.lastModifiedTime().toMillis());
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("不是普通文件：" + path);
        }
        return FileFingerprint.knownFile(attributes.size(),
                attributes.lastModifiedTime().toMillis(), sha256(path));
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream input = new BufferedInputStream(Files.newInputStream(file));
            try {
                DigestInputStream digestInput = new DigestInputStream(input, digest);
                byte[] buffer = new byte[1024 * 1024];
                while (digestInput.read(buffer) != -1) {
                    // DigestInputStream updates the digest.
                }
            } finally {
                input.close();
            }
            StringBuilder value = new StringBuilder(64);
            for (byte current : digest.digest()) {
                value.append(String.format("%02x", current & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("当前环境不支持 SHA-256", ex);
        }
    }

    static Set<Path> createMissingParents(Path parent, Path stopAt) throws IOException {
        java.util.LinkedHashSet<Path> missing = new java.util.LinkedHashSet<Path>();
        if (parent == null) {
            return missing;
        }
        Path current = parent;
        Path normalizedStop = stopAt == null ? null : stopAt.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            missing.add(current);
            if (normalizedStop != null
                    && current.toAbsolutePath().normalize().equals(normalizedStop)) {
                break;
            }
            current = current.getParent();
        }
        Files.createDirectories(parent);
        return missing;
    }
}

final class SyncText {
    private SyncText() {
    }

    static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }
}
