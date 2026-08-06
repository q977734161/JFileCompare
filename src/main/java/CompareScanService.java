import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class CompareScanService {
    static final int HASH_BUFFER_SIZE = 1024 * 1024;
    static final int MAX_HASH_WORKERS = 4;
    private static final long SMALL_TASK_BYTES = 64L * 1024L * 1024L;
    private static final int SMALL_TASK_FILES = 64;
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);

    ScanResult execute(ScanRequest request, CancellationToken cancellation,
                       ProgressListener listener) throws IOException {
        long started = System.nanoTime();
        MutableProgress progress = new MutableProgress(request.taskId, listener, started);
        progress.stage(ScanStage.DISCOVERING, true);

        long discoveryStarted = System.nanoTime();
        DiscoveryPair discovered = request.directoryMode
                ? discoverDirectories(request, cancellation, progress)
                : discoverFiles(request, cancellation, progress);
        long discoveryMillis = elapsedMillis(discoveryStarted);
        cancellation.throwIfCancelled();

        List<FileCandidate> candidates = interleave(discovered.left.files, discovered.right.files);
        long totalBytes = sumBytes(candidates);
        progress.hashTotals(candidates.size(), totalBytes);
        int workers = HashParallelismPolicy.workerCount(request.directoryMode,
                request.leftRoot, request.rightRoot, candidates.size(), totalBytes);
        progress.workerCount(workers);

        long hashStarted = System.nanoTime();
        progress.stage(ScanStage.HASHING, false);
        hashCandidates(candidates, workers, cancellation, progress);
        long hashMillis = elapsedMillis(hashStarted);
        cancellation.throwIfCancelled();

        long buildStarted = System.nanoTime();
        progress.stage(ScanStage.BUILDING, false);
        ScanResult result = buildResult(request, discovered, candidates, cancellation, progress);
        long buildMillis = elapsedMillis(buildStarted);
        long totalMillis = elapsedMillis(started);
        result.metrics = new ScanMetrics(discoveryMillis, hashMillis, buildMillis, 0L,
                totalMillis, candidates.size(), progress.totalBytes, workers,
                progress.hashRetries.get());
        progress.complete();
        return result;
    }

    private DiscoveryPair discoverFiles(ScanRequest request, CancellationToken cancellation,
                                         MutableProgress progress) throws IOException {
        cancellation.throwIfCancelled();
        DiscoverySnapshot left = new DiscoverySnapshot();
        DiscoverySnapshot right = new DiscoverySnapshot();
        left.files.add(candidate(ScanSide.LEFT, request.leftRoot, "selected-file"));
        progress.discovered(ScanSide.LEFT, left.files.get(0));
        cancellation.throwIfCancelled();
        right.files.add(candidate(ScanSide.RIGHT, request.rightRoot, "selected-file"));
        progress.discovered(ScanSide.RIGHT, right.files.get(0));
        return new DiscoveryPair(left, right);
    }

    private DiscoveryPair discoverDirectories(final ScanRequest request,
                                               final CancellationToken cancellation,
                                               final MutableProgress progress) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(2, new NamedThreadFactory("compare-discovery"));
        Future<DiscoverySnapshot> left = executor.submit(new Callable<DiscoverySnapshot>() {
            @Override
            public DiscoverySnapshot call() throws Exception {
                return discoverDirectory(ScanSide.LEFT, request.leftRoot, request.filter,
                        cancellation, progress);
            }
        });
        Future<DiscoverySnapshot> right = executor.submit(new Callable<DiscoverySnapshot>() {
            @Override
            public DiscoverySnapshot call() throws Exception {
                return discoverDirectory(ScanSide.RIGHT, request.rightRoot, request.filter,
                        cancellation, progress);
            }
        });
        executor.shutdown();
        try {
            return new DiscoveryPair(getFuture(left, cancellation), getFuture(right, cancellation));
        } finally {
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    private DiscoverySnapshot discoverDirectory(final ScanSide side, final Path root,
                                                final ScanFilter filter,
                                                final CancellationToken cancellation,
                                                final MutableProgress progress) throws IOException {
        final DiscoverySnapshot snapshot = new DiscoverySnapshot();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                cancellation.throwIfCancelled();
                String relative = normalize(root.relativize(directory));
                progress.currentPath(side, directory);
                if (!relative.isEmpty() && filter.matchesDirectory(relative)) {
                    snapshot.excludedDirectories++;
                    progress.excludedDirectory();
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!relative.isEmpty()) {
                    snapshot.directories.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                cancellation.throwIfCancelled();
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = normalize(root.relativize(file));
                progress.currentPath(side, file);
                if (filter.matchesFile(relative)) {
                    snapshot.excludedFiles++;
                    progress.excludedFile();
                    return FileVisitResult.CONTINUE;
                }
                FileCandidate candidate = new FileCandidate(side, file, relative,
                        attrs.size(), attrs.lastModifiedTime().toMillis());
                snapshot.files.add(candidate);
                progress.discovered(side, candidate);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception)
                    throws IOException {
                cancellation.throwIfCancelled();
                throw new IOException("无法读取" + side.displayName + "路径：" + file, exception);
            }
        });
        return snapshot;
    }

    private FileCandidate candidate(ScanSide side, Path file, String relativePath)
            throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileCandidate(side, file, relativePath, attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    private void hashCandidates(final List<FileCandidate> candidates, int workerCount,
                                final CancellationToken cancellation,
                                final MutableProgress progress) throws IOException {
        if (candidates.isEmpty()) {
            return;
        }
        final AtomicInteger nextIndex = new AtomicInteger();
        final AtomicReference<IOException> failure = new AtomicReference<IOException>();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount,
                new NamedThreadFactory("compare-hash"));
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < workerCount; i++) {
            futures.add(executor.submit(new Runnable() {
                @Override
                public void run() {
                    while (!cancellation.isCancelled() && failure.get() == null) {
                        int index = nextIndex.getAndIncrement();
                        if (index >= candidates.size()) {
                            return;
                        }
                        FileCandidate candidate = candidates.get(index);
                        try {
                            hashStable(candidate, cancellation, progress);
                        } catch (CancellationException ex) {
                            return;
                        } catch (IOException ex) {
                            if (failure.compareAndSet(null, ex)) {
                                cancellation.cancel();
                            }
                            return;
                        }
                    }
                }
            }));
        }
        executor.shutdown();
        try {
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    cancellation.cancel();
                    throw new CancellationException("扫描任务已中断");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException("Hash 工作线程失败", cause);
                }
            }
            IOException error = failure.get();
            if (error != null) {
                throw error;
            }
            cancellation.throwIfCancelled();
        } finally {
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    private void hashStable(FileCandidate candidate, CancellationToken cancellation,
                            MutableProgress progress) throws IOException {
        long expectedSize = candidate.size;
        long expectedModified = candidate.modifiedTime;
        for (int attempt = 0; attempt < 2; attempt++) {
            cancellation.throwIfCancelled();
            HashRead read = hashOnce(candidate.path, cancellation, progress, candidate.relativePath);
            BasicFileAttributes after = Files.readAttributes(candidate.path, BasicFileAttributes.class);
            boolean stable = expectedSize == after.size()
                    && expectedModified == after.lastModifiedTime().toMillis()
                    && read.bytesRead == after.size();
            if (stable) {
                candidate.size = after.size();
                candidate.modifiedTime = after.lastModifiedTime().toMillis();
                candidate.hash = read.hash;
                progress.fileCompleted(candidate.relativePath);
                return;
            }
            progress.rollbackBytes(read.bytesRead);
            if (attempt == 0) {
                progress.hashRetries.incrementAndGet();
                progress.adjustTotalBytes(after.size() - expectedSize);
                expectedSize = after.size();
                expectedModified = after.lastModifiedTime().toMillis();
            }
        }
        throw new IOException("文件在 Hash 期间持续变化：" + candidate.path);
    }

    private HashRead hashOnce(Path file, CancellationToken cancellation,
                              MutableProgress progress, String relativePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("当前环境不支持 SHA-256", ex);
        }
        long bytesRead = 0L;
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        InputStream input = new BufferedInputStream(Files.newInputStream(file), HASH_BUFFER_SIZE);
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytesRead += count;
                progress.bytesRead(count, relativePath);
                cancellation.throwIfCancelled();
            }
        } finally {
            input.close();
        }
        return new HashRead(toHex(digest.digest()), bytesRead);
    }

    private ScanResult buildResult(ScanRequest request, DiscoveryPair discovered,
                                   List<FileCandidate> candidates,
                                   CancellationToken cancellation,
                                   MutableProgress progress) {
        Map<String, ScannedFile> left = new LinkedHashMap<String, ScannedFile>();
        Map<String, ScannedFile> right = new LinkedHashMap<String, ScannedFile>();
        for (FileCandidate candidate : candidates) {
            cancellation.throwIfCancelled();
            ScannedFile file = candidate.toScannedFile();
            (candidate.side == ScanSide.LEFT ? left : right).put(candidate.relativePath, file);
        }
        List<String> relativePaths = new ArrayList<String>(left.keySet());
        for (String relative : right.keySet()) {
            if (!left.containsKey(relative)) {
                relativePaths.add(relative);
            }
        }
        Collections.sort(relativePaths);
        List<ScannedPair> pairs = new ArrayList<ScannedPair>(relativePaths.size());
        progress.buildTotal(relativePaths.size());
        for (String relative : relativePaths) {
            cancellation.throwIfCancelled();
            pairs.add(new ScannedPair(relative, left.get(relative), right.get(relative)));
            progress.builtItem(relative);
        }
        return new ScanResult(request.directoryMode, request.leftRoot, request.rightRoot,
                pairs, discovered.left.directories, discovered.right.directories,
                discovered.left.excludedDirectories + discovered.right.excludedDirectories,
                discovered.left.excludedFiles + discovered.right.excludedFiles);
    }

    private static <T> T getFuture(Future<T> future, CancellationToken cancellation)
            throws IOException {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancellation.cancel();
            throw new CancellationException("扫描任务已中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof CancellationException) {
                throw (CancellationException) cause;
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("目录发现失败", cause);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<FileCandidate> interleave(List<FileCandidate> left,
                                                  List<FileCandidate> right) {
        List<FileCandidate> result = new ArrayList<FileCandidate>(left.size() + right.size());
        int count = Math.max(left.size(), right.size());
        for (int i = 0; i < count; i++) {
            if (i < left.size()) {
                result.add(left.get(i));
            }
            if (i < right.size()) {
                result.add(right.get(i));
            }
        }
        return result;
    }

    private static long sumBytes(List<FileCandidate> candidates) {
        long result = 0L;
        for (FileCandidate candidate : candidates) {
            if (Long.MAX_VALUE - result < candidate.size) {
                return Long.MAX_VALUE;
            }
            result += candidate.size;
        }
        return result;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    interface ScanFilter {
        boolean matchesDirectory(String relativePath);
        boolean matchesFile(String relativePath);
    }

    interface ProgressListener {
        void onProgress(ScanProgress progress);
    }

    static final class ScanRequest {
        final long taskId;
        final boolean directoryMode;
        final Path leftRoot;
        final Path rightRoot;
        final ScanFilter filter;

        ScanRequest(long taskId, boolean directoryMode, Path leftRoot, Path rightRoot,
                    ScanFilter filter) {
            this.taskId = taskId;
            this.directoryMode = directoryMode;
            this.leftRoot = leftRoot;
            this.rightRoot = rightRoot;
            this.filter = filter == null ? NO_FILTER : filter;
        }
    }

    static final ScanFilter NO_FILTER = new ScanFilter() {
        @Override
        public boolean matchesDirectory(String relativePath) {
            return false;
        }

        @Override
        public boolean matchesFile(String relativePath) {
            return false;
        }
    };

    static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void throwIfCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("扫描任务已取消");
            }
        }
    }

    enum ScanStage {
        DISCOVERING("发现文件"), HASHING("计算 Hash"), BUILDING("构建结果"),
        PUBLISHING("更新界面"), COMPLETED("扫描完成"), CANCELLED("已取消"), FAILED("扫描失败");

        final String displayName;

        ScanStage(String displayName) {
            this.displayName = displayName;
        }
    }

    static final class ScanProgress {
        final long taskId;
        final ScanStage stage;
        final boolean indeterminate;
        final int discoveredLeft;
        final int discoveredRight;
        final long discoveredBytes;
        final int excludedDirectories;
        final int excludedFiles;
        final int totalFiles;
        final int completedFiles;
        final long totalBytes;
        final long completedBytes;
        final int builtItems;
        final int totalBuildItems;
        final int workerCount;
        final String currentPath;
        final long elapsedMillis;

        ScanProgress(long taskId, ScanStage stage, boolean indeterminate,
                     int discoveredLeft, int discoveredRight, long discoveredBytes,
                     int excludedDirectories,
                     int excludedFiles, int totalFiles, int completedFiles,
                     long totalBytes, long completedBytes, int builtItems,
                     int totalBuildItems, int workerCount, String currentPath,
                     long elapsedMillis) {
            this.taskId = taskId;
            this.stage = stage;
            this.indeterminate = indeterminate;
            this.discoveredLeft = discoveredLeft;
            this.discoveredRight = discoveredRight;
            this.discoveredBytes = discoveredBytes;
            this.excludedDirectories = excludedDirectories;
            this.excludedFiles = excludedFiles;
            this.totalFiles = totalFiles;
            this.completedFiles = completedFiles;
            this.totalBytes = totalBytes;
            this.completedBytes = completedBytes;
            this.builtItems = builtItems;
            this.totalBuildItems = totalBuildItems;
            this.workerCount = workerCount;
            this.currentPath = currentPath;
            this.elapsedMillis = elapsedMillis;
        }
    }

    static final class ScanMetrics {
        final long discoveryMillis;
        final long hashMillis;
        long buildMillis;
        long publishMillis;
        long totalMillis;
        final int totalFiles;
        final long totalBytes;
        final int workerCount;
        final int hashRetries;

        ScanMetrics(long discoveryMillis, long hashMillis, long buildMillis,
                    long publishMillis, long totalMillis, int totalFiles,
                    long totalBytes, int workerCount, int hashRetries) {
            this.discoveryMillis = discoveryMillis;
            this.hashMillis = hashMillis;
            this.buildMillis = buildMillis;
            this.publishMillis = publishMillis;
            this.totalMillis = totalMillis;
            this.totalFiles = totalFiles;
            this.totalBytes = totalBytes;
            this.workerCount = workerCount;
            this.hashRetries = hashRetries;
        }

        double throughputMegabytesPerSecond() {
            return hashMillis <= 0L ? 0.0
                    : (totalBytes / (1024.0 * 1024.0)) / (hashMillis / 1000.0);
        }
    }

    static final class ScanResult {
        final boolean directoryMode;
        final Path leftRoot;
        final Path rightRoot;
        final List<ScannedPair> pairs;
        final Set<String> leftDirectories;
        final Set<String> rightDirectories;
        final int excludedDirectoryCount;
        final int excludedFileCount;
        ScanMetrics metrics;

        ScanResult(boolean directoryMode, Path leftRoot, Path rightRoot,
                   List<ScannedPair> pairs, Set<String> leftDirectories,
                   Set<String> rightDirectories, int excludedDirectoryCount,
                   int excludedFileCount) {
            this.directoryMode = directoryMode;
            this.leftRoot = leftRoot;
            this.rightRoot = rightRoot;
            this.pairs = Collections.unmodifiableList(new ArrayList<ScannedPair>(pairs));
            this.leftDirectories = Collections.unmodifiableSet(new LinkedHashSet<String>(leftDirectories));
            this.rightDirectories = Collections.unmodifiableSet(new LinkedHashSet<String>(rightDirectories));
            this.excludedDirectoryCount = excludedDirectoryCount;
            this.excludedFileCount = excludedFileCount;
        }
    }

    static final class ScannedPair {
        final String relativePath;
        final ScannedFile left;
        final ScannedFile right;

        ScannedPair(String relativePath, ScannedFile left, ScannedFile right) {
            this.relativePath = relativePath;
            this.left = left;
            this.right = right;
        }
    }

    static final class ScannedFile {
        final Path path;
        final String relativePath;
        final long size;
        final long modifiedTime;
        final String hash;

        ScannedFile(Path path, String relativePath, long size, long modifiedTime, String hash) {
            this.path = path;
            this.relativePath = relativePath;
            this.size = size;
            this.modifiedTime = modifiedTime;
            this.hash = hash;
        }
    }

    static final class HashParallelismPolicy {
        static int workerCount(boolean directoryMode, Path leftRoot, Path rightRoot,
                               int fileCount, long totalBytes) {
            int cpus = Runtime.getRuntime().availableProcessors();
            if (!directoryMode || cpus <= 2 || fileCount < SMALL_TASK_FILES
                    || totalBytes < SMALL_TASK_BYTES || isNetworkPath(leftRoot)
                    || isNetworkPath(rightRoot)) {
                return 1;
            }
            if (sameFileStore(leftRoot, rightRoot)) {
                return 2;
            }
            return Math.min(MAX_HASH_WORKERS, Math.max(2, cpus / 2));
        }

        private static boolean isNetworkPath(Path path) {
            String value = path.toAbsolutePath().toString();
            if (value.startsWith("\\\\")) {
                return true;
            }
            try {
                String type = Files.getFileStore(path).type().toLowerCase();
                return type.contains("smb") || type.contains("cifs") || type.contains("nfs");
            } catch (IOException ex) {
                return true;
            }
        }

        private static boolean sameFileStore(Path left, Path right) {
            try {
                FileStore leftStore = Files.getFileStore(left);
                FileStore rightStore = Files.getFileStore(right);
                return leftStore.equals(rightStore)
                        || (leftStore.name().equalsIgnoreCase(rightStore.name())
                        && leftStore.type().equalsIgnoreCase(rightStore.type()));
            } catch (IOException ex) {
                return true;
            }
        }
    }

    private enum ScanSide {
        LEFT("左侧"), RIGHT("右侧");
        final String displayName;
        ScanSide(String displayName) {
            this.displayName = displayName;
        }
    }

    private static final class FileCandidate {
        final ScanSide side;
        final Path path;
        final String relativePath;
        long size;
        long modifiedTime;
        String hash;

        FileCandidate(ScanSide side, Path path, String relativePath,
                      long size, long modifiedTime) {
            this.side = side;
            this.path = path;
            this.relativePath = relativePath;
            this.size = size;
            this.modifiedTime = modifiedTime;
        }

        ScannedFile toScannedFile() {
            return new ScannedFile(path, relativePath, size, modifiedTime, hash);
        }
    }

    private static final class DiscoverySnapshot {
        final List<FileCandidate> files = new ArrayList<FileCandidate>();
        final Set<String> directories = new LinkedHashSet<String>();
        int excludedDirectories;
        int excludedFiles;
    }

    private static final class DiscoveryPair {
        final DiscoverySnapshot left;
        final DiscoverySnapshot right;

        DiscoveryPair(DiscoverySnapshot left, DiscoverySnapshot right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class HashRead {
        final String hash;
        final long bytesRead;
        HashRead(String hash, long bytesRead) {
            this.hash = hash;
            this.bytesRead = bytesRead;
        }
    }

    private static final class MutableProgress {
        final long taskId;
        final ProgressListener listener;
        final long started;
        final AtomicInteger discoveredLeft = new AtomicInteger();
        final AtomicInteger discoveredRight = new AtomicInteger();
        final AtomicLong discoveredBytes = new AtomicLong();
        final AtomicInteger excludedDirectories = new AtomicInteger();
        final AtomicInteger excludedFiles = new AtomicInteger();
        final AtomicInteger completedFiles = new AtomicInteger();
        final AtomicLong completedBytes = new AtomicLong();
        final AtomicInteger builtItems = new AtomicInteger();
        final AtomicInteger hashRetries = new AtomicInteger();
        final AtomicLong lastPublished = new AtomicLong();
        final AtomicReference<String> currentPath = new AtomicReference<String>("");
        volatile ScanStage stage = ScanStage.DISCOVERING;
        volatile boolean indeterminate = true;
        volatile int totalFiles;
        volatile long totalBytes;
        volatile int totalBuildItems;
        volatile int workerCount;

        MutableProgress(long taskId, ProgressListener listener, long started) {
            this.taskId = taskId;
            this.listener = listener;
            this.started = started;
        }

        void stage(ScanStage next, boolean unknown) {
            stage = next;
            indeterminate = unknown;
            publish(true);
        }

        void discovered(ScanSide side, FileCandidate candidate) {
            (side == ScanSide.LEFT ? discoveredLeft : discoveredRight).incrementAndGet();
            discoveredBytes.addAndGet(candidate.size);
            currentPath.set(candidate.path.toString());
            publish(false);
        }

        void currentPath(ScanSide side, Path path) {
            currentPath.set(side.displayName + "：" + path);
            publish(false);
        }

        void excludedDirectory() {
            excludedDirectories.incrementAndGet();
            publish(false);
        }

        void excludedFile() {
            excludedFiles.incrementAndGet();
            publish(false);
        }

        void hashTotals(int files, long bytes) {
            totalFiles = files;
            totalBytes = bytes;
        }

        void workerCount(int value) {
            workerCount = value;
        }

        void bytesRead(int count, String path) {
            completedBytes.addAndGet(count);
            currentPath.set(path);
            publish(false);
        }

        void rollbackBytes(long count) {
            completedBytes.addAndGet(-count);
            publish(true);
        }

        void adjustTotalBytes(long delta) {
            if (delta > 0L && Long.MAX_VALUE - totalBytes < delta) {
                totalBytes = Long.MAX_VALUE;
            } else {
                totalBytes = Math.max(0L, totalBytes + delta);
            }
            publish(true);
        }

        void fileCompleted(String path) {
            completedFiles.incrementAndGet();
            currentPath.set(path);
            publish(false);
        }

        void buildTotal(int count) {
            totalBuildItems = count;
            publish(true);
        }

        void builtItem(String path) {
            builtItems.incrementAndGet();
            currentPath.set(path);
            publish(false);
        }

        void complete() {
            stage = ScanStage.COMPLETED;
            indeterminate = false;
            publish(true);
        }

        void publish(boolean force) {
            if (listener == null) {
                return;
            }
            long now = System.nanoTime();
            long previous = lastPublished.get();
            if (!force && now - previous < PROGRESS_INTERVAL_NANOS) {
                return;
            }
            if (!lastPublished.compareAndSet(previous, now) && !force) {
                return;
            }
            listener.onProgress(new ScanProgress(taskId, stage, indeterminate,
                    discoveredLeft.get(), discoveredRight.get(), discoveredBytes.get(),
                    excludedDirectories.get(),
                    excludedFiles.get(), totalFiles, completedFiles.get(), totalBytes,
                    Math.min(totalBytes, Math.max(0L, completedBytes.get())),
                    builtItems.get(), totalBuildItems,
                    workerCount, currentPath.get(), elapsedMillis(started)));
        }
    }

    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
