import com.sun.net.httpserver.HttpExchange;
 
import com.sun.net.httpserver.HttpHandler;
 
import com.sun.net.httpserver.HttpServer;
 
import java.io.IOException;
 
import java.io.OutputStream;
 
import java.net.InetSocketAddress;
 
import java.nio.file.Files;
 
import java.nio.file.Path;
 
import java.nio.file.Paths;
 
import java.util.ArrayList;
 
import java.util.List;
 
import java.util.concurrent.Executors;
 
import org.opencv.core.*;
 
import org.opencv.imgcodecs.Imgcodecs;
 
import org.opencv.imgproc.Imgproc;
 
import org.opencv.videoio.VideoCapture;
 
public class VideoStreamingServer {
 
   private static final int DEFAULT_PORT  = 9090;
 
   private static final int TARGET_HEIGHT = 360;
 
   private static final int TARGET_WIDTH  = 640;
 
   private static final int OVERLAP_PX    = 80;
 
   // =========================================================================
 
   public static void main(String[] args) throws IOException {
 
       Path frontVideo = Paths.get("Front_g.mp4");
 
       Path rearVideo  = Paths.get("left_g.mov");
 
       Path sideVideo  = Paths.get("rear_g.mov");
 
       Path backVideo  = Paths.get("right_g.mov");
 
       Path[] videos = { frontVideo, rearVideo, sideVideo, backVideo };
 
       for (Path v : videos) {
 
           if (!Files.exists(v) || Files.isDirectory(v)) {
 
               System.err.println("Video file not found: " + v);
 
               return;
 
           }
 
       }
 
       int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
 
       try {
 
           System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
 
       } catch (UnsatisfiedLinkError e) {
 
           System.err.println("OpenCV native library not found: " + e.getMessage());
 
           return;
 
       }
 
       HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
 
       server.createContext("/stitch", new StitchHandler(videos));   // raw MJPEG stream
 
       // server.createContext("/player", new StreamOnlyPageHandler()); // just the video, no UI
 
       server.createContext("/play",   new PlayerPageHandler());      // links-only dashboard
 
       server.createContext("/meta",   new MetaHandler(port));        // JSON metadata
 
       server.createContext("/",       new RootRedirectHandler());    // root → /play
 
       server.setExecutor(Executors.newFixedThreadPool(8));
 
       server.start();
 
       String publicUrl = System.getenv("RENDER_EXTERNAL_URL");
 
       if (publicUrl == null || publicUrl.isEmpty()) publicUrl = "http://localhost:" + port;
 
       System.out.println("=================================================");
 
       System.out.println("  Dashboard  →  " + publicUrl + "/play");
 
       // System.out.println("  Player     →  " + publicUrl + "/player  (stream only)");
 
       System.out.println("  Meta       →  " + publicUrl + "/meta");
 
       System.out.println("  Stream     →  " + publicUrl + "/stitch  (raw MJPEG)");
 
       System.out.println("=================================================");
 
   }
 
   // =========================================================================
 
   //  ROOT REDIRECT  —  "/" → "/play"
 
   // =========================================================================
 
   private static class RootRedirectHandler implements HttpHandler {
 
       @Override
 
       public void handle(HttpExchange ex) throws IOException {
 
           ex.getResponseHeaders().set("Location", "/play");
 
           ex.sendResponseHeaders(302, -1);
 
       }
 
   }
 
   // =========================================================================
 
   //  STREAM-ONLY PAGE  —  /player  just the live video, full viewport, no UI
 
   // =========================================================================
 
   // private static class StreamOnlyPageHandler implements HttpHandler {
 
   //     @Override
 
   //     public void handle(HttpExchange ex) throws IOException {
 
   //         String html = "<!DOCTYPE html><html lang='en'><head>"
 
   //             + "<meta charset='UTF-8'>"
 
   //             + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
 
   //             + "<title>Live 360\u00b0 Stream</title>"
 
   //             + "<style>"
 
   //             + "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }"
 
   //             + "html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }"
 
   //             + "img { display: block; width: 100%; height: 100%; object-fit: contain; }"
 
