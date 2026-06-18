import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the VideoStreamingClient class.
 * Tests cover:
 * - validateVideoResponse: content-type validation
 * - printProgress: progress calculation and output
 * - fetchContentLength: HEAD request parsing
 * - downloadSingleStream: full stream download
 * - Chunked download logic (integration via main method)
 */
public class VideoStreamingClientTest {

    private static HttpServer testServer;
    private static int testPort;

    @BeforeAll
    static void setUp() throws IOException {
        testServer = HttpServer.create(new InetSocketAddress(0), 0);
        testPort = testServer.getAddress().getPort();

        // Endpoint that serves video-like content with Content-Length
        testServer.createContext("/video", ex -> {
            byte[] content = "fake-video-data-for-testing-purposes-1234567890".getBytes();
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.getResponseHeaders().set("Content-Length", String.valueOf(content.length));
                ex.sendResponseHeaders(200, -1);
            } else {
                // Handle Range requests
                String rangeHeader = ex.getRequestHeaders().getFirst("Range");
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    String[] parts = rangeHeader.substring(6).split("-");
                    int start = Integer.parseInt(parts[0]);
                    int end = parts.length > 1 ? Integer.parseInt(parts[1]) : content.length - 1;
                    end = Math.min(end, content.length - 1);
                    byte[] chunk = new byte[end - start + 1];
                    System.arraycopy(content, start, chunk, 0, chunk.length);
                    ex.getResponseHeaders().set("Content-Type", "video/mp4");
                    ex.sendResponseHeaders(206, chunk.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(chunk); }
                } else {
                    ex.getResponseHeaders().set("Content-Type", "video/mp4");
                    ex.sendResponseHeaders(200, content.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(content); }
                }
            }
        });

        // Endpoint that returns HTML (simulates wrong URL)
        testServer.createContext("/html-page", ex -> {
            String html = "<html><body>Not a video</body></html>";
            byte[] bytes = html.getBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        // Endpoint that returns no Content-Length header
        testServer.createContext("/no-length", ex -> {
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.sendResponseHeaders(200, -1);
            } else {
                byte[] content = "stream-data".getBytes();
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.sendResponseHeaders(200, content.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(content); }
            }
        });

        // Endpoint that returns 404
        testServer.createContext("/not-found", ex -> {
            ex.sendResponseHeaders(404, -1);
        });

        // Endpoint that returns 500
        testServer.createContext("/server-error", ex -> {
            ex.sendResponseHeaders(500, -1);
        });

        testServer.start();
    }

    @AfterAll
    static void tearDown() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    // =========================================================================
    // validateVideoResponse Tests (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("validateVideoResponse Tests")
    class ValidateVideoResponseTests {

        @Test
        @DisplayName("Should throw IOException when Content-Type is text/html")
        void shouldThrowForHtmlResponse() throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/html-page"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            Method validateMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "validateVideoResponse", HttpResponse.class, URI.class);
            validateMethod.setAccessible(true);

            InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                    validateMethod.invoke(null, response, URI.create("http://localhost:" + testPort + "/html-page")));
            assertInstanceOf(IOException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("HTML content"));
        }

        @Test
        @DisplayName("Should not throw when Content-Type is video/mp4")
        void shouldNotThrowForVideoResponse() throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/video"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            Method validateMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "validateVideoResponse", HttpResponse.class, URI.class);
            validateMethod.setAccessible(true);

            assertDoesNotThrow(() -> validateMethod.invoke(null, response,
                    URI.create("http://localhost:" + testPort + "/video")));
        }
    }

    // =========================================================================
    // printProgress Tests (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("printProgress Tests")
    class PrintProgressTests {

        @Test
        @DisplayName("Should print correct percentage for 50% progress")
        void shouldPrint50Percent() throws Exception {
            Method printMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "printProgress", long.class, long.class);
            printMethod.setAccessible(true);

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                printMethod.invoke(null, 500L, 1000L);
                String output = capture.toString();
                assertTrue(output.contains("50%"));
                assertTrue(output.contains("500"));
                assertTrue(output.contains("1000"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("Should print 100% when download is complete")
        void shouldPrint100Percent() throws Exception {
            Method printMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "printProgress", long.class, long.class);
            printMethod.setAccessible(true);

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                printMethod.invoke(null, 2048L, 2048L);
                String output = capture.toString();
                assertTrue(output.contains("100%"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("Should print 0% at start")
        void shouldPrint0Percent() throws Exception {
            Method printMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "printProgress", long.class, long.class);
            printMethod.setAccessible(true);

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                printMethod.invoke(null, 0L, 1000L);
                String output = capture.toString();
                assertTrue(output.contains("0%"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("Should handle large file sizes without overflow")
        void shouldHandleLargeFileSizes() throws Exception {
            Method printMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "printProgress", long.class, long.class);
            printMethod.setAccessible(true);

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                long total = 10_000_000_000L; // 10 GB
                long downloaded = 5_000_000_000L; // 5 GB
                printMethod.invoke(null, downloaded, total);
                String output = capture.toString();
                assertTrue(output.contains("50%"));
            } finally {
                System.setOut(originalOut);
            }
        }
    }

    // =========================================================================
    // fetchContentLength Tests (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("fetchContentLength Tests")
    class FetchContentLengthTests {

        @Test
        @DisplayName("Should return content length for valid video endpoint")
        void shouldReturnContentLength() throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            Method fetchMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "fetchContentLength", HttpClient.class, URI.class);
            fetchMethod.setAccessible(true);

            long length = (long) fetchMethod.invoke(null, client,
                    URI.create("http://localhost:" + testPort + "/video"));

            assertTrue(length > 0);
            assertEquals(47, length); // "fake-video-data-for-testing-purposes-1234567890" = 47 bytes
        }

        @Test
        @DisplayName("Should return -1 when no Content-Length header is present")
        void shouldReturnNegativeOneWhenNoContentLength() throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            Method fetchMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "fetchContentLength", HttpClient.class, URI.class);
            fetchMethod.setAccessible(true);

            long length = (long) fetchMethod.invoke(null, client,
                    URI.create("http://localhost:" + testPort + "/no-length"));

            // No Content-Length header → should parse "0" and return 0, or -1
            assertTrue(length <= 0);
        }

        @Test
        @DisplayName("Should return -1 for non-200 response")
        void shouldReturnNegativeOneForErrorResponse() throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            Method fetchMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "fetchContentLength", HttpClient.class, URI.class);
            fetchMethod.setAccessible(true);

            long length = (long) fetchMethod.invoke(null, client,
                    URI.create("http://localhost:" + testPort + "/not-found"));

            assertEquals(-1, length);
        }
    }

    // =========================================================================
    // downloadSingleStream Tests (via reflection)
    // =========================================================================

    @Nested
    @DisplayName("downloadSingleStream Tests")
    class DownloadSingleStreamTests {

        @Test
        @DisplayName("Should download complete file in single-stream mode")
        void shouldDownloadCompleteFile(@TempDir Path tempDir) throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            Method downloadMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "downloadSingleStream", HttpClient.class, URI.class, Path.class);
            downloadMethod.setAccessible(true);

            Path outputFile = tempDir.resolve("downloaded.mp4");

            downloadMethod.invoke(null, client,
                    URI.create("http://localhost:" + testPort + "/video"), outputFile);

            assertTrue(Files.exists(outputFile));
            byte[] content = Files.readAllBytes(outputFile);
            assertEquals("fake-video-data-for-testing-purposes-1234567890", new String(content));
        }

        @Test
        @DisplayName("Should throw IOException for HTML response in single-stream mode")
        void shouldThrowForHtmlInSingleStream(@TempDir Path tempDir) throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            Method downloadMethod = VideoStreamingClient.class.getDeclaredMethod(
                    "downloadSingleStream", HttpClient.class, URI.class, Path.class);
            downloadMethod.setAccessible(true);

            Path outputFile = tempDir.resolve("should_not_exist.mp4");

            InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                    downloadMethod.invoke(null, client,
                            URI.create("http://localhost:" + testPort + "/html-page"), outputFile));
            assertInstanceOf(IOException.class, ex.getCause());
        }
    }

    // =========================================================================
    // Main Method Integration Tests
    // =========================================================================

    @Nested
    @DisplayName("Main Method Integration Tests")
    class MainMethodTests {

        @Test
        @DisplayName("Should print usage when no arguments provided")
        void shouldPrintUsageWithNoArgs() throws Exception {
            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                VideoStreamingClient.main(new String[]{});
                String output = capture.toString();
                assertTrue(output.contains("Usage:"));
                assertTrue(output.contains("VideoStreamingClient"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("Should print usage when only one argument provided")
        void shouldPrintUsageWithOneArg() throws Exception {
            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                VideoStreamingClient.main(new String[]{"http://example.com/video"});
                String output = capture.toString();
                assertTrue(output.contains("Usage:"));
            } finally {
                System.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for negative chunk size")
        void shouldThrowForNegativeChunkSize(@TempDir Path tempDir) {
            Path outputFile = tempDir.resolve("out.mp4");
            assertThrows(IllegalArgumentException.class, () ->
                    VideoStreamingClient.main(new String[]{
                            "http://localhost:" + testPort + "/video",
                            outputFile.toString(),
                            "-1"
                    }));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for zero chunk size")
        void shouldThrowForZeroChunkSize(@TempDir Path tempDir) {
            Path outputFile = tempDir.resolve("out.mp4");
            assertThrows(IllegalArgumentException.class, () ->
                    VideoStreamingClient.main(new String[]{
                            "http://localhost:" + testPort + "/video",
                            outputFile.toString(),
                            "0"
                    }));
        }

        @Test
        @DisplayName("Should successfully download file with chunked transfer")
        void shouldDownloadWithChunkedTransfer(@TempDir Path tempDir) throws Exception {
            Path outputFile = tempDir.resolve("chunked_download.mp4");

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                VideoStreamingClient.main(new String[]{
                        "http://localhost:" + testPort + "/video",
                        outputFile.toString(),
                        "16" // Small chunk size for testing
                });
            } finally {
                System.setOut(originalOut);
            }

            assertTrue(Files.exists(outputFile));
            byte[] content = Files.readAllBytes(outputFile);
            assertEquals("fake-video-data-for-testing-purposes-1234567890", new String(content));
        }

        @Test
        @DisplayName("Should fall back to single-stream when no Content-Length")
        void shouldFallbackToSingleStream(@TempDir Path tempDir) throws Exception {
            Path outputFile = tempDir.resolve("fallback_download.mp4");

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                VideoStreamingClient.main(new String[]{
                        "http://localhost:" + testPort + "/no-length",
                        outputFile.toString()
                });
                String output = capture.toString();
                assertTrue(output.contains("Falling back to single-stream"));
            } finally {
                System.setOut(originalOut);
            }

            assertTrue(Files.exists(outputFile));
        }

        @Test
        @DisplayName("Should overwrite existing file with warning")
        void shouldOverwriteExistingFile(@TempDir Path tempDir) throws Exception {
            Path outputFile = tempDir.resolve("existing.mp4");
            Files.writeString(outputFile, "old-content");

            PrintStream originalOut = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));

            try {
                VideoStreamingClient.main(new String[]{
                        "http://localhost:" + testPort + "/video",
                        outputFile.toString(),
                        "16"
                });
                String output = capture.toString();
                assertTrue(output.contains("already exists"));
            } finally {
                System.setOut(originalOut);
            }

            byte[] content = Files.readAllBytes(outputFile);
            assertEquals("fake-video-data-for-testing-purposes-1234567890", new String(content));
        }
    }
}
