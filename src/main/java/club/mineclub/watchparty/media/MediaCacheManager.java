package club.mineclub.watchparty.media;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Handles downloading and caching of static media files (e.g. .mp4) to disk.
 */
public class MediaCacheManager {

    private final Plugin plugin;
    private final Logger logger;
    private final File cacheDir;

    // Track ongoing downloads to prevent multiple threads downloading the same file
    private final ConcurrentHashMap<String, CompletableFuture<File>> ongoingDownloads = new ConcurrentHashMap<>();

    public MediaCacheManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.cacheDir = new File(plugin.getDataFolder(), "cache");
        
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /**
     * Checks if a URL points to a static media file rather than a live stream.
     */
    public boolean isStaticMedia(String url) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();
        return lowerUrl.startsWith("http") && (
                lowerUrl.endsWith(".mp4") ||
                lowerUrl.endsWith(".webm") ||
                lowerUrl.endsWith(".mkv") ||
                lowerUrl.endsWith(".mov") ||
                lowerUrl.endsWith(".avi")
        );
    }

    /**
     * Clears the entire cache directory.
     */
    public void clearCache() {
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        logger.info("[TheBigScreen™] Media cache cleared.");
    }

    /**
     * Deletes a specific cached file given its URL.
     */
    public void clearCacheForUrl(String url) {
        File file = getCachedFile(url);
        if (file.exists()) {
            if (file.delete()) {
                logger.info("[TheBigScreen™] Deleted cache for " + url);
            }
        }
    }

    /**
     * Downloads a media file asynchronously, or returns the cached file if it exists.
     */
    public CompletableFuture<File> downloadMediaAsync(String url) {
        File cachedFile = getCachedFile(url);
        if (cachedFile.exists()) {
            return CompletableFuture.completedFuture(cachedFile);
        }

        // Check if a download for this URL is already in progress
        return ongoingDownloads.computeIfAbsent(url, k -> CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("[TheBigScreen™] Downloading media: " + url);
                long startTime = System.currentTimeMillis();

                URL connectionUrl = new URL(url);
                URLConnection conn = connectionUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "WatchParty-Minecraft-Plugin");
                conn.connect();

                // Download to a temporary file first
                File tempFile = new File(cacheDir, cachedFile.getName() + ".tmp");
                
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }

                // Move temp to actual
                Files.move(tempFile.toPath(), cachedFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                long duration = System.currentTimeMillis() - startTime;
                logger.info("[TheBigScreen™] Downloaded " + url + " in " + duration + "ms.");
                return cachedFile;
            } catch (Exception e) {
                logger.warning("[TheBigScreen™] Failed to download media " + url + ": " + e.getMessage());
                return null;
            } finally {
                ongoingDownloads.remove(url);
            }
        }));
    }

    /**
     * Generates a unique file path for a URL using MD5.
     */
    private File getCachedFile(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            // Extract extension
            String ext = ".mp4";
            int lastDot = url.lastIndexOf('.');
            if (lastDot != -1 && lastDot > url.lastIndexOf('/')) {
                ext = url.substring(lastDot);
                // Clean extension of query params
                int queryIdx = ext.indexOf('?');
                if (queryIdx != -1) {
                    ext = ext.substring(0, queryIdx);
                }
            }

            return new File(cacheDir, hexString.toString() + ext);
        } catch (Exception e) {
            // Fallback
            return new File(cacheDir, Math.abs(url.hashCode()) + ".mp4");
        }
    }
}
