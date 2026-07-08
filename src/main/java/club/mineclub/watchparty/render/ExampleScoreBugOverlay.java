package club.mineclub.watchparty.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Example OverlayRenderer that draws a simple score bug (e.g. Red vs Blue team score)
 * and a live clock in the corner of the video.
 */
public final class ExampleScoreBugOverlay implements OverlayRenderer {

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    @Override
    public void render(Graphics2D g2d, String screenName, long frameIndex) {
        // Draw score bug box in top-left
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(10, 10, 150, 40);

        // Border
        g2d.setColor(Color.WHITE);
        g2d.drawRect(10, 10, 150, 40);

        // Scores
        g2d.setFont(new Font("Arial", Font.BOLD, 14));
        g2d.setColor(Color.RED);
        g2d.drawString("RED: 2", 20, 35);
        g2d.setColor(Color.BLUE);
        g2d.drawString("BLU: 1", 95, 35);

        // Live Clock in top-right
        String clockText = timeFormat.format(new Date());
        g2d.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g2d.setColor(new Color(0, 0, 0, 150));
        g2d.fillRect(170, 10, 80, 20);
        g2d.setColor(Color.GREEN);
        g2d.drawString(clockText, 175, 24);
    }
}
