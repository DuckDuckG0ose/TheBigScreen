package club.mineclub.watchparty.render;

import java.awt.Graphics2D;

/**
 * Phase-4 stub: pluggable overlay renderer.
 * <p>
 * Implementations can draw arbitrary 2D graphics (score bugs, clocks, logos)
 * on top of a decoded video frame before it is converted to the MC palette.
 * Register implementations via the {@link OverlayRegistry} (Phase 4).
 * <p>
 * The graphics context is already scaled to the full screen pixel size
 * ({@code widthMaps * 128} × {@code heightMaps * 128}) and has ARGB support.
 */
public interface OverlayRenderer {

    /**
     * Draw overlay graphics onto the provided context.
     *
     * @param g2d        Graphics context for the full frame.
     * @param screenName Name of the screen being rendered, for context-aware overlays.
     * @param frameIndex Monotonically increasing frame counter (useful for animation).
     */
    void render(Graphics2D g2d, String screenName, long frameIndex);
}
