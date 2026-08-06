import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class AppInfo {
    static final String NAME = "文件对比工具";
    static final String VERSION = "0.9.0-rc1";
    static final String CHANNEL = "候选版";
    static final String BUILD_DATE = "2026-08-06";
    static final long SOFT_EDITABLE_FILE_BYTES = 20L * 1024L * 1024L;
    static final long HARD_EDITABLE_FILE_BYTES = 100L * 1024L * 1024L;

    private AppInfo() {
    }

    static String version() {
        return propertyOrDefault("filecompare.version", VERSION);
    }

    static String channel() {
        return propertyOrDefault("filecompare.channel", CHANNEL);
    }

    static String buildDate() {
        return propertyOrDefault("filecompare.build.date", BUILD_DATE);
    }

    static String commit() {
        return propertyOrDefault("filecompare.build.commit", "unknown");
    }

    static String runtimeSummary() {
        return System.getProperty("java.runtime.version", System.getProperty("java.version"))
                + " · " + System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.arch", "unknown");
    }

    static Path dataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData, "FileCompareTool").toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".file-compare-tool")
                .toAbsolutePath().normalize();
    }

    static String diagnosticInfo() {
        return "应用：" + NAME + System.lineSeparator()
                + "版本：" + version() + "（" + channel() + "）" + System.lineSeparator()
                + "构建：" + buildDate() + " · " + commit() + System.lineSeparator()
                + "运行环境：" + runtimeSummary();
    }

    static Path distributionFile(String name) {
        Path base = Paths.get(System.getProperty("user.dir", "."));
        try {
            Path codeLocation = Paths.get(AppInfo.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeLocation)) {
                base = codeLocation.getParent();
            } else if (Files.isDirectory(codeLocation)
                    && codeLocation.getFileName() != null
                    && "app".equalsIgnoreCase(codeLocation.getFileName().toString())) {
                base = codeLocation.getParent();
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // The working directory remains a usable fallback for development runs.
        }
        Path candidate = base.resolve(name).normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        if (base.getParent() != null) {
            Path parentCandidate = base.getParent().resolve(name).normalize();
            if (Files.exists(parentCandidate)) {
                return parentCandidate;
            }
        }
        return Paths.get(name).toAbsolutePath().normalize();
    }

    static void openInDesktop(Path path) throws IOException {
        if (path == null) {
            throw new IOException("路径为空");
        }
        if (!GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(path.toFile());
            return;
        }
        throw new IOException("当前系统不支持打开文件或目录");
    }

    private static String propertyOrDefault(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
