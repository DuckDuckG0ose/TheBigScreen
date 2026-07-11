package club.mineclub.watchparty.screen;

import club.mineclub.watchparty.model.VideoScreen;
import club.mineclub.watchparty.media.YouTubeResolver;
import club.mineclub.watchparty.packet.FakeEntityIdAllocator;
import club.mineclub.watchparty.packet.PacketSender;
import club.mineclub.watchparty.render.MapPaletteConverter;
import club.mineclub.watchparty.render.OverlayRegistry;
import club.mineclub.watchparty.video.VideoSession;
import club.mineclub.watchparty.media.MediaCacheManager;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;

import java.io.File;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * High-level orchestrator for video screens.
 *
 * <p>Coordinates: {@link ScreenRegistry} (persistence),
 * {@link PacketSender} (rendering), and {@link VideoSession} (streaming).
 */
public final class ScreenManager {

    // How far the player can look to anchor a screen (blocks)
    private static final double RAY_DISTANCE = 10.0;
    // Next fake map ID counter — starts at fakeMapIdStart from config
    private int nextFakeMapId;

    private final ScreenRegistry       registry;
    private final PacketSender         packetSender;
    private final MapPaletteConverter  palette;
    private final FakeEntityIdAllocator entityIds;
    private final Plugin               plugin;
    private final Logger               logger;

    // Active streaming sessions: screen name → session
    private final ConcurrentHashMap<String, VideoSession> sessions = new ConcurrentHashMap<>();
    private final OverlayRegistry overlayRegistry;

    // Config values (volatile so reloadConfig can update them live)
    private volatile int    targetFps;
    private final int    reconnectAttempts;
    private final long   reconnectDelayMs;
    private volatile double renderRadiusBlocks;
    private volatile String placeholderImagePath;

    private final MediaCacheManager mediaCacheManager;
    private final YouTubeResolver   youTubeResolver;

    // Tracks in-flight YouTube resolutions so a late callback can't start a
    // session on a screen that was stopped/deleted while resolving.
    private final ConcurrentHashMap<String, String> pendingYouTube = new ConcurrentHashMap<>();

    public ScreenManager(
            ScreenRegistry registry,
            PacketSender packetSender,
            MapPaletteConverter palette,
            FakeEntityIdAllocator entityIds,
            Plugin plugin,
            int fakeMapIdStart,
            int targetFps,
            int reconnectAttempts,
            long reconnectDelayMs,
            double renderRadiusBlocks,
            OverlayRegistry overlayRegistry,
            MediaCacheManager mediaCacheManager,
            String placeholderImagePath,
            YouTubeResolver youTubeResolver) {
        this.registry              = registry;
        this.packetSender          = packetSender;
        this.palette               = palette;
        this.entityIds             = entityIds;
        this.plugin                = plugin;
        this.logger                = plugin.getLogger();
        this.nextFakeMapId         = fakeMapIdStart;
        this.targetFps             = targetFps;
        this.reconnectAttempts     = reconnectAttempts;
        this.reconnectDelayMs      = reconnectDelayMs;
        this.renderRadiusBlocks    = renderRadiusBlocks;
        this.overlayRegistry       = overlayRegistry;
        this.mediaCacheManager     = mediaCacheManager;
        this.placeholderImagePath  = placeholderImagePath;
        this.youTubeResolver       = youTubeResolver;
    }

