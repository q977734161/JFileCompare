import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class CompareHistoryService implements AutoCloseable {
    static final int MAX_ENTRIES = 20;
    static final int MAX_PINNED = 10;

    interface SaveCallback {
        void completed(List<CompareHistoryEntry> entries, String errorMessage);
    }

    private final HistoryRepository repository;
    private final ExecutorService writer;
    private List<CompareHistoryEntry> entries;
    private final String loadWarning;

    CompareHistoryService() {
        this(new HistoryRepository());
    }

    CompareHistoryService(HistoryRepository repository) {
        this.repository = repository;
        HistoryRepository.LoadResult loaded = repository.load();
        this.entries = normalize(loaded.entries());
        this.loadWarning = loaded.warning();
        this.writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "compare-history-writer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    synchronized List<CompareHistoryEntry> entries() {
        return immutable(entries);
    }

    synchronized CompareHistoryEntry find(String id) {
        return findById(entries, id);
    }

    String loadWarning() {
        return loadWarning;
    }

    void recordSuccessAsync(final CompareHistoryMode mode, final Path left, final Path right,
                            final HistoryFilterSnapshot filter,
                            final HistoryResultSummary summary,
                            final SaveCallback callback) {
        final String leftValue = left.toAbsolutePath().normalize().toString();
        final String rightValue = right.toAbsolutePath().normalize().toString();
        writer.submit(new Runnable() {
            @Override public void run() {
                String error = null;
                List<CompareHistoryEntry> result;
                try {
                    result = persistSuccess(mode, leftValue, rightValue, filter, summary);
                } catch (Exception ex) {
                    error = rootMessage(ex);
                    synchronized (CompareHistoryService.this) {
                        result = immutable(entries);
                    }
                }
                if (callback != null) {
                    callback.completed(result, error);
                }
            }
        });
    }

    private synchronized List<CompareHistoryEntry> persistSuccess(
            final CompareHistoryMode mode, final String leftValue, final String rightValue,
            final HistoryFilterSnapshot filter, final HistoryResultSummary summary)
            throws IOException {
        List<CompareHistoryEntry> result = normalize(repository.update(
                new HistoryRepository.Mutation() {
                    @Override public List<CompareHistoryEntry> apply(
                            List<CompareHistoryEntry> latest) {
                        return upsertSuccess(latest, mode, leftValue, rightValue,
                                filter, summary, System.currentTimeMillis());
                    }
                }));
        entries = result;
        return immutable(result);
    }

    List<CompareHistoryEntry> togglePinned(final String id) throws IOException {
        return update(new HistoryRepository.Mutation() {
            @Override public List<CompareHistoryEntry> apply(List<CompareHistoryEntry> latest) {
                List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(latest);
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("历史记录不存在或已被其他实例删除");
                }
                CompareHistoryEntry current = values.get(index);
                if (!current.pinned() && countPinned(values) >= MAX_PINNED) {
                    throw new IllegalArgumentException("固定记录最多保存 " + MAX_PINNED + " 条");
                }
                values.set(index, current.withPinned(!current.pinned()));
                return normalize(values);
            }
        });
    }

    List<CompareHistoryEntry> updateNote(final String id, final String note) throws IOException {
        final String validNote = CompareHistoryEntry.cleanNote(note);
        return update(new HistoryRepository.Mutation() {
            @Override public List<CompareHistoryEntry> apply(List<CompareHistoryEntry> latest) {
                List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(latest);
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("历史记录不存在或已被其他实例删除");
                }
                values.set(index, values.get(index).withNote(validNote));
                return normalize(values);
            }
        });
    }

    List<CompareHistoryEntry> delete(final String id) throws IOException {
        return update(new HistoryRepository.Mutation() {
            @Override public List<CompareHistoryEntry> apply(List<CompareHistoryEntry> latest) {
                List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(latest);
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("历史记录不存在或已被其他实例删除");
                }
                values.remove(index);
                return normalize(values);
            }
        });
    }

    List<CompareHistoryEntry> relocate(final String id, final String leftPath,
                                       final String rightPath) throws IOException {
        return update(new HistoryRepository.Mutation() {
            @Override public List<CompareHistoryEntry> apply(List<CompareHistoryEntry> latest) {
                List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(latest);
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("历史记录不存在或已被其他实例删除");
                }
                CompareHistoryEntry moved = values.get(index).withPaths(leftPath, rightPath);
                int duplicateIndex = indexOfKey(values, moved.normalizedKey(), id);
                if (duplicateIndex < 0) {
                    values.set(index, moved);
                    return normalize(values);
                }
                CompareHistoryEntry duplicate = values.get(duplicateIndex);
                CompareHistoryEntry newest = moved.lastSuccessTime() >= duplicate.lastSuccessTime()
                        ? moved : duplicate;
                String note = duplicate.note().isEmpty() ? moved.note() : duplicate.note();
                CompareHistoryEntry merged = new CompareHistoryEntry(duplicate.id(), duplicate.mode(),
                        moved.leftPath(), moved.rightPath(),
                        Math.min(moved.createdTime(), duplicate.createdTime()),
                        Math.max(moved.lastSuccessTime(), duplicate.lastSuccessTime()),
                        moved.pinned() || duplicate.pinned(), note, newest.summary(), newest.filter());
                int high = Math.max(index, duplicateIndex);
                int low = Math.min(index, duplicateIndex);
                values.remove(high);
                values.remove(low);
                values.add(merged);
                return normalize(values);
            }
        });
    }

    synchronized void clear() throws IOException {
        repository.clear();
        entries = Collections.emptyList();
    }

    private synchronized List<CompareHistoryEntry> update(HistoryRepository.Mutation mutation)
            throws IOException {
        List<CompareHistoryEntry> result = normalize(repository.update(mutation));
        entries = result;
        return immutable(result);
    }

    private static List<CompareHistoryEntry> upsertSuccess(
            List<CompareHistoryEntry> latest, CompareHistoryMode mode,
            String left, String right, HistoryFilterSnapshot filter,
            HistoryResultSummary summary, long now) {
        List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(latest);
        String key = HistoryKeyFactory.key(mode, left, right);
        int existing = indexOfKey(values, key, null);
        if (existing >= 0) {
            CompareHistoryEntry current = values.get(existing);
            values.set(existing, current.successful(left, right, now, summary, filter));
        } else {
            values.add(new CompareHistoryEntry(UUID.randomUUID().toString(), mode, left, right,
                    now, now, false, "", summary, filter));
        }
        return normalize(values);
    }

    private static List<CompareHistoryEntry> normalize(List<CompareHistoryEntry> source) {
        List<CompareHistoryEntry> values = new ArrayList<CompareHistoryEntry>(source);
        Collections.sort(values, new Comparator<CompareHistoryEntry>() {
            @Override public int compare(CompareHistoryEntry left, CompareHistoryEntry right) {
                if (left.pinned() != right.pinned()) {
                    return left.pinned() ? -1 : 1;
                }
                int time = Long.compare(right.lastSuccessTime(), left.lastSuccessTime());
                return time != 0 ? time : left.id().compareTo(right.id());
            }
        });
        while (values.size() > MAX_ENTRIES) {
            int remove = values.size() - 1;
            while (remove >= 0 && values.get(remove).pinned()) {
                remove--;
            }
            if (remove < 0) {
                throw new IllegalArgumentException("固定记录过多，无法保存新的历史任务");
            }
            values.remove(remove);
        }
        return immutable(values);
    }

    private static int countPinned(List<CompareHistoryEntry> values) {
        int count = 0;
        for (CompareHistoryEntry entry : values) {
            if (entry.pinned()) {
                count++;
            }
        }
        return count;
    }

    private static int indexOf(List<CompareHistoryEntry> values, String id) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfKey(List<CompareHistoryEntry> values, String key,
                                  String exceptId) {
        for (int i = 0; i < values.size(); i++) {
            CompareHistoryEntry entry = values.get(i);
            if ((exceptId == null || !exceptId.equals(entry.id()))
                    && key.equals(entry.normalizedKey())) {
                return i;
            }
        }
        return -1;
    }

    private static CompareHistoryEntry findById(List<CompareHistoryEntry> values, String id) {
        int index = indexOf(values, id);
        return index < 0 ? null : values.get(index);
    }

    private static List<CompareHistoryEntry> immutable(List<CompareHistoryEntry> values) {
        return Collections.unmodifiableList(new ArrayList<CompareHistoryEntry>(values));
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    @Override public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
