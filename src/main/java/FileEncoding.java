import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class FileEncoding {
    enum Confidence {
        CONFIRMED,
        RELIABLE,
        HEURISTIC,
        UNCONFIRMED
    }

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16_LE_BOM = new byte[]{(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE_BOM = new byte[]{(byte) 0xFE, (byte) 0xFF};

    private final Charset charset;
    private final byte[] bom;
    private final Confidence confidence;
    private final String detectionSource;
    private final boolean userConfirmed;

    FileEncoding(Charset charset, byte[] bom, Confidence confidence,
                 String detectionSource, boolean userConfirmed) {
        this.charset = charset;
        this.bom = bom == null ? new byte[0] : Arrays.copyOf(bom, bom.length);
        this.confidence = confidence;
        this.detectionSource = detectionSource == null ? "" : detectionSource;
        this.userConfirmed = userConfirmed;
    }

    static FileEncoding utf8(boolean withBom, Confidence confidence, String source) {
        return new FileEncoding(StandardCharsets.UTF_8, withBom ? UTF8_BOM : new byte[0],
                confidence, source, false);
    }

    static FileEncoding utf16Le(boolean withBom, Confidence confidence, String source) {
        return new FileEncoding(StandardCharsets.UTF_16LE,
                withBom ? UTF16_LE_BOM : new byte[0], confidence, source, false);
    }

    static FileEncoding utf16Be(boolean withBom, Confidence confidence, String source) {
        return new FileEncoding(StandardCharsets.UTF_16BE,
                withBom ? UTF16_BE_BOM : new byte[0], confidence, source, false);
    }

    static FileEncoding manual(Charset charset, boolean withBom) {
        byte[] selectedBom = new byte[0];
        if (withBom && StandardCharsets.UTF_8.equals(charset)) {
            selectedBom = UTF8_BOM;
        } else if (withBom && StandardCharsets.UTF_16LE.equals(charset)) {
            selectedBom = UTF16_LE_BOM;
        } else if (withBom && StandardCharsets.UTF_16BE.equals(charset)) {
            selectedBom = UTF16_BE_BOM;
        }
        return new FileEncoding(charset, selectedBom, Confidence.CONFIRMED,
                "用户选择", true);
    }

    Charset getCharset() {
        return charset;
    }

    byte[] getBom() {
        return Arrays.copyOf(bom, bom.length);
    }

    int getBomLength() {
        return bom.length;
    }

    Confidence getConfidence() {
        return confidence;
    }

    String getDetectionSource() {
        return detectionSource;
    }

    boolean isUserConfirmed() {
        return userConfirmed;
    }

    boolean hasBom() {
        return bom.length > 0;
    }

    FileEncoding confirmedByUser() {
        return new FileEncoding(charset, bom, Confidence.CONFIRMED, "用户确认", true);
    }

    String getDisplayName() {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return hasBom() ? "UTF-8 BOM" : "UTF-8";
        }
        if (StandardCharsets.UTF_16LE.equals(charset)) {
            return hasBom() ? "UTF-16 LE BOM" : "UTF-16 LE";
        }
        if (StandardCharsets.UTF_16BE.equals(charset)) {
            return hasBom() ? "UTF-16 BE BOM" : "UTF-16 BE";
        }
        String name = charset.name();
        if ("GB18030".equalsIgnoreCase(name)) {
            return "GB18030";
        }
        if ("GBK".equalsIgnoreCase(name)) {
            return "GBK";
        }
        if ("GB2312".equalsIgnoreCase(name) || "EUC-CN".equalsIgnoreCase(name)) {
            return "GB2312";
        }
        if ("Big5".equalsIgnoreCase(name)) {
            return "Big5";
        }
        return name;
    }

    String getConfidenceLabel() {
        if (confidence == Confidence.CONFIRMED) {
            return "已确认";
        }
        if (confidence == Confidence.RELIABLE) {
            return "可靠";
        }
        if (confidence == Confidence.HEURISTIC) {
            return "推测";
        }
        return "待确认";
    }

    boolean sameFormat(FileEncoding other) {
        return other != null && charset.equals(other.charset) && Arrays.equals(bom, other.bom);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
