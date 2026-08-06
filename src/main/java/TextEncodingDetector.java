import org.mozilla.universalchardet.UniversalDetector;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TextEncodingDetector {
    EncodingDetection detect(byte[] bytes) {
        if (startsWith(bytes, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) {
            return confirmed(FileEncoding.utf8(true, FileEncoding.Confidence.CONFIRMED,
                    "UTF-8 BOM"));
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xFE})) {
            return confirmed(FileEncoding.utf16Le(true, FileEncoding.Confidence.CONFIRMED,
                    "UTF-16 LE BOM"));
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFE, (byte) 0xFF})) {
            return confirmed(FileEncoding.utf16Be(true, FileEncoding.Confidence.CONFIRMED,
                    "UTF-16 BE BOM"));
        }
        if (bytes.length == 0) {
            return confirmed(FileEncoding.utf8(false, FileEncoding.Confidence.RELIABLE,
                    "空文件默认 UTF-8"));
        }
        if (isAscii(bytes)) {
            return confirmed(FileEncoding.utf8(false, FileEncoding.Confidence.RELIABLE,
                    "纯 ASCII，默认 UTF-8"));
        }

        FileEncoding utf16 = detectUtf16WithoutBom(bytes);
        if (utf16 != null) {
            return confirmed(utf16);
        }
        if (canDecode(bytes, StandardCharsets.UTF_8, 0)
                && isPlausibleText(bytes, StandardCharsets.UTF_8)) {
            return confirmed(FileEncoding.utf8(false, FileEncoding.Confidence.RELIABLE,
                    "严格 UTF-8 校验"));
        }

        Set<String> candidateNames = new LinkedHashSet<String>();
        String detectedName = detectStatistically(bytes);
        if (detectedName != null) {
            candidateNames.add(normalizeDetectedName(detectedName));
        }
        candidateNames.add("GB18030");
        candidateNames.add("GBK");
        candidateNames.add("GB2312");
        candidateNames.add("Big5");

        List<FileEncoding> candidates = new ArrayList<FileEncoding>();
        for (String name : candidateNames) {
            if (!Charset.isSupported(name)) {
                continue;
            }
            Charset charset = Charset.forName(name);
            if (canDecode(bytes, charset, 0) && isPlausibleText(bytes, charset)) {
                candidates.add(new FileEncoding(charset, new byte[0],
                        FileEncoding.Confidence.HEURISTIC,
                        name.equalsIgnoreCase(detectedName)
                                ? "统计检测" : "严格解码候选", false));
            }
        }
        if (candidates.isEmpty()) {
            FileEncoding fallback = FileEncoding.utf8(false,
                    FileEncoding.Confidence.UNCONFIRMED, "未识别");
            candidates.add(fallback);
            return new EncodingDetection(fallback, candidates, true, true,
                    "未找到可可靠解码的文本编码，文件可能是二进制文件");
        }
        FileEncoding suggested = candidates.get(0);
        boolean likelyBinary = controlByteRatio(bytes) > 0.18;
        return new EncodingDetection(suggested, candidates, true, likelyBinary,
                "检测建议：" + suggested.getDisplayName() + "（推测）");
    }

    private EncodingDetection confirmed(FileEncoding encoding) {
        List<FileEncoding> values = new ArrayList<FileEncoding>();
        values.add(encoding);
        return new EncodingDetection(encoding, values, false, false,
                encoding.getDetectionSource());
    }

    private FileEncoding detectUtf16WithoutBom(byte[] bytes) {
        if (bytes.length < 4 || bytes.length % 2 != 0) {
            return null;
        }
        int pairs = bytes.length / 2;
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            if (bytes[i] == 0) {
                evenZeros++;
            }
            if (bytes[i + 1] == 0) {
                oddZeros++;
            }
        }
        int strong = Math.max(2, pairs / 4);
        int weak = Math.max(1, pairs / 16);
        if (oddZeros >= strong && evenZeros <= weak
                && canDecode(bytes, StandardCharsets.UTF_16LE, 0)
                && isPlausibleText(bytes, StandardCharsets.UTF_16LE)) {
            return FileEncoding.utf16Le(false, FileEncoding.Confidence.RELIABLE,
                    "UTF-16 LE 字节分布");
        }
        if (evenZeros >= strong && oddZeros <= weak
                && canDecode(bytes, StandardCharsets.UTF_16BE, 0)
                && isPlausibleText(bytes, StandardCharsets.UTF_16BE)) {
            return FileEncoding.utf16Be(false, FileEncoding.Confidence.RELIABLE,
                    "UTF-16 BE 字节分布");
        }
        return null;
    }

    private String detectStatistically(byte[] bytes) {
        UniversalDetector detector = new UniversalDetector();
        detector.handleData(bytes);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    private String normalizeDetectedName(String name) {
        if (name == null) {
            return null;
        }
        if ("GB18030".equalsIgnoreCase(name)) {
            return "GB18030";
        }
        if ("BIG5".equalsIgnoreCase(name)) {
            return "Big5";
        }
        return name;
    }

    private boolean isPlausibleText(byte[] bytes, Charset charset) {
        try {
            String text = decode(bytes, charset, 0);
            if (text.isEmpty()) {
                return true;
            }
            int controls = 0;
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                if (Character.isISOControl(value) && value != '\r' && value != '\n'
                        && value != '\t' && value != '\f') {
                    controls++;
                }
            }
            return controls * 20 <= text.length();
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    static String decode(byte[] bytes, Charset charset, int skip)
            throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer value = decoder.decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip));
        return value.toString();
    }

    private boolean canDecode(byte[] bytes, Charset charset, int skip) {
        try {
            decode(bytes, charset, skip);
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private boolean isAscii(byte[] bytes) {
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            if (unsigned == 0 || unsigned >= 0x80
                    || (unsigned < 0x20 && unsigned != '\r' && unsigned != '\n'
                    && unsigned != '\t' && unsigned != '\f')) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private double controlByteRatio(byte[] bytes) {
        int controls = 0;
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            if ((value < 0x09 || (value > 0x0D && value < 0x20)) && value != 0) {
                controls++;
            }
        }
        return bytes.length == 0 ? 0 : (double) controls / bytes.length;
    }
}
