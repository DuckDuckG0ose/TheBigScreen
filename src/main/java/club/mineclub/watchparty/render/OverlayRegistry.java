package club.mineclub.watchparty.render;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for pluggable {@link OverlayRenderer}s.
 * Overlays are applied to decoded video frames before palette conversion.
 */
public final class OverlayRegistry {

    private final ConcurrentHashMap<String, OverlayRenderer> overlays = new ConcurrentHashMap<>();

    /**
     * Registers an overlay with a unique key.
     */
    public void register(String id, OverlayRenderer renderer) {
        overlays.put(id, renderer);
    }

    /**
     * Unregisters an overlay by key.
     */
    public void unregister(String id) {
        overlays.remove(id);
    }

    /**
     * Returns all currently active overlays.
     */
    public Collection<OverlayRenderer> getAll() {
        return Collections.unmodifiableCollection(overlays.values());
    }
}
