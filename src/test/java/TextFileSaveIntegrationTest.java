import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class TextFileSaveIntegrationTest {
    public static void main(String[] args) throws Exception {
        TextFileCodec codec = new TextFileCodec();
        Path temp = Files.createTempDirectory("file-encoding-save-test");
        Path left = temp.resolve("left.properties");
        Path right = temp.resolve("right.properties");

        FileEncoding gb18030 = FileEncoding.manual(Charset.forName("GB18030"), false);
        FileEncoding utf8Bom = FileEncoding.manual(StandardCharsets.UTF_8, true);
        LineDocument leftDocument = LineDocument.parse("名称=左侧\r\n状态=完成\n末行");
        LineDocument rightDocument = LineDocument.parse("名称=右侧\n状态=完成\n");

        byte[] leftBytes = codec.write(left, leftDocument, gb18030);
        byte[] rightBytes = codec.write(right, rightDocument, utf8Bom);
        if (leftBytes.length == 0 || (leftBytes[0] & 0xFF) == 0xEF) {
            throw new AssertionError("GB18030 file unexpectedly contains a UTF-8 BOM");
        }
        if (rightBytes.length < 3 || (rightBytes[0] & 0xFF) != 0xEF
                || (rightBytes[1] & 0xFF) != 0xBB || (rightBytes[2] & 0xFF) != 0xBF) {
            throw new AssertionError("UTF-8 BOM was not written");
        }

        TextFileSnapshot savedLeft = codec.decode(left, true, Files.readAllBytes(left), gb18030);
        TextFileSnapshot savedRight = codec.decode(right, true, Files.readAllBytes(right), utf8Bom);
        if (!leftDocument.toText().equals(savedLeft.getDocument().toText())) {
            throw new AssertionError("GB18030 text or mixed line endings changed after save");
        }
        if (!rightDocument.toText().equals(savedRight.getDocument().toText())) {
            throw new AssertionError("UTF-8 BOM text changed after save");
        }
        System.out.println("TextFileSaveIntegrationTest passed");
    }
}
