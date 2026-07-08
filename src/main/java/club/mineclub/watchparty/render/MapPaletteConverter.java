package club.mineclub.watchparty.render;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Converts {@link BufferedImage} frames to Minecraft map-palette byte arrays.
 * <p>
 * <h2>Palette</h2>
 * Minecraft 1.21 defines 61 base colours, each with 4 shade multipliers
 * (180, 220, 255, 135 — expressed as fractions of 255).  The resulting
 * 244 usable palette entries occupy byte values 4–247 (indices 0–3 are
 * transparent/void and should not appear on rendered map tiles).
 * <p>
 * The full base-colour RGB table below is extracted from the 1.21 source
 * (MaterialMapColor / MapColor) and verified against the
 * <a href="https://github.com/cerus/minecraft-map-colors">cerus/minecraft-map-colors</a>
 * dataset.
 * <p>
 * <h2>Look-Up Table (LUT)</h2>
 * A 256³ LUT (16 777 216 entries) is pre-built once at startup.  Each entry
 * stores the palette byte for the closest palette colour in squared-Euclidean
 * RGB distance.  Per-pixel conversion is then a single array read.
 * <p>
 * Build time: ~300–600 ms depending on CPU; runs on a background thread so
 * the server thread is never blocked.
 */
public final class MapPaletteConverter {

    // ── Shade multipliers (applied to each base colour) ───────────────────────
    // Index within a group of 4: 0 → dark, 1 → medium, 2 → full, 3 → darkest
    private static final int[] SHADE_MULTIPLIERS = {180, 220, 255, 135};

    /**
     * Base colours for the Minecraft 1.21 map palette.
     * Each entry is {R, G, B}.  Index in this array = base colour index N.
     * Palette byte value for shade S of base N = (N * 4) + S, with N starting
     * from 1 (index 0 is transparent / unused; we set it to black here so the
     * LUT is still complete but we never emit 0–3 in practice).
     */
    private static final int[][] BASE_COLORS = {
        // 0  – NONE (transparent) — placeholder, never emitted
        {  0,   0,   0},
        // 1  – GRASS
        {127, 178,  56},
        // 2  – SAND
        {247, 233, 163},
        // 3  – WOOL / QUARTZ
        {199, 199, 199},
        // 4  – FIRE / TNT / REDSTONE
        {255,   0,   0},
        // 5  – ICE
        {160, 160, 255},
        // 6  – METAL / IRON
        {167, 167, 167},
        // 7  – PLANT / LEAVES
        {  0, 124,   0},
        // 8  – SNOW
        {255, 255, 255},
        // 9  – CLAY
        {164, 168, 184},
        // 10 – DIRT
        {151,  60,  23},
        // 11 – STONE
        {112, 112, 112},
        // 12 – WATER
        { 64,  64, 255},
        // 13 – WOOD
        {143, 119,  72},
        // 14 – QUARTZ (white mineral)
        {255, 252, 245},
        // 15 – COLOR_ORANGE
        {216, 127,  51},
        // 16 – COLOR_MAGENTA
        {178,  76, 216},
        // 17 – COLOR_LIGHT_BLUE
        {102, 153, 216},
        // 18 – COLOR_YELLOW
        {229, 229,  51},
        // 19 – COLOR_LIGHT_GREEN / LIME
        {127, 204,  25},
        // 20 – COLOR_PINK
        {242, 127, 165},
        // 21 – COLOR_GRAY
        { 76,  76,  76},
        // 22 – COLOR_LIGHT_GRAY / SILVER
        {153, 153, 153},
        // 23 – COLOR_CYAN
        { 76, 127, 153},
        // 24 – COLOR_PURPLE
        {127,  63, 178},
        // 25 – COLOR_BLUE
        { 51,  76, 178},
        // 26 – COLOR_BROWN
        {102,  76,  51},
        // 27 – COLOR_GREEN
        {102, 127,  51},
        // 28 – COLOR_RED
        {153,  51,  51},
        // 29 – COLOR_BLACK
        { 25,  25,  25},
        // 30 – GOLD
        {250, 238,  77},
        // 31 – DIAMOND
        { 92, 219, 213},
        // 32 – LAPIS
        { 74, 128, 255},
        // 33 – EMERALD
        {  0, 217,  58},
        // 34 – PODZOL / SPRUCE
        {129,  86,  49},
        // 35 – NETHER
        {112,   2,   0},
        // 36 – TERRACOTTA_WHITE
        {209, 177, 161},
        // 37 – TERRACOTTA_ORANGE
        {159,  82,  36},
        // 38 – TERRACOTTA_MAGENTA
        {149,  87, 108},
        // 39 – TERRACOTTA_LIGHT_BLUE
        {112, 108, 138},
        // 40 – TERRACOTTA_YELLOW
        {186, 133,  36},
        // 41 – TERRACOTTA_LIGHT_GREEN
        {103, 117,  53},
        // 42 – TERRACOTTA_PINK
        {160,  77,  78},
        // 43 – TERRACOTTA_GRAY
        { 57,  41,  35},
        // 44 – TERRACOTTA_LIGHT_GRAY
        {135, 107,  98},
        // 45 – TERRACOTTA_CYAN
        { 87,  92,  92},
        // 46 – TERRACOTTA_PURPLE
        {122,  73,  88},
        // 47 – TERRACOTTA_BLUE
        { 76,  62,  92},
        // 48 – TERRACOTTA_BROWN
        { 76,  50,  35},
        // 49 – TERRACOTTA_GREEN
        { 76,  82,  42},
        // 50 – TERRACOTTA_RED
        {142,  60,  46},
        // 51 – TERRACOTTA_BLACK
        { 37,  22,  16},
        // 52 – CRIMSON_NYLIUM
        {189,  48,  49},
        // 53 – CRIMSON_STEM
        {148,  63,  97},
        // 54 – CRIMSON_HYPHAE
        { 92,  25,  29},
        // 55 – WARPED_NYLIUM
        { 22, 126, 134},
        // 56 – WARPED_STEM
        { 58, 142, 140},
        // 57 – WARPED_HYPHAE
        { 86,  44,  62},
        // 58 – WARPED_WART_BLOCK
        { 20, 180, 133},
        // 59 – DEEPSLATE
        {100, 100, 100},
        // 60 – RAW_IRON
        {216, 175, 147},
        // 61 – GLOW_LICHEN (1.18+)
        {127, 167, 150},
    };

