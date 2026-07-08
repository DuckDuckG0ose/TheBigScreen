package club.mineclub.watchparty.screen;

import club.mineclub.watchparty.model.VideoScreen;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory registry of all defined {@link VideoScreen}s, backed by a JSON
 * file ({@code screens.json} in the plugin data folder) for persistence across
 * restarts.
 *
 * <p>Screens are stored without their runtime state (last-frame bytes, active
 * sessions) — only the geometry and ID allocations are persisted.  On load,
 * all screens start in {@link VideoScreen.State#IDLE}.
 *
 * <p>Thread-safety: the in-memory map is a {@link ConcurrentHashMap}; file
 * writes are synchronised on {@code this}.
 */
public final class ScreenRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConcurrentHashMap<String, VideoScreen> screens = new ConcurrentHashMap<>();
    private final Path persistPath;
    private final Logger logger;

    public ScreenRegistry(Plugin plugin) {
        this.persistPath = plugin.getDataFolder().toPath().resolve("screens.json");
        this.logger      = plugin.getLogger();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Load persisted screens from disk. Call during plugin enable. */
    public void load() {
        if (!Files.exists(persistPath)) {
            logger.info("[TheBigScreen™] No screens.json found — starting with empty registry.");
            return;
        }
        try (Reader reader = Files.newBufferedReader(persistPath)) {
            JsonArray arr = GSON.fromJson(reader, JsonArray.class);
            if (arr == null) return;
            int count = 0;
            for (JsonElement el : arr) {
                try {
                    VideoScreen screen = deserialize(el.getAsJsonObject());
                    screens.put(screen.getName(), screen);
                    count++;
                } catch (Exception e) {
                    logger.warning("[TheBigScreen™] Skipping corrupt screen entry: " + e.getMessage());
                }
            }
            logger.info("[TheBigScreen™] Loaded " + count + " screen(s) from disk.");
        } catch (IOException e) {
            logger.severe("[TheBigScreen™] Failed to read screens.json: " + e.getMessage());
        }
    }

    /** Persist current registry to disk. Call after any create/delete. */
    public synchronized void save() {
        try {
            Files.createDirectories(persistPath.getParent());
            JsonArray arr = new JsonArray();
            for (VideoScreen screen : screens.values()) {
                arr.add(serialize(screen));
            }
            try (Writer writer = Files.newBufferedWriter(persistPath)) {
                GSON.toJson(arr, writer);
            }
        } catch (IOException e) {
            logger.severe("[TheBigScreen™] Failed to write screens.json: " + e.getMessage());
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public void register(VideoScreen screen) {
        screens.put(screen.getName(), screen);
        save();
    }

    public void unregister(String name) {
        screens.remove(name);
        save();
    }

    public Optional<VideoScreen> get(String name) {
        return Optional.ofNullable(screens.get(name));
    }

    public boolean exists(String name) {
        return screens.containsKey(name);
    }

    public Collection<VideoScreen> all() {
        return Collections.unmodifiableCollection(screens.values());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(screens.keySet());
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private static JsonObject serialize(VideoScreen s) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name",        s.getName());
        obj.addProperty("world",       s.getAnchorLocation().getWorld().getName());
        obj.addProperty("x",           s.getAnchorLocation().getBlockX());
        obj.addProperty("y",           s.getAnchorLocation().getBlockY());
        obj.addProperty("z",           s.getAnchorLocation().getBlockZ());
        obj.addProperty("facing",      s.getFacing().name());
        obj.addProperty("widthMaps",   s.getWidthMaps());
        obj.addProperty("heightMaps",  s.getHeightMaps());

        // Fake map IDs
        JsonArray mapIds = new JsonArray();
        for (int[] row : s.getFakeMapIds()) {
            JsonArray rowArr = new JsonArray();
            for (int id : row) rowArr.add(id);
            mapIds.add(rowArr);
        }
        obj.add("fakeMapIds", mapIds);

        // Fake entity IDs
        JsonArray entIds = new JsonArray();
        for (int[] row : s.getFakeEntityIds()) {
            JsonArray rowArr = new JsonArray();
            for (int id : row) rowArr.add(id);
            entIds.add(rowArr);
        }
        obj.add("fakeEntityIds", entIds);

        // UUIDs
        JsonArray uuids = new JsonArray();
        for (UUID[] row : s.getFakeEntityUuids()) {
            JsonArray rowArr = new JsonArray();
            for (UUID u : row) rowArr.add(u.toString());
            uuids.add(rowArr);
        }
        obj.add("fakeEntityUuids", uuids);

        return obj;
    }

    private static VideoScreen deserialize(JsonObject obj) {
        String name     = obj.get("name").getAsString();
        String worldName= obj.get("world").getAsString();
        int bx          = obj.get("x").getAsInt();
        int by          = obj.get("y").getAsInt();
        int bz          = obj.get("z").getAsInt();
        BlockFace facing= BlockFace.valueOf(obj.get("facing").getAsString());
        int w           = obj.get("widthMaps").getAsInt();
        int h           = obj.get("heightMaps").getAsInt();

        World world = Bukkit.getWorld(worldName);
        if (world == null) throw new IllegalArgumentException("World not found: " + worldName);
        Location anchor = new Location(world, bx, by, bz);

        // Map IDs
        JsonArray mapArr = obj.getAsJsonArray("fakeMapIds");
        int[][] fakeMapIds = new int[h][w];
        for (int r = 0; r < h; r++) {
            JsonArray row = mapArr.get(r).getAsJsonArray();
            for (int c = 0; c < w; c++) {
                fakeMapIds[r][c] = row.get(c).getAsInt();
            }
        }

        // Entity IDs
        JsonArray entArr = obj.getAsJsonArray("fakeEntityIds");
        int[][] fakeEntityIds = new int[h][w];
        for (int r = 0; r < h; r++) {
            JsonArray row = entArr.get(r).getAsJsonArray();
            for (int c = 0; c < w; c++) {
                fakeEntityIds[r][c] = row.get(c).getAsInt();
            }
        }

        // UUIDs
        JsonArray uuidArr = obj.getAsJsonArray("fakeEntityUuids");
        UUID[][] fakeEntityUuids = new UUID[h][w];
        for (int r = 0; r < h; r++) {
            JsonArray row = uuidArr.get(r).getAsJsonArray();
            for (int c = 0; c < w; c++) {
                fakeEntityUuids[r][c] = UUID.fromString(row.get(c).getAsString());
            }
        }

        return new VideoScreen(name, anchor, facing, w, h,
                fakeMapIds, fakeEntityIds, fakeEntityUuids);
    }
}
