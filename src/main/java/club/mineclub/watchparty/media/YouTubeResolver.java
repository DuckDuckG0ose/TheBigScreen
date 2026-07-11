package club.mineclub.watchparty.media;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Resolves a YouTube watch/short/live URL into a direct, playable stream URL
 * using an external {@code yt-dlp} installation.
 *
 * <p>The resulting URL is fed straight into the existing
 * {@link club.mineclub.watchparty.video.FrameDecoder} pipeline, which only
 * consumes the video track (audio frames are skipped). The plugin is therefore
 * video-only — there is no audio.
 *
 * <p>Resolution runs on a background thread (via the returned
 * {@link CompletableFuture}) so the calling command/thread is never blocked.
 */
public final class YouTubeResolver {

    /** Whitelist of YouTube URL shapes we attempt to resolve. */
    public static boolean isYouTubeUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("youtube.com/watch")
                || u.contains("youtu.be/")
                || u.contains("youtube.com/live")
                || u.contains("youtube.com/embed")
                || u.contains("youtube.com/shorts");
    }

    private final Logger logger;
    private final String ytDlpPath;
    private final int    maxHeight;
    private final boolean enabled;

    public YouTubeResolver(Logger logger, String ytDlpPath, int maxHeight, boolean enabled) {
        this.logger     = logger;
        this.ytDlpPath  = (ytDlpPath == null || ytDlpPath.isEmpty()) ? "yt-dlp" : ytDlpPath;
        this.maxHeight  = Math.max(144, Math.min(4320, maxHeight));
        this.enabled    = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Resolves {@code url} to a direct stream URL.
     *
     * @return a future completing with the resolved URL, or {@code null} on
     *         failure (missing {@code yt-dlp}, private/deleted video, etc.).
     */
    public CompletableFuture<String> resolve(String url) {
        return resolveWithFormat(url,
                "best[height<=?" + maxHeight + "][ext=mp4]/best[height<=?" + maxHeight + "]/best");
    }

    /**
     * Resolves just the audio track of a YouTube URL (used for the Discord
     * audio bridge).  Returns the direct audio stream URL, or {@code null}.
     */
    public CompletableFuture<String> resolveAudio(String url) {
        return resolveWithFormat(url, "bestaudio");
    }

    private CompletableFuture<String> resolveWithFormat(String url, String format) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(ytDlpPath, "-f", format, "-g", url);
                pb.redirectErrorStream(false);
                Process p = pb.start();

                // Drain stderr on a separate thread to avoid pipe deadlock.
                StringBuilder err = new StringBuilder();
                Thread errThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getErrorStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) err.append(line).append('\n');
                    } catch (Exception ignored) { }
                });
                errThread.start();

                String resolved = null;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        // Take the first real URL line (video or audio track).
                        if (!line.isEmpty() && (resolved == null)) resolved = line;
                    }
                }

                int exit = p.waitFor();
                errThread.join();
                if (exit != 0 || resolved == null) {
                    logger.warning("[TheBigScreen™] yt-dlp failed for " + url
                            + " (exit " + exit + "): " + err.toString().trim());
                    return null;
                }
                logger.info("[TheBigScreen™] Resolved YouTube link → " + resolved);
                return resolved;
            } catch (Exception e) {
                logger.warning("[TheBigScreen™] Failed to resolve YouTube URL '"
                        + url + "': " + e.getMessage());
                return null;
            }
        });
    }
}
