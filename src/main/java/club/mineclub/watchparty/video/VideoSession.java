package club.mineclub.watchparty.video;

import club.mineclub.watchparty.model.VideoScreen;
import club.mineclub.watchparty.packet.PacketSender;
import club.mineclub.watchparty.render.MapPaletteConverter;
import club.mineclub.watchparty.render.OverlayRegistry;
import club.mineclub.watchparty.render.OverlayRenderer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Owns the three-stage decode→convert→send pipeline for one video screen.
 *
 * <pre>
 * [Decode Thread] → BlockingQueue(raw frames) → [Convert Thread] → BlockingQueue(map bytes) → [Send Thread(s)]
 * </pre>
 *
 * <h2>Frame-rate pacing</h2>
 * A {@link ScheduledExecutorService} ticks at {@code 1000/targetFps} ms.
 * Each tick triggers one decode attempt; if the decode thread is busy the
 * tick is skipped (non-blocking offer to the queue).
 *
 * <h2>Reconnect</h2>
 * On a {@link org.bytedeco.javacv.FrameGrabber.Exception}, the session
 * closes the grabber, waits {@code reconnectDelayMs}, then retries up to
 * {@code reconnectAttempts} times before marking the screen {@link VideoScreen.State#IDLE}.
 *
 * <h2>Performance logging</h2>
 * Every 60 seconds the average decode, convert, and send latency per frame
 * is logged to the plugin logger.
 */
public final class VideoSession {

    private static final int QUEUE_CAPACITY = 2; // backpressure

    // ── Configuration ─────────────────────────────────────────────────────────
    private final VideoScreen       screen;
    private final String            streamUrl;
    private final MapPaletteConverter palette;
    private final PacketSender      sender;
    private final Logger            logger;

    private final int    targetFps;
    private final int    reconnectAttempts;
    private final long   reconnectDelayMs;
    private final double renderRadiusBlocks;
    private final OverlayRegistry overlayRegistry;
    private final Plugin plugin;

    // ── Pipeline ──────────────────────────────────────────────────────────────
    // Queue of raw BufferedImages from the decode thread
    private final ArrayBlockingQueue<BufferedImage> rawQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    // Queue of palette-converted map data [row][col][128*128]
    private final ArrayBlockingQueue<byte[][][]>    mapQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private ScheduledExecutorService decodeScheduler;
    private ExecutorService          convertExecutor;
    private ExecutorService          sendExecutor;

    private volatile FrameDecoder    frameDecoder;
    private volatile boolean         running = false;

    // ── Metrics ───────────────────────────────────────────────────────────────
    private final AtomicLong totalDecodeNs   = new AtomicLong();
    private final AtomicLong totalConvertNs  = new AtomicLong();
    private final AtomicLong totalSendNs     = new AtomicLong();
    private final AtomicLong frameCount      = new AtomicLong();
    private       long        lastMetricLog  = System.currentTimeMillis();

    // Tracks which players have been sent the initial spawn packets
    private final java.util.Set<java.util.UUID> spawnedPlayers =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // Cache of nearby players, updated on the main thread every 20 ticks
    private volatile java.util.List<Player> nearbyPlayersCache = java.util.Collections.emptyList();
    private BukkitTask nearbyPlayerTask;

    public VideoSession(
            VideoScreen screen,
            String streamUrl,
            MapPaletteConverter palette,
            PacketSender sender,
            Logger logger,
            int targetFps,
            int reconnectAttempts,
            long reconnectDelayMs,
            double renderRadiusBlocks,
            OverlayRegistry overlayRegistry,
            Plugin plugin) {
        this.screen             = screen;
        this.streamUrl          = streamUrl;
        this.palette            = palette;
        this.sender             = sender;
        this.logger             = logger;
        this.targetFps          = targetFps;
        this.reconnectAttempts  = reconnectAttempts;
        this.reconnectDelayMs   = reconnectDelayMs;
        this.renderRadiusBlocks = renderRadiusBlocks;
        this.overlayRegistry    = overlayRegistry;
        this.plugin             = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the pipeline on background threads.
     * Non-blocking; returns immediately.
     */
    public void start() {
        if (running) return;
        running = true;
        screen.compareAndSetState(VideoScreen.State.IDLE, VideoScreen.State.RUNNING);

        decodeScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "TheBigScreen-Decode-" + screen.getName()));
        convertExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "TheBigScreen-Convert-" + screen.getName()));
        sendExecutor    = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "TheBigScreen-Send-" + screen.getName()));

        // Start the convert and send loops first
        convertExecutor.submit(this::convertLoop);
        sendExecutor.submit(this::sendLoop);

        // Schedule decode ticks at the target FPS
        long intervalMs = 1000L / targetFps;
        decodeScheduler.scheduleAtFixedRate(
                this::decodeTick, 0, intervalMs, TimeUnit.MILLISECONDS);

        // Update nearby player cache on the main thread every second (20 ticks)
        // This is thread-safe — Bukkit world.getPlayers() must be called on main thread
        nearbyPlayerTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshNearbyPlayers, 0L, 20L);

        logger.info("[TheBigScreen™] Session started for '" + screen.getName()
                + "' at " + targetFps + " fps → " + streamUrl);
    }

    /** Stops the pipeline gracefully, waits up to 5 s for threads to finish. */
    public void stop() {
        if (!running) return;
        running = false;
        screen.compareAndSetState(VideoScreen.State.RUNNING, VideoScreen.State.STOPPING);

        if (nearbyPlayerTask != null) {
            nearbyPlayerTask.cancel();
            nearbyPlayerTask = null;
        }

        decodeScheduler.shutdownNow();
        convertExecutor.shutdownNow();
        sendExecutor.shutdownNow();

        // Close the grabber (interrupts any blocking grab() call)
        FrameDecoder fd = frameDecoder;
        if (fd != null) {
            fd.close();
            frameDecoder = null;
        }

        rawQueue.clear();
        mapQueue.clear();

        screen.setState(VideoScreen.State.IDLE);
        screen.setCurrentStreamUrl(null);
        screen.invalidateFrameCache();
        spawnedPlayers.clear();

        logger.info("[TheBigScreen™] Session stopped for '" + screen.getName() + "'.");
    }

    /** Re-sends spawn/metadata to a newly joined (or chunk-loaded) player. */
    public void onPlayerEnterRange(Player player) {
        if (!running) return;
        if (spawnedPlayers.add(player.getUniqueId())) {
            sender.spawnScreenForPlayer(screen, player);
        }
    }

    /** Removes a player from tracking (e.g. on quit or world-change). */
    public void onPlayerLeaveRange(Player player) {
        spawnedPlayers.remove(player.getUniqueId());
        sender.destroyScreen(screen, java.util.List.of(player));
    }

    // ── Pipeline stages ───────────────────────────────────────────────────────

    /** Called by the scheduler; captures one frame and offers it to the queue. */
    private void decodeTick() {
        if (!running) return;

        // Open or reopen the grabber if needed
        if (frameDecoder == null) {
            if (!openGrabberWithRetry()) {
                running = false;
                screen.setState(VideoScreen.State.IDLE);
                return;
            }
        }

        try {
            long t0 = System.nanoTime();
            BufferedImage img = frameDecoder.grabNextFrame();
            if (img == null) {
                // Stream ended normally
                logger.info("[TheBigScreen™] Stream ended for '" + screen.getName() + "'. Attempting reconnect.");
                frameDecoder.close();
                frameDecoder = null;
                return;
            }
            totalDecodeNs.addAndGet(System.nanoTime() - t0);

            // Non-blocking offer: drop the new frame if the convert thread is busy
            // (backpressure) rather than blocking the scheduler
            rawQueue.offer(img);
        } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
            logger.warning("[TheBigScreen™] Decode error on '" + screen.getName() + "': " + e.getMessage());
            if (frameDecoder != null) { frameDecoder.close(); frameDecoder = null; }
        }
    }

    /** Convert thread: poll raw images, palette-convert, push to map queue. */
    private void convertLoop() {
        while (running || !rawQueue.isEmpty()) {
            try {
                BufferedImage img = rawQueue.poll(50, TimeUnit.MILLISECONDS);
                if (img == null) continue;

                long t0 = System.nanoTime();
                
                // Pluggable Overlay Rendering Phase
                java.awt.Graphics2D g2d = img.createGraphics();
                long fIndex = frameCount.get();
                for (OverlayRenderer r : overlayRegistry.getAll()) {
                    try {
                        r.render(g2d, screen.getName(), fIndex);
                    } catch (Exception e) {
                        logger.warning("[TheBigScreen™] Overlay error on " + screen.getName() + ": " + e.getMessage());
                    }
                }
                g2d.dispose();

                byte[][][] maps = palette.convertFrame(img, screen.getWidthMaps(), screen.getHeightMaps());
                totalConvertNs.addAndGet(System.nanoTime() - t0);

                // Build dirty map: only include slots that changed vs last frame
                byte[][][] dirty = buildDirtyMap(maps);

                // Non-blocking offer — drop if send thread is behind
                if (!mapQueue.offer(dirty)) {
                    mapQueue.poll(); // discard head
                    mapQueue.offer(dirty);
                }

                // Update last-frame cache for all slots
                for (int row = 0; row < screen.getHeightMaps(); row++) {
                    for (int col = 0; col < screen.getWidthMaps(); col++) {
                        screen.setLastFrameBytes(row, col, maps[row][col]);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warning("[TheBigScreen™] Convert error: " + e.getMessage());
            }
        }
    }

    /** Send thread: poll dirty map arrays, dispatch packets to nearby players. */
    private void sendLoop() {
        while (running || !mapQueue.isEmpty()) {
            try {
                byte[][][] dirty = mapQueue.poll(50, TimeUnit.MILLISECONDS);
                if (dirty == null) continue;

                long t0 = System.nanoTime();
                Collection<Player> nearby = nearbyPlayers();
                // Ensure newly-in-range players have frames spawned
                for (Player p : nearby) {
                    if (spawnedPlayers.add(p.getUniqueId())) {
                        sender.spawnScreenForPlayer(screen, p);
                    }
                }
                sender.sendFrameUpdate(nearby, screen, dirty);
                totalSendNs.addAndGet(System.nanoTime() - t0);

                long count = frameCount.incrementAndGet();
                logMetricsIfDue(count);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warning("[TheBigScreen™] Send error: " + e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compares new map data against the cached last frame.
     * Returns a [row][col] array where null = unchanged (skip packet).
     */
    private byte[][][] buildDirtyMap(byte[][][] newMaps) {
        byte[][][] dirty = new byte[screen.getHeightMaps()][screen.getWidthMaps()][];
        for (int row = 0; row < screen.getHeightMaps(); row++) {
            for (int col = 0; col < screen.getWidthMaps(); col++) {
                byte[] prev = screen.getLastFrameBytes(row, col);
                byte[] next = newMaps[row][col];
                if (prev == null || !Arrays.equals(prev, next)) {
                    dirty[row][col] = next;  // changed
                }
                // else null → PacketSender skips this slot
            }
        }
        return dirty;
    }

    /**
     * Called on the main thread every second to refresh the nearby-player list.
     * This is the ONLY place Bukkit world API is called, keeping it thread-safe.
     */
    private void refreshNearbyPlayers() {
        Location anchor = screen.getAnchorLocation();
        if (anchor.getWorld() == null) {
            nearbyPlayersCache = java.util.Collections.emptyList();
            return;
        }
        double radiusSq = renderRadiusBlocks * renderRadiusBlocks;
        nearbyPlayersCache = anchor.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(anchor) <= radiusSq)
                .collect(Collectors.toList());
    }

    /** Returns the cached list of nearby players (updated on main thread). */
    private java.util.List<Player> nearbyPlayers() {
        return nearbyPlayersCache;
    }

    /**
     * Attempts to open the grabber, retrying up to {@code reconnectAttempts} times.
     *
     * @return {@code true} if the grabber opened successfully.
     */
    private boolean openGrabberWithRetry() {
        int attempt = 0;
        while (attempt < reconnectAttempts && running) {
            attempt++;
            logger.info("[TheBigScreen™] Connecting to stream (attempt " + attempt
                    + "/" + reconnectAttempts + "): " + streamUrl);
            try {
                FrameDecoder fd = new FrameDecoder(streamUrl,
                        screen.getWidthMaps()  * 128,
                        screen.getHeightMaps() * 128,
                        targetFps, logger);
                fd.start();
                frameDecoder = fd;
                return true;
            } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
                logger.warning("[TheBigScreen™] Connection failed: " + e.getMessage());
                if (attempt < reconnectAttempts) {
                    try { Thread.sleep(reconnectDelayMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        logger.severe("[TheBigScreen™] Giving up on stream for '" + screen.getName()
                + "' after " + reconnectAttempts + " attempts.");
        return false;
    }

    /** Logs avg pipeline latencies every 60 s. */
    private void logMetricsIfDue(long count) {
        long now = System.currentTimeMillis();
        if (now - lastMetricLog < 60_000) return;
        lastMetricLog = now;
        if (count == 0) return;
        logger.info(String.format(
                "[TheBigScreen™] '%s' stats — frames: %d | decode: %.1f ms | convert: %.1f ms | send: %.1f ms",
                screen.getName(), count,
                totalDecodeNs.get()  / (double) count / 1e6,
                totalConvertNs.get() / (double) count / 1e6,
                totalSendNs.get()    / (double) count / 1e6));
    }
}
