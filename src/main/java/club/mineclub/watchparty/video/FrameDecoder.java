package club.mineclub.watchparty.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

/**
 * Wraps {@link FFmpegFrameGrabber} to decode video frames from an RTMP (or
 * any FFmpeg-supported) URL and return them as scaled {@link BufferedImage}s.
 *
 * <h2>Frame-skipping</h2>
 * After each {@link #grabNextFrame()} call the caller compares elapsed wall
 * time to the ideal frame interval.  If the pipeline is falling behind, it
 * calls {@link #skipToLatest()} which drains queued frames until the grabber
 * timestamp is within one frame interval of the current wall clock.
 *
 * <h2>Thread-safety</h2>
 * All methods must be called from the same decode thread; FFmpegFrameGrabber
 * is not thread-safe.
 */
public final class FrameDecoder implements AutoCloseable {

    private final String         streamUrl;
    private final int            targetWidth;
    private final int            targetHeight;
    private final int            targetFps;

    private FFmpegFrameGrabber   grabber;
    private Java2DFrameConverter converter;
    private boolean              started = false;

    // Reusable output image (avoids per-frame allocation when size is fixed)
    private BufferedImage        scaledBuffer;
    private Graphics2D           scaledG2d;

    private final Logger logger;

    /**
     * @param streamUrl    RTMP URL (or any FFmpeg-supported source).
     * @param targetWidth  Final image width  in pixels (= widthMaps  * 128).
     * @param targetHeight Final image height in pixels (= heightMaps * 128).
     * @param targetFps    Desired output frame-rate; used for frame-skipping.
     */
    public FrameDecoder(String streamUrl, int targetWidth, int targetHeight,
                        int targetFps, Logger logger) {
        this.streamUrl    = streamUrl;
        this.targetWidth  = targetWidth;
        this.targetHeight = targetHeight;
        this.targetFps    = targetFps;
        this.logger       = logger;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Opens the stream.  Blocks until the stream header is received.
     *
     * @throws FFmpegFrameGrabber.Exception if the stream cannot be opened.
     */
    public void start() throws FFmpegFrameGrabber.Exception {
        grabber   = new FFmpegFrameGrabber(streamUrl);
        // Request only the video stream to reduce CPU overhead
        grabber.setOption("rtsp_transport", "tcp");
        // Low latency options helpful for RTMP/live streams
        grabber.setOption("fflags", "nobuffer");
        grabber.setOption("flags", "low_delay");
        grabber.setOption("analyzeduration", "1000000"); // 1 s
        grabber.setOption("probesize",       "1000000"); // 1 MB
        grabber.setVideoOption("tune", "zerolatency");

        grabber.start();
        converter    = new Java2DFrameConverter();
        scaledBuffer = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        scaledG2d    = scaledBuffer.createGraphics();
        scaledG2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        started = true;
        logger.info("[WatchParty] Opened stream: " + streamUrl
                + " (" + grabber.getImageWidth() + "x" + grabber.getImageHeight()
                + " @ " + String.format("%.1f", grabber.getFrameRate()) + " fps)");
    }

    /**
     * Grabs the next video frame and returns it scaled to the target size.
     *
     * @return A {@link BufferedImage} of size {@code targetWidth × targetHeight},
     *         or {@code null} if the stream ended.
     * @throws FFmpegFrameGrabber.Exception on a decode error.
     */
    public BufferedImage grabNextFrame() throws FFmpegFrameGrabber.Exception {
        ensureStarted();
        Frame frame;
        // Skip audio-only frames
        while ((frame = grabber.grab()) != null) {
            if (frame.image != null) {
                return scaleFrame(frame);
            }
        }
        return null; // EOF
    }

    /**
     * Drains frames until the grabber's timestamp is within one frame interval
     * of the wall clock.  Useful when the decode pipeline has fallen behind.
     *
     * @return The most recent frame after draining, or {@code null} on EOF.
     * @throws FFmpegFrameGrabber.Exception on a decode error.
     */
    public BufferedImage skipToLatest() throws FFmpegFrameGrabber.Exception {
        ensureStarted();
        long frameIntervalUs = 1_000_000L / targetFps;
        long wallUs          = System.nanoTime() / 1_000L;

        Frame last = null;
        Frame frame;
        while ((frame = grabber.grab()) != null) {
            if (frame.image == null) continue;
            if (grabber.getTimestamp() >= wallUs - frameIntervalUs) {
                last = frame.clone(); // retain for conversion after loop
                break;
            }
            // frame is stale; keep draining
        }
        return last != null ? scaleFrame(last) : null;
    }

    /** Closes the grabber and releases native resources. */
    @Override
    public void close() {
        if (scaledG2d != null) { scaledG2d.dispose(); scaledG2d = null; }
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
            } catch (FFmpegFrameGrabber.Exception e) {
                logger.warning("[WatchParty] Error closing grabber: " + e.getMessage());
            }
            grabber = null;
        }
        started = false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BufferedImage scaleFrame(Frame frame) {
        BufferedImage src = converter.convert(frame);
        if (src == null) return null;
        // Draw into the reusable buffer at target dimensions
        scaledG2d.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        // Return a snapshot — the caller may hold this across threads
        // We must NOT return scaledBuffer directly because the G2D reuses it.
        // For minimal allocation, only copy when the caller needs to queue it;
        // VideoSession clones the array after palette conversion, not here.
        return scaledBuffer;
    }

    private void ensureStarted() {
        if (!started) throw new IllegalStateException("FrameDecoder not started.");
    }

    public int getTargetWidth()  { return targetWidth; }
    public int getTargetHeight() { return targetHeight; }
    public int getTargetFps()    { return targetFps; }
    public String getStreamUrl() { return streamUrl; }
}
