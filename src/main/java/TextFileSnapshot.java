import java.nio.file.Path;
import java.util.Arrays;

final class TextFileSnapshot {
    private final Path path;
    private final boolean existed;
    private final byte[] rawBytes;
    private final FileEncoding encoding;
    private final LineDocument document;

    TextFileSnapshot(Path path, boolean existed, byte[] rawBytes,
                     FileEncoding encoding, LineDocument document) {
        this.path = path;
        this.existed = existed;
        this.rawBytes = Arrays.copyOf(rawBytes, rawBytes.length);
        this.encoding = encoding;
        this.document = document;
    }

    Path getPath() {
        return path;
    }

    boolean existed() {
        return existed;
    }

    byte[] getRawBytes() {
        return Arrays.copyOf(rawBytes, rawBytes.length);
    }

    FileEncoding getEncoding() {
        return encoding;
    }

    LineDocument getDocument() {
        return document;
    }
}
