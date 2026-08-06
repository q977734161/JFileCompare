import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class FilterPresetService implements AutoCloseable {
    static final int MAX_CUSTOM_PRESETS = 20;
    static final int MAX_NAME_LENGTH = 30;

    interface SaveCallback {
        void completed(String errorMessage);
    }

    private final FilterConfigRepository repository;
    private final ExecutorService persistenceExecutor;
    private FilterConfigSnapshot snapshot;
    private final String loadWarning;

    FilterPresetService() {
        this(new FilterConfigRepository());
    }

    FilterPresetService(FilterConfigRepository repository) {
        this.repository = repository;
        FilterConfigRepository.LoadResult result = repository.load();
        this.snapshot = reconcile(result.snapshot());
        this.loadWarning = result.warning();
        this.persistenceExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "filter-config-writer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    synchronized ActiveFilterState active() {
        return snapshot.active();
    }

    synchronized List<FilterPreset> customPresets() {
        return sortedCustom(snapshot.customPresets());
    }

    synchronized List<FilterPreset> allPresets() {
        List<FilterPreset> all = new ArrayList<FilterPreset>(BuiltInFilterPresets.all());
        all.addAll(sortedCustom(snapshot.customPresets()));
        return Collections.unmodifiableList(all);
    }

    String loadWarning() {
        return loadWarning;
    }

    synchronized FilterPreset findPreset(String id) {
        FilterPreset builtIn = BuiltInFilterPresets.find(id);
        if (builtIn != null) {
            return builtIn;
        }
        return findCustom(snapshot.customPresets(), id);
    }

    synchronized FilterPreset findCustomByName(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        for (FilterPreset preset : snapshot.customPresets()) {
            if (preset.name().toLowerCase(Locale.ROOT).equals(key)) {
                return preset;
            }
        }
        return null;
    }

    synchronized String displayName(ActiveFilterState state) {
        if (state == null || state.rules().isEmpty()) {
            return "未设置";
        }
        FilterPreset preset = findPreset(state.basePresetId());
        if (preset == null) {
            return "自定义规则";
        }
        return preset.rules().equals(state.rules()) ? preset.name() : preset.name() + "（已修改）";
    }

    synchronized ActiveFilterState activate(FilterRuleSet rules, String basePresetId) {
        ActiveFilterState next;
        if (rules == null || rules.isEmpty()) {
            next = ActiveFilterState.empty();
        } else {
            FilterPreset base = findPreset(basePresetId);
            ActiveFilterState.Source source = base == null
                    ? ActiveFilterState.Source.CUSTOM_RULES
                    : base.isBuiltIn() ? ActiveFilterState.Source.BUILT_IN
                    : ActiveFilterState.Source.CUSTOM_PRESET;
            next = new ActiveFilterState(rules, source, base == null ? null : base.id());
        }
        snapshot = snapshot.withActive(next);
        return next;
    }

    void persistActiveAsync(final SaveCallback callback) {
        final ActiveFilterState desired;
        synchronized (this) {
            desired = snapshot.active();
        }
        persistenceExecutor.submit(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    FilterConfigSnapshot persisted = repository.update(new FilterConfigRepository.Mutation() {
                        @Override
                        public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                            return latest.withActive(desired);
                        }
                    });
                    synchronized (FilterPresetService.this) {
                        snapshot = new FilterConfigSnapshot(desired, persisted.customPresets());
                    }
                } catch (IOException ex) {
                    error = ex.getMessage();
                }
                notifyCallback(callback, error);
            }
        });
    }

    FilterPreset createPreset(final String name, final FilterRuleSet rules) throws IOException {
        final String validName = validateName(name, null);
        final long now = System.currentTimeMillis();
        final FilterPreset created = new FilterPreset(UUID.randomUUID().toString(),
                FilterPreset.Kind.CUSTOM, validName, rules, now, now);
        FilterConfigSnapshot persisted = repository.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                validateCapacity(latest.customPresets());
                ensureUniqueName(validName, null, latest.customPresets());
                List<FilterPreset> values = new ArrayList<FilterPreset>(latest.customPresets());
                values.add(created);
                return latest.withCustomPresets(values);
            }
        });
        synchronized (this) {
            snapshot = reconcile(persisted);
        }
        return created;
    }

    void updatePreset(final String id, final FilterRuleSet rules) throws IOException {
        if (BuiltInFilterPresets.find(id) != null) {
            throw new IllegalArgumentException("内置预设不能修改");
        }
        FilterConfigSnapshot persisted = repository.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                List<FilterPreset> values = new ArrayList<FilterPreset>(latest.customPresets());
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("预设不存在或已被其他实例删除");
                }
                values.set(index, values.get(index).withRules(rules, System.currentTimeMillis()));
                return latest.withCustomPresets(values);
            }
        });
        synchronized (this) {
            snapshot = reconcile(persisted);
        }
    }

    void renamePreset(final String id, String name) throws IOException {
        final String validName = validateName(name, id);
        if (BuiltInFilterPresets.find(id) != null) {
            throw new IllegalArgumentException("内置预设不能重命名");
        }
        FilterConfigSnapshot persisted = repository.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                ensureUniqueName(validName, id, latest.customPresets());
                List<FilterPreset> values = new ArrayList<FilterPreset>(latest.customPresets());
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("预设不存在或已被其他实例删除");
                }
                values.set(index, values.get(index).renamed(validName, System.currentTimeMillis()));
                return latest.withCustomPresets(values);
            }
        });
        synchronized (this) {
            snapshot = reconcile(persisted);
        }
    }

    void deletePreset(final String id) throws IOException {
        if (BuiltInFilterPresets.find(id) != null) {
            throw new IllegalArgumentException("内置预设不能删除");
        }
        FilterConfigSnapshot persisted = repository.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                List<FilterPreset> values = new ArrayList<FilterPreset>(latest.customPresets());
                int index = indexOf(values, id);
                if (index < 0) {
                    throw new IllegalArgumentException("预设不存在或已被其他实例删除");
                }
                values.remove(index);
                ActiveFilterState active = latest.active();
                if (id.equals(active.basePresetId())) {
                    active = active.withoutPreset();
                }
                return new FilterConfigSnapshot(active, values);
            }
        });
        synchronized (this) {
            snapshot = reconcile(persisted);
        }
    }

    void resetAll() throws IOException {
        repository.reset();
        synchronized (this) {
            snapshot = FilterConfigSnapshot.empty();
        }
    }

    private synchronized String validateName(String name, String exceptId) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("请输入预设名称");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("预设名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("预设名称不能包含控制字符");
            }
        }
        List<FilterPreset> current = snapshot.customPresets();
        ensureUniqueName(value, exceptId, current);
        return value;
    }

    private static void validateCapacity(List<FilterPreset> presets) {
        if (presets.size() >= MAX_CUSTOM_PRESETS) {
            throw new IllegalArgumentException("自定义预设最多保存 " + MAX_CUSTOM_PRESETS + " 个");
        }
    }

    private static void ensureUniqueName(String name, String exceptId,
                                         List<FilterPreset> customPresets) {
        String key = name.toLowerCase(Locale.ROOT);
        for (FilterPreset builtIn : BuiltInFilterPresets.all()) {
            if (builtIn.name().toLowerCase(Locale.ROOT).equals(key)) {
                throw new IllegalArgumentException("预设名称已存在：" + name);
            }
        }
        for (FilterPreset preset : customPresets) {
            if (!preset.id().equals(exceptId)
                    && preset.name().toLowerCase(Locale.ROOT).equals(key)) {
                throw new IllegalArgumentException("预设名称已存在：" + name);
            }
        }
    }

    private static int indexOf(List<FilterPreset> presets, String id) {
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static FilterPreset findCustom(List<FilterPreset> presets, String id) {
        int index = indexOf(presets, id);
        return index < 0 ? null : presets.get(index);
    }

    private static List<FilterPreset> sortedCustom(List<FilterPreset> presets) {
        List<FilterPreset> sorted = new ArrayList<FilterPreset>(presets);
        Collections.sort(sorted, new Comparator<FilterPreset>() {
            @Override
            public int compare(FilterPreset left, FilterPreset right) {
                return left.name().compareToIgnoreCase(right.name());
            }
        });
        return Collections.unmodifiableList(sorted);
    }

    private static FilterConfigSnapshot reconcile(FilterConfigSnapshot value) {
        ActiveFilterState active = value.active();
        if (active.basePresetId() != null
                && BuiltInFilterPresets.find(active.basePresetId()) == null
                && findCustom(value.customPresets(), active.basePresetId()) == null) {
            active = active.withoutPreset();
        }
        return new FilterConfigSnapshot(active, value.customPresets());
    }

    private static void notifyCallback(final SaveCallback callback, final String error) {
        if (callback == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                callback.completed(error);
            }
        });
    }

    @Override
    public void close() {
        persistenceExecutor.shutdown();
        try {
            persistenceExecutor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

}
