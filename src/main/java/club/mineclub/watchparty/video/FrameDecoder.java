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
 * <h2>Real-time playback (1x speed)</h2>
 * {@link #grabRealtime()} keeps the presented frames locked to the wall clock.
 * Each decode tick computes the stream timestamp it "should" be at for the
 * elapsed real time and drains intermediate source frames until it reaches
 * that point.  This means playback always advances at 1x speed regardless of
 * {@code targetFps}: if the display rate is lower than the source rate, extra
 * source frames are skipped; if it is higher, frames are repeated.  No frame
 * is ever slowed down or sped up.
 *
 * <h2>Buffer ownership</h2>
 * Two reusable {@link BufferedImage} buffers are handed to the caller.  The
 * caller takes ownership and MUST call {@link #release(BufferedImage)} once it
 * is done reading, so the buffer can be recycled.  This avoids both the
 * per-frame allocation cost and the torn-frame race that a single shared
 * buffer would cause when the decode thread overwrites a frame the convert
 * thread is still reading.
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

    // Two reusable output buffers — handed to the caller under ownership.
    private BufferedImage[]      bufs;
    private Graphics2D[]         graphics;
    private final boolean[]      busy = {false, false};

    // Real-time anchors: map stream timestamp (µs) ↔ wall clock (ns).
    private long                 streamStartUs;
    private long                 wallStartNs;

    // Set when the grabber hits EOF so the caller can distinguish "ended"
    // from "skipped because no buffer was free".
    private volatile boolean     exhausted = false;

    private final Logger logger;

    /**
     * @param streamUrl    RTMP URL (or any FFmpeg-supported source).
     * @param targetWidth  Final image width  in pixels (= widthMaps  * 128).
     * @param targetHeight Final image height in pixels (= heightMaps * 128).
     * @param targetFps    Desired display frame-rate; used only for logging.
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
        bufs     = new BufferedImage[2];
        graphics = new Graphics2D[2];
        for (int i = 0; i < 2; i++) {
            bufs[i] = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            graphics[i] = bufs[i].createGraphics();
            graphics[i].setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }

        // Anchor real-time clocks to the moment the stream opens.
        streamStartUs = grabber.getTimestamp();
        wallStartNs   = System.nanoTime();
        exhausted     = false;
        started = true;
        logger.info("[WatchParty] Opened stream: " + streamUrl
                + " (" + grabber.getImageWidth() + "x" + grabber.getImageHeight()
                + " @ " + String.format("%.1f", grabber.getFrameRate()) + " fps)");
    }

    /**
     * Grabs the next video frame and returns it scaled to the target size,
     * advancing the stream to the current real-time position.
     *
     * <p>Ownership of the returned image passes to the caller, which must call
     * {@link #release(BufferedImage)} when done reading it.  The same object
     * must not be read after it is released.
     *
     * @return A {@link BufferedImage} of size {@code targetWidth × targetHeight},
     *         or {@code null} if the stream ended, paused for backpressure, or
     *         produced no decodable frame this tick.
     * @throws FFmpegFrameGrabber.Exception on a decode error.
     */
    public BufferedImage grabRealtime() throws FFmpegFrameGrabber.Exception {
        ensureStarted();

        // Where the stream "should" be, in stream timestamp microseconds,
        // given how much real time has elapsed since we started.
        long elapsedUs = (System.nanoTime() - wallStartNs) / 1000L;
        long targetUs  = streamStartUs + elapsedUs;

        // Drain source frames until we reach the target real-time position.
        // Intermediate frames are skipped so playback stays at 1x speed even
        // when targetFps is lower than the source frame-rate.
        Frame last = null;
        Frame frame;
        while ((frame = grabber.grab()) != null) {
            if (frame.image == null) continue;
            last = frame;
            if (grabber.getTimestamp() >= targetUs) break;
        }

        if (last == null) {
            exhausted = true;   // grab() returned null → EOF
            return null;
        }
        exhausted = false;

        BufferedImage src = converter.convert(last);
        if (src == null) return null;

        // Acquire a free buffer; if both are still in flight (slow consumer)
        // we skip this tick rather than overwrite a frame being read.
        int bi = freeBufferIndex();
        if (bi < 0) return null;

        busy[bi] = true;
        graphics[bi].drawImage(src, 0, 0, targetWidth, targetHeight, null);
        return bufs[bi];
    }

    /**
     * Returns the buffer to the free pool.  Safe to call with {@code null} or
     * with an image that does not belong to this decoder (no-op).
     */
    public void release(BufferedImage img) {
        if (img == null) return;
        for (int i = 0; i < bufs.length; i++) {
            if (bufs[i] == img) {
                busy[i] = false;
                return;
            }
        }
    }

    /** True if the last {@link #grabRealtime()} hit end-of-stream. */
    public boolean isExhausted() {
        return exhausted;
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
        long frameIntervalUs = 1_000_000L / Math.max(1, targetFps);
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
        if (last == null) {
            exhausted = true;
            return null;
        }
        BufferedImage src = converter.convert(last);
        if (src == null) return null;
        int bi = freeBufferIndex();
        if (bi < 0) return null;
        busy[bi] = true;
        graphics[bi].drawImage(src, 0, 0, targetWidth, targetHeight, null);
        return bufs[bi];
    }

    /** Closes the grabber and releases native resources. */
    @Override
    public void close() {
        if (graphics != null) {
            for (Graphics2D g : graphics) {
                if (g != null) g.dispose();
            }
            graphics = null;
        }
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

    private int freeBufferIndex() {
        for (int i = 0; i < busy.length; i++) {
            if (!busy[i]) return i;
        }
        return -1;
    }

    private void ensureStarted() {
        if (!started) throw new IllegalStateException("FrameDecoder not started.");
    }

    public int getTargetWidth()  { return targetWidth; }
    public int getTargetHeight() { return targetHeight; }
    public int getTargetFps()    { return targetFps; }
    public String getStreamUrl() { return streamUrl; }
}
