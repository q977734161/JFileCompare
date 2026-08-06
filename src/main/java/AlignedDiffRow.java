final class AlignedDiffRow {
    enum Status {
        SAME,
        CHANGE,
        LEFT_ONLY,
        RIGHT_ONLY
    }

    private final int hunkId;
    private final Status status;
    private final int leftLineIndex;
    private final int rightLineIndex;
    private final String leftText;
    private final String rightText;

    AlignedDiffRow(int hunkId, Status status, int leftLineIndex, int rightLineIndex,
                   String leftText, String rightText) {
        this.hunkId = hunkId;
        this.status = status;
        this.leftLineIndex = leftLineIndex;
        this.rightLineIndex = rightLineIndex;
        this.leftText = leftText;
        this.rightText = rightText;
    }

    int getHunkId() {
        return hunkId;
    }

    Status getStatus() {
        return status;
    }

    int getLeftLineIndex() {
        return leftLineIndex;
    }

    int getRightLineIndex() {
        return rightLineIndex;
    }

    String getLeftText() {
        return leftText;
    }

    String getRightText() {
        return rightText;
    }
}
