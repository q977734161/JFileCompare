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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class HistoryRepository {
    static final int VERSION = 1;
    static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long LOCK_TIMEOUT_MILLIS = 2000L;
    private static final int MAX_CORRUPT_BACKUPS = 3;

    interface Mutation {
        List<CompareHistoryEntry> apply(List<CompareHistoryEntry> latest);
    }

    static final class LoadResult {
        private final List<CompareHistoryEntry> entries;
        private final String warning;

        LoadResult(List<CompareHistoryEntry> entries, String warning) {
            this.entries = immutable(entries);
            this.warning = warning;
        }

        List<CompareHistoryEntry> entries() { return entries; }
        String warning() { return warning; }
    }

    private final Path configPath;
    private final Path tempPath;
    private final Path lockPath;

    HistoryRepository() {
        this(defaultConfigPath());
    }

    HistoryRepository(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.tempPath = this.configPath.resolveSibling(this.configPath.getFileName() + ".tmp");
        this.lockPath = this.configPath.resolveSibling("history.lock");
    }

    static Path defaultConfigPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData, "FileCompareTool", "history.xml");
        }
        return Paths.get(System.getProperty("user.home"), ".file-compare-tool", "history.xml");
    }

    Path configPath() { return configPath; }

    LoadResult load() {
        if (!Files.exists(configPath)) {
            return new LoadResult(Collections.<CompareHistoryEntry>emptyList(), null);
        }
        try {
            return new LoadResult(readEntries(), null);
        } catch (Exception ex) {
            String warning = "对比历史已损坏，已恢复为空列表：" + rootMessage(ex);
            try {
                backupCorruptFile();
            } catch (IOException backupFailure) {
                warning += "；损坏文件备份失败：" + backupFailure.getMessage();
            }
            return new LoadResult(Collections.<CompareHistoryEntry>emptyList(), warning);
        }
    }

    List<CompareHistoryEntry> update(Mutation mutation) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = acquireLock(channel);
            try {
                List<CompareHistoryEntry> latest = loadUnderLock();
                List<CompareHistoryEntry> next = immutable(mutation.apply(latest));
                writeEntries(next);
                return next;
            } finally {
                lock.release();
            }
        }
    }

    void clear() throws IOException {
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
                // Another repository in this JVM owns the lock.
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("等待历史文件锁时被中断", ex);
            }
        }
        throw new IOException("对比历史正在被另一个程序实例修改，请稍后重试");
    }

    private List<CompareHistoryEntry> loadUnderLock() throws IOException {
        if (!Files.exists(configPath)) {
            return Collections.emptyList();
        }
        try {
            return readEntries();
        } catch (Exception ex) {
            backupCorruptFile();
            return Collections.emptyList();
        }
    }

    private List<CompareHistoryEntry> readEntries()
            throws IOException, ParserConfigurationException, SAXException {
        if (Files.size(configPath) > MAX_FILE_BYTES) {
            throw new IOException("历史文件超过 2 MB 上限");
        }
        DocumentBuilder builder = secureDocumentBuilder();
        Document document;
        try (InputStream input = Files.newInputStream(configPath)) {
            document = builder.parse(input);
        }
        Element root = document.getDocumentElement();
        if (root == null || !"compare-history".equals(root.getTagName())) {
            throw new IOException("历史根节点无效");
        }
        if (!String.valueOf(VERSION).equals(root.getAttribute("version"))) {
            throw new IOException("不支持的历史版本：" + root.getAttribute("version"));
        }

        List<CompareHistoryEntry> entries = new ArrayList<CompareHistoryEntry>();
        Set<String> ids = new HashSet<String>();
        Set<String> keys = new HashSet<String>();
        int pinnedCount = 0;
        NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength() && entries.size() < CompareHistoryService.MAX_ENTRIES;
             i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element) || !"entry".equals(node.getNodeName())) {
                continue;
            }
            try {
                CompareHistoryEntry entry = parseEntry((Element) node);
                if (ids.contains(entry.id()) || keys.contains(entry.normalizedKey())) {
                    continue;
                }
                if (entry.pinned() && pinnedCount >= CompareHistoryService.MAX_PINNED) {
                    entry = entry.withPinned(false);
                }
                ids.add(entry.id());
                keys.add(entry.normalizedKey());
                if (entry.pinned()) {
                    pinnedCount++;
                }
                entries.add(entry);
            } catch (RuntimeException ignored) {
                // One malformed optional entry does not discard other valid history.
            }
        }
        return entries;
    }

    private static CompareHistoryEntry parseEntry(Element element) {
        String id = requiredAttribute(element, "id");
        CompareHistoryMode mode = CompareHistoryMode.valueOf(requiredAttribute(element, "mode"));
        boolean pinned = Boolean.parseBoolean(element.getAttribute("pinned"));
        String left = requiredChildText(element, "left-path");
        String right = requiredChildText(element, "right-path");
        long created = parseLong(childText(element, "created-time"));
        long success = parseLong(childText(element, "last-success-time"));
        String note = childText(element, "note");

        Element summaryElement = firstChild(element, "summary");
        HistoryResultSummary summary = summaryElement == null ? HistoryResultSummary.empty()
                : new HistoryResultSummary(parseInt(summaryElement.getAttribute("same")),
                parseInt(summaryElement.getAttribute("different")),
                parseInt(summaryElement.getAttribute("left-only")),
                parseInt(summaryElement.getAttribute("right-only")),
                parseInt(summaryElement.getAttribute("excluded-directories")),
                parseInt(summaryElement.getAttribute("excluded-files")));

        Element filterElement = firstChild(element, "filter");
        HistoryFilterSnapshot filter = filterElement == null ? HistoryFilterSnapshot.empty()
                : new HistoryFilterSnapshot(childText(filterElement, "directories"),
                childText(filterElement, "extensions"), childText(filterElement, "wildcards"),
                emptyToNull(filterElement.getAttribute("preset-id")));
        return new CompareHistoryEntry(id, mode, left, right, created, success, pinned,
                note, summary, filter);
    }

    private void writeEntries(List<CompareHistoryEntry> entries) throws IOException {
        try {
            DocumentBuilder builder = secureDocumentBuilder();
            Document document = builder.newDocument();
            Element root = document.createElement("compare-history");
            root.setAttribute("version", String.valueOf(VERSION));
            document.appendChild(root);
            for (CompareHistoryEntry entry : entries) {
                appendEntry(document, root, entry);
            }

            TransformerFactory factory = TransformerFactory.newInstance();
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerException ignored) {
                // Keep compatibility with older JAXP providers.
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
                throw new IOException("历史文件超过 2 MB 上限");
            }
            try {
                Files.move(tempPath, configPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (ParserConfigurationException ex) {
            throw new IOException("无法创建历史 XML", ex);
        } catch (TransformerException ex) {
            throw new IOException("无法写入历史 XML", ex);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static void appendEntry(Document document, Element root, CompareHistoryEntry entry) {
        Element element = document.createElement("entry");
        element.setAttribute("id", entry.id());
        element.setAttribute("mode", entry.mode().name());
        element.setAttribute("pinned", String.valueOf(entry.pinned()));
        appendText(document, element, "left-path", entry.leftPath());
        appendText(document, element, "right-path", entry.rightPath());
        appendText(document, element, "created-time", String.valueOf(entry.createdTime()));
        appendText(document, element, "last-success-time",
                String.valueOf(entry.lastSuccessTime()));
        appendText(document, element, "note", entry.note());

        HistoryResultSummary value = entry.summary();
        Element summary = document.createElement("summary");
        summary.setAttribute("same", String.valueOf(value.sameCount()));
        summary.setAttribute("different", String.valueOf(value.differentCount()));
        summary.setAttribute("left-only", String.valueOf(value.leftOnlyCount()));
        summary.setAttribute("right-only", String.valueOf(value.rightOnlyCount()));
        summary.setAttribute("excluded-directories",
                String.valueOf(value.excludedDirectoryCount()));
        summary.setAttribute("excluded-files", String.valueOf(value.excludedFileCount()));
        element.appendChild(summary);

        HistoryFilterSnapshot filterValue = entry.filter();
        Element filter = document.createElement("filter");
        if (filterValue.presetId() != null) {
            filter.setAttribute("preset-id", filterValue.presetId());
        }
        appendText(document, filter, "directories", filterValue.directoryText());
        appendText(document, filter, "extensions", filterValue.extensionText());
        appendText(document, filter, "wildcards", filterValue.wildcardText());
        element.appendChild(filter);
        root.appendChild(element);
    }

    private static void appendText(Document document, Element parent, String name, String value) {
        Element element = document.createElement(name);
        element.appendChild(document.createTextNode(value == null ? "" : value));
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
        if (!Files.exists(configPath)) {
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(new Date());
        Path backup = configPath.resolveSibling("history.corrupt-" + timestamp + ".xml");
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
        for (Path backup : corruptBackups()) {
            Files.deleteIfExists(backup);
        }
    }

    private List<Path> corruptBackups() throws IOException {
        List<Path> result = new ArrayList<Path>();
        Path parent = configPath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent,
                "history.corrupt-*.xml")) {
            for (Path path : stream) {
                result.add(path);
            }
        }
        return result;
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
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

    private static String requiredChildText(Element parent, String name) {
        String value = childText(parent, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少历史字段：" + name);
        }
        return value;
    }

    private static String requiredAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少历史属性：" + name);
        }
        return value;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static List<CompareHistoryEntry> immutable(List<CompareHistoryEntry> values) {
        return Collections.unmodifiableList(new ArrayList<CompareHistoryEntry>(
                values == null ? Collections.<CompareHistoryEntry>emptyList() : values));
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
