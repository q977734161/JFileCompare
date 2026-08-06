import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class EncodingDetection {
    private final FileEncoding suggested;
    private final List<FileEncoding> candidates;
    private final boolean confirmationRequired;
    private final boolean likelyBinary;
    private final String message;

    EncodingDetection(FileEncoding suggested, List<FileEncoding> candidates,
                      boolean confirmationRequired, boolean likelyBinary, String message) {
        this.suggested = suggested;
        this.candidates = Collections.unmodifiableList(
                new ArrayList<FileEncoding>(candidates));
        this.confirmationRequired = confirmationRequired;
        this.likelyBinary = likelyBinary;
        this.message = message;
    }

    FileEncoding getSuggested() {
        return suggested;
    }

    List<FileEncoding> getCandidates() {
        return candidates;
    }

    boolean isConfirmationRequired() {
        return confirmationRequired;
    }

    boolean isLikelyBinary() {
        return likelyBinary;
    }

    String getMessage() {
        return message;
    }
}
