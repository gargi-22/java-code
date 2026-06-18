/**
 * Single source of truth for panorama layout constants and dimension
 * calculations shared between {@link VideoStreamingServer}'s
 * {@code MetaHandler} and {@code featherStitch}.
 */
final class PanoramaConfig {

    private PanoramaConfig() { }

    static final int TARGET_HEIGHT = 360;
    static final int TARGET_WIDTH  = 640;
    static final int OVERLAP_PX    = 80;

    static int effectiveOverlap() {
        return Math.min(OVERLAP_PX, TARGET_WIDTH / 4);
    }

    static int panoramaWidth(int numFrames) {
        int overlap = effectiveOverlap();
        return TARGET_WIDTH + (numFrames - 1) * (TARGET_WIDTH - overlap);
    }
}
