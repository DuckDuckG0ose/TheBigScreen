package club.mineclub.watchparty.listener;

import club.mineclub.watchparty.screen.ScreenManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Re-sends fake item frame + map data to players when they join the server or
 * switch worlds, so they always see running screens without needing a command.
 *
 * <p>Also cleans up session state on player quit to stop sending packets
 * to disconnected clients.
 */
public final class PlayerConnectionListener implements Listener {

    private final ScreenManager manager;
    private final Plugin        plugin;

    public PlayerConnectionListener(ScreenManager manager, Plugin plugin) {
        this.manager = manager;
        this.plugin  = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Slight delay to ensure the player's chunk is loaded before packets
        // are sent (avoids entities appearing in the wrong position).
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> manager.sendInitialState(event.getPlayer()),
                20L // 1 second
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // When changing worlds, destroy old frames then re-send in new world
        manager.handlePlayerLeave(event.getPlayer());
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> manager.sendInitialState(event.getPlayer()),
                20L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.handlePlayerLeave(event.getPlayer());
    }
}
