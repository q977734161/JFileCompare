import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class PreferencesService implements AutoCloseable {
    static final long SAVE_DELAY_MILLIS = 600L;

    interface Mutation {
        AppPreferences apply(AppPreferences current);
    }

    interface ErrorListener {
        void saveFailed(String message);
    }

    private final PreferencesRepository repository;
    private final ScheduledExecutorService writer;
    private final Object writeLock = new Object();
    private AppPreferences preferences;
    private AppPreferences persisted;
    private final String loadWarning;
    private ErrorListener errorListener;
    private ScheduledFuture<?> pendingSave;
    private long revision;
    private boolean closed;

    PreferencesService() {
        this(new PreferencesRepository());
    }

    PreferencesService(PreferencesRepository repository) {
        this.repository = repository;
        PreferencesRepository.LoadResult loaded = repository.load();
        this.preferences = loaded.preferences();
        this.persisted = loaded.preferences();
        this.loadWarning = loaded.warning();
        this.writer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "preferences-writer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    synchronized AppPreferences current() {
        return preferences;
    }

    String loadWarning() {
        return loadWarning;
    }

    synchronized void setErrorListener(ErrorListener listener) {
        this.errorListener = listener;
    }

    void update(Mutation mutation) {
        synchronized (this) {
            if (closed) return;
            AppPreferences next = mutation.apply(preferences);
            if (next == null || next.equals(preferences)) return;
            preferences = next;
            scheduleSaveLocked();
        }
    }

    void replace(final AppPreferences value) {
        if (value == null) return;
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return value;
            }
        });
    }

    void updateMainWindow(final WindowBounds bounds, final boolean maximized) {
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return current.withMainWindow(bounds, maximized);
            }
        });
    }

    void updateMainDivider(final double ratio) {
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return current.withMainDivider(ratio);
            }
        });
    }

    void updateEditorWindow(final WindowBounds bounds) {
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return current.withEditorWindow(bounds);
            }
        });
    }

    void updateLinkedScroll(final boolean selected) {
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return current.withLinkedScrollDefault(selected);
            }
        });
    }

    void updateConfirmDeletion(final boolean selected) {
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return current.withConfirmHunkDeletion(selected);
            }
        });
    }

    void updateChooserLocation(final boolean directory, final Path selected) {
        if (selected == null) return;
        final String value = selected.toAbsolutePath().normalize().toString();
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                if (!current.rememberChooserLocations()) return current;
                return current.withChooserLocation(directory, value);
            }
        });
    }

    void clearChooserLocation(final boolean directory) {
        update(new Mutation() {
            @Override public AppPreferences apply(AppPreferences current) {
                return current.clearChooserLocation(directory);
            }
        });
    }

    Path chooserStart(boolean directory) {
        AppPreferences snapshot = current();
        if (!snapshot.rememberChooserLocations()) return null;
        String value = directory ? snapshot.recentDirectoryLocation()
                : snapshot.recentFileLocation();
        if (value == null) return null;
        try {
            Path candidate = Paths.get(value).toAbsolutePath().normalize();
            while (candidate != null && !Files.isDirectory(candidate)) {
                candidate = candidate.getParent();
            }
            return candidate;
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    void reset() throws IOException {
        synchronized (this) {
            if (pendingSave != null) pendingSave.cancel(false);
            pendingSave = null;
            revision++;
            preferences = AppPreferences.defaults();
            persisted = preferences;
        }
        synchronized (writeLock) {
            repository.reset();
        }
    }

    void flush() throws IOException {
        AppPreferences snapshot;
        synchronized (this) {
            if (pendingSave != null) pendingSave.cancel(false);
            pendingSave = null;
            snapshot = preferences;
            if (snapshot.equals(persisted)) return;
        }
        persist(snapshot, revision);
    }

    private void scheduleSaveLocked() {
        if (pendingSave != null) pendingSave.cancel(false);
        final long scheduledRevision = ++revision;
        pendingSave = writer.schedule(new Runnable() {
            @Override public void run() {
                AppPreferences snapshot;
                synchronized (PreferencesService.this) {
                    if (closed || scheduledRevision != revision) return;
                    snapshot = preferences;
                    pendingSave = null;
                    if (snapshot.equals(persisted)) return;
                }
                try {
                    persist(snapshot, scheduledRevision);
                } catch (IOException ex) {
                    notifyFailure(ex.getMessage());
                }
            }
        }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void persist(AppPreferences snapshot, long expectedRevision) throws IOException {
        synchronized (writeLock) {
            synchronized (this) {
                if (expectedRevision != revision) return;
            }
            repository.save(snapshot);
        }
        synchronized (this) {
            if (expectedRevision == revision) persisted = snapshot;
            if (!preferences.equals(snapshot) && !closed) scheduleSaveLocked();
        }
    }

    private void notifyFailure(String message) {
        ErrorListener listener;
        synchronized (this) {
            listener = errorListener;
        }
        if (listener != null) listener.saveFailed(message);
    }

    @Override public void close() {
        try {
            flush();
        } catch (IOException ex) {
            notifyFailure(ex.getMessage());
        }
        synchronized (this) {
            closed = true;
            if (pendingSave != null) pendingSave.cancel(false);
            pendingSave = null;
        }
        writer.shutdown();
        try {
            writer.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
