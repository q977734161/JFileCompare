import java.io.IOException;

final class TextFileOpenCancelledException extends IOException {
    TextFileOpenCancelledException() {
        super("已取消打开文件");
    }
}
