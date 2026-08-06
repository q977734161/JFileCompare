import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BuiltInFilterPresets {
    private static final List<FilterPreset> PRESETS;

    static {
        List<FilterPreset> presets = new ArrayList<FilterPreset>();
        presets.add(builtIn("builtin:general:v1", "通用",
                ".git, .svn, .hg",
                ".log, .tmp, .bak, .swp",
                "Thumbs.db, .DS_Store, ~$*, *.part"));
        presets.add(builtIn("builtin:java-source:v1", "Java 源码",
                ".git, .idea, .gradle, target, build, out",
                ".class, .log, .tmp",
                "*.iml, hs_err_pid*.log"));
        presets.add(builtIn("builtin:frontend-source:v1", "前端源码",
                ".git, node_modules, .cache, coverage, .next, .nuxt, dist, build",
                ".log, .tmp",
                "npm-debug.log*, yarn-debug.log*, yarn-error.log*, pnpm-debug.log*"));
        PRESETS = Collections.unmodifiableList(presets);
    }

    private BuiltInFilterPresets() {
    }

    private static FilterPreset builtIn(String id, String name, String directories,
                                        String extensions, String wildcards) {
        return new FilterPreset(id, FilterPreset.Kind.BUILT_IN, name,
                FilterRuleSet.fromText(directories, extensions, wildcards), 0L, 0L);
    }

    static List<FilterPreset> all() {
        return PRESETS;
    }

    static FilterPreset find(String id) {
        if (id == null) {
            return null;
        }
        for (FilterPreset preset : PRESETS) {
            if (preset.id().equals(id)) {
                return preset;
            }
        }
        return null;
    }
}
