import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FilterPresetServiceTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("out", "filter-config-test-data");
        Files.createDirectories(root);
        Path temp = Files.createTempDirectory(root, "service-");
        FilterPresetService service = new FilterPresetService(
                new FilterConfigRepository(temp.resolve("filter-config.xml")));
        try {
            assertEquals(3, BuiltInFilterPresets.all().size(), "built-in count");
            FilterPreset javaPreset = BuiltInFilterPresets.find("builtin:java-source:v1");
            assertEquals(6, javaPreset.rules().directoryCount(), "java directory count");

            FilterRuleSet rules = FilterRuleSet.fromText(".git,temp", ".log", "*_old.*");
            FilterPreset custom = service.createPreset("发布目录", rules);
            assertEquals(1, service.customPresets().size(), "create preset");
            expectFailure(new ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    service.createPreset("发布目录", FilterRuleSet.empty());
                }
            }, "已存在");

            service.renamePreset(custom.id(), "部署目录");
            assertEquals("部署目录", service.findPreset(custom.id()).name(), "rename preset");
            FilterRuleSet updated = FilterRuleSet.fromText(".git,target", ".tmp", "*.bak");
            service.updatePreset(custom.id(), updated);
            assertEquals(updated, service.findPreset(custom.id()).rules(), "update preset");

            service.activate(updated, custom.id());
            assertEquals("部署目录", service.displayName(service.active()), "active preset name");
            final CountDownLatch saved = new CountDownLatch(1);
            final String[] saveError = new String[1];
            service.persistActiveAsync(new FilterPresetService.SaveCallback() {
                @Override
                public void completed(String errorMessage) {
                    saveError[0] = errorMessage;
                    saved.countDown();
                }
            });
            assertEquals(true, saved.await(5L, TimeUnit.SECONDS), "active save callback");
            assertEquals(null, saveError[0], "active save error");

            service.deletePreset(custom.id());
            assertEquals(0, service.customPresets().size(), "delete preset");
            assertEquals("自定义规则", service.displayName(service.active()),
                    "deleted active preset keeps rules");
            assertEquals(updated, service.active().rules(), "deleted active rules");

            expectFailure(new ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    service.deletePreset("builtin:general:v1");
                }
            }, "内置预设");

            service.close();
            FilterPresetService restarted = new FilterPresetService(
                    new FilterConfigRepository(temp.resolve("filter-config.xml")));
            try {
                assertEquals(updated, restarted.active().rules(), "restart restores active rules");
                assertEquals("自定义规则", restarted.displayName(restarted.active()),
                        "restart keeps deleted preset as custom rules");
            } finally {
                restarted.close();
            }
        } finally {
            service.close();
            deleteTree(temp);
        }
        System.out.println("FilterPresetServiceTest passed");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void expectFailure(ThrowingRunnable runnable, String message) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("expected failure containing " + message);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(message)) {
                throw new AssertionError("unexpected message: " + expected.getMessage());
            }
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        ArrayList<Path> paths = new ArrayList<Path>();
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