    /** Updates live-reloadable config values without restarting sessions. */
    public void applyReloadedConfig(int targetFps, double renderRadiusBlocks, String placeholderImagePath) {
        this.targetFps            = targetFps;
        this.renderRadiusBlocks   = renderRadiusBlocks;
        this.placeholderImagePath = placeholderImagePath;

        // Immediately push the new placeholder (or black screen) to all online players for idle screens
        for (VideoScreen screen : registry.all()) {
            if (screen.getState() != VideoScreen.State.RUNNING) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    sendPlaceholder(screen, player);
                }
            }
        }
    }

    // ── Screen creation ───────────────────────────────────────────────────────

    /**
     * Creates a new screen anchored at the block face the player is looking at.
     *
     * @param player     Creator (used for ray-cast and feedback messages).
     * @param name       Unique screen name.
     * @param widthMaps  Grid width in maps (each = 128 px).
     * @param heightMaps Grid height in maps.
     * @return The created screen, or {@code null} if the ray-cast failed.
     */
    public VideoScreen createScreen(Player player, String name, int widthMaps, int heightMaps) {
        if (registry.exists(name)) {
            player.sendMessage("§c[TheBigScreen™] A screen named '" + name + "' already exists.");
            return null;
        }

        // Ray-cast to find the target block face
        RayTraceResult ray = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), RAY_DISTANCE);
        if (ray == null || ray.getHitBlock() == null) {
            player.sendMessage("§c[TheBigScreen™] Look at a block within " + (int) RAY_DISTANCE + " blocks.");
            return null;
        }

        Block hitBlock  = ray.getHitBlock();
        BlockFace face  = ray.getHitBlockFace();
        if (face == null || face == BlockFace.UP || face == BlockFace.DOWN) {
            // Vertical faces not currently supported for anchor orientation
            player.sendMessage("§c[TheBigScreen™] Look at a vertical wall face (N/S/E/W).");
            return null;
        }

        // Anchor = the block one step out from the hit face
        // (so the item frames sit in front of the wall, not inside it)
        Location anchor = hitBlock.getLocation().clone()
                .add(face.getModX(), face.getModY(), face.getModZ());
        anchor.setWorld(player.getWorld());

        // Allocate IDs for all slots
        int[][] fakeMapIds     = new int[heightMaps][widthMaps];
        int[][] fakeEntityIds  = new int[heightMaps][widthMaps];
        UUID[][] fakeUuids     = new UUID[heightMaps][widthMaps];
        for (int r = 0; r < heightMaps; r++) {
            for (int c = 0; c < widthMaps; c++) {
                fakeMapIds[r][c]    = nextFakeMapId++;
                fakeEntityIds[r][c] = entityIds.allocate();
                fakeUuids[r][c]     = UUID.randomUUID();
            }
        }

        VideoScreen screen = new VideoScreen(name, anchor, face, widthMaps, heightMaps,
                fakeMapIds, fakeEntityIds, fakeUuids);
        registry.register(screen);

        // Show a static test pattern so the player can verify placement
        sendTestPattern(screen, player);

        player.sendMessage("§a[TheBigScreen™] Screen '" + name + "' created ("
                + widthMaps + "×" + heightMaps + " maps) anchored at "
                + anchor.getBlockX() + "," + anchor.getBlockY() + "," + anchor.getBlockZ()
                + " facing " + face.name() + ".");
        return screen;
    }

    // ── Stream control ────────────────────────────────────────────────────────

    /**
     * Starts an RTMP stream for the named screen.
     *
     * @return {@code true} on success.
     */
    public boolean startStream(Player sender, String name, String url) {
        Optional<VideoScreen> opt = registry.get(name);
        if (opt.isEmpty()) {
            sender.sendMessage("§c[TheBigScreen™] No screen named '" + name + "'.");
            return false;
        }
        VideoScreen screen = opt.get();
        if (screen.getState() == VideoScreen.State.RUNNING) {
            sender.sendMessage("§c[TheBigScreen™] '" + name + "' is already streaming.");
            return false;
        }
        if (!palette.isLutReady()) {
            sender.sendMessage("§e[TheBigScreen™] Palette LUT is still building — please wait a few seconds and retry.");
            return false;
        }

        screen.setCurrentStreamUrl(url);

        // YouTube links: resolve to a direct stream URL via yt-dlp first.
        if (youTubeResolver.isEnabled() && YouTubeResolver.isYouTubeUrl(url)) {
            sender.sendMessage("§e[TheBigScreen™] Resolving YouTube link, this may take a moment…");
            pendingYouTube.put(name, url);
            youTubeResolver.resolve(url).thenAccept(resolved -> {
                pendingYouTube.remove(name, url);
                if (resolved == null) {
                    sender.sendMessage("§c[TheBigScreen™] Could not resolve YouTube link. "
                            + "Is yt-dlp installed and on PATH (or configured)?");
                    if (screen.getCurrentStreamUrl() != null
                            && screen.getCurrentStreamUrl().equals(url)) {
                        screen.setCurrentStreamUrl(null);
                    }
                    return;
                }
                // Only start if this screen is still waiting on THIS url
                // (not stopped, deleted, or pointed at another stream).
                String current = screen.getCurrentStreamUrl();
                if (current != null && current.equals(url)
                        && screen.getState() != VideoScreen.State.RUNNING) {
                    sender.sendMessage("§a[TheBigScreen™] YouTube link resolved! Starting playback for '" + name + "'.");
                    // For audio we hand Lavaplayer the original YouTube URL so it
                    // can resolve the audio track itself.
                    startSessionForScreen(screen, resolved, url);
                }
            });
            return true;
        }

        if (mediaCacheManager.isStaticMedia(url)) {
            sender.sendMessage("§e[TheBigScreen™] Downloading static media to cache... this might take a moment.");
            mediaCacheManager.downloadMediaAsync(url).thenAccept(file -> {
                if (file == null || !file.exists()) {
                    sender.sendMessage("§c[TheBigScreen™] Failed to download media for '" + name + "'.");
                    screen.setCurrentStreamUrl(null);
                    return;
                }

                // Only start if the screen hasn't been deleted or another stream started while downloading
                if (screen.getCurrentStreamUrl() != null && screen.getCurrentStreamUrl().equals(url)) {
                    sender.sendMessage("§a[TheBigScreen™] Download complete! Starting playback for '" + name + "'.");
                    // Video plays the cached file; audio plays the same file path.
                    startSessionForScreen(screen, file.getAbsolutePath(), file.getAbsolutePath());
                }
            });
        } else {
            sender.sendMessage("§a[TheBigScreen™] Started stream for '" + name + "' ← " + url);
            startSessionForScreen(screen, url, url);
        }

        return true;
    }

    /**
     * Starts the video session and, if {@code screen} is the one selected for
     * Discord audio, begins playing its audio track.  {@code videoSource} is what
     * the in-game renderer decodes; {@code audioSource} is what Lavaplayer loads
     * (for YouTube this is the original URL, for local/static media the file path).
     */
    private void startSessionForScreen(VideoScreen screen, String videoSource, String audioSource) {
        startVideoSession(screen, videoSource);
        // Audio bridge removed; audioSource is currently unused
    }

    private void startVideoSession(VideoScreen screen, String sourceUrl) {
        VideoSession session = new VideoSession(screen, sourceUrl, palette, packetSender,
                logger, targetFps, reconnectAttempts, reconnectDelayMs, renderRadiusBlocks, overlayRegistry, plugin);
        sessions.put(screen.getName(), session);
        session.start();
    }

    /**
     * Stops the stream for the named screen.
     */
    public boolean stopStream(Player sender, String name) {
        pendingYouTube.remove(name);
        VideoSession session = sessions.remove(name);
        if (session == null) {
            sender.sendMessage("§c[TheBigScreen™] '" + name + "' is not streaming.");
            return false;
        }
        session.stop();
        // Send placeholder or blank map data for all online players instead of destroying the screen
        Optional<VideoScreen> opt = registry.get(name);
        opt.ifPresent(s -> {
            s.invalidateFrameCache();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                sendPlaceholder(s, player);
            }
        });
        sender.sendMessage("§a[TheBigScreen™] Stopped stream for '" + name + "'.");
        return true;
    }

    /**
     * Deletes a screen entirely (stops stream + removes from registry).
     */
    public boolean deleteScreen(Player sender, String name) {
        pendingYouTube.remove(name);
        stopStream(sender, name); // no-op if not running
        if (!registry.exists(name)) {
            sender.sendMessage("§c[TheBigScreen™] No screen named '" + name + "'.");
            return false;
        }
        Optional<VideoScreen> opt = registry.get(name);
        opt.ifPresent(s -> packetSender.destroyScreen(s, plugin.getServer().getOnlinePlayers()));
        registry.unregister(name);
        sender.sendMessage("§a[TheBigScreen™] Screen '" + name + "' deleted.");
        return true;
    }

    /**
     * Deletes the cached media file for the given screen's current stream URL.
     */
    public void clearScreenCache(CommandSender sender, String name) {
        Optional<VideoScreen> opt = registry.get(name);
        if (opt.isEmpty()) {
            sender.sendMessage("§c[TheBigScreen™] No screen named '" + name + "'.");
            return;
        }
        VideoScreen screen = opt.get();
        String url = screen.getCurrentStreamUrl();
        if (url == null || !mediaCacheManager.isStaticMedia(url)) {
            sender.sendMessage("§e[TheBigScreen™] No cached media to clear for '" + name + "'.");
            return;
        }
        mediaCacheManager.clearCacheForUrl(url);
        sender.sendMessage("§a[TheBigScreen™] Cleared cached media for '" + name + "'.");
    }

    // ── Player events ─────────────────────────────────────────────────────────

    /**
     * Called when a player joins or enters a world.
     * Re-sends spawn + metadata packets for all running screens visible to the player.
     */
    public void sendInitialState(Player player) {
        for (VideoScreen screen : registry.all()) {
            if (screen.getState() != VideoScreen.State.RUNNING) {
                // Send the static structure so players know the screen exists
                packetSender.spawnScreenForPlayer(screen, player);
                sendPlaceholder(screen, player);
                continue;
            }
            VideoSession session = sessions.get(screen.getName());
            if (session != null) {
                session.onPlayerEnterRange(player);
            }
        }
    }

    private void sendPlaceholder(VideoScreen screen, Player player) {
        boolean sent = false;
        if (placeholderImagePath != null && !placeholderImagePath.isEmpty()) {
            File file = new File(plugin.getDataFolder(), placeholderImagePath);
            if (!file.exists()) {
                file = new File(placeholderImagePath);
            }
            if (file.exists()) {
                try {
                    BufferedImage img = javax.imageio.ImageIO.read(file);
                    if (img != null) {
                        int pw = screen.getWidthMaps() * 128;
                        int ph = screen.getHeightMaps() * 128;
                        BufferedImage resized = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = resized.createGraphics();
                        g.drawImage(img, 0, 0, pw, ph, null);
                        g.dispose();

                        byte[][][] maps = palette.convertFrame(resized, screen.getWidthMaps(), screen.getHeightMaps());
                        for (int row = 0; row < screen.getHeightMaps(); row++) {
                            for (int col = 0; col < screen.getWidthMaps(); col++) {
                                packetSender.sendMapData(player,
                                        screen.getFakeMapIds()[row][col], maps[row][col]);
                            }
                        }
                        sent = true;
                    }
                } catch (Exception e) {
                    logger.warning("[WatchParty] Failed to load placeholder image: " + e.getMessage());
                }
            }
        }

        if (!sent) {
            byte[] initData = new byte[128 * 128];
            java.util.Arrays.fill(initData, (byte) 119);
            for (int row = 0; row < screen.getHeightMaps(); row++) {
                for (int col = 0; col < screen.getWidthMaps(); col++) {
                    packetSender.sendMapData(player,
                            screen.getFakeMapIds()[row][col], initData);
                }
            }
        }
    }

    /**
     * Called when a player quits or changes world.
     * Removes them from session tracking and destroys fake entities client-side.
     */
    public void handlePlayerLeave(Player player) {
        for (Map.Entry<String, VideoSession> e : sessions.entrySet()) {
            e.getValue().onPlayerLeaveRange(player);
        }
    }

    /** Stops all active sessions — call during plugin disable. */
    public void stopAll() {
        for (Map.Entry<String, VideoSession> e : new HashMap<>(sessions).entrySet()) {
            e.getValue().stop();
        }
        sessions.clear();
    }

    // ── Test pattern ──────────────────────────────────────────────────────────

    /**
     * Sends a colourful diagonal gradient test image to a single player
     * so they can verify screen geometry without a live stream.
     */
    private void sendTestPattern(VideoScreen screen, Player player) {
        if (!palette.isLutReady()) {
            // LUT still building — skip test pattern, not critical
            return;
        }
        int pw = screen.getWidthMaps()  * 128;
        int ph = screen.getHeightMaps() * 128;
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // Diagonal rainbow gradient
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                float hue = ((float)(x + y) / (pw + ph));
                img.setRGB(x, y, Color.HSBtoRGB(hue, 0.9f, 0.85f));
            }
        }
        g.dispose();

        byte[][][] maps = palette.convertFrame(img, screen.getWidthMaps(), screen.getHeightMaps());
        // Send as a "fully dirty" frame
        packetSender.spawnScreenForPlayer(screen, player);
        // Override the blank map data with the test pattern
        for (int row = 0; row < screen.getHeightMaps(); row++) {
            for (int col = 0; col < screen.getWidthMaps(); col++) {
                packetSender.sendMapData(player,
                        screen.getFakeMapIds()[row][col], maps[row][col]);
            }
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ScreenRegistry getRegistry() { return registry; }
}