   //             + "</style>"
 
   //             + "</head><body>"
 
   //             + "<img src='/stitch' alt='Live 360\u00b0 panorama stream'>"
 
   //             + "</body></html>";
 
   //         byte[] bytes = html.getBytes("UTF-8");
 
   //         ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
 
   //         ex.sendResponseHeaders(200, bytes.length);
 
   //         try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
 
   //     }
 
   // }
 
   // =========================================================================
 
   //  DASHBOARD PAGE  —  /play  title + endpoint links ONLY (no video embed)
 
   // =========================================================================
 
   private static class PlayerPageHandler implements HttpHandler {
 
       @Override
 
       public void handle(HttpExchange ex) throws IOException {
 
           String host    = ex.getRequestHeaders().getFirst("Host");
 
           boolean secure = (host != null && !host.contains("localhost"));
 
           String scheme  = secure ? "https" : "http";
 
           String baseUrl = (host != null && !host.isEmpty())
 
                            ? scheme + "://" + host : "http://localhost";
 
           // String playerUrl = baseUrl + "/player";
 
           String metaUrl   = baseUrl + "/meta";
 
           String html = "<!DOCTYPE html><html lang='en'><head>"
 
               + "<meta charset='UTF-8'>"
 
               + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
 
               + "<title>360\u00b0 Panoramic View</title>"
 
               + "<style>"
 
               + "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }"
 
               + "html, body { height: 100%; background: #0a0a0f; color: #e0e0e0;"
 
               + "  font-family: 'Segoe UI', sans-serif; }"
 
               + ".container { display: flex; flex-direction: column;"
 
               + "  align-items: center; justify-content: center;"
 
               + "  min-height: 100vh; padding: 16px; gap: 14px; }"
 
               + "h1 { font-size: 1.4rem; font-weight: 300; letter-spacing: 2px;"
 
               + "  color: #7ec8e3; text-align: center; flex-shrink: 0; }"
 
               + ".url-box { width: 100%; max-width: 700px; background: #12121c;"
 
               + "  border: 1px solid #2a2a3a; border-radius: 8px; padding: 16px 20px;"
 
               + "  flex-shrink: 0; }"
 
               + ".url-box h2 { font-size: 0.72rem; font-weight: 600; letter-spacing: 1.5px;"
 
               + "  color: #7ec8e3; text-transform: uppercase; margin-bottom: 12px; }"
 
               + ".url-row { display: flex; align-items: center; gap: 10px; margin: 6px 0; }"
 
               + ".url-label { min-width: 55px; color: #555; font-size: 0.76rem; }"
 
               + ".url-link { color: #7ec8e3; text-decoration: none; word-break: break-all;"
 
               + "  font-size: 0.84rem; flex: 1; }"
 
               + ".url-link:hover { text-decoration: underline; }"
 
               + ".copy-btn { cursor: pointer; background: #1e1e2e; border: 1px solid #3a3a5a;"
 
               + "  color: #aaa; border-radius: 4px; padding: 3px 10px; font-size: 0.72rem;"
 
               + "  white-space: nowrap; transition: background 0.15s, color 0.15s; }"
 
               + ".copy-btn:hover { background: #2a2a3a; color: #fff; }"
 
               + "</style>"
 
               + "</head><body>"
 
               + "<div class='container'>"
 
               + "  <h1>360\u00b0 Panoramic Camera System</h1>"
 
               // + "  <div class='url-box'>"
 
               // + "    <h2>Endpoint URLs</h2>"
 
               // + "    <div class='url-row'>"
 
               // + "      <span class='url-label'>Player</span>"
 
               // + "      <a class='url-link' href='" + playerUrl + "' target='_blank'>" + playerUrl + "</a>"
 
               // + "      <button class='copy-btn'"
 
               // + "        onclick=\"navigator.clipboard.writeText('" + playerUrl + "')"
 
               // + "          .then(()=>{this.textContent='Copied!';setTimeout(()=>this.textContent='Copy',1500)})\">Copy</button>"
 
               // + "    </div>"
 
               + "    <div class='url-row'>"
 
               + "      <span class='url-label'>Meta</span>"
 
               + "      <a class='url-link' href='" + metaUrl + "' target='_blank'>" + metaUrl + "</a>"
 
               + "      <button class='copy-btn'"
 
               + "        onclick=\"navigator.clipboard.writeText('" + metaUrl + "')"
 
               + "          .then(()=>{this.textContent='Copied!';setTimeout(()=>this.textContent='Copy',1500)})\">Copy</button>"
 
               + "    </div>"
 
               + "  </div>"
 
               + "</div>"
 
               + "</body></html>";
 
           byte[] bytes = html.getBytes("UTF-8");
 
           ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
 
           ex.sendResponseHeaders(200, bytes.length);
 
           try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
 
       }
 
   }
 
