import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Shared HTTP response helpers used by multiple handlers in
 * {@link VideoStreamingServer} to eliminate duplicated
 * response-sending and request-inspection logic.
 */
final class HttpResponseUtil {

    private HttpResponseUtil() { }

    // -- response senders ---------------------------------------------------

    static void sendHtml(HttpExchange ex, String html) throws IOException {
        sendBytes(ex, "text/html; charset=UTF-8", html.getBytes("UTF-8"));
    }

    static void sendJson(HttpExchange ex, String json) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        sendBytes(ex, "application/json; charset=UTF-8", json.getBytes("UTF-8"));
    }

    static void sendBytes(HttpExchange ex, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // -- request helpers ----------------------------------------------------

    /**
     * Returns {@code true} (and sends a 405 response) when the request method
     * is anything other than GET, so the caller can short-circuit.
     */
    static boolean rejectNonGet(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return true;
        }
        return false;
    }

    /**
     * Builds the public base URL (e.g. {@code https://example.com}) from the
     * incoming request's {@code Host} header.  Falls back to
     * {@code http://localhost:<fallbackPort>} when the header is absent.
     */
    static String buildBaseUrl(HttpExchange ex, int fallbackPort) {
        String host    = ex.getRequestHeaders().getFirst("Host");
        boolean secure = (host != null && !host.contains("localhost"));
        String scheme  = secure ? "https" : "http";

        if (host == null || host.isEmpty()) {
            host = "localhost:" + fallbackPort;
        }
        return scheme + "://" + host;
    }
}
