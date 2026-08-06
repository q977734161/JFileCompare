import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class PreferencesRepository {
    static final int VERSION = 1;
    static final long MAX_FILE_BYTES = 256L * 1024L;
    private static final long LOCK_TIMEOUT_MILLIS = 2000L;
    private static final int MAX_CORRUPT_BACKUPS = 3;

    static final class LoadResult {
        private final AppPreferences preferences;
        private final String warning;

        LoadResult(AppPreferences preferences, String warning) {
            this.preferences = preferences;
            this.warning = warning;
        }

        AppPreferences preferences() { return preferences; }
        String warning() { return warning; }
    }

    private final Path configPath;
    private final Path tempPath;
    private final Path lockPath;

    PreferencesRepository() {
        this(defaultConfigPath());
    }

    PreferencesRepository(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.tempPath = this.configPath.resolveSibling(this.configPath.getFileName() + ".tmp");
        this.lockPath = this.configPath.resolveSibling("preferences.lock");
    }

    static Path defaultConfigPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData, "FileCompareTool", "preferences.xml");
        }
        return Paths.get(System.getProperty("user.home"), ".file-compare-tool",
                "preferences.xml");
    }

    Path configPath() { return configPath; }

    LoadResult load() {
        if (!Files.exists(configPath)) {
            return new LoadResult(AppPreferences.defaults(), null);
        }
        try {
            return new LoadResult(readPreferences(), null);
        } catch (Exception ex) {
            String warning = "偏好设置已损坏，已恢复默认值：" + rootMessage(ex);
            try {
                backupCorruptFile();
            } catch (IOException backupFailure) {
                warning += "；损坏文件备份失败：" + backupFailure.getMessage();
            }
            return new LoadResult(AppPreferences.defaults(), warning);
        }
    }

    void save(AppPreferences preferences) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = acquireLock(channel);
            try {
                writePreferences(preferences);
            } finally {
                lock.release();
            }
        }
    }

    void reset() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = acquireLock(channel);
            try {
                Files.deleteIfExists(configPath);
                Files.deleteIfExists(tempPath);
                deleteCorruptBackups();
            } finally {
                lock.release();
            }
        }
    }

    private FileLock acquireLock(FileChannel channel) throws IOException {
        long deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() <= deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {
                // Another repository in this JVM currently owns the lock.
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("等待偏好设置文件锁时被中断", ex);
            }
        }
        throw new IOException("偏好设置正在被另一个程序实例修改，请稍后重试");
    }

    private AppPreferences readPreferences()
            throws IOException, ParserConfigurationException, SAXException {
        if (Files.size(configPath) > MAX_FILE_BYTES) {
            throw new IOException("偏好设置文件超过 256 KB 上限");
        }
        DocumentBuilder builder = secureDocumentBuilder();
        Document document;
        try (InputStream input = Files.newInputStream(configPath)) {
            document = builder.parse(input);
        }
        Element root = document.getDocumentElement();
        if (root == null || !"preferences".equals(root.getTagName())) {
            throw new IOException("偏好设置根节点无效");
        }
        if (!String.valueOf(VERSION).equals(root.getAttribute("version"))) {
            throw new IOException("不支持的偏好设置版本：" + root.getAttribute("version"));
        }

        AppPreferences defaults = AppPreferences.defaults();
        Element behavior = firstChild(root, "behavior");
        boolean linked = booleanAttribute(behavior, "linked-scroll",
                defaults.linkedScrollDefault());
        boolean confirm = booleanAttribute(behavior, "confirm-hunk-deletion",
                defaults.confirmHunkDeletion());
        boolean remember = booleanAttribute(behavior, "remember-chooser-locations",
                defaults.rememberChooserLocations());

        Element restore = firstChild(root, "restore");
        boolean restoreMain = booleanAttribute(restore, "main-window",
                defaults.restoreMainWindow());
        boolean restoreDivider = booleanAttribute(restore, "main-divider",
                defaults.restoreMainDivider());
        boolean restoreEditor = booleanAttribute(restore, "editor-window",
                defaults.restoreEditorWindow());

        Element main = firstChild(root, "main-window");
        WindowBounds mainBounds = parseBounds(main);
        boolean maximized = booleanAttribute(main, "maximized", false);
        double divider = doubleAttribute(main, "divider-ratio",
                AppPreferences.DEFAULT_DIVIDER_RATIO,
                AppPreferences.MIN_DIVIDER_RATIO, AppPreferences.MAX_DIVIDER_RATIO);

        WindowBounds editorBounds = parseBounds(firstChild(root, "editor-window"));
        Element locations = firstChild(root, "chooser-locations");
        String directory = remember ? safePath(childText(locations, "directory")) : null;
        String file = remember ? safePath(childText(locations, "file")) : null;
        return new AppPreferences(restoreMain, restoreDivider, restoreEditor, linked,
                confirm, remember, mainBounds, maximized, divider, editorBounds,
                directory, file);
    }

    private void writePreferences(AppPreferences value) throws IOException {
        try {
            DocumentBuilder builder = secureDocumentBuilder();
            Document document = builder.newDocument();
            Element root = document.createElement("preferences");
            root.setAttribute("version", String.valueOf(VERSION));
            document.appendChild(root);

            Element behavior = document.createElement("behavior");
            behavior.setAttribute("linked-scroll", String.valueOf(value.linkedScrollDefault()));
            behavior.setAttribute("confirm-hunk-deletion",
                    String.valueOf(value.confirmHunkDeletion()));
            behavior.setAttribute("remember-chooser-locations",
                    String.valueOf(value.rememberChooserLocations()));
            root.appendChild(behavior);

            Element restore = document.createElement("restore");
            restore.setAttribute("main-window", String.valueOf(value.restoreMainWindow()));
            restore.setAttribute("main-divider", String.valueOf(value.restoreMainDivider()));
            restore.setAttribute("editor-window", String.valueOf(value.restoreEditorWindow()));
            root.appendChild(restore);

            Element main = document.createElement("main-window");
            appendBounds(main, value.mainWindowBounds());
            main.setAttribute("maximized", String.valueOf(value.mainWindowMaximized()));
            main.setAttribute("divider-ratio", String.valueOf(value.mainDividerRatio()));
            root.appendChild(main);

            Element editor = document.createElement("editor-window");
            appendBounds(editor, value.editorWindowBounds());
            root.appendChild(editor);

            Element locations = document.createElement("chooser-locations");
            if (value.rememberChooserLocations()) {
                if (value.recentDirectoryLocation() != null) {
                    appendText(document, locations, "directory",
                            value.recentDirectoryLocation());
                }
                if (value.recentFileLocation() != null) {
                    appendText(document, locations, "file", value.recentFileLocation());
                }
            }
            root.appendChild(locations);

            TransformerFactory factory = TransformerFactory.newInstance();
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerException ignored) {
                // Parser features already protect input; keep older JAXP compatibility.
            }
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            try (FileOutputStream output = new FileOutputStream(tempPath.toFile())) {
                transformer.transform(new DOMSource(document), new StreamResult(output));
                output.getChannel().force(true);
            }
            if (Files.size(tempPath) > MAX_FILE_BYTES) {
                Files.deleteIfExists(tempPath);
                throw new IOException("偏好设置文件超过 256 KB 上限");
            }
            try {
                Files.move(tempPath, configPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (ParserConfigurationException ex) {
            throw new IOException("无法创建偏好设置 XML", ex);
        } catch (TransformerException ex) {
            throw new IOException("无法写入偏好设置 XML", ex);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static void appendBounds(Element element, WindowBounds bounds) {
        if (bounds == null) return;
        element.setAttribute("x", String.valueOf(bounds.x()));
        element.setAttribute("y", String.valueOf(bounds.y()));
        element.setAttribute("width", String.valueOf(bounds.width()));
        element.setAttribute("height", String.valueOf(bounds.height()));
    }

    private static void appendText(Document document, Element parent, String name,
                                   String value) {
        Element element = document.createElement(name);
        element.appendChild(document.createTextNode(value));
        parent.appendChild(element);
    }

    private static WindowBounds parseBounds(Element element) {
        if (element == null) return null;
        try {
            String x = element.getAttribute("x");
            String y = element.getAttribute("y");
            String width = element.getAttribute("width");
            String height = element.getAttribute("height");
            if (x.isEmpty() || y.isEmpty() || width.isEmpty() || height.isEmpty()) return null;
            return new WindowBounds(Integer.parseInt(x), Integer.parseInt(y),
                    Integer.parseInt(width), Integer.parseInt(height));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean booleanAttribute(Element element, String name, boolean fallback) {
        if (element == null) return fallback;
        String value = element.getAttribute(name);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private static double doubleAttribute(Element element, String name, double fallback,
                                          double minimum, double maximum) {
        if (element == null) return fallback;
        try {
            double value = Double.parseDouble(element.getAttribute(name));
            return Double.isNaN(value) || Double.isInfinite(value)
                    || value < minimum || value > maximum ? fallback : value;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String safePath(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            AppPreferences defaults = AppPreferences.defaults();
            return defaults.withChooserLocation(true, value).recentDirectoryLocation();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element child = firstChild(parent, name);
        return child == null || child.getTextContent() == null ? "" : child.getTextContent();
    }

    private static DocumentBuilder secureDocumentBuilder()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }
            @Override public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }
            @Override public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder;
    }

    private void backupCorruptFile() throws IOException {
        if (!Files.exists(configPath)) return;
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(new Date());
        Path backup = configPath.resolveSibling("preferences.corrupt-" + timestamp + ".xml");
        Files.move(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
        pruneCorruptBackups();
    }

    private void pruneCorruptBackups() throws IOException {
        List<Path> backups = corruptBackups();
        Collections.sort(backups, new Comparator<Path>() {
            @Override public int compare(Path left, Path right) {
                return right.getFileName().toString().compareTo(left.getFileName().toString());
            }
        });
        for (int i = MAX_CORRUPT_BACKUPS; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private void deleteCorruptBackups() throws IOException {
        for (Path path : corruptBackups()) Files.deleteIfExists(path);
    }

    private List<Path> corruptBackups() throws IOException {
        List<Path> result = new ArrayList<Path>();
        Path parent = configPath.getParent();
        if (parent == null || !Files.isDirectory(parent)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent,
                "preferences.corrupt-*.xml")) {
            for (Path path : stream) result.add(path);
        }
        return result;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
