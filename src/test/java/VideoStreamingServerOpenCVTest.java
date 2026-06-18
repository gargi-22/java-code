import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Assumptions;

import org.opencv.core.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenCV-dependent methods in VideoStreamingServer.
 * These tests require the OpenCV native library to be loaded.
 * Tests cover: buildFeatherMask, featherStitch, encodeJpeg
 */
public class VideoStreamingServerOpenCVTest {

    private static boolean opencvLoaded = false;

    @BeforeAll
    static void loadOpenCV() {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            opencvLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("OpenCV native library not available, skipping OpenCV tests: " + e.getMessage());
        }
    }

    // =========================================================================
    // buildFeatherMask Tests
    // =========================================================================

    @Nested
    @DisplayName("buildFeatherMask Tests")
    class BuildFeatherMaskTests {

        @Test
        @DisplayName("Should create mask with correct dimensions")
        void shouldCreateMaskWithCorrectDimensions() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat mask = VideoStreamingServer.buildFeatherMask(360, 640, 80);

            assertEquals(360, mask.rows());
            assertEquals(640, mask.cols());
            assertEquals(CvType.CV_32FC1, mask.type());
            mask.release();
        }

        @Test
        @DisplayName("Center of mask should have weight 1.0")
        void centerShouldHaveWeight1() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat mask = VideoStreamingServer.buildFeatherMask(360, 640, 80);

            // Center pixel should be 1.0
            double[] val = mask.get(180, 320);
            assertEquals(1.0, val[0], 1e-5);
            mask.release();
        }

        @Test
        @DisplayName("Left edge should start at 0 and ramp up")
        void leftEdgeShouldRampUp() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat mask = VideoStreamingServer.buildFeatherMask(100, 200, 50);

            // x=0 → alpha = 0/50 = 0.0
            double[] val0 = mask.get(50, 0);
            assertEquals(0.0, val0[0], 1e-5);

            // x=25 → alpha = 25/50 = 0.5
            double[] val25 = mask.get(50, 25);
            assertEquals(0.5, val25[0], 1e-5);

            // x=49 → alpha = 49/50 = 0.98
            double[] val49 = mask.get(50, 49);
            assertEquals(0.98, val49[0], 1e-2);

            mask.release();
        }

        @Test
        @DisplayName("Right edge should ramp down to 0")
        void rightEdgeShouldRampDown() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat mask = VideoStreamingServer.buildFeatherMask(100, 200, 50);

            // x=199 (W-1-0) → alpha = 0/50 = 0.0
            double[] valEnd = mask.get(50, 199);
            assertEquals(0.0, valEnd[0], 1e-5);

            // x=175 (W-1-24) → alpha = 24/50 = 0.48
            double[] val175 = mask.get(50, 175);
            assertEquals(0.48, val175[0], 1e-2);

            mask.release();
        }

        @Test
        @DisplayName("Mask values should be symmetric")
        void maskShouldBeSymmetric() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            int W = 200;
            int overlap = 50;
            Mat mask = VideoStreamingServer.buildFeatherMask(100, W, overlap);

            for (int x = 0; x < overlap; x++) {
                double[] left = mask.get(50, x);
                double[] right = mask.get(50, W - 1 - x);
                assertEquals(left[0], right[0], 1e-5,
                        "Mask not symmetric at offset " + x);
            }

            mask.release();
        }

        @Test
        @DisplayName("Should handle overlap of 0 gracefully (all 1.0)")
        void shouldHandleZeroOverlap() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat mask = VideoStreamingServer.buildFeatherMask(100, 200, 0);

            // With overlap=0, the for loop doesn't execute, so all values are 1.0
            double[] valLeft = mask.get(50, 0);
            assertEquals(1.0, valLeft[0], 1e-5);
            double[] valRight = mask.get(50, 199);
            assertEquals(1.0, valRight[0], 1e-5);

            mask.release();
        }

        @Test
        @DisplayName("All mask values should be between 0 and 1")
        void allValuesShouldBeBetween0And1() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat mask = VideoStreamingServer.buildFeatherMask(50, 100, 20);

            for (int y = 0; y < mask.rows(); y++) {
                for (int x = 0; x < mask.cols(); x++) {
                    double val = mask.get(y, x)[0];
                    assertTrue(val >= 0.0 && val <= 1.0,
                            "Value at (" + y + "," + x + ") = " + val + " is out of [0,1]");
                }
            }

            mask.release();
        }
    }

    // =========================================================================
    // featherStitch Tests
    // =========================================================================

    @Nested
    @DisplayName("featherStitch Tests")
    class FeatherStitchTests {

        @Test
        @DisplayName("Should produce panorama with correct dimensions for 4 frames")
        void shouldProducePanoramaWithCorrectDimensions() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            // Create 4 test frames of 640x360
            Mat[] frames = new Mat[4];
            for (int i = 0; i < 4; i++) {
                frames[i] = new Mat(360, 640, CvType.CV_8UC3, new Scalar(100 + i * 30, 50, 50));
            }

            Mat result = VideoStreamingServer.featherStitch(frames);

            // Expected: W + (N-1)*(W-overlap) = 640 + 3*(640-80) = 640 + 1680 = 2320
            assertEquals(2320, result.cols());
            assertEquals(360, result.rows());
            assertEquals(CvType.CV_8UC3, result.type());

            result.release();
            for (Mat f : frames) f.release();
        }

        @Test
        @DisplayName("Should handle single frame")
        void shouldHandleSingleFrame() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat[] frames = new Mat[]{
                new Mat(360, 640, CvType.CV_8UC3, new Scalar(128, 128, 128))
            };

            Mat result = VideoStreamingServer.featherStitch(frames);

            // Single frame: panoW = 640 + 0*(640-80) = 640
            assertEquals(640, result.cols());
            assertEquals(360, result.rows());

            result.release();
            frames[0].release();
        }

        @Test
        @DisplayName("Should produce valid pixel values (0-255 range)")
        void shouldProduceValidPixelValues() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat[] frames = new Mat[4];
            for (int i = 0; i < 4; i++) {
                frames[i] = new Mat(360, 640, CvType.CV_8UC3, new Scalar(200, 100, 50));
            }

            Mat result = VideoStreamingServer.featherStitch(frames);

            // Check some pixels are within valid range
            for (int y = 0; y < result.rows(); y += 50) {
                for (int x = 0; x < result.cols(); x += 50) {
                    double[] pixel = result.get(y, x);
                    for (double ch : pixel) {
                        assertTrue(ch >= 0 && ch <= 255,
                                "Pixel value out of range at (" + y + "," + x + "): " + ch);
                    }
                }
            }

            result.release();
            for (Mat f : frames) f.release();
        }

        @Test
        @DisplayName("Non-overlapping regions should preserve original colors")
        void nonOverlappingRegionsShouldPreserveColors() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            // Frame 0 is red, frame 1 is green (non-overlapping center should be pure)
            Mat[] frames = new Mat[2];
            frames[0] = new Mat(360, 640, CvType.CV_8UC3, new Scalar(0, 0, 255)); // Red in BGR
            frames[1] = new Mat(360, 640, CvType.CV_8UC3, new Scalar(0, 255, 0)); // Green in BGR

            Mat result = VideoStreamingServer.featherStitch(frames);

            // The center of frame 0 (around x=320) should be predominantly red
            // but we need to account for the feather mask which ramps from edges
            // At x=320 (center of first frame), the feather mask should be 1.0
            // and only frame[0] contributes there
            double[] pixel = result.get(180, 320);
            // Should be close to red (BGR: 0, 0, 255)
            assertTrue(pixel[2] > 200, "Red channel should be high in center of first frame");
            assertTrue(pixel[1] < 50, "Green channel should be low in center of first frame");

            result.release();
            for (Mat f : frames) f.release();
        }
    }

    // =========================================================================
    // encodeJpeg Tests
    // =========================================================================

    @Nested
    @DisplayName("encodeJpeg Tests")
    class EncodeJpegTests {

        @Test
        @DisplayName("Should produce valid JPEG bytes (starts with FFD8)")
        void shouldProduceValidJpegBytes() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat frame = new Mat(100, 100, CvType.CV_8UC3, new Scalar(128, 128, 128));
            byte[] jpeg = VideoStreamingServer.encodeJpeg(frame);

            assertNotNull(jpeg);
            assertTrue(jpeg.length > 0);
            // JPEG files start with FFD8
            assertEquals((byte) 0xFF, jpeg[0]);
            assertEquals((byte) 0xD8, jpeg[1]);

            frame.release();
        }

        @Test
        @DisplayName("Should produce non-empty output for colored image")
        void shouldProduceNonEmptyOutputForColoredImage() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat frame = new Mat(200, 300, CvType.CV_8UC3, new Scalar(50, 100, 200));
            byte[] jpeg = VideoStreamingServer.encodeJpeg(frame);

            assertTrue(jpeg.length > 100, "JPEG output should be at least 100 bytes");

            frame.release();
        }

        @Test
        @DisplayName("Larger frames should produce larger JPEG output")
        void largerFramesShouldProduceLargerOutput() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat small = new Mat(50, 50, CvType.CV_8UC3, new Scalar(100, 100, 100));
            Mat large = new Mat(500, 500, CvType.CV_8UC3, new Scalar(100, 100, 100));

            byte[] smallJpeg = VideoStreamingServer.encodeJpeg(small);
            byte[] largeJpeg = VideoStreamingServer.encodeJpeg(large);

            assertTrue(largeJpeg.length > smallJpeg.length,
                    "Larger frame should produce larger JPEG");

            small.release();
            large.release();
        }

        @Test
        @DisplayName("Should end with JPEG EOI marker (FFD9)")
        void shouldEndWithEoiMarker() {
            Assumptions.assumeTrue(opencvLoaded, "OpenCV not loaded");

            Mat frame = new Mat(100, 100, CvType.CV_8UC3, new Scalar(64, 128, 192));
            byte[] jpeg = VideoStreamingServer.encodeJpeg(frame);

            // JPEG files end with FFD9
            assertEquals((byte) 0xFF, jpeg[jpeg.length - 2]);
            assertEquals((byte) 0xD9, jpeg[jpeg.length - 1]);

            frame.release();
        }
    }
}
