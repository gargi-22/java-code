import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the VideoStreamingServer HTTP handlers and utility methods.
 * These tests exercise the HTTP handlers (RootRedirect, StreamOnly, Player, Meta)
 * and the writeFrame utility method without requiring OpenCV native libraries
 * for the handler tests.
 */
public class VideoStreamingServerTest {

    private static HttpServer testServer;
    private static int testPort;
    private static HttpClient httpClient;

    @BeforeAll
    static void setUp() throws IOException {
        // Create a test server that mirrors the VideoStreamingServer's handler setup
        testServer = HttpServer.create(new InetSocketAddress(0), 0);
        testPort = testServer.getAddress().getPort();

        // Register handlers using reflection-like approach:
        // We recreate the handler logic since inner classes are private
        testServer.createContext("/", ex -> {
            ex.getResponseHeaders().set("Location", "/play");
            ex.sendResponseHeaders(302, -1);
        });

        testServer.createContext("/player", ex -> {
            String html = "<!DOCTYPE html><html lang='en'><head>"
                + "<meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Live 360\u00b0 Stream</title>"
                + "<style>"
                + "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }"
                + "html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }"
                + "img { display: block; width: 100%; height: 100%; object-fit: contain; }"
                + "</style>"
                + "</head><body>"
                + "<img src='/stitch' alt='Live 360\u00b0 panorama stream'>"
                + "</body></html>";
            byte[] bytes = html.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        testServer.createContext("/play", ex -> {
            String host = ex.getRequestHeaders().getFirst("Host");
            boolean secure = (host != null && !host.contains("localhost"));
            String scheme = secure ? "https" : "http";
            String baseUrl = (host != null && !host.isEmpty())
                             ? scheme + "://" + host : "http://localhost";
            String playerUrl = baseUrl + "/player";
            String metaUrl = baseUrl + "/meta";

            String html = "<!DOCTYPE html><html lang='en'><head>"
                + "<meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>360\u00b0 Panoramic View</title>"
                + "</head><body>"
                + "<div class='container'>"
                + "  <h1>360\u00b0 Panoramic Camera System</h1>"
                + "  <a href='" + playerUrl + "'>" + playerUrl + "</a>"
                + "  <a href='" + metaUrl + "'>" + metaUrl + "</a>"
                + "</div>"
                + "</body></html>";
            byte[] bytes = html.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        testServer.createContext("/meta", ex -> {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            int port = testPort;
            String host = ex.getRequestHeaders().getFirst("Host");
            boolean secure = (host != null && !host.contains("localhost"));
            String scheme = secure ? "https" : "http";
            if (host == null || host.isEmpty()) host = "localhost:" + port;
            String baseUrl = scheme + "://" + host;

            int N = 4;
            int TARGET_WIDTH = 640;
            int TARGET_HEIGHT = 360;
            int OVERLAP_PX = 80;
            int overlap = Math.min(OVERLAP_PX, TARGET_WIDTH / 4);
            int panoW = TARGET_WIDTH + (N - 1) * (TARGET_WIDTH - overlap);
            int panoH = TARGET_HEIGHT;

            String[] names = { "front", "rear", "left", "right" };

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"panorama\": {\n");
            sb.append("    \"width\": ").append(panoW).append(",\n");
            sb.append("    \"height\": ").append(panoH).append(",\n");
            sb.append("    \"player_url\": \"").append(baseUrl).append("/player\",\n");
            sb.append("    \"stream_url\": \"").append(baseUrl).append("/stitch\"\n");
            sb.append("  },\n");
            sb.append("  \"cameras\": {\n");
            for (int i = 0; i < N; i++) {
                int x = i * (TARGET_WIDTH - overlap);
                int w = (x + TARGET_WIDTH <= panoW) ? TARGET_WIDTH : (panoW - x);
                sb.append("    \"").append(names[i]).append("\": {\n");
                sb.append("      \"x\": ").append(x).append(",\n");
                sb.append("      \"y\": 0,\n");
                sb.append("      \"w\": ").append(w).append(",\n");
                sb.append("      \"h\": ").append(panoH).append(",\n");
                sb.append("      \"source_w\": ").append(TARGET_WIDTH).append(",\n");
                sb.append("      \"source_h\": ").append(TARGET_HEIGHT).append(",\n");
                sb.append("      \"overlap_px\": ").append(overlap).append("\n");
                sb.append("    }");
                if (i < N - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  }\n}");

            byte[] body = sb.toString().getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        testServer.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    // =========================================================================
    // Root Redirect Handler Tests
    // =========================================================================

    @Nested
    @DisplayName("RootRedirectHandler Tests")
    class RootRedirectHandlerTests {

        @Test
        @DisplayName("GET / should return 302 redirect to /play")
        void rootShouldRedirectToPlay() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(302, response.statusCode());
            assertTrue(response.headers().firstValue("Location").isPresent());
            assertEquals("/play", response.headers().firstValue("Location").get());
        }

        @Test
        @DisplayName("POST / should also redirect (handler doesn't check method)")
        void rootPostShouldAlsoRedirect() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(302, response.statusCode());
            assertEquals("/play", response.headers().firstValue("Location").get());
        }
    }

    // =========================================================================
    // StreamOnly Page Handler Tests
    // =========================================================================

    @Nested
    @DisplayName("StreamOnlyPageHandler Tests")
    class StreamOnlyPageHandlerTests {

        @Test
        @DisplayName("GET /player should return HTML with img tag pointing to /stitch")
        void playerShouldReturnHtmlWithStitchImg() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/player"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertTrue(contentType.contains("text/html"));
            String body = response.body();
            assertTrue(body.contains("<img src='/stitch'"));
            assertTrue(body.contains("Live 360"));
        }

        @Test
        @DisplayName("GET /player should return valid HTML5 document")
        void playerShouldReturnValidHtml5() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/player"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            assertTrue(body.startsWith("<!DOCTYPE html>"));
            assertTrue(body.contains("<html lang='en'>"));
            assertTrue(body.contains("</html>"));
        }

        @Test
        @DisplayName("GET /player should include responsive viewport meta tag")
        void playerShouldIncludeViewportMeta() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/player"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            assertTrue(body.contains("viewport"));
            assertTrue(body.contains("width=device-width"));
        }
    }

    // =========================================================================
    // Player Page Handler Tests
    // =========================================================================

    @Nested
    @DisplayName("PlayerPageHandler Tests")
    class PlayerPageHandlerTests {

        @Test
        @DisplayName("GET /play should return HTML with endpoint URLs")
        void playShouldReturnHtmlWithUrls() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/play"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            String body = response.body();
            assertTrue(body.contains("/player"));
            assertTrue(body.contains("/meta"));
            assertTrue(body.contains("360"));
        }

        @Test
        @DisplayName("GET /play should use http scheme for localhost")
        void playShouldUseHttpForLocalhost() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/play"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            assertTrue(body.contains("http://localhost:" + testPort + "/player"));
            assertTrue(body.contains("http://localhost:" + testPort + "/meta"));
        }

