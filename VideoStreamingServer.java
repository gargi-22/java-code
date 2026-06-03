import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
 
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
 
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
 
public class VideoStreamingServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int BUFFER_SIZE = 64 * 1024;
 
    public static void main(String[] args) throws IOException {
 
        Path frontVideo = Paths.get("right_camera.mp4");
        Path rearVideo  = Paths.get("rear_camera.mp4");
        Path sideVideo  = Paths.get("left_camera.mp4");
        Path backVideo  = Paths.get("top_camera.mp4");
 
        // CHANGED: labels updated to match camera names
        Path[] videos   = {frontVideo, rearVideo, sideVideo, backVideo};
        String[] labels = {"Right", "Rear", "Left", "Top"};
        for (Path v : videos) {
            if (!Files.exists(v) || Files.isDirectory(v)) {
                System.err.println("Video file does not exist or is not a regular file: " + v);
                return;
            }
        }
 
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
 
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load OpenCV native library: " + e.getMessage());
            System.err.println("Make sure OpenCV is installed and the Java native library is on java.library.path.");
            return;
        }
 
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
 
        
        server.createContext("/videoFront", new VideoHandler(frontVideo));
        server.createContext("/videoRear",  new VideoHandler(rearVideo));
        server.createContext("/videoSide",  new VideoHandler(sideVideo));
        server.createContext("/videoBack",  new VideoHandler(backVideo));
 
        server.createContext("/warpFront",  new WarpHandler(frontVideo));
        server.createContext("/warpRear",   new WarpHandler(rearVideo));
        server.createContext("/warpSide",   new WarpHandler(sideVideo));
        server.createContext("/warpBack",   new WarpHandler(backVideo));
 
        
        server.createContext("/play", new PlayerPageHandler(videos, labels));
 
        server.createContext("/stitch", new StitchHandler(videos));
 
       
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();
 
        System.out.println("Panoramic streaming server started on http://localhost:" + port);
        System.out.println("Open http://localhost:" + port + "/play for the panoramic view.");
    }
 
   
    private static class VideoHandler implements HttpHandler {
        private final Path videoFile;
 
        VideoHandler(Path videoFile) {
            this.videoFile = videoFile;
        }
 
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
 
            long fileSize = Files.size(videoFile);
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            long start = 0;
            long end = fileSize - 1;
            int responseCode = 200;
 
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                responseCode = 206;
                String[] parts = rangeHeader.substring(6).split("-", 2);
                try {
                    if (!parts[0].isEmpty()) {
                        start = Long.parseLong(parts[0]);
                    }
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException e) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                if (start > end || start < 0 || end >= fileSize) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
            }
 
            long contentLength = end - start + 1;
            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.set("Accept-Ranges", "bytes");
            responseHeaders.set("Content-Type", probeContentType(videoFile));
            if (responseCode == 206) {
                responseHeaders.set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            }
            exchange.sendResponseHeaders(responseCode, method.equalsIgnoreCase("HEAD") ? -1 : contentLength);
 
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseBody().close();
                return;
            }
 
            try (SeekableByteChannel channel = Files.newByteChannel(videoFile, StandardOpenOption.READ);
                 OutputStream out = exchange.getResponseBody()) {
                channel.position(start);
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                long bytesRemaining = contentLength;
 
                while (bytesRemaining > 0) {
                    buffer.clear();
                    int bytesRead = channel.read(buffer);
                    if (bytesRead < 0) {
                        break;
                    }
                    buffer.flip();
                    int bytesToWrite = (int) Math.min(bytesRead, bytesRemaining);
                    out.write(buffer.array(), 0, bytesToWrite);
                    bytesRemaining -= bytesToWrite;
                }
            }
        }
 
        private String probeContentType(Path path) throws IOException {
            String type = Files.probeContentType(path);
            return type != null ? type : "application/octet-stream";
        }
    }
 
    
    private static class WarpHandler implements HttpHandler {
        private final Path videoFile;
 
        WarpHandler(Path videoFile) {
            this.videoFile = videoFile;
        }
 
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
 
            VideoCapture capture = new VideoCapture(videoFile.toString());
            try {
                if (!capture.isOpened()) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
 
                exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
                exchange.sendResponseHeaders(200, 0);
 
                try (OutputStream out = exchange.getResponseBody()) {
                    Mat frame = new Mat();
                    Mat warped = new Mat();
                    while (capture.read(frame) && !frame.empty()) {
                        warpFrame(frame, warped);
                        MatOfByte jpegBuffer = new MatOfByte();
                        Imgcodecs.imencode(".jpg", warped, jpegBuffer);
                        byte[] imageBytes = jpegBuffer.toArray();
 
                        String header = "--frame\r\n"
                                + "Content-Type: image/jpeg\r\n"
                                + "Content-Length: " + imageBytes.length + "\r\n\r\n";
                        out.write(header.getBytes("UTF-8"));
                        out.write(imageBytes);
                        out.write("\r\n".getBytes("UTF-8"));
                        out.flush();
                    }
                }
            } finally {
                capture.release();
            }
        }
 
        
        private void warpFrame(Mat src, Mat dst) {
            int width = src.cols();
            int height = src.rows();
            Point[] srcPts = new Point[]{
                    new Point(0, 0),
                    new Point(width - 1, 0),
                    new Point(width - 1, height - 1),
                    new Point(0, height - 1)
            };
            Point[] dstPts = new Point[]{
                    new Point(width * 0.05, height * 0.15),
                    new Point(width * 0.95, height * 0.05),
                    new Point(width * 0.85, height * 0.95),
                    new Point(width * 0.15, height * 0.85)
            };
            MatOfPoint2f source = new MatOfPoint2f(srcPts);
            MatOfPoint2f destination = new MatOfPoint2f(dstPts);
            Mat transform = Imgproc.getPerspectiveTransform(source, destination);
            Imgproc.warpPerspective(src, dst, transform, new Size(width, height));
        }
    }
 
    
    private static class StitchHandler implements HttpHandler {
        private final Path[] videoFiles;
 
        StitchHandler(Path[] videoFiles) {
            this.videoFiles = videoFiles;
        }
 
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
 
            VideoCapture[] captures = new VideoCapture[videoFiles.length];
            for (int i = 0; i < videoFiles.length; i++) {
                captures[i] = new VideoCapture(videoFiles[i].toString());
                if (!captures[i].isOpened()) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
            }
 
            exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
            exchange.sendResponseHeaders(200, 0);
 
            try (OutputStream out = exchange.getResponseBody()) {
                Mat[] frames = new Mat[videoFiles.length];
                Mat[] resized = new Mat[videoFiles.length];
                for (int i = 0; i < videoFiles.length; i++) {
                    frames[i]  = new Mat();
                    resized[i] = new Mat();
                }
 
                
                final int TARGET_HEIGHT = 360;
 
                while (true) {
                    boolean anyFailed = false;
                    for (int i = 0; i < captures.length; i++) {
                        if (!captures[i].read(frames[i]) || frames[i].empty()) {
                            anyFailed = true;
                            break;
                        }
                      
                        int newW = (int) ((double) frames[i].cols() / frames[i].rows() * TARGET_HEIGHT);
                        Imgproc.resize(frames[i], resized[i], new Size(newW, TARGET_HEIGHT));
                    }
                    if (anyFailed) break;
 
                    
                    java.util.List<Mat> matList = new java.util.ArrayList<>();
                    for (Mat r : resized) matList.add(r);
                    Mat stitched = new Mat();
                    Core.hconcat(matList, stitched); 
 
                    MatOfByte jpegBuffer = new MatOfByte();
                    Imgcodecs.imencode(".jpg", stitched, jpegBuffer);
                    byte[] imageBytes = jpegBuffer.toArray();
 
                    String header = "--frame\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + imageBytes.length + "\r\n\r\n";
                    out.write(header.getBytes("UTF-8"));
                    out.write(imageBytes);
                    out.write("\r\n".getBytes("UTF-8"));
                    out.flush();
                }
            } finally {
                for (VideoCapture c : captures) c.release();
            }
        }
    }
 
    
    private static class PlayerPageHandler implements HttpHandler {
 
        
        private final Path[]   videoFiles;
        private final String[] cameraLabels;
 
        PlayerPageHandler(Path[] videoFiles, String[] cameraLabels) {
            this.videoFiles   = videoFiles;
            this.cameraLabels = cameraLabels;
        }
 
        @Override
        public void handle(HttpExchange exchange) throws IOException {
 
          
            String[] warpSrcs  = {"/warpFront",  "/warpRear",  "/warpSide",  "/warpBack"};
            String[] videoSrcs = {"/videoFront",  "/videoRear", "/videoSide", "/videoBack"};
 

            StringBuilder tiles = new StringBuilder();
            for (int i = 0; i < videoFiles.length; i++) {
                String mime = probeContentType(videoFiles[i]);
                tiles.append(
                
                    "<div style='background:#111; border:2px solid #444; border-radius:8px; overflow:hidden;'>"
                  +   "<div style='text-align:center; color:#aaa; font-size:0.85rem; padding:4px;" +
                                  "background:#1a1a1a; letter-spacing:1px;'>" + cameraLabels[i] + "</div>"
                  +   "<img src='" + warpSrcs[i] + "' alt='" + cameraLabels[i] + " warped stream' "
                  +        "style='width:100%; display:block; border-bottom:2px solid #444;'>"
                  +   "<video controls autoplay playsinline muted preload='metadata' "
                  +          "style='width:100%; display:block;'>"
                  +     "<source src='" + videoSrcs[i] + "' type='" + mime + "'>"
                  +     "Your device does not support HTML5 video playback."
                  +   "</video>"
                  + "</div>"
                );
            }
 
           
            String html = "<!DOCTYPE html>"
                + "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<title>Panoramic Camera View</title></head>"
                + "<body style=\"margin:0; padding:8px; background:#000; color:#fff; font-family:Arial,sans-serif;\">"
                + "<h2 style=\"text-align:center; margin-bottom:6px;\">360 Panoramic Stitched View</h2>"
                
                + "<img src='/stitch' alt='360 Stitched Panorama' "
                +      "style='width:100%; max-width:1400px; display:block; margin:0 auto 12px auto;"
                +             "border:2px solid #444; border-radius:6px;'>"
                + "<h2 style=\"text-align:center; margin-bottom:10px;\">Individual Camera Views</h2>"

                + "<div style=\"display:grid; grid-template-columns:1fr 1fr; gap:8px; max-width:1400px; margin:0 auto;\">"
                +   tiles.toString()
                + "</div>"
                + "</body></html>";
 
            byte[] bytes = html.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
 
        private String probeContentType(Path path) throws IOException {
            String type = Files.probeContentType(path);
            return type != null ? type : "application/octet-stream";
        }
    }
}