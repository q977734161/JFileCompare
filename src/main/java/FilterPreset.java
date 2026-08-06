import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FilterPreset {
    enum Kind { BUILT_IN, CUSTOM }

    private final String id;
    private final Kind kind;
    private final String name;
    private final FilterRuleSet rules;
    private final long createdTime;
    private final long updatedTime;

    FilterPreset(String id, Kind kind, String name, FilterRuleSet rules,
                 long createdTime, long updatedTime) {
        if (id == null || id.trim().isEmpty() || name == null || rules == null || kind == null) {
            throw new IllegalArgumentException("预设字段不能为空");
        }
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.rules = rules;
        this.createdTime = createdTime;
        this.updatedTime = updatedTime;
    }

    String id() { return id; }
    Kind kind() { return kind; }
    String name() { return name; }
    FilterRuleSet rules() { return rules; }
    long createdTime() { return createdTime; }
    long updatedTime() { return updatedTime; }
    boolean isBuiltIn() { return kind == Kind.BUILT_IN; }

    FilterPreset renamed(String nextName, long now) {
        return new FilterPreset(id, kind, nextName, rules, createdTime, now);
    }

    FilterPreset withRules(FilterRuleSet nextRules, long now) {
        return new FilterPreset(id, kind, name, nextRules, createdTime, now);
    }
}

final class ActiveFilterState {
    enum Source { EMPTY, BUILT_IN, CUSTOM_PRESET, CUSTOM_RULES }

    private final FilterRuleSet rules;
    private final Source source;
    private final String basePresetId;

    ActiveFilterState(FilterRuleSet rules, Source source, String basePresetId) {
        this.rules = rules == null ? FilterRuleSet.empty() : rules;
        this.source = source == null ? Source.CUSTOM_RULES : source;
        this.basePresetId = basePresetId == null || basePresetId.trim().isEmpty()
                ? null : basePresetId;
    }

    static ActiveFilterState empty() {
        return new ActiveFilterState(FilterRuleSet.empty(), Source.EMPTY, null);
    }

    FilterRuleSet rules() { return rules; }
    Source source() { return source; }
    String basePresetId() { return basePresetId; }

    ActiveFilterState withoutPreset() {
        return rules.isEmpty() ? empty()
                : new ActiveFilterState(rules, Source.CUSTOM_RULES, null);
    }
}

final class FilterConfigSnapshot {
    private final ActiveFilterState active;
    private final List<FilterPreset> customPresets;

    FilterConfigSnapshot(ActiveFilterState active, List<FilterPreset> customPresets) {
        this.active = active == null ? ActiveFilterState.empty() : active;
        this.customPresets = Collections.unmodifiableList(
                new ArrayList<FilterPreset>(customPresets == null
                        ? Collections.<FilterPreset>emptyList() : customPresets));
    }

    static FilterConfigSnapshot empty() {
        return new FilterConfigSnapshot(ActiveFilterState.empty(),
                Collections.<FilterPreset>emptyList());
    }

    ActiveFilterState active() { return active; }
    List<FilterPreset> customPresets() { return customPresets; }

    FilterConfigSnapshot withActive(ActiveFilterState value) {
        return new FilterConfigSnapshot(value, customPresets);
    }

    FilterConfigSnapshot withCustomPresets(List<FilterPreset> values) {
        return new FilterConfigSnapshot(active, values);
    }
}
