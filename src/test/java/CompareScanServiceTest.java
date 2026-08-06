import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CompareScanServiceTest {
    public static void main(String[] args) throws Exception {
        testDirectoryComparisonAndFiltering();
        testParallelPolicyBounds();
        testFileChangeRetriesOnce();
        testReadFailureIsNotCancellation();
        testCancellation();
        System.out.println("CompareScanServiceTest passed");
    }

    private static void testDirectoryComparisonAndFiltering() throws Exception {
        Path root = createTempDirectory("compare-scan-service");
        Path left = Files.createDirectories(root.resolve("left"));
        Path right = Files.createDirectories(root.resolve("right"));
        write(left.resolve("same.txt"), "same");
        write(right.resolve("same.txt"), "same");
        write(left.resolve("different.txt"), "left");
        write(right.resolve("different.txt"), "right");
        write(left.resolve("left-only.txt"), "left-only");
        write(right.resolve("right-only.txt"), "right-only");
        write(Files.createDirectories(left.resolve("target")).resolve("ignored.log"), "ignored");
        write(Files.createDirectories(right.resolve("target")).resolve("ignored.log"), "ignored");

        final List<CompareScanService.ScanProgress> updates =
                new ArrayList<CompareScanService.ScanProgress>();
        CompareScanService service = new CompareScanService();
        CompareScanService.ScanResult result = service.execute(
                new CompareScanService.ScanRequest(7L, true, left, right,
                        new CompareScanService.ScanFilter() {
                            @Override
                            public boolean matchesDirectory(String relativePath) {
                                return relativePath.equals("target");
                            }

                            @Override
                            public boolean matchesFile(String relativePath) {
                                return false;
                            }
                        }),
                new CompareScanService.CancellationToken(),
                new CompareScanService.ProgressListener() {
                    @Override
                    public void onProgress(CompareScanService.ScanProgress progress) {
                        synchronized (updates) {
                            updates.add(progress);
                        }
                    }
                });

        assertEquals(4, result.pairs.size(), "pair count");
        assertEquals(2, result.excludedDirectoryCount, "excluded directories");
        assertEquals(6, result.metrics.totalFiles, "hashed files");
        assertTrue(result.metrics.workerCount >= 1 && result.metrics.workerCount <= 4,
                "worker count must be bounded");
        assertTrue(find(result, "same.txt").left.hash.equals(find(result, "same.txt").right.hash),
                "same files must have equal hashes");
        assertTrue(!find(result, "different.txt").left.hash.equals(
                find(result, "different.txt").right.hash), "different hashes expected");
        synchronized (updates) {
            assertTrue(!updates.isEmpty(), "progress updates expected");
            assertEquals(CompareScanService.ScanStage.COMPLETED,
                    updates.get(updates.size() - 1).stage, "terminal stage");
            for (CompareScanService.ScanProgress update : updates) {
                assertTrue(update.completedBytes <= update.totalBytes,
                        "byte progress must not exceed total");
                assertTrue(update.completedFiles <= update.totalFiles,
                        "file progress must not exceed total");
            }
        }
    }

    private static void testParallelPolicyBounds() throws Exception {
        Path root = createTempDirectory("compare-policy");
        assertEquals(1, CompareScanService.HashParallelismPolicy.workerCount(
                false, root, root, 1000, 1024L * 1024L * 1024L), "file mode");
        assertEquals(1, CompareScanService.HashParallelismPolicy.workerCount(
                true, root, root, 10, 1024L * 1024L * 1024L), "small file count");
        int sameStore = CompareScanService.HashParallelismPolicy.workerCount(
                true, root, root, 1000, 1024L * 1024L * 1024L);
        assertTrue(sameStore >= 1 && sameStore <= 2, "same store limit");
    }

    private static void testCancellation() throws Exception {
        Path root = createTempDirectory("compare-cancel");
        Path left = Files.createDirectories(root.resolve("left"));
        Path right = Files.createDirectories(root.resolve("right"));
        for (int i = 0; i < 80; i++) {
            byte[] data = new byte[256 * 1024];
            data[0] = (byte) i;
            Files.write(left.resolve("left-" + i + ".bin"), data);
            Files.write(right.resolve("right-" + i + ".bin"), data);
        }
        final CompareScanService.CancellationToken token =
                new CompareScanService.CancellationToken();
        final AtomicBoolean cancelled = new AtomicBoolean();
        try {
            new CompareScanService().execute(
                    new CompareScanService.ScanRequest(8L, true, left, right,
                            CompareScanService.NO_FILTER), token,
                    new CompareScanService.ProgressListener() {
                        @Override
                        public void onProgress(CompareScanService.ScanProgress progress) {
                            if (progress.stage == CompareScanService.ScanStage.HASHING) {
                                token.cancel();
                            }
                        }
                    });
        } catch (CancellationException expected) {
            cancelled.set(true);
        }
        assertTrue(cancelled.get(), "cancelled scan must not return a result");
    }

    private static void testFileChangeRetriesOnce() throws Exception {
        Path root = createTempDirectory("compare-change");
        final Path left = root.resolve("left.txt");
        Path right = root.resolve("right.txt");
        write(left, "old");
        write(right, "right");
        final AtomicBoolean changed = new AtomicBoolean();
        CompareScanService.ScanResult result = new CompareScanService().execute(
                new CompareScanService.ScanRequest(9L, false, left, right,
                        CompareScanService.NO_FILTER),
                new CompareScanService.CancellationToken(),
                new CompareScanService.ProgressListener() {
                    @Override
                    public void onProgress(CompareScanService.ScanProgress progress) {
                        if (progress.stage == CompareScanService.ScanStage.HASHING
                                && changed.compareAndSet(false, true)) {
                            try {
                                write(left, "changed-and-longer");
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    }
                });
        assertEquals(1, result.metrics.hashRetries, "hash retry count");
        assertEquals(Files.size(left) + Files.size(right), result.metrics.totalBytes,
                "adjusted total bytes");
    }

    private static void testReadFailureIsNotCancellation() throws Exception {
        Path root = createTempDirectory("compare-failure");
        final Path left = root.resolve("left.txt");
        Path right = root.resolve("right.txt");
        write(left, "left");
        write(right, "right");
        final AtomicBoolean deleted = new AtomicBoolean();
        boolean failed = false;
        try {
            new CompareScanService().execute(
                    new CompareScanService.ScanRequest(10L, false, left, right,
                            CompareScanService.NO_FILTER),
                    new CompareScanService.CancellationToken(),
                    new CompareScanService.ProgressListener() {
                        @Override
                        public void onProgress(CompareScanService.ScanProgress progress) {
                            if (progress.stage == CompareScanService.ScanStage.HASHING
                                    && deleted.compareAndSet(false, true)) {
                                try {
                                    Files.delete(left);
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            }
                        }
                    });
        } catch (java.io.IOException expected) {
            failed = true;
        } catch (CancellationException wrongTerminalState) {
            throw new AssertionError("Read failure was reported as cancellation");
        }
        assertTrue(failed, "deleted candidate must fail the scan");
    }

    private static CompareScanService.ScannedPair find(CompareScanService.ScanResult result,
                                                       String relativePath) {
        for (CompareScanService.ScannedPair pair : result.pairs) {
            if (relativePath.equals(pair.relativePath)) {
                return pair;
            }
        }
        throw new AssertionError("Missing pair " + relativePath);
    }

    private static void write(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static Path createTempDirectory(String prefix) throws Exception {
        Path testRoot = Paths.get("out", "scan-test-data");
        Files.createDirectories(testRoot);
        return Files.createTempDirectory(testRoot, prefix);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