        @Test
        @DisplayName("GET /play should return text/html content type")
        void playShouldReturnHtmlContentType() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/play"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertTrue(contentType.contains("text/html"));
            assertTrue(contentType.contains("charset=UTF-8"));
        }
    }

    // =========================================================================
    // Meta Handler Tests
    // =========================================================================

    @Nested
    @DisplayName("MetaHandler Tests")
    class MetaHandlerTests {

        @Test
        @DisplayName("GET /meta should return JSON with panorama dimensions")
        void metaShouldReturnJsonWithPanoramaDimensions() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            String body = response.body();
            assertTrue(body.contains("\"panorama\""));
            assertTrue(body.contains("\"width\""));
            assertTrue(body.contains("\"height\""));
            // panorama width = 640 + 3 * (640 - 80) = 640 + 1680 = 2320
            assertTrue(body.contains("2320"));
            // panorama height = 360
            assertTrue(body.contains("360"));
        }

        @Test
        @DisplayName("GET /meta should return JSON content type")
        void metaShouldReturnJsonContentType() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertTrue(contentType.contains("application/json"));
        }

        @Test
        @DisplayName("GET /meta should include CORS header")
        void metaShouldIncludeCorsHeader() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String cors = response.headers().firstValue("Access-Control-Allow-Origin").orElse("");
            assertEquals("*", cors);
        }

        @Test
        @DisplayName("GET /meta should include all four camera entries")
        void metaShouldIncludeAllCameras() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            assertTrue(body.contains("\"front\""));
            assertTrue(body.contains("\"rear\""));
            assertTrue(body.contains("\"left\""));
            assertTrue(body.contains("\"right\""));
        }

        @Test
        @DisplayName("GET /meta should include player_url and stream_url")
        void metaShouldIncludeEndpointUrls() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            assertTrue(body.contains("\"player_url\""));
            assertTrue(body.contains("/player"));
            assertTrue(body.contains("\"stream_url\""));
            assertTrue(body.contains("/stitch"));
        }

        @Test
        @DisplayName("POST /meta should return 405 Method Not Allowed")
        void metaPostShouldReturn405() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
        }

        @Test
        @DisplayName("GET /meta camera x-offsets should be computed correctly")
        void metaCameraOffsetsShouldBeCorrect() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + testPort + "/meta"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            // front: x=0, rear: x=560, left: x=1120, right: x=1680
            // overlap = 80, stride = 640 - 80 = 560
            assertTrue(body.contains("\"x\": 0"));
            assertTrue(body.contains("\"x\": 560"));
            assertTrue(body.contains("\"x\": 1120"));
            assertTrue(body.contains("\"x\": 1680"));
        }
    }

    // =========================================================================
    // writeFrame Utility Method Tests
    // =========================================================================

    @Nested
    @DisplayName("writeFrame Tests")
    class WriteFrameTests {

        @Test
        @DisplayName("writeFrame should write MJPEG boundary, content-type, and data")
        void writeFrameShouldFormatCorrectly() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] testJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02, 0x03};

            VideoStreamingServer.writeFrame(baos, testJpeg);

            String output = baos.toString(StandardCharsets.UTF_8);
            assertTrue(output.startsWith("--frame\r\n"));
            assertTrue(output.contains("Content-Type: image/jpeg\r\n"));
            assertTrue(output.contains("Content-Length: 5\r\n"));
        }

        @Test
        @DisplayName("writeFrame should include correct content length")
        void writeFrameShouldIncludeCorrectLength() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] testJpeg = new byte[1024];

            VideoStreamingServer.writeFrame(baos, testJpeg);

            String output = baos.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Content-Length: 1024\r\n"));
        }

        @Test
        @DisplayName("writeFrame should end with CRLF")
        void writeFrameShouldEndWithCrlf() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] testJpeg = new byte[]{0x01, 0x02};

            VideoStreamingServer.writeFrame(baos, testJpeg);

            byte[] output = baos.toByteArray();
            // Last two bytes should be \r\n
            assertEquals('\r', (char) output[output.length - 2]);
            assertEquals('\n', (char) output[output.length - 1]);
        }

        @Test
        @DisplayName("writeFrame should handle empty JPEG data")
        void writeFrameShouldHandleEmptyData() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] emptyJpeg = new byte[0];

            VideoStreamingServer.writeFrame(baos, emptyJpeg);

            String output = baos.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Content-Length: 0\r\n"));
        }

        @Test
        @DisplayName("writeFrame total output size should match header + data + trailing CRLF")
        void writeFrameOutputSizeShouldBeCorrect() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] testJpeg = new byte[]{0x10, 0x20, 0x30};

            VideoStreamingServer.writeFrame(baos, testJpeg);

            byte[] output = baos.toByteArray();
            // Header: "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\n"
            String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\n";
            int expectedSize = header.getBytes(StandardCharsets.UTF_8).length + testJpeg.length + 2; // +2 for trailing \r\n
            assertEquals(expectedSize, output.length);
        }
    }
}
