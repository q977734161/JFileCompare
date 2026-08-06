import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;

public class TextFileCodecTest {
    public static void main(String[] args) throws Exception {
        TextFileCodec codec = new TextFileCodec();

        assertDetection(codec, withBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "中文\n".getBytes(StandardCharsets.UTF_8)), "UTF-8 BOM", false);
        assertDetection(codec, withBom(new byte[]{(byte) 0xFF, (byte) 0xFE},
                "name=中文\r\n".getBytes(StandardCharsets.UTF_16LE)), "UTF-16 LE BOM", false);
        assertDetection(codec, "name=中文\r\n".getBytes(StandardCharsets.UTF_16LE),
                "UTF-16 LE", false);
        assertDetection(codec, "name=value\r\n".getBytes(StandardCharsets.UTF_16LE),
                "UTF-16 LE", false);
        assertDetection(codec, "plain ascii\n".getBytes(StandardCharsets.US_ASCII),
                "UTF-8", false);
        EncodingDetection binary = codec.detect(new byte[]{1, 2, 0, 3, 4, 5, 0, 6});
        if (!binary.isConfirmationRequired()) {
            throw new AssertionError("Control-byte data must not open as reliable UTF-8");
        }

        byte[] gb18030 = "名称=文件对比工具\r\n状态=完成".getBytes(Charset.forName("GB18030"));
        EncodingDetection legacy = codec.detect(gb18030);
        if (!legacy.isConfirmationRequired()) {
            throw new AssertionError("Legacy encoding must require preview confirmation");
        }
        TextFileSnapshot decoded = codec.decode(Paths.get("legacy.txt"), true, gb18030,
                legacy.getSuggested().confirmedByUser());
        if (!decoded.getDocument().toText().contains("文件对比工具")) {
            throw new AssertionError("GB18030 text was not decoded correctly using suggestion");
        }

        roundTrip(codec, "中文\r\n第二行\n末行", FileEncoding.manual(
                Charset.forName("GB18030"), false));
        roundTrip(codec, "中文\r\n第二行\n末行", FileEncoding.manual(
                StandardCharsets.UTF_8, true));
        roundTrip(codec, "中文\r\n第二行\n末行", FileEncoding.manual(
                StandardCharsets.UTF_16BE, true));

        try {
            codec.encode(LineDocument.parse("emoji: \uD83D\uDE00"),
                    FileEncoding.manual(Charset.forName("GBK"), false));
            throw new AssertionError("GBK encoding must reject unmappable emoji");
        } catch (java.nio.charset.CharacterCodingException expected) {
            // Expected: save must fail instead of replacing the character with '?'.
        }
        System.out.println("TextFileCodecTest passed");
    }

    private static void assertDetection(TextFileCodec codec, byte[] bytes,
                                        String expectedName, boolean confirmation) {
        EncodingDetection detection = codec.detect(bytes);
        if (!expectedName.equals(detection.getSuggested().getDisplayName())
                || detection.isConfirmationRequired() != confirmation) {
            throw new AssertionError("Expected " + expectedName + "/" + confirmation
                    + " but got " + detection.getSuggested().getDisplayName() + "/"
                    + detection.isConfirmationRequired());
        }
    }

    private static void roundTrip(TextFileCodec codec, String text,
                                  FileEncoding encoding) throws Exception {
        LineDocument original = LineDocument.parse(text);
        byte[] bytes = codec.encode(original, encoding);
        TextFileSnapshot decoded = codec.decode(Paths.get("round-trip.txt"), true,
                bytes, encoding);
        if (!text.equals(decoded.getDocument().toText())) {
            throw new AssertionError("Round trip failed for " + encoding.getDisplayName());
        }
        if (!Arrays.equals(bytes, codec.encode(decoded.getDocument(), encoding))) {
            throw new AssertionError("Byte round trip failed for " + encoding.getDisplayName());
        }
    }

    private static byte[] withBom(byte[] bom, byte[] content) {
        byte[] result = Arrays.copyOf(bom, bom.length + content.length);
        System.arraycopy(content, 0, result, bom.length, content.length);
        return result;
    }
}
