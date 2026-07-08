package club.mineclub.watchparty.model;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable-ish data record for a single virtual video screen.
 * <p>
 * A screen is an W×H grid of 128×128-pixel Minecraft maps, each displayed
 * inside a fake (packet-only) item frame. No world data is modified.
 * <p>
 * Thread-safety: {@code lastFrameBytes} is written only by the single palette
 * thread inside {@link club.mineclub.watchparty.video.VideoSession}; reads
 * happen on the same thread for dirty-checking, so no synchronisation is
 * needed for that array. The {@code state} field is an {@link AtomicReference}.
 */
public final class VideoScreen {

    /** Lifecycle states of a screen. */
    public enum State {
        IDLE,     // defined but no stream running
        RUNNING,  // actively decoding and sending frames
        STOPPING  // shutdown in progress
    }

    // ── Identity ─────────────────────────────────────────────────────────────
    private final String name;

    // ── Geometry ──────────────────────────────────────────────────────────────
    /** World + block coordinate of the top-left item frame slot (slot [0][0]). */
    private final Location anchorLocation;
    /** Direction the screen face points toward (i.e. the face the player sees). */
    private final BlockFace facing;
    /** Screen width in map-tiles (each tile = 128 px). */
    private final int widthMaps;
    /** Screen height in map-tiles. */
    private final int heightMaps;

    // ── Fake entity / map IDs ─────────────────────────────────────────────────
    /**
     * [row][col] array of fake map IDs allocated from config {@code fakeMapIdStart}.
     * Indices: row 0 = top, col 0 = left.
     */
    private final int[][] fakeMapIds;

    /**
     * [row][col] array of fake entity IDs for the spawned item frames.
     */
    private final int[][] fakeEntityIds;

    /**
     * [row][col] array of UUIDs for the fake item frame entities.
     */
    private final UUID[][] fakeEntityUuids;

    // ── Frame cache (dirty-check) ─────────────────────────────────────────────
    /**
     * Per-map pixel data from the last rendered frame.
     * Outer array: [row * widthMaps + col], inner: 128*128 bytes.
     * {@code null} means "not yet sent" → always dirty.
     */
    private final byte[][] lastFrameBytes;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    /** RTMP URL currently streamed to this screen; null when IDLE. */
    private volatile String currentStreamUrl;

    // ── Constructor ───────────────────────────────────────────────────────────

    public VideoScreen(
            String name,
            Location anchorLocation,
            BlockFace facing,
            int widthMaps,
            int heightMaps,
            int[][] fakeMapIds,
            int[][] fakeEntityIds,
            UUID[][] fakeEntityUuids) {
        this.name            = name;
        this.anchorLocation  = anchorLocation.clone();
        this.facing          = facing;
        this.widthMaps       = widthMaps;
        this.heightMaps      = heightMaps;
        this.fakeMapIds      = fakeMapIds;
        this.fakeEntityIds   = fakeEntityIds;
        this.fakeEntityUuids = fakeEntityUuids;
        this.lastFrameBytes  = new byte[heightMaps * widthMaps][];
        // lastFrameBytes[i] remains null → first frame is always "dirty"
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getName()                          { return name; }
    public Location getAnchorLocation()              { return anchorLocation.clone(); }
    public BlockFace getFacing()                     { return facing; }
    public int getWidthMaps()                        { return widthMaps; }
    public int getHeightMaps()                       { return heightMaps; }
    public int[][] getFakeMapIds()                   { return fakeMapIds; }
    public int[][] getFakeEntityIds()                { return fakeEntityIds; }
    public UUID[][] getFakeEntityUuids()             { return fakeEntityUuids; }
    public State getState()                          { return state.get(); }
    public String getCurrentStreamUrl()              { return currentStreamUrl; }

    /**
     * Returns the flat index into {@link #lastFrameBytes} for the given grid position.
     */
    public int slotIndex(int row, int col) {
        return row * widthMaps + col;
    }

    public byte[] getLastFrameBytes(int row, int col) {
        return lastFrameBytes[slotIndex(row, col)];
    }

    /**
     * Updates the cached frame bytes for the given slot.
     * Caller is responsible for ensuring this is only called from the
     * palette/convert thread.
     */
    public void setLastFrameBytes(int row, int col, byte[] data) {
        lastFrameBytes[slotIndex(row, col)] = data;
    }

    /**
     * Atomically transitions state if the current state matches {@code expected}.
     *
     * @return {@code true} if the transition succeeded.
     */
    public boolean compareAndSetState(State expected, State next) {
        return state.compareAndSet(expected, next);
    }

    public void setState(State next) {
        state.set(next);
    }

    public void setCurrentStreamUrl(String url) {
        this.currentStreamUrl = url;
    }

    /** Wipes last-frame cache so the next update sends all maps unconditionally. */
    public void invalidateFrameCache() {
        Arrays.fill(lastFrameBytes, null);
    }

    // ── Object identity ───────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "VideoScreen{name='" + name + "', size=" + widthMaps + "x" + heightMaps
                + ", state=" + state.get() + "}";
    }
}