   // =========================================================================
 
   //  META HANDLER  —  player_url → /player (stream-only), stream_url → /stitch
 
   // =========================================================================
 
   private static class MetaHandler implements HttpHandler {
 
       private final int port;
 
       MetaHandler(int port) { this.port = port; }
 
       @Override
 
       public void handle(HttpExchange ex) throws IOException {
 
           if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
 
               ex.sendResponseHeaders(405, -1);
 
               return;
 
           }
 
           String host    = ex.getRequestHeaders().getFirst("Host");
 
           boolean secure = (host != null && !host.contains("localhost"));
 
           String scheme  = secure ? "https" : "http";
 
           if (host == null || host.isEmpty()) host = "localhost:" + port;
 
           String baseUrl = scheme + "://" + host;
 
           int N       = 4;
 
           int overlap = Math.min(OVERLAP_PX, TARGET_WIDTH / 4);
 
           int panoW   = TARGET_WIDTH + (N - 1) * (TARGET_WIDTH - overlap);
 
           int panoH   = TARGET_HEIGHT;
 
           String[] names = { "front", "rear", "left", "right" };
 
           StringBuilder sb = new StringBuilder();
 
           sb.append("{\n");
 
           sb.append("  \"panorama\": {\n");
 
           sb.append("    \"width\": ").append(panoW).append(",\n");
 
           sb.append("    \"height\": ").append(panoH).append(",\n");
 
