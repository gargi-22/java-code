import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared stream-copy helper used by {@link VideoStreamingClient} to
 * eliminate the duplicated buffer-read-write loops in both the chunked
 * download and the single-stream fallback paths.
 */
final class StreamUtil {

    private static final int BUFFER_SIZE = 8192;

    private StreamUtil() { }

    /**
     * Copies all bytes from {@code in} to {@code out} using a fixed-size
     * buffer and returns the total number of bytes transferred.
     */
    static long transfer(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            total += bytesRead;
        }
        return total;
    }
}
