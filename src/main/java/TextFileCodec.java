import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;

final class TextFileCodec {
    private final TextEncodingDetector detector = new TextEncodingDetector();

    EncodingDetection detect(byte[] bytes) {
        return detector.detect(bytes);
    }

    TextFileSnapshot read(Path path, FileEncoding selectedEncoding) throws IOException {
        boolean exists = Files.exists(path);
        if (!exists) {
            FileEncoding encoding = selectedEncoding == null
                    ? FileEncoding.utf8(false, FileEncoding.Confidence.RELIABLE,
                    "新文件默认 UTF-8") : selectedEncoding;
            return new TextFileSnapshot(path, false, new byte[0], encoding,
                    LineDocument.empty("\n"));
        }
        byte[] bytes = Files.readAllBytes(path);
        FileEncoding encoding = selectedEncoding;
        if (encoding == null) {
            EncodingDetection detection = detect(bytes);
            if (detection.isConfirmationRequired()) {
                throw new IOException("文件编码需要用户确认");
            }
            encoding = detection.getSuggested();
        }
        return decode(path, true, bytes, encoding);
    }

    TextFileSnapshot decode(Path path, boolean existed, byte[] bytes,
                            FileEncoding encoding) throws IOException {
        try {
            String text = TextEncodingDetector.decode(bytes, encoding.getCharset(),
                    matchingBomLength(bytes, encoding));
            return new TextFileSnapshot(path, existed, bytes, encoding, LineDocument.parse(text));
        } catch (CharacterCodingException ex) {
            throw new IOException("无法使用 " + encoding.getDisplayName() + " 解码文件", ex);
        }
    }

    String preview(byte[] bytes, FileEncoding encoding, int maxCharacters) throws IOException {
        try {
            String text = TextEncodingDetector.decode(bytes, encoding.getCharset(),
                    matchingBomLength(bytes, encoding));
            return text.length() <= maxCharacters ? text : text.substring(0, maxCharacters);
        } catch (CharacterCodingException ex) {
            throw new IOException("该编码无法完整解码文件", ex);
        }
    }

    byte[] encode(LineDocument document, FileEncoding encoding)
            throws CharacterCodingException, IOException {
        CharsetEncoder encoder = encoding.getCharset().newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPORT);
        encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(document.toText()));
        byte[] bom = encoding.getBom();
        ByteArrayOutputStream output = new ByteArrayOutputStream(bom.length + encoded.remaining());
        output.write(bom);
        byte[] content = new byte[encoded.remaining()];
        encoded.get(content);
        output.write(content);
        return output.toByteArray();
    }

    byte[] write(Path path, LineDocument document, FileEncoding encoding) throws IOException {
        byte[] bytes;
        try {
            bytes = encode(document, encoding);
        } catch (CharacterCodingException ex) {
            throw new IOException("当前内容包含 " + encoding.getDisplayName()
                    + " 无法表示的字符", ex);
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        return bytes;
    }

    private int matchingBomLength(byte[] bytes, FileEncoding encoding) {
        byte[] bom = encoding.getBom();
        if (bom.length == 0 || bytes.length < bom.length) {
            return 0;
        }
        for (int i = 0; i < bom.length; i++) {
            if (bytes[i] != bom[i]) {
                return 0;
            }
        }
        return bom.length;
    }
}