    // 16 777 216 entries — one per possible 24-bit RGB value.
    // Value = MC map palette byte (4–247).
    private final byte[] lut = new byte[256 * 256 * 256];

    private final AtomicBoolean lutReady   = new AtomicBoolean(false);
    private final Logger         logger;

    // Pre-computed palette: index = palette byte (0..247), value = packed RGB
    // Built alongside the LUT to keep the logic in one place.
    private final int[]  paletteRgb  = new int[248];
    private final byte[] paletteBytes;   // valid palette byte values in order

    public MapPaletteConverter(Logger logger) {
        this.logger       = logger;
        this.paletteBytes = buildPaletteRgb();
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Builds the 16 M LUT on a background thread.
     * Call once during plugin enable if {@code lutBuildOnStartup} is true.
     * The volatile {@link #isLutReady()} flag gates any rendering.
     */
    public void buildLutAsync() {
        Thread t = new Thread(this::buildLut, "WatchParty-LUT-Builder");
        t.setDaemon(true);
        t.start();
    }

    /** Blocks until the LUT is ready (useful for synchronous first-frame). */
    public void awaitLut() throws InterruptedException {
        while (!lutReady.get()) {
            Thread.sleep(10);
        }
    }

    public boolean isLutReady() {
        return lutReady.get();
    }

    // ── Palette construction ──────────────────────────────────────────────────

    /**
     * Expands BASE_COLORS × SHADE_MULTIPLIERS into the {@link #paletteRgb} array.
     *
     * @return the array of valid palette byte values (4 through 4+N*4-1).
     */
    private byte[] buildPaletteRgb() {
        // Count valid entries (skip base index 0 = transparent)
        int validCount = (BASE_COLORS.length - 1) * SHADE_MULTIPLIERS.length;
        byte[] bytes = new byte[validCount];
        int idx = 0;

        for (int base = 1; base < BASE_COLORS.length; base++) {
            int[] rgb = BASE_COLORS[base];
            for (int shade = 0; shade < SHADE_MULTIPLIERS.length; shade++) {
                int mult = SHADE_MULTIPLIERS[shade];
                int r = (rgb[0] * mult) / 255;
                int g = (rgb[1] * mult) / 255;
                int b = (rgb[2] * mult) / 255;
                int palByte = base * 4 + shade;  // e.g. base=1,shade=0 → 4
                paletteRgb[palByte] = packRgb(r, g, b);
                bytes[idx++] = (byte) palByte;
            }
        }
        return bytes;
    }

    /** Builds the full 16M LUT — call off the main thread. */
    private void buildLut() {
        long start = System.currentTimeMillis();
        logger.info("[WatchParty] Building RGB→palette LUT (16 M entries)…");

        for (int r = 0; r < 256; r++) {
            for (int g = 0; g < 256; g++) {
                for (int b = 0; b < 256; b++) {
                    lut[lutIndex(r, g, b)] = nearestPaletteByte(r, g, b);
                }
            }
        }

        lutReady.set(true);
        long elapsed = System.currentTimeMillis() - start;
        logger.info("[WatchParty] LUT build complete in " + elapsed + " ms.");
    }

    /** Finds the nearest palette byte for the given RGB triple via squared distance. */
    private byte nearestPaletteByte(int r, int g, int b) {
        int bestDist = Integer.MAX_VALUE;
        byte bestByte = paletteBytes[0];

        for (byte pb : paletteBytes) {
            int packed = paletteRgb[pb & 0xFF];
            int pr = (packed >> 16) & 0xFF;
            int pg = (packed >>  8) & 0xFF;
            int pp = packed & 0xFF;
            int dr = r - pr, dg = g - pg, db = b - pp;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                bestByte = pb;
                if (dist == 0) break;
            }
        }
        return bestByte;
    }

    // ── Frame conversion ──────────────────────────────────────────────────────

    /**
     * Converts a {@link BufferedImage} (already scaled to
     * {@code widthMaps * 128} × {@code heightMaps * 128} pixels) into a 2D
     * array of per-map byte arrays ready to be compared against the last frame
     * and sent as map packets.
     *
     * @param image      The source image, exactly {@code widthMaps*128} wide
     *                   and {@code heightMaps*128} tall.
     * @param widthMaps  Number of map columns.
     * @param heightMaps Number of map rows.
     * @return {@code byte[row][col][128*128]} — ready for
     *         {@link club.mineclub.watchparty.packet.PacketSender}.
     */
    public byte[][][] convertFrame(BufferedImage image, int widthMaps, int heightMaps) {
        if (!lutReady.get()) {
            throw new IllegalStateException("LUT not yet ready — call buildLutAsync() and await.");
        }

        byte[][][] result = new byte[heightMaps][widthMaps][128 * 128];
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                null, 0, image.getWidth());

        for (int mapRow = 0; mapRow < heightMaps; mapRow++) {
            for (int mapCol = 0; mapCol < widthMaps; mapCol++) {
                byte[] mapData = result[mapRow][mapCol];
                int baseX = mapCol * 128;
                int baseY = mapRow * 128;
                for (int py = 0; py < 128; py++) {
                    for (int px = 0; px < 128; px++) {
                        int argb = pixels[(baseY + py) * image.getWidth() + (baseX + px)];
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >>  8) & 0xFF;
                        int b =  argb        & 0xFF;
                        mapData[py * 128 + px] = lut[lutIndex(r, g, b)];
                    }
                }
            }
        }
        return result;
    }

    /**
     * Convenience: converts a single BufferedImage to a flat 128×128 byte array.
     * Image must be exactly 128×128.
     */
    public byte[] convertSingleMap(BufferedImage image) {
        if (!lutReady.get()) {
            throw new IllegalStateException("LUT not yet ready.");
        }
        byte[] result = new byte[128 * 128];
        for (int py = 0; py < 128; py++) {
            for (int px = 0; px < 128; px++) {
                int argb = image.getRGB(px, py);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >>  8) & 0xFF;
                int bv = argb & 0xFF;
                result[py * 128 + px] = lut[lutIndex(r, g, bv)];
            }
        }
        return result;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static int lutIndex(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int packRgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }
}
