package club.mineclub.watchparty.command;

import club.mineclub.watchparty.screen.ScreenManager;
import club.mineclub.watchparty.screen.ScreenRegistry;
import club.mineclub.watchparty.model.VideoScreen;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the {@code /videoscreen} (alias {@code /vs}) command.
 *
 * <pre>
 * /videoscreen create  &lt;name&gt; &lt;width&gt; &lt;height&gt;
 * /videoscreen start   &lt;name&gt; &lt;rtmp-url&gt;
 * /videoscreen stop    &lt;name&gt;
 * /videoscreen delete  &lt;name&gt;
 * /videoscreen list
 * </pre>
 *
 * Permission node: {@code videoscreen.admin} (default: op).
 */
public final class VideoScreenCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("create", "start", "stop", "delete", "list", "clearcache", "reload");

    private final ScreenManager  manager;
    private final ScreenRegistry registry;
    private final club.mineclub.watchparty.WatchPartyPlugin plugin;

    public VideoScreenCommand(ScreenManager manager, club.mineclub.watchparty.WatchPartyPlugin plugin) {
        this.manager  = manager;
        this.registry = manager.getRegistry();
        this.plugin   = plugin;
    }

    // ── Executor ──────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!sender.hasPermission("videoscreen.admin")) {
            sender.sendMessage("§c[TheBigScreen™] You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "create"     -> handleCreate(sender, label, args);
            case "start"      -> handleStart(sender, label, args);
            case "stop"       -> handleStop(sender, label, args);
            case "delete"     -> handleDelete(sender, label, args);
            case "list"       -> handleList(sender);
            case "clearcache" -> handleClearCache(sender, label, args);
            case "reload"     -> handleReload(sender);
            default           -> { sendUsage(sender, label); yield true; }
        };
    }

    // ── Sub-command handlers ──────────────────────────────────────────────────

    private boolean handleCreate(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[TheBigScreen™] This command must be run by a player.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§7Usage: /" + label + " create <name> <width> <height>");
            return true;
        }
        String name = args[1];
        int width, height;
        try {
            width  = Integer.parseInt(args[2]);
            height = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c[TheBigScreen™] Width and height must be integers.");
            return true;
        }
        if (width < 1 || width > 32 || height < 1 || height > 32) {
            sender.sendMessage("§c[TheBigScreen™] Width and height must be between 1 and 32.");
            return true;
        }
        manager.createScreen(player, name, width, height);
        return true;
    }

    private boolean handleStart(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§7Usage: /" + label + " start <name> <rtmp-url>");
            return true;
        }
        String name = args[1];
        String url  = args[2];
        // If the player passed a URL with spaces somehow, rejoin
        if (args.length > 3) {
            StringBuilder sb = new StringBuilder(url);
            for (int i = 3; i < args.length; i++) sb.append(' ').append(args[i]);
            url = sb.toString();
        }
        Player player = sender instanceof Player p ? p : null;
        if (player == null) {
            sender.sendMessage("§c[TheBigScreen™] This command must be run by a player.");
            return true;
        }
        manager.startStream(player, name, url);
        return true;
    }

    private boolean handleStop(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /" + label + " stop <name>");
            return true;
        }
        Player player = sender instanceof Player p ? p : null;
        if (player == null) {
            sender.sendMessage("§c[TheBigScreen™] This command must be run by a player.");
            return true;
        }
        manager.stopStream(player, args[1]);
        return true;
    }

    private boolean handleDelete(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /" + label + " delete <name>");
            return true;
        }
        Player player = sender instanceof Player p ? p : null;
        if (player == null) {
            sender.sendMessage("§c[TheBigScreen™] This command must be run by a player.");
            return true;
        }
        manager.deleteScreen(player, args[1]);
        return true;
    }

    private boolean handleClearCache(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /" + label + " clearcache <name>");
            return true;
        }
        manager.clearScreenCache(sender, args[1]);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.reloadPluginConfig(sender);
        return true;
    }

    private boolean handleList(CommandSender sender) {
        Collection<VideoScreen> all = registry.all();
        if (all.isEmpty()) {
            sender.sendMessage("§7[TheBigScreen™] No screens defined.");
            return true;
        }
        sender.sendMessage("§6[TheBigScreen™] §eScreens (" + all.size() + "):");
        for (VideoScreen s : all) {
            String stateColor = switch (s.getState()) {
                case RUNNING  -> "§a";
                case STOPPING -> "§e";
                default       -> "§7";
            };
            sender.sendMessage("  §f" + s.getName()
                    + " §7| " + s.getWidthMaps() + "×" + s.getHeightMaps() + " maps"
                    + " §7| " + stateColor + s.getState().name()
                    + (s.getCurrentStreamUrl() != null
                        ? " §7← §b" + s.getCurrentStreamUrl()
                        : ""));
        }
        return true;
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("videoscreen.admin")) return List.of();

        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "start", "stop", "delete", "clearcache" -> filterPrefix(
                        new ArrayList<>(registry.names()), args[1]);
                case "create" -> List.of("<name>");
                default -> List.of();
            };
        }

        if (args.length == 3 && sub.equals("create")) return List.of("<width>");
        if (args.length == 4 && sub.equals("create")) return List.of("<height>");
        if (args.length == 3 && sub.equals("start"))  return List.of("<rtmp-url>");

        return List.of();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        return candidates.stream()
                .filter(c -> c.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("§6[TheBigScreen™] §eUsage:");
        sender.sendMessage("  §f/" + label + " create     <name> <width> <height>");
        sender.sendMessage("  §f/" + label + " start      <name> <url>");
        sender.sendMessage("  §f/" + label + " stop       <name>");
        sender.sendMessage("  §f/" + label + " delete     <name>");
        sender.sendMessage("  §f/" + label + " list");
        sender.sendMessage("  §f/" + label + " clearcache <name>");
        sender.sendMessage("  §f/" + label + " reload");
    }
}
