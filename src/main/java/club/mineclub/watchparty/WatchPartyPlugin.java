package club.mineclub.watchparty;

import club.mineclub.watchparty.command.VideoScreenCommand;
import club.mineclub.watchparty.listener.PlayerConnectionListener;
import club.mineclub.watchparty.packet.FakeEntityIdAllocator;
import club.mineclub.watchparty.packet.PacketSender;
import club.mineclub.watchparty.render.MapPaletteConverter;
import club.mineclub.watchparty.screen.ScreenManager;
import club.mineclub.watchparty.screen.ScreenRegistry;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

/**
 * Main entry point for the WatchParty plugin.
 *
 * <h2>Enable sequence</h2>
 * <ol>
 *   <li>Save default config.</li>
 *   <li>Read all tunables from config.yml.</li>
 *   <li>Check for fake-map-ID collisions with real world data (emit warning).</li>
 *   <li>Build the RGB→palette LUT asynchronously (or synchronously if configured).</li>
 *   <li>Load persisted screens from screens.json.</li>
 *   <li>Register commands and listeners.</li>
 * </ol>
 *
 * <h2>Disable sequence</h2>
 * <ol>
 *   <li>Stop all active streaming sessions.</li>
 *   <li>Save screen registry to disk.</li>
 * </ol>
 */
public final class WatchPartyPlugin extends JavaPlugin {

    private MapPaletteConverter  palette;
    private ScreenRegistry       registry;
    private ScreenManager        manager;
    private PacketSender         packetSender;
    private club.mineclub.watchparty.render.OverlayRegistry overlayRegistry;
    private club.mineclub.watchparty.media.MediaCacheManager mediaCacheManager;

    @Override
    public void onEnable() {
        // ── Config ────────────────────────────────────────────────────────────
        saveDefaultConfig();
        int    fakeMapIdStart     = getConfig().getInt("fakeMapIdStart", 10_000);
        int    targetFps          = clamp(getConfig().getInt("targetFps", 15), 1, 30);
        int    maxQueueDepth      = clamp(getConfig().getInt("maxQueueDepth", 2), 1, 8);
        int    reconnectAttempts  = getConfig().getInt("reconnectAttempts", 5);
        long   reconnectDelayMs   = getConfig().getLong("reconnectDelayMs", 3_000L);
        boolean lutBuildOnStartup = getConfig().getBoolean("lutBuildOnStartup", true);
        double renderRadiusBlocks = getConfig().getDouble("renderRadiusBlocks", 64.0);
        boolean hideFrameBorder   = getConfig().getBoolean("hideFrameBorder", true);
        String placeholderImagePath = getConfig().getString("placeholderImage", "");

        getLogger().info("TheBigScreen™ enabling — fakeMapIdStart=" + fakeMapIdStart
                + ", targetFps=" + targetFps + ", renderRadius=" + (int) renderRadiusBlocks + " blocks");

        // ── Map ID collision check ─────────────────────────────────────────────
        checkMapIdCollisions(fakeMapIdStart, fakeMapIdStart + 9_999);

        // ── Palette ───────────────────────────────────────────────────────────
        palette = new MapPaletteConverter(getLogger());
        if (lutBuildOnStartup) {
            palette.buildLutAsync();
        }

        // ── Packet sender ─────────────────────────────────────────────────────
        packetSender = new PacketSender(getLogger(), hideFrameBorder);

        // ── Registry ──────────────────────────────────────────────────────────
        registry = new ScreenRegistry(this);
        registry.load();

        // ── Overlay Registry ──────────────────────────────────────────────────
        overlayRegistry = new club.mineclub.watchparty.render.OverlayRegistry();
        // No overlays registered by default — add your own via overlayRegistry.register()

        // ── Media Cache ───────────────────────────────────────────────────────
        mediaCacheManager = new club.mineclub.watchparty.media.MediaCacheManager(this);
        mediaCacheManager.clearCache(); // Clear cache on server restart

        // ── Manager ───────────────────────────────────────────────────────────
        FakeEntityIdAllocator entityIdAllocator = new FakeEntityIdAllocator();
        manager = new ScreenManager(
                registry, packetSender, palette, entityIdAllocator,
                this, fakeMapIdStart, targetFps,
                reconnectAttempts, reconnectDelayMs, renderRadiusBlocks,
                overlayRegistry, mediaCacheManager, placeholderImagePath);

        // ── Commands ──────────────────────────────────────────────────────────
        VideoScreenCommand cmdHandler = new VideoScreenCommand(manager, this);
        Objects.requireNonNull(getCommand("videoscreen")).setExecutor(cmdHandler);
        Objects.requireNonNull(getCommand("videoscreen")).setTabCompleter(cmdHandler);

        // ── Listeners ─────────────────────────────────────────────────────────
        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(manager, this), this);

