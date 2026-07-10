package club.mineclub.watchparty.packet;

import club.mineclub.watchparty.model.VideoScreen;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Sends ProtocolLib packets to display a {@link VideoScreen} on clients.
 *
 * <h2>Packet sequence per screen per player</h2>
 * <ol>
 *   <li>{@code SPAWN_ENTITY} — create a fake item frame at each grid slot.</li>
 *   <li>{@code ENTITY_METADATA} — set the held item to a filled-map with the
 *       corresponding fake map ID; optionally mark the frame invisible.</li>
 *   <li>{@code MAP} — paint the initial (or updated) pixel data onto each map.</li>
 * </ol>
 *
 * On stop/delete or player leave, {@code ENTITY_DESTROY} removes all fake frames.
 *
 * <h2>Thread-safety</h2>
 * All {@code send*} methods may be called from any thread; ProtocolLib's
 * {@code sendServerPacket} is thread-safe.
 */
public final class PacketSender {

    // Item frame facing → yaw (degrees) for the SPAWN_ENTITY packet.
    // Minecraft sends the entity's yaw as the facing direction of the frame.
    private static final java.util.Map<BlockFace, Float> FACE_YAW = new java.util.EnumMap<>(BlockFace.class);
    // Item frame facing → pitch
    private static final java.util.Map<BlockFace, Float> FACE_PITCH = new java.util.EnumMap<>(BlockFace.class);
    // Item frame facing → attachment direction byte (used in older metadata; kept for reference)
    private static final java.util.Map<BlockFace, Integer> FACE_DIRECTION = new java.util.EnumMap<>(BlockFace.class);

    static {
        // Yaw values for a standing entity looking "out" of each face
        FACE_YAW.put(BlockFace.SOUTH, 0f);
        FACE_YAW.put(BlockFace.WEST,  90f);
        FACE_YAW.put(BlockFace.NORTH, 180f);
        FACE_YAW.put(BlockFace.EAST,  270f);
        FACE_YAW.put(BlockFace.UP,    0f);
        FACE_YAW.put(BlockFace.DOWN,  0f);

        FACE_PITCH.put(BlockFace.SOUTH, 0f);
        FACE_PITCH.put(BlockFace.WEST,  0f);
        FACE_PITCH.put(BlockFace.NORTH, 0f);
        FACE_PITCH.put(BlockFace.EAST,  0f);
        FACE_PITCH.put(BlockFace.UP,    -90f);
        FACE_PITCH.put(BlockFace.DOWN,   90f);

        // Attachment direction for entity metadata (1.13+ Direction 3D data values)
        // 0=down, 1=up, 2=north, 3=south, 4=west, 5=east
        FACE_DIRECTION.put(BlockFace.DOWN,  0);
        FACE_DIRECTION.put(BlockFace.UP,    1);
        FACE_DIRECTION.put(BlockFace.NORTH, 2);
        FACE_DIRECTION.put(BlockFace.SOUTH, 3);
        FACE_DIRECTION.put(BlockFace.WEST,  4);
        FACE_DIRECTION.put(BlockFace.EAST,  5);
    }

    private final ProtocolManager protocol;
    private final Logger           logger;
    private final boolean          hideFrameBorder;

    // Global outbound bandwidth cap (bytes/sec). 0 = unlimited. Shared across
    // all screens so one giant screen cannot starve every player's connection.
    private final long             bandwidthLimitBps;

    // ── Reflection caches (resolved once, reused for every packet) ────────────
    // Selected ClientboundMapItemDataPacket constructor.
    private volatile java.lang.reflect.Constructor<?> cachedMapCtor;
    // Selected MapPatch constructor + whether it takes (x,z,w,h,pixels) vs (x,z,pixels).
    private volatile java.lang.reflect.Constructor<?> cachedMapPatchCtor;
    private volatile boolean         mapPatch5Arg;

    // Token-bucket state for the bandwidth pacer.
    private long                     paceWindowStartNs = System.nanoTime();
    private long                     paceSentBytes     = 0;

    public PacketSender(Logger logger, boolean hideFrameBorder) {
        this(logger, hideFrameBorder, 0L);
    }