           // sb.append("    \"player_url\": \"").append(baseUrl).append("/player\",\n");
 
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
 
       }
 
   }
 
   // =========================================================================
 
   //  STITCH HANDLER  —  raw MJPEG stream
 
   // =========================================================================
 
   private static class StitchHandler implements HttpHandler {
 
       private final Path[] videoFiles;
 
       StitchHandler(Path[] f) { this.videoFiles = f; }
 
       @Override
 
       public void handle(HttpExchange ex) throws IOException {
 
           if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
 
               ex.sendResponseHeaders(405, -1);
 
               return;
 
           }
 
           VideoCapture[] caps = new VideoCapture[videoFiles.length];
 
           for (int i = 0; i < videoFiles.length; i++) {
 
               caps[i] = new VideoCapture(videoFiles[i].toString());
 
               if (!caps[i].isOpened()) {
 
                   ex.sendResponseHeaders(500, -1);
 
                   return;
 
               }
 
           }
 
           ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
 
           ex.getResponseHeaders().set("Cache-Control", "no-cache");
 
           ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
 
           ex.sendResponseHeaders(200, 0);
 
           try (OutputStream out = ex.getResponseBody()) {
 
               Mat[] frames  = new Mat[videoFiles.length];
 
               Mat[] resized = new Mat[videoFiles.length];
 
               for (int i = 0; i < videoFiles.length; i++) {
 
                   frames[i]  = new Mat();
 
                   resized[i] = new Mat();
 
               }
 
               while (true) {
 
                   boolean done = false;
 
                   for (int i = 0; i < caps.length; i++) {
 
                       if (!caps[i].read(frames[i]) || frames[i].empty()) {
 
                           done = true; break;
 
                       }
 
                       Imgproc.resize(frames[i], resized[i], new Size(TARGET_WIDTH, TARGET_HEIGHT));
 
                   }
 
                   if (done) break;
 
                   Mat panorama = featherStitch(resized);
 
                   writeFrame(out, encodeJpeg(panorama));
 
                   panorama.release();
 
               }
 
           } finally {
 
               for (VideoCapture c : caps) c.release();
 
           }
 
       }
 
   }
 
   // =========================================================================
 
   //  CORE BLENDING  —  featherStitch
 
   // =========================================================================
 
   static Mat featherStitch(Mat[] frames) {
 
       int N = frames.length;
 
       int H = TARGET_HEIGHT;
 
       int W = TARGET_WIDTH;
 
       int overlap = Math.min(OVERLAP_PX, W / 4);
 
       int panoW   = W + (N - 1) * (W - overlap);
 
       Mat accumColor  = Mat.zeros(H, panoW, CvType.CV_32FC3);
 
       Mat accumWeight = Mat.zeros(H, panoW, CvType.CV_32FC1);
 
       for (int i = 0; i < N; i++) {
 
           int xStart = i * (W - overlap);
 
           Mat weight = buildFeatherMask(H, W, overlap);
 
           Mat frameF = new Mat();
 
           frames[i].convertTo(frameF, CvType.CV_32FC3);
 
           Mat weight3 = new Mat();
 
           List<Mat> ch = new ArrayList<>();
 
           ch.add(weight); ch.add(weight); ch.add(weight);
 
           Core.merge(ch, weight3);
 
           Mat wFrame = new Mat();
 
           Core.multiply(frameF, weight3, wFrame);
 
           int xEnd    = Math.min(xStart + W, panoW);
 
           int wActual = xEnd - xStart;
 
           Mat colorRoi  = accumColor.submat(0, H, xStart, xEnd);
 
           Mat weightRoi = accumWeight.submat(0, H, xStart, xEnd);
 
           Mat wFrameCrop = wFrame.colRange(0, wActual);
 
           Mat weightCrop = weight.colRange(0, wActual);
 
           Core.add(colorRoi,  wFrameCrop, colorRoi);
 
           Core.add(weightRoi, weightCrop, weightRoi);
 
           colorRoi.release(); weightRoi.release();
 
           frameF.release(); weight.release(); weight3.release();
 
           wFrame.release();
 
       }
 
       Mat safeW = new Mat();
 
       Core.max(accumWeight, new Scalar(1e-6), safeW);
 
       Mat safeW3 = new Mat();
 
       List<Mat> wch = new ArrayList<>();
 
       wch.add(safeW); wch.add(safeW); wch.add(safeW);
 
       Core.merge(wch, safeW3);
 
       Mat blended = new Mat();
 
       Core.divide(accumColor, safeW3, blended);
 
       Mat result = new Mat();
 
       blended.convertTo(result, CvType.CV_8UC3);
 
       accumColor.release(); accumWeight.release();
 
       safeW.release(); safeW3.release(); blended.release();
 
       return result;
 
   }
 
   static Mat buildFeatherMask(int H, int W, int overlap) {
 
       Mat mask = new Mat(H, W, CvType.CV_32FC1, new Scalar(1.0));
 
       for (int x = 0; x < overlap; x++) {
 
           float alpha = (float) x / overlap;
 
           for (int y = 0; y < H; y++) {
 
               mask.put(y, x,         new float[]{ alpha });
 
               mask.put(y, W - 1 - x, new float[]{ alpha });
 
           }
 
       }
 
       return mask;
 
   }
 
   // =========================================================================
 
   //  SHARED HELPERS
 
   // =========================================================================
 
   static byte[] encodeJpeg(Mat frame) {
 
       MatOfByte buf    = new MatOfByte();
 
       MatOfInt  params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 88);
 
       Imgcodecs.imencode(".jpg", frame, buf, params);
 
       return buf.toArray();
 
   }
 
   static void writeFrame(OutputStream out, byte[] jpeg) throws IOException {
 
       String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
 
                     + jpeg.length + "\r\n\r\n";
 
       out.write(header.getBytes("UTF-8"));
 
       out.write(jpeg);
 
       out.write("\r\n".getBytes("UTF-8"));
 
       out.flush();
 
   }
 
}
 
