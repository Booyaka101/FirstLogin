package firstlogin;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FirstLogin extends JavaPlugin {
    public static FileConfiguration config;
    public static FileConfiguration players;

    private File playersFile;
    private final Logger log = Logger.getLogger("Minecraft");
    private File messagesFile;
    private YamlConfiguration messages;

    // Adventure / MiniMessage
    private BukkitAudiences adventure;
    private MiniMessage mm;

    // PlaceholderAPI availability
    private boolean papiAvailable;

    @Override
    public void onEnable() {
        // config.yml
        saveDefaultConfig();
        config = getConfig();
        ensureDefaultConfigValues();

        // players.yml in plugin data folder
        if (!getDataFolder().exists()) {
            // noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try {
                // noinspection ResultOfMethodCallIgnored
                playersFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create players.yml: " + e.getMessage());
            }
        }
        players = YamlConfiguration.loadConfiguration(playersFile);
        players.options().setHeader(java.util.Collections.singletonList(
                "This is a list of players who had joined before but first joined again after the plugin was installed."));
        savePlayers();

        // messages.yml (copy default if missing)
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        // Adventure init
        adventure = BukkitAudiences.create(this);
        mm = MiniMessage.miniMessage();

        // PlaceholderAPI detection
        papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        // Register listener (inner class)
        Bukkit.getPluginManager().registerEvents(new JoinListener(this), this);

        // Initialize metrics (bStats) if enabled and pluginId > 0
        if (config.getBoolean("metrics.enabled", true)) {
            int pluginId = config.getInt("metrics.pluginId", 0);
            if (pluginId > 0) {
                try {
                    new Metrics(this, pluginId);
                } catch (Throwable t) {
                    getLogger().warning("Could not start bStats metrics: " + t.getMessage());
                }
            }
        }

        String ver = getDescription().getVersion();
        log.info("FirstLogin " + ver + " - Enabled");
    }

    @Override
    public void onDisable() {
        savePlayers();
        if (adventure != null) {
            adventure.close();
            adventure = null;
        }
        String ver = getDescription().getVersion();
        log.info("FirstLogin " + ver + " - Disabled");
    }

    void savePlayers() {
        try {
            players.save(playersFile);
        } catch (IOException e) {
            getLogger().severe("Could not save players.yml: " + e.getMessage());
        }
    }

    private void ensureDefaultConfigValues() {
        config.addDefault("World.name", "world");
        config.addDefault("World.Owner", "default");

        config.addDefault("messageGlobal.enabled", true);
        config.addDefault("messageGlobal.string", "first time user logged in");
        config.addDefault("messageGlobal.color", "&f");

        config.addDefault("message.enabled", true);
        config.addDefault("message.string", "Welcome to the server");
        config.addDefault("message.color", "&f");

        config.addDefault("messageBack.enabled", true);
        config.addDefault("messageBack.string", "first time user logged in");
        config.addDefault("messageBack.color", "&f");

        config.options().copyDefaults(true);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        String worldName = config.getString("World.name", "world");

        // Determine playerdata dir for counting / listing players to date
        File worldFolder = Bukkit.getWorld(worldName) != null
                ? Bukkit.getWorld(worldName).getWorldFolder()
                : Bukkit.getWorldContainer().toPath().resolve(worldName).toFile();
        File playerDataDir = new File(worldFolder, "playerdata");
        List<String> namesToDate = new ArrayList<>();
        if (playerDataDir.isDirectory()) {
            File[] files = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files != null) {
                for (File f : files) {
                    String base = f.getName().substring(0, f.getName().length() - 4);
                    try {
                        UUID uuid = UUID.fromString(base);
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        String name = op.getName();
                        if (name != null) namesToDate.add(name);
                    } catch (IllegalArgumentException ignored) {
                        // Not a UUID (unlikely on modern), skip
                    }
                }
            }
        }

        String cmdName = cmd.getName().toLowerCase(Locale.ROOT);
        switch (cmdName) {
            case "listp": {
                if (!player.hasPermission("firstlogin.command.listp")) {
                    sendMsg(player, msg("messages.noPermission"), player, 0);
                    return true;
                }
                int num = namesToDate.size();
                player.sendMessage(ChatColor.DARK_RED + "Number of players joined to date: " + num);
                return true;
            }
            case "pnames": {
                if (!player.hasPermission("firstlogin.command.pnames")) {
                    sendMsg(player, msg("messages.noPermission"), player, 0);
                    return true;
                }
                String names = namesToDate.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
                player.sendMessage(ChatColor.DARK_RED + "Names of players joined to date: " + (names.isEmpty() ? "(none)" : names));
                return true;
            }
            case "owner": {
                if (!player.hasPermission("firstlogin.command.owner")) {
                    sendMsg(player, msg("messages.noPermission"), player, 0);
                    return true;
                }
                String owner = config.getString("World.Owner", "default");
                player.sendMessage(owner);
                return true;
            }
            case "onlinep": {
                if (!player.hasPermission("firstlogin.command.onlinep")) {
                    sendMsg(player, msg("messages.noPermission"), player, 0);
                    return true;
                }
                int online = Bukkit.getOnlinePlayers().size();
                int total = namesToDate.size();
                player.sendMessage("Currently there are " + online + " of " + total + " players online.");
                return true;
            }
            case "firsthelp": {
                player.sendMessage("/listp: Lists the number of players joined to date.");
                player.sendMessage("/pnames: Lists all the names of players that joined the server.");
                player.sendMessage("/owner: Shows the owner of the server's name.");
                player.sendMessage("/onlinep: Shows how many players are currently online.");
                player.sendMessage("/firsthelp: Shows this help.");
                return true;
            }
            case "firstlogin": {
                if (!player.hasPermission("firstlogin.admin")) {
                    sendMsg(player, msg("messages.noPermission"), player, namesToDate.size());
                    return true;
                }
                if (args.length == 0) {
                    sendMsg(player, msg("messages.helpHeader"), player, namesToDate.size());
                    sendMsg(player, msg("messages.helpLine"), player, namesToDate.size());
                    return true;
                }
                String sub = args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "reload": {
                        reloadConfig();
                        config = getConfig();
                        ensureDefaultConfigValues();
                        messages = YamlConfiguration.loadConfiguration(messagesFile);
                        papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
                        sendMsg(player, msg("messages.reloaded"), player, namesToDate.size());
                        return true;
                    }
                    case "seen": {
                        if (args.length < 2) {
                            sendMsg(player, msg("messages.usageSeen"), player, namesToDate.size());
                            return true;
                        }
                        String targetName = args[1];
                        OfflinePlayer op = getOfflineByName(targetName);
                        if (op == null || op.getUniqueId() == null) {
                            player.sendMessage(ChatColor.RED + "Player not found: " + targetName);
                            return true;
                        }
                        boolean seen = players.getBoolean("players." + op.getUniqueId(), false);
                        sendMsg(player, msg(seen ? "messages.seenTrue" : "messages.seenFalse").replace("{player}", op.getName() == null ? targetName : op.getName()), player, namesToDate.size());
                        return true;
                    }
                    case "reset": {
                        if (args.length < 2) {
                            sendMsg(player, msg("messages.usageReset"), player, namesToDate.size());
                            return true;
                        }
                        String who = args[1];
                        if (who.equalsIgnoreCase("all")) {
                            players.set("players", null);
                            savePlayers();
                            sendMsg(player, msg("messages.resetAll"), player, namesToDate.size());
                        } else {
                            OfflinePlayer op = getOfflineByName(who);
                            if (op == null || op.getUniqueId() == null) {
                                player.sendMessage(ChatColor.RED + "Player not found: " + who);
                                return true;
                            }
                            players.set("players." + op.getUniqueId(), false);
                            savePlayers();
                            sendMsg(player, msg("messages.resetPlayer").replace("{player}", op.getName() == null ? who : op.getName()), player, namesToDate.size());
                        }
                        return true;
                    }
                    default:
                        sendMsg(player, msg("messages.helpHeader"), player, namesToDate.size());
                        sendMsg(player, msg("messages.helpLine"), player, namesToDate.size());
                        return true;
                }
            }
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("firstlogin")) {
            if (args.length == 1) {
                return Arrays.asList("reload", "seen", "reset").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toList());
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("reset")) {
                    List<String> base = new ArrayList<>();
                    base.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) base.add(p.getName());
                    return base.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                } else if (args[0].equalsIgnoreCase("seen")) {
                    List<String> base = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) base.add(p.getName());
                    return base.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                }
            }
        }
        return Collections.emptyList();
    }

    static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    // Inner join listener to reduce file count
    private static class JoinListener implements Listener {
        private final FirstLogin plugin;
        public JoinListener(FirstLogin plugin) { this.plugin = plugin; }

        @EventHandler
        public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            Player player = event.getPlayer();

            boolean isFirstEverJoin = !player.hasPlayedBefore();

            if (isFirstEverJoin) {
                // Global first-join message
                if (FirstLogin.config.getBoolean("messageGlobal.enabled", true)) {
                    String msg = FirstLogin.config.getString("messageGlobal.string", "first time user logged in");
                    String color = FirstLogin.config.getString("messageGlobal.color", "&f");
                    String combined = color + player.getDisplayName() + ": " + msg;
                    plugin.broadcastFormatted(combined, player, plugin.countPlayersToDate());
                }
                // Private first-join message
                if (FirstLogin.config.getBoolean("message.enabled", true)) {
                    String msg = FirstLogin.config.getString("message.string", "Welcome to the server");
                    String color = FirstLogin.config.getString("message.color", "&f");
                    plugin.sendMsg(player, color + msg, player, plugin.countPlayersToDate());
                }

                // Visuals (title/actionbar/sound)
                plugin.handleFirstJoinVisuals(player);
                return;
            }

            // Returning player: only send once after plugin installed (tracked in players.yml)
            String key = "players." + player.getUniqueId();
            boolean seen = FirstLogin.players.getBoolean(key, false);
            if (!seen && FirstLogin.config.getBoolean("messageBack.enabled", true)) {
                int minDays = FirstLogin.config.getInt("returningGate.minDaysOffline", 0);
                long lastPlayed = player.getLastPlayed();
                long now = System.currentTimeMillis();
                long days = lastPlayed > 0 ? (now - lastPlayed) / (1000L * 60L * 60L * 24L) : Long.MAX_VALUE;
                if (days >= minDays) {
                    String msg = FirstLogin.config.getString("messageBack.string", "first time user logged in");
                    String color = FirstLogin.config.getString("messageBack.color", "&f");
                    plugin.sendMsg(player, color + msg, player, plugin.countPlayersToDate());
                    FirstLogin.players.set(key, true);
                    plugin.savePlayers();
                }
            }
        }
    }

    // ===== Helpers =====
    private String msg(String path) {
        String s = messages.getString(path, "");
        return s == null ? "" : s;
    }

    private void broadcastFormatted(String text, Player context, int total) {
        // Send to all players and console with chosen formatting
        if (config.getBoolean("formatting.useMiniMessage", true)) {
            Component c = parseToComponent(text, context, total);
            for (Player p : Bukkit.getOnlinePlayers()) {
                adventure.player(p).sendMessage(c);
            }
            adventure.console().sendMessage(c);
        } else {
            String legacy = applyAllPlaceholders(text, context, total);
            Bukkit.getServer().broadcastMessage(colorize(legacy));
        }
    }

    private void sendMsg(CommandSender target, String text, Player context, int total) {
        if (config.getBoolean("formatting.useMiniMessage", true)) {
            Component c = parseToComponent(text, context, total);
            adventure.sender(target).sendMessage(c);
        } else {
            String legacy = applyAllPlaceholders(text, context, total);
            target.sendMessage(colorize(legacy));
        }
    }

    private Component parseToComponent(String text, Player context, int total) {
        String with = applyAllPlaceholders(text, context, total);
        // If MiniMessage-style tags are present, use MiniMessage. If legacy '&' codes are present, parse via legacy.
        if (with.indexOf('<') >= 0 && with.indexOf('>') > with.indexOf('<')) {
            return mm.deserialize(with);
        }
        if (with.contains("&")) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(with);
        }
        return Component.text(with);
    }

    private String applyAllPlaceholders(String text, Player player, int total) {
        String s = applyBuiltins(text, player, total);
        if (config.getBoolean("formatting.usePlaceholderAPI", true) && papiAvailable && player != null) {
            try {
                Class<?> clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                java.lang.reflect.Method m = clazz.getMethod("setPlaceholders", Player.class, String.class);
                Object out = m.invoke(null, player, s);
                if (out instanceof String) s = (String) out;
            } catch (Throwable ignored) {}
        }
        return s;
    }

    private String applyBuiltins(String text, Player player, int total) {
        if (!config.getBoolean("formatting.usePlaceholders", true)) return text;
        String s = text;
        String owner = config.getString("World.Owner", "default");
        s = s.replace("{owner}", owner);
        s = s.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
        s = s.replace("{total}", String.valueOf(total));
        if (player != null) {
            s = s.replace("{player}", player.getName());
        }
        return s;
    }

    private void handleFirstJoinVisuals(Player player) {
        // Title
        if (config.getBoolean("firstJoinVisuals.title.enabled", true)) {
            String title = config.getString("firstJoinVisuals.title.title", "<green>Welcome, {player}!");
            String subtitle = config.getString("firstJoinVisuals.title.subtitle", "<gray>Enjoy your stay.");
            int fi = config.getInt("firstJoinVisuals.title.fadeIn", 10);
            int st = config.getInt("firstJoinVisuals.title.stay", 60);
            int fo = config.getInt("firstJoinVisuals.title.fadeOut", 10);
            Component t = parseToComponent(title, player, countPlayersToDate());
            Component sub = parseToComponent(subtitle, player, countPlayersToDate());
            Title.Times times = Title.Times.times(Duration.ofMillis(fi * 50L), Duration.ofMillis(st * 50L), Duration.ofMillis(fo * 50L));
            adventure.player(player).showTitle(Title.title(t, sub, times));
        }
        // Actionbar
        if (config.getBoolean("firstJoinVisuals.actionbar.enabled", false)) {
            String ab = config.getString("firstJoinVisuals.actionbar.message", "<yellow>First time here!");
            adventure.player(player).sendActionBar(parseToComponent(ab, player, countPlayersToDate()));
        }
        // Sound
        if (config.getBoolean("firstJoinVisuals.sound.enabled", false)) {
            String name = config.getString("firstJoinVisuals.sound.name", "ENTITY_PLAYER_LEVELUP");
            float vol = (float) config.getDouble("firstJoinVisuals.sound.volume", 1.0);
            float pitch = (float) config.getDouble("firstJoinVisuals.sound.pitch", 1.0);
            try {
                Sound s = Sound.valueOf(name);
                player.playSound(player.getLocation(), s, vol, pitch);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private OfflinePlayer getOfflineByName(String name) {
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        }
        return null;
    }

    private int countPlayersToDate() {
        String worldName = config.getString("World.name", "world");
        File worldFolder = Bukkit.getWorld(worldName) != null
                ? Bukkit.getWorld(worldName).getWorldFolder()
                : Bukkit.getWorldContainer().toPath().resolve(worldName).toFile();
        File playerDataDir = new File(worldFolder, "playerdata");
        int count = 0;
        if (playerDataDir.isDirectory()) {
            File[] files = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files != null) count = files.length;
        }
        return count;
    }
}
