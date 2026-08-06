import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FilterConfigRepositoryTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "filter-config-test-data");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "repository-");
        try {
            roundTripAndMerge(temp.resolve("filter-config.xml"));
            skipsInvalidAndDuplicatePresets(temp.resolve("duplicates.xml"));
            rejectsDoctypeAndBacksUp(temp.resolve("malicious.xml"));
            resetDeletesConfigAndCorruptBackups(temp.resolve("reset.xml"));
        } finally {
            deleteTree(temp);
        }
        System.out.println("FilterConfigRepositoryTest passed");
    }

    private static void skipsInvalidAndDuplicatePresets(Path path) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<filter-config version=\"1\"><active/><custom-presets>"
                + presetXml("custom-1", "有效预设")
                + presetXml("custom-1", "重复 ID")
                + presetXml("custom-2", "有效预设")
                + presetXml("builtin:general:v1", "占用内置 ID")
                + presetXml("custom-3", "通用")
                + presetXml(repeat("x", 129), "超长 ID")
                + presetXml("custom-5", repeat("名", 31))
                + presetXml("custom-6", "")
                + presetXml("custom-2", "重名记录未占用 ID")
                + presetXml("custom-7", "第二个有效预设")
                + "</custom-presets></filter-config>";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));

        FilterConfigRepository.LoadResult loaded = new FilterConfigRepository(path).load();
        assertEquals(null, loaded.warning(), "invalid optional records warning");
        assertEquals(3, loaded.snapshot().customPresets().size(),
                "valid preset count " + presetIds(loaded.snapshot().customPresets()));
        assertEquals("custom-1", loaded.snapshot().customPresets().get(0).id(),
                "first valid preset");
        assertEquals("custom-2", loaded.snapshot().customPresets().get(1).id(),
                "recovered valid preset");
        assertEquals("custom-7", loaded.snapshot().customPresets().get(2).id(),
                "last valid preset");
    }

    private static String presetXml(String id, String name) {
        return "<preset id=\"" + id + "\" name=\"" + name
                + "\" created-time=\"1\" updated-time=\"1\">"
                + "<directories/><extensions/><wildcards/></preset>";
    }

    private static String presetIds(List<FilterPreset> presets) {
        List<String> values = new ArrayList<String>();
        for (FilterPreset preset : presets) {
            values.add(preset.id() + "=" + preset.name());
        }
        return values.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void roundTripAndMerge(Path path) throws Exception {
        final FilterRuleSet activeRules = FilterRuleSet.fromText(".git,target", ".log", "*.bak");
        final FilterPreset custom = new FilterPreset("custom-1", FilterPreset.Kind.CUSTOM,
                "发布 & 配置 <1>", FilterRuleSet.fromText("temp", ".tmp", "*_old.*"),
                10L, 20L);
        FilterConfigRepository first = new FilterConfigRepository(path);
        first.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                List<FilterPreset> values = new ArrayList<FilterPreset>();
                values.add(custom);
                return new FilterConfigSnapshot(new ActiveFilterState(activeRules,
                        ActiveFilterState.Source.BUILT_IN, "builtin:java-source:v1"), values);
            }
        });

        FilterConfigRepository.LoadResult loaded = first.load();
        assertEquals(null, loaded.warning(), "round-trip warning");
        assertEquals(activeRules, loaded.snapshot().active().rules(), "active rules");
        assertEquals("发布 & 配置 <1>", loaded.snapshot().customPresets().get(0).name(),
                "escaped preset name");

        FilterConfigRepository second = new FilterConfigRepository(path);
        second.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                List<FilterPreset> values = new ArrayList<FilterPreset>(latest.customPresets());
                values.add(new FilterPreset("custom-2", FilterPreset.Kind.CUSTOM, "第二个",
                        FilterRuleSet.empty(), 30L, 30L));
                return latest.withCustomPresets(values);
            }
        });
        FilterConfigSnapshot merged = first.load().snapshot();
        assertEquals(2, merged.customPresets().size(), "multi-instance merge");
        assertEquals(activeRules, merged.active().rules(), "multi-instance keeps active");
        assertEquals(false, Files.exists(path.resolveSibling("filter-config.xml.tmp")),
                "temporary file removed");
    }

    private static void rejectsDoctypeAndBacksUp(Path path) throws Exception {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]>"
                + "<filter-config version=\"1\"><active><directories>&e;</directories>"
                + "</active></filter-config>";
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        FilterConfigRepository.LoadResult loaded = new FilterConfigRepository(path).load();
        assertEquals(true, loaded.warning() != null, "doctype warning");
        assertEquals(true, loaded.snapshot().active().rules().isEmpty(), "doctype fallback");
        assertEquals(false, Files.exists(path), "malicious config moved");
        assertEquals(1, countCorrupt(path.getParent()), "corrupt backup count");
    }

    private static void resetDeletesConfigAndCorruptBackups(Path path) throws Exception {
        final FilterConfigRepository repository = new FilterConfigRepository(path);
        repository.update(new FilterConfigRepository.Mutation() {
            @Override
            public FilterConfigSnapshot apply(FilterConfigSnapshot latest) {
                return latest.withActive(new ActiveFilterState(
                        FilterRuleSet.fromText(".git", "", ""),
                        ActiveFilterState.Source.CUSTOM_RULES, null));
            }
        });
        Files.write(path.resolveSibling("filter-config.corrupt-20000101-000000-000.xml"),
                "bad".getBytes(StandardCharsets.UTF_8));
        repository.reset();
        assertEquals(false, Files.exists(path), "reset config");
        assertEquals(0, countCorrupt(path.getParent()), "reset corrupt files");
    }

    private static int countCorrupt(Path directory) throws Exception {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                "filter-config.corrupt-*.xml")) {
            for (Path ignored : stream) {
                count++;
            }
        }
        return count;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(paths.get(i));
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected " + expected + " but was " + actual);
        }
    }
}