        getLogger().info("TheBigScreen™ enabled successfully. Developed by WmWi. "
                + "Use /videoscreen create <name> <w> <h> to get started.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stopAll();
        }
        if (registry != null) {
            registry.save();
        }
        getLogger().info("TheBigScreen™ disabled. All sessions stopped.");
    }

    /** Reloads config values that can be changed at runtime (targetFps, renderRadiusBlocks). */
    public void reloadPluginConfig(org.bukkit.command.CommandSender sender) {
        reloadConfig();
        int targetFps          = clamp(getConfig().getInt("targetFps", 15), 1, 30);
        double renderRadius    = getConfig().getDouble("renderRadiusBlocks", 64.0);
        String placeholder     = getConfig().getString("placeholderImage", "");
        if (manager != null) {
            manager.applyReloadedConfig(targetFps, renderRadius, placeholder);
        }
        sender.sendMessage("§a[TheBigScreen™] Config reloaded — targetFps=" + targetFps
                + ", renderRadius=" + (int) renderRadius + " blocks.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Scans all worlds' data folders for {@code map_<id>.dat} files whose
     * IDs fall within the reserved fake-map range.  Emits a WARNING if any
     * are found so the operator can adjust {@code fakeMapIdStart} in config.yml.
     */
    private void checkMapIdCollisions(int rangeStart, int rangeEnd) {
        int collisions = 0;
        for (World world : getServer().getWorlds()) {
            File dataDir = new File(world.getWorldFolder(), "data");
            if (!dataDir.isDirectory()) continue;
            File[] files = dataDir.listFiles((dir, name) ->
                    name.startsWith("map_") && name.endsWith(".dat"));
            if (files == null) continue;
            for (File f : files) {
                String idStr = f.getName().replace("map_", "").replace(".dat", "");
                try {
                    int id = Integer.parseInt(idStr);
                    if (id >= rangeStart && id <= rangeEnd) {
                        collisions++;
                        if (collisions == 1) {
                            getLogger().warning("[TheBigScreen™] ⚠ Map ID collision detected! "
                                    + "Real map data exists in the reserved range "
                                    + rangeStart + "–" + rangeEnd + ".");
                            getLogger().warning("[TheBigScreen™]   First collision: "
                                    + world.getName() + "/data/" + f.getName());
                        }
                    }
                } catch (NumberFormatException ignored) { /* non-numeric — skip */ }
            }
        }
        if (collisions > 0) {
            getLogger().warning("[TheBigScreen™] Total collisions found: " + collisions
                    + ". Raise 'fakeMapIdStart' in config.yml to avoid visual glitches.");
        } else {
            getLogger().info("[TheBigScreen™] Map ID range " + rangeStart + "–" + rangeEnd
                    + " is clear of real map data. ✓");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── Accessors (for tests / future API) ───────────────────────────────────

    public ScreenManager       getScreenManager()  { return manager; }
    public MapPaletteConverter getPalette()        { return palette; }
    public ScreenRegistry      getScreenRegistry() { return registry; }
    public club.mineclub.watchparty.render.OverlayRegistry getOverlayRegistry() { return overlayRegistry; }
}