    public PacketSender(Logger logger, boolean hideFrameBorder, long bandwidthLimitBps) {
        this.protocol           = ProtocolLibrary.getProtocolManager();
        this.logger             = logger;
        this.hideFrameBorder    = hideFrameBorder;
        this.bandwidthLimitBps  = bandwidthLimitBps;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Spawns all fake item frames for this screen and sends the initial
     * (blank) map data for each slot.  Call once per player when they come
     * into range or join the server while the screen exists.
     */
    public void spawnScreen(VideoScreen screen, Collection<? extends Player> players) {
        for (Player player : players) {
            spawnScreenForPlayer(screen, player);
        }
    }

    /** Spawns and initialises the screen for a single player. */
    public void spawnScreenForPlayer(VideoScreen screen, Player player) {
        int rows = screen.getHeightMaps();
        int cols = screen.getWidthMaps();

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Location frameLoc = slotLocation(screen, row, col);
                int entityId    = screen.getFakeEntityIds()[row][col];
                var uuid        = screen.getFakeEntityUuids()[row][col];
                int fakeMapId   = screen.getFakeMapIds()[row][col];

                sendSpawnItemFrame(player, entityId, uuid, frameLoc, screen.getFacing());
                sendItemFrameMetadata(player, entityId, fakeMapId, screen.getFacing());
                // Blank map data (all black pixels = 119) to avoid
                // the default missing-map purple/black checkerboard.
                byte[] initData = new byte[128 * 128];
                java.util.Arrays.fill(initData, (byte) 119);
                sendMapData(player, fakeMapId, initData);
            }
        }
    }

    /**
     * Sends map-pixel updates for the dirty slots in a frame.
     * Only sends packets for slots where {@code dirtyMaps[row][col] != null}.
     *
     * <p>Each changed tile is converted into a {@link PacketContainer} exactly
     * once and then sent to every target player — the packet payload for a
     * given tile is identical across recipients, so rebuilding it per-player
     * would be pure wasted CPU and reflection.</p>
     *
     * @param players   Target players (typically those within render radius).
     * @param screen    The screen being updated.
     * @param dirtyMaps [row][col] — non-null only for changed slots.
     */
    public void sendFrameUpdate(
            Collection<? extends Player> players,
            VideoScreen screen,
            byte[][][] dirtyMaps) {

        int rows = screen.getHeightMaps();
        int cols = screen.getWidthMaps();

        // Build one packet per dirty tile (shared across all players).
        java.util.List<PacketContainer> packets = new java.util.ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                byte[] data = dirtyMaps[row][col];
                if (data != null) {
                    PacketContainer pkt = buildMapPacket(
                            screen.getFakeMapIds()[row][col], data);
                    if (pkt != null) packets.add(pkt);
                }
            }
        }
        if (packets.isEmpty()) return;

        for (Player player : players) {
            for (PacketContainer pkt : packets) {
                sendPacket(player, pkt);
            }
        }
    }

    /**
     * Destroys all fake item frames for this screen for the given players.
     * Call on stop, delete, or player leaving range.
     */
    public void destroyScreen(VideoScreen screen, Collection<? extends Player> players) {
        int rows = screen.getHeightMaps();
        int cols = screen.getWidthMaps();
        int total = rows * cols;
        int[] ids = new int[total];
        int idx = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ids[idx++] = screen.getFakeEntityIds()[row][col];
            }
        }
        for (Player player : players) {
            sendEntityDestroy(player, ids);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet builders
    // ─────────────────────────────────────────────────────────────────────────

    /** Sends a SPAWN_ENTITY packet that creates a fake item frame at {@code loc}. */
    private void sendSpawnItemFrame(
            Player player, int entityId, java.util.UUID uuid,
            Location loc, BlockFace facing) {

        PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);

        pkt.getIntegers().write(0, entityId);            // Entity ID
        pkt.getUUIDs().write(0, uuid);                   // UUID
        pkt.getEntityTypeModifier().write(0, EntityType.ITEM_FRAME);

        // Position — centre of the block
        pkt.getDoubles().write(0, loc.getX() + 0.5);
        pkt.getDoubles().write(1, loc.getY() + 0.5);
        pkt.getDoubles().write(2, loc.getZ() + 0.5);

        // Angles (stored as byte = angle * 256 / 360)
        float yaw   = FACE_YAW.getOrDefault(facing, 0f);
        float pitch = FACE_PITCH.getOrDefault(facing, 0f);
        pkt.getBytes().write(0, angleToByte(yaw));
        pkt.getBytes().write(1, angleToByte(pitch));

        // Object data for item frames = attachment direction
        // 0=south,1=west,2=north,3=east,4=up,5=down
        pkt.getIntegers().write(1, FACE_DIRECTION.getOrDefault(facing, 0));

        sendPacket(player, pkt);
    }

    /**
     * Sends ENTITY_METADATA to set the item frame's held item to a filled map
     * with the given fake map ID, and optionally marks the frame invisible.
     */
    private void sendItemFrameMetadata(
            Player player, int entityId, int fakeMapId, BlockFace facing) {

        PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        pkt.getIntegers().write(0, entityId);

        // Create an NMS ItemStack of filled_map and set its Map ID
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
        org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapId(fakeMapId);
            item.setItemMeta(meta);
        }
        
        // Convert the Bukkit ItemStack to NMS representation
        Object nmsItem = com.comphenix.protocol.wrappers.BukkitConverters.getItemStackConverter().getGeneric(item);

        List<WrappedDataValue> dataValues = new ArrayList<>();

        // Index 0 — base Entity: flags byte
        // Bit 5 = invisible (0x20)
        byte flags = hideFrameBorder ? (byte) 0x20 : (byte) 0x00;
        dataValues.add(new WrappedDataValue(
                0,
                WrappedDataWatcher.Registry.get(Byte.class),
                flags));

        // Index 9 — ItemFrame item (the filled map ItemStack)
        dataValues.add(new WrappedDataValue(
                9,
                WrappedDataWatcher.Registry.getItemStackSerializer(false),
                nmsItem));

        // Index 10 — ItemFrame rotation (0 = no rotation)
        dataValues.add(new WrappedDataValue(
                10,
                WrappedDataWatcher.Registry.get(Integer.class),
                0));

        pkt.getDataValueCollectionModifier().write(0, dataValues);

        sendPacket(player, pkt);
    }

    // Cache the resolved MapPatch class so we don't re-scan every frame.
    private volatile Class<?> cachedMapPatchClass  = null;
    private volatile Class<?> cachedMapIdClass     = null;
    private volatile Class<?> cachedMapPacketClass = null;
    private volatile java.lang.reflect.Constructor<?> cachedMapIdCtor = null;

    /**
     * Sends a MAP packet containing the full 128×128 pixel data for one map tile.
     *
     * <p>Since MC 1.20.5 the {@code ClientboundMapItemDataPacket} is an immutable Java
     * record whose fields are not accessible via plain {@code getIntegers()}.  We build
     * the NMS packet directly with reflection (resolved once and cached) and wrap it in
     * a {@link PacketContainer}.  The returned container is safe to reuse for multiple
     * players because ProtocolLib serialises it per recipient channel.
     *
     * @return a ready-to-send {@link PacketContainer}, or {@code null} if the NMS
     *         classes could not be resolved.
     */
    public PacketContainer buildMapPacket(int mapId, byte[] data) {
        try {
            // ── Lazy-init + cache NMS class references (once) ──────────────────
            if (cachedMapIdClass == null) {
                cachedMapIdClass = Class.forName(
                        "net.minecraft.world.level.saveddata.maps.MapId");
                cachedMapPacketClass = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundMapItemDataPacket");
                cachedMapPatchClass  = resolveMapPatchClass(cachedMapPacketClass);
                if (cachedMapPatchClass != null) {
                    cacheMapPatchCtor();
                }
                cacheMapCtor();
            }

            if (cachedMapPatchClass == null || cachedMapCtor == null) {
                return null; // Already warned during resolution.
            }

            // ── Build MapId ───────────────────────────────────────────────────
            if (cachedMapIdCtor == null) {
                cachedMapIdCtor = cachedMapIdClass.getConstructor(int.class);
                cachedMapIdCtor.setAccessible(true);
            }
            Object nmsMapId = cachedMapIdCtor.newInstance(mapId);

            // ── Build MapPatch (cached constructor) ───────────────────────────
            Object mapPatch = mapPatch5Arg
                    ? cachedMapPatchCtor.newInstance(0, 0, 128, 128, data)
                    : cachedMapPatchCtor.newInstance(0, 0, 128, data);

            // ── Construct the NMS record (cached constructor) ─────────────────
            Object nmsPacket = cachedMapCtor.newInstance(
                    buildMapArgs(cachedMapCtor, mapId, nmsMapId, mapPatch));

            return new PacketContainer(PacketType.Play.Server.MAP, nmsPacket);

        } catch (Exception e) {
            logger.warning("[WatchParty] MAP packet error for mapId=" + mapId
                    + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
            return null;
        }
    }

    /** Builds the NMS packet and sends it to a single player. */
    public void sendMapData(Player player, int mapId, byte[] data) {
        PacketContainer pkt = buildMapPacket(mapId, data);
        if (pkt != null) sendPacket(player, pkt);
    }

    /**
     * Locates the nested {@code MapPatch} record inside
     * {@code ClientboundMapItemDataPacket} using three progressive strategies:
     * <ol>
     *   <li>Direct {@code $MapPatch} class name lookup.</li>
     *   <li>Name-pattern scan of all declared inner classes.</li>
     *   <li>Generic type extraction from the canonical constructor's last
     *       {@code Optional} parameter.</li>
     * </ol>
     * This runs at most once per plugin instance (when the first MAP packet is
     * built) and only logs diagnostics on that single occasion.
     */
    @SuppressWarnings("unchecked")
    private Class<?> resolveMapPatchClass(Class<?> pktClass) {
        // Strategy 1 — direct $MapPatch lookup (Mojang mapped name)
        try {
            return Class.forName(pktClass.getName() + "$MapPatch");
        } catch (ClassNotFoundException ignored) { }

        // Strategy 2 — scan declared inner classes.
        Class<?>[] inner = pktClass.getDeclaredClasses();
        for (Class<?> c : inner) {
            String sn = c.getSimpleName().toLowerCase(java.util.Locale.ROOT);
            if (sn.contains("patch") || sn.contains("color") || sn.contains("map")) {
                return c;
            }
        }

        // Strategy 3 — extract from canonical constructor's generic Optional parameter.
        for (java.lang.reflect.Constructor<?> ctor : pktClass.getDeclaredConstructors()) {
            java.lang.reflect.Type[] types = ctor.getGenericParameterTypes();
            for (java.lang.reflect.Type t : types) {
                if (t instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() == Optional.class) {
                    java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                    if (arg instanceof Class<?> candidate
                            && candidate != java.util.List.class
                            && !candidate.getName().contains("MapDecoration")
                            && !candidate.getName().contains("List")) {
                        return candidate;
                    }
                }
            }
        }

        logger.warning("[WatchParty] Could not resolve MapPatch class in any way."
                + " MAP packets will be skipped. Packet: " + pktClass.getName());
        return null;
    }

    /** Picks and caches the best MapPatch constructor (5-arg preferred). */
    private void cacheMapPatchCtor() {
        java.lang.reflect.Constructor<?> c5 = null, c4 = null;
        for (java.lang.reflect.Constructor<?> c : cachedMapPatchClass.getDeclaredConstructors()) {
            int n = c.getParameterCount();
            if (n == 5) c5 = c;
            else if (n == 4) c4 = c;
        }
        cachedMapPatchCtor = (c5 != null) ? c5 : c4;
        mapPatch5Arg = (c5 != null);
        cachedMapPatchCtor.setAccessible(true);
    }

    /**
     * Selects and caches the {@code ClientboundMapItemDataPacket} constructor.
     * Preference is given to the canonical record constructor that carries an
     * {@link Optional} (the MapPatch), then any constructor we can fulfil.
     */
    private void cacheMapCtor() {
        java.util.List<java.lang.reflect.Constructor<?>> candidates = new java.util.ArrayList<>();
        for (java.lang.reflect.Constructor<?> ctor : cachedMapPacketClass.getDeclaredConstructors()) {
            boolean hasOptional = false;
            for (Class<?> p : ctor.getParameterTypes()) {
                if (p == Optional.class) { hasOptional = true; break; }
            }
            if (hasOptional) candidates.add(0, ctor);
            else candidates.add(ctor);
        }

        for (java.lang.reflect.Constructor<?> ctor : candidates) {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            boolean matches = true;
            for (Class<?> type : paramTypes) {
                if (type == cachedMapIdClass
                        || type == int.class || type == Integer.class
                        || type == byte.class || type == Byte.class
                        || type == boolean.class || type == Boolean.class
                        || type == Optional.class
                        || type == java.util.Collection.class || type == java.util.List.class
                        || type == cachedMapPatchClass) {
                    // compatible
                } else {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                cachedMapCtor = ctor;
                cachedMapCtor.setAccessible(true);
                return;
            }
        }
        logger.warning("[WatchParty] Could not find a compatible constructor for "
                + cachedMapPacketClass.getName() + ". MAP packets will be skipped.");
    }

    /** Builds the argument array for the (cached) MAP packet constructor. */
    private Object[] buildMapArgs(java.lang.reflect.Constructor<?> ctor, int mapId,
                                  Object nmsMapId, Object mapPatch) {
        Class<?>[] paramTypes = ctor.getParameterTypes();
        java.lang.reflect.Type[] genTypes = ctor.getGenericParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> type = paramTypes[i];
            if (type == cachedMapIdClass) {
                args[i] = nmsMapId;
            } else if (type == int.class || type == Integer.class) {
                args[i] = mapId;
            } else if (type == byte.class || type == Byte.class) {
                args[i] = (byte) 0; // scale
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false; // locked / trackingPosition = false
            } else if (type == Optional.class) {
                String gen = genTypes[i].toString();
                if (gen.contains("MapPatch") || gen.contains("Patch")) {
                    args[i] = Optional.of(mapPatch);
                } else {
                    args[i] = Optional.empty();
                }
            } else if (type == java.util.Collection.class || type == java.util.List.class) {
                args[i] = java.util.Collections.emptyList(); // decorations
            } else if (type == cachedMapPatchClass) {
                args[i] = mapPatch; // raw MapPatch parameter
            }
        }
        return args;
    }

    /** Sends ENTITY_DESTROY for a batch of entity IDs. */
    private void sendEntityDestroy(Player player, int[] entityIds) {
        PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        // MC 1.17+ / ProtocolLib 5.x: ENTITY_DESTROY carries a VarInt list.
        // PacketContainer exposes this as getIntLists() returning List<Integer>.
        List<Integer> idList = new ArrayList<>(entityIds.length);
        for (int id : entityIds) idList.add(id);
        pkt.getIntLists().write(0, idList);
        sendPacket(player, pkt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Geometry helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes the block location for slot [row][col] in a screen grid.
     * The grid grows:
     * <ul>
     *   <li>Row 0 = top, row N = bottom (so row increases downward = -Y).</li>
     *   <li>Col 0 = left of the viewer, col N = right (relative to the facing).</li>
     * </ul>
     * "Left of the viewer" is the left when standing in front of the screen
     * looking at it — i.e., the direction that is 90° counter-clockwise from
     * the face normal in the XZ plane.
     */
    public static Location slotLocation(VideoScreen screen, int row, int col) {
        Location anchor = screen.getAnchorLocation();
        BlockFace facing = screen.getFacing();

        // Right vector (from the viewer's perspective, left of anchor = slot 0)
        // Facing NORTH → right is WEST (+X in block coords)... derive from face:
        int[] right = faceRight(facing);

        int x = anchor.getBlockX() + right[0] * col;
        int y = anchor.getBlockY() - row;           // rows go downward
        int z = anchor.getBlockZ() + right[1] * col;

        return new Location(anchor.getWorld(), x, y, z);
    }

    /**
     * Returns the [dx, dz] unit vector that points to the viewer's "right" 
     * when looking AT a screen that is facing the given {@link BlockFace}.
     * Used to expand the grid horizontally from left to right.
     */
    private static int[] faceRight(BlockFace facing) {
        return switch (facing) {
            case NORTH -> new int[]{-1,  0};   // viewer looks south -> right is west (-X)
            case SOUTH -> new int[]{ 1,  0};   // viewer looks north -> right is east (+X)
            case EAST  -> new int[]{ 0, -1};   // viewer looks west  -> right is north (-Z)
            case WEST  -> new int[]{ 0,  1};   // viewer looks east  -> right is south (+Z)
            default    -> new int[]{-1,  0};
        };
    }

    private static byte angleToByte(float angle) {
        return (byte) Math.round(angle * 256f / 360f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private void sendPacket(Player player, PacketContainer pkt) {
        // Pace outbound traffic so a large screen cannot flood a player's
        // connection.  MAP packets dominate bandwidth (~16 KB each); metadata
        // and spawn packets are negligible, so we estimate by packet type.
        if (bandwidthLimitBps > 0) {
            long bytes = (pkt.getType() == PacketType.Play.Server.MAP) ? (128L * 128L) : 256L;
            pace(bytes);
        }
        try {
            protocol.sendServerPacket(player, pkt);
        } catch (Exception e) {
            logger.warning("[WatchParty] Failed to send " + pkt.getType() +
                    " to " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Token-bucket pacer.  Sleeps (on the calling send thread) just enough to
     * keep the rolling 1-second average at or below {@link #bandwidthLimitBps}.
     * A limit of 0 disables pacing entirely.
     */
    private synchronized void pace(long bytes) {
        long now = System.nanoTime();
        if (now - paceWindowStartNs >= 1_000_000_000L) {
            paceWindowStartNs = now;
            paceSentBytes = 0;
        }
        paceSentBytes += bytes;
        if (paceSentBytes > bandwidthLimitBps) {
            long excess = paceSentBytes - bandwidthLimitBps;
            long sleepNs = (excess * 1_000_000_000L) / bandwidthLimitBps;
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
