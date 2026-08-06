import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ErrorHandler;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FilterConfigRepository {
    static final long MAX_FILE_BYTES = 1024L * 1024L;
    static final int VERSION = 1;
    private static final long LOCK_TIMEOUT_MILLIS = 2000L;
    private static final int MAX_CORRUPT_BACKUPS = 3;

    interface Mutation {
        FilterConfigSnapshot apply(FilterConfigSnapshot latest);
    }

    static final class LoadResult {
        private final FilterConfigSnapshot snapshot;
        private final String warning;

        LoadResult(FilterConfigSnapshot snapshot, String warning) {
            this.snapshot = snapshot;
            this.warning = warning;
        }

        FilterConfigSnapshot snapshot() { return snapshot; }
        String warning() { return warning; }
    }

    private final Path configPath;
    private final Path tempPath;
    private final Path lockPath;

    FilterConfigRepository() {
        this(defaultConfigPath());
    }

    FilterConfigRepository(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.tempPath = this.configPath.resolveSibling(this.configPath.getFileName() + ".tmp");
        this.lockPath = this.configPath.resolveSibling("filter-config.lock");
    }

    static Path defaultConfigPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData, "FileCompareTool", "filter-config.xml");
        }
        return Paths.get(System.getProperty("user.home"), ".file-compare-tool",
                "filter-config.xml");
    }

    Path configPath() {
        return configPath;
    }

    LoadResult load() {
        if (!Files.exists(configPath)) {
            return new LoadResult(FilterConfigSnapshot.empty(), null);
        }
        try {
            return new LoadResult(readSnapshot(), null);
        } catch (Exception ex) {
            String warning = "过滤配置已损坏，已恢复为空规则：" + rootMessage(ex);
            try {
                backupCorruptFile();
            } catch (IOException backupFailure) {
                warning += "；损坏文件备份失败：" + backupFailure.getMessage();
            }
            return new LoadResult(FilterConfigSnapshot.empty(), warning);
        }
    }

    FilterConfigSnapshot update(Mutation mutation) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = acquireLock(channel);
            try {
                FilterConfigSnapshot latest = loadUnderLock();
                FilterConfigSnapshot next = mutation.apply(latest);
                writeSnapshot(next);
                return next;
            } finally {
                lock.release();
            }
        }
    }

    void reset() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
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
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Another repository instance in this JVM currently owns the lock.
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("等待过滤配置文件锁时被中断", ex);
            }
        }
        throw new IOException("过滤配置正在被另一个程序实例修改，请稍后重试");
    }

    private FilterConfigSnapshot loadUnderLock() throws IOException {
        if (!Files.exists(configPath)) {
            return FilterConfigSnapshot.empty();
        }
        try {
            return readSnapshot();
        } catch (Exception ex) {
            backupCorruptFile();
            return FilterConfigSnapshot.empty();
        }
    }

    private FilterConfigSnapshot readSnapshot()
            throws IOException, ParserConfigurationException, SAXException {
        long size = Files.size(configPath);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("配置文件超过 1 MB 上限");
        }
        DocumentBuilder builder = secureDocumentBuilder();
        Document document;
        try (InputStream input = Files.newInputStream(configPath)) {
            document = builder.parse(input);
        }
        Element root = document.getDocumentElement();
        if (root == null || !"filter-config".equals(root.getTagName())) {
            throw new IOException("配置根节点无效");
        }
        if (!String.valueOf(VERSION).equals(root.getAttribute("version"))) {
            throw new IOException("不支持的过滤配置版本：" + root.getAttribute("version"));
        }

        ActiveFilterState active = parseActive(firstChild(root, "active"));
        List<FilterPreset> custom = new ArrayList<FilterPreset>();
        Set<String> ids = new HashSet<String>();
        Set<String> names = new HashSet<String>();
        for (FilterPreset builtIn : BuiltInFilterPresets.all()) {
            ids.add(builtIn.id());
            names.add(builtIn.name().toLowerCase(Locale.ROOT));
        }
        Element customRoot = firstChild(root, "custom-presets");
        if (customRoot != null) {
            NodeList nodes = customRoot.getChildNodes();
            for (int i = 0; i < nodes.getLength() && custom.size() < 20; i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element) || !"preset".equals(node.getNodeName())) {
                    continue;
                }
                try {
                    FilterPreset preset = parsePreset((Element) node);
                    String nameKey = preset.name().toLowerCase(Locale.ROOT);
                    if (isValidPersistedPreset(preset)
                            && !ids.contains(preset.id()) && !names.contains(nameKey)) {
                        ids.add(preset.id());
                        names.add(nameKey);
                        custom.add(preset);
                    }
                } catch (RuntimeException ignored) {
                    // A malformed optional preset does not discard the other valid records.
                }
            }
        }
        return new FilterConfigSnapshot(active, custom);
    }

    private static ActiveFilterState parseActive(Element element) {
        if (element == null) {
            return ActiveFilterState.empty();
        }
        try {
            FilterRuleSet rules = parseRules(element);
            ActiveFilterState.Source source = ActiveFilterState.Source.valueOf(
                    attributeOr(element, "source", rules.isEmpty() ? "EMPTY" : "CUSTOM_RULES"));
            String baseId = emptyToNull(element.getAttribute("base-preset-id"));
            if (rules.isEmpty()) {
                return ActiveFilterState.empty();
            }
            return new ActiveFilterState(rules, source, baseId);
        } catch (RuntimeException ex) {
            return ActiveFilterState.empty();
        }
    }

    private static FilterPreset parsePreset(Element element) {
        String id = requiredAttribute(element, "id");
        String name = requiredAttribute(element, "name");
        long created = parseLong(element.getAttribute("created-time"));
        long updated = parseLong(element.getAttribute("updated-time"));
        return new FilterPreset(id, FilterPreset.Kind.CUSTOM, name, parseRules(element),
                created, updated);
    }

    private static boolean isValidPersistedPreset(FilterPreset preset) {
        String id = preset.id();
        String name = preset.name();
        if (!id.equals(id.trim()) || id.length() > 128
                || !name.equals(name.trim()) || name.isEmpty()
                || name.length() > FilterPresetService.MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            if (Character.isISOControl(id.charAt(i))) {
                return false;
            }
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static FilterRuleSet parseRules(Element parent) {
        return FilterRuleSet.fromText(childText(parent, "directories"),
                childText(parent, "extensions"), childText(parent, "wildcards"));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private void writeSnapshot(FilterConfigSnapshot snapshot) throws IOException {
        try {
            DocumentBuilder builder = secureDocumentBuilder();
            Document document = builder.newDocument();
            Element root = document.createElement("filter-config");
            root.setAttribute("version", String.valueOf(VERSION));
            document.appendChild(root);
            appendActive(document, root, snapshot.active());
            Element customRoot = document.createElement("custom-presets");
            root.appendChild(customRoot);
            for (FilterPreset preset : snapshot.customPresets()) {
                appendPreset(document, customRoot, preset);
            }

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            try {
                transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerException ignored) {
                // The parser is already hardened; keep compatibility with older JAXP providers.
            }
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            try (FileOutputStream output = new FileOutputStream(tempPath.toFile())) {
                transformer.transform(new DOMSource(document), new StreamResult(output));
                output.getChannel().force(true);
            }
            if (Files.size(tempPath) > MAX_FILE_BYTES) {
                Files.deleteIfExists(tempPath);
                throw new IOException("过滤配置超过 1 MB 上限");
            }
            try {
                Files.move(tempPath, configPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (ParserConfigurationException ex) {
            throw new IOException("无法创建过滤配置 XML", ex);
        } catch (TransformerException ex) {
            throw new IOException("无法写入过滤配置 XML", ex);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static void appendActive(Document document, Element root, ActiveFilterState state) {
        Element active = document.createElement("active");
        active.setAttribute("source", state.source().name());
        if (state.basePresetId() != null) {
            active.setAttribute("base-preset-id", state.basePresetId());
        }
        appendRules(document, active, state.rules());
        root.appendChild(active);
    }

    private static void appendPreset(Document document, Element parent, FilterPreset preset) {
        Element element = document.createElement("preset");
        element.setAttribute("id", preset.id());
        element.setAttribute("name", preset.name());
        element.setAttribute("created-time", String.valueOf(preset.createdTime()));
        element.setAttribute("updated-time", String.valueOf(preset.updatedTime()));
        appendRules(document, element, preset.rules());
        parent.appendChild(element);
    }

    private static void appendRules(Document document, Element parent, FilterRuleSet rules) {
        appendText(document, parent, "directories", rules.directoryText());
        appendText(document, parent, "extensions", rules.extensionText());
        appendText(document, parent, "wildcards", rules.wildcardText());
    }

    private static void appendText(Document document, Element parent, String name, String value) {
        Element element = document.createElement(name);
        element.appendChild(document.createTextNode(value));
        parent.appendChild(element);
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
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder;
    }

    private void backupCorruptFile() throws IOException {
        if (!Files.exists(configPath)) {
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(new Date());
        Path backup = configPath.resolveSibling("filter-config.corrupt-" + timestamp + ".xml");
        Files.move(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
        pruneCorruptBackups();
    }

    private void pruneCorruptBackups() throws IOException {
        List<Path> backups = corruptBackups();
        Collections.sort(backups, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return right.getFileName().toString().compareTo(left.getFileName().toString());
            }
        });
        for (int i = MAX_CORRUPT_BACKUPS; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private void deleteCorruptBackups() throws IOException {
        for (Path path : corruptBackups()) {
            Files.deleteIfExists(path);
        }
    }

    private List<Path> corruptBackups() throws IOException {
        List<Path> backups = new ArrayList<Path>();
        Path parent = configPath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return backups;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent,
                "filter-config.corrupt-*.xml")) {
            for (Path path : stream) {
                backups.add(path);
            }
        }
        return backups;
    }

    private static Element firstChild(Element parent, String name) {
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
        return child == null ? "" : child.getTextContent();
    }

    private static String requiredAttribute(Element element, String name) {
        String value = emptyToNull(element.getAttribute(name));
        if (value == null) {
            throw new IllegalArgumentException("缺少字段：" + name);
        }
        return value;
    }

    private static String attributeOr(Element element, String name, String fallback) {
        String value = emptyToNull(element.getAttribute(name));
        return value == null ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
