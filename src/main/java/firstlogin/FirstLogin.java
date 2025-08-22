package firstlogin;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import firstlogin.gui.WelcomeGui;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FirstLogin extends JavaPlugin {
    public static FileConfiguration config;
    public static FileConfiguration players;

    private File playersFile;
    private final Logger log = Logger.getLogger("Minecraft");
    private File messagesFile;
    private YamlConfiguration messages;
    private final Map<String, YamlConfiguration> localeCache = new HashMap<>();

    // Adventure / MiniMessage
    private BukkitAudiences adventure;
    private MiniMessage mm;

    // PlaceholderAPI availability
    private boolean papiAvailable;

    // Welcome GUI
    WelcomeGui welcomeGui;

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
        // Extract any bundled locale files (messages_*.yml) to data folder on startup
        extractBundledLocaleFiles();
        // Adventure init
        adventure = BukkitAudiences.create(this);
        mm = MiniMessage.miniMessage();

        // PlaceholderAPI detection
        papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        // Register listener (inner class)
        Bukkit.getPluginManager().registerEvents(new JoinListener(this), this);

        // Welcome GUI listener
        welcomeGui = new WelcomeGui(this);
        Bukkit.getPluginManager().registerEvents(welcomeGui, this);

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
                    sendMsg(player, msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                int num = namesToDate.size();
                player.sendMessage(ChatColor.DARK_RED + "Number of players joined to date: " + num);
                return true;
            }
            case "pnames": {
                if (!player.hasPermission("firstlogin.command.pnames")) {
                    sendMsg(player, msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                String names = namesToDate.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
                player.sendMessage(ChatColor.DARK_RED + "Names of players joined to date: " + (names.isEmpty() ? "(none)" : names));
                return true;
            }
            case "owner": {
                if (!player.hasPermission("firstlogin.command.owner")) {
                    sendMsg(player, msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                String owner = config.getString("World.Owner", "default");
                player.sendMessage(owner);
                return true;
            }
            case "onlinep": {
                if (!player.hasPermission("firstlogin.command.onlinep")) {
                    sendMsg(player, msgFor(player, "messages.noPermission"), player, 0);
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
                // Allow non-admin locale override for self
                if (args.length > 0 && args[0].equalsIgnoreCase("locale")) {
                    if (args.length < 2) {
                        sendMsg(player, msgFor(player, "messages.usageLocale"), player, namesToDate.size());
                        return true;
                    }
                    String sub = args[1];
                    if (sub.equalsIgnoreCase("reset")) {
                        players.set("locale." + player.getUniqueId(), null);
                        savePlayers();
                        sendMsg(player, msgFor(player, "messages.localeReset"), player, namesToDate.size());
                    } else {
                        players.set("locale." + player.getUniqueId(), sub);
                        savePlayers();
                        sendMsg(player, msgFor(player, "messages.localeSet").replace("{locale}", sub), player, namesToDate.size());
                    }
                    return true;
                }
                if (!player.hasPermission("firstlogin.admin")) {
                    sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size());
                    return true;
                }
                if (args.length == 0) {
                    sendMsg(player, msgFor(player, "messages.helpHeader"), player, namesToDate.size());
                    sendMsg(player, msgFor(player, "messages.helpLine"), player, namesToDate.size());
                    return true;
                }
                String sub = args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "reload": {
                        reloadConfig();
                        config = getConfig();
                        ensureDefaultConfigValues();
                        messages = YamlConfiguration.loadConfiguration(messagesFile);
                        // Extract any bundled locale files to data folder
                        extractBundledLocaleFiles();
                        papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
                        localeCache.clear();
                        sendMsg(player, msgFor(player, "messages.reloaded"), player, namesToDate.size());
                        return true;
                    }
                    case "gui": {
                        if (welcomeGui == null) {
                            player.sendMessage(ChatColor.RED + "Welcome GUI not initialized.");
                            return true;
                        }
                        if (args.length == 1) {
                            player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin gui <open|accept|trigger> [...]");
                            return true;
                        }
                        String sub2 = args[1].toLowerCase(Locale.ROOT);
                        switch (sub2) {
                            case "open": {
                                Player target = player;
                                if (args.length >= 3) {
                                    Player p = Bukkit.getPlayerExact(args[2]);
                                    if (p != null) target = p; else { player.sendMessage(ChatColor.RED + "Player not found."); return true; }
                                }
                                welcomeGui.openFor(target);
                                player.sendMessage(ChatColor.GREEN + "Opened GUI for " + target.getName());
                                return true;
                            }
                            case "accept": {
                                Player target = player;
                                if (args.length >= 3) {
                                    Player p = Bukkit.getPlayerExact(args[2]);
                                    if (p != null) target = p; else { player.sendMessage(ChatColor.RED + "Player not found."); return true; }
                                }
                                welcomeGui.acceptRules(target);
                                player.sendMessage(ChatColor.GREEN + "Marked rules accepted for " + target.getName());
                                return true;
                            }
                            case "trigger": {
                                if (args.length < 3) { player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin gui trigger <key> [player]"); return true; }
                                String key = args[2];
                                Player target = player;
                                if (args.length >= 4) {
                                    Player p = Bukkit.getPlayerExact(args[3]);
                                    if (p != null) target = p; else { player.sendMessage(ChatColor.RED + "Player not found."); return true; }
                                }
                                welcomeGui.triggerItem(target, key);
                                player.sendMessage(ChatColor.GREEN + "Triggered '" + key + "' for " + target.getName());
                                return true;
                            }
                            default:
                                player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin gui <open|accept|trigger>");
                                return true;
                        }
                    }
                    case "clearcooldown": {
                        if (args.length < 3) {
                            player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin clearcooldown <player> <key|all>");
                            return true;
                        }
                        OfflinePlayer op = getOfflineByName(args[1]);
                        if (op == null || op.getUniqueId() == null) { player.sendMessage(ChatColor.RED + "Player not found: " + args[1]); return true; }
                        String key = args[2];
                        if (key.equalsIgnoreCase("all")) {
                            players.set("cooldowns." + op.getUniqueId(), null);
                            savePlayers();
                            sendMsg(player, msgFor(player, "messages.clearedCooldownAll").replace("{player}", op.getName() == null ? args[1] : op.getName()), player, namesToDate.size());
                        } else {
                            players.set("cooldowns." + op.getUniqueId() + "." + key, null);
                            savePlayers();
                            sendMsg(player, msgFor(player, "messages.clearedCooldown").replace("{player}", op.getName() == null ? args[1] : op.getName()).replace("{key}", key), player, namesToDate.size());
                        }
                        return true;
                    }
                    case "clearflag": {
                        if (args.length < 3) {
                            player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin clearflag <player> <flag|all>");
                            return true;
                        }
                        OfflinePlayer op = getOfflineByName(args[1]);
                        if (op == null || op.getUniqueId() == null) { player.sendMessage(ChatColor.RED + "Player not found: " + args[1]); return true; }
                        String flag = args[2];
                        if (flag.equalsIgnoreCase("all")) {
                            players.set("flags." + op.getUniqueId(), null);
                            savePlayers();
                            sendMsg(player, msgFor(player, "messages.clearedAllFlags").replace("{player}", op.getName() == null ? args[1] : op.getName()), player, namesToDate.size());
                        } else {
                            String v = versionedFlagName(flag);
                            players.set("flags." + op.getUniqueId() + "." + v, null);
                            savePlayers();
                            sendMsg(player, msgFor(player, "messages.clearedFlag").replace("{player}", op.getName() == null ? args[1] : op.getName()).replace("{flag}", v), player, namesToDate.size());
                        }
                        return true;
                    }
                    case "seen": {
                        if (args.length < 2) {
                            sendMsg(player, msgFor(player, "messages.usageSeen"), player, namesToDate.size());
                            return true;
                        }
                        String targetName = args[1];
                        OfflinePlayer op = getOfflineByName(targetName);
                        if (op == null || op.getUniqueId() == null) {
                            player.sendMessage(ChatColor.RED + "Player not found: " + targetName);
                            return true;
                        }
                        boolean seen = players.getBoolean("players." + op.getUniqueId(), false);
                        sendMsg(player, msgFor(player, seen ? "messages.seenTrue" : "messages.seenFalse").replace("{player}", op.getName() == null ? targetName : op.getName()), player, namesToDate.size());
                        return true;
                    }
                    case "reset": {
                        if (args.length < 2) {
                            sendMsg(player, msgFor(player, "messages.usageReset"), player, namesToDate.size());
                            return true;
                        }
                        String who = args[1];
                        if (who.equalsIgnoreCase("all")) {
                            players.set("players", null);
                            savePlayers();
                            sendMsg(player, msgFor(player, "messages.resetAll"), player, namesToDate.size());
                        } else {
                            OfflinePlayer op = getOfflineByName(who);
                            if (op == null || op.getUniqueId() == null) {
                                player.sendMessage(ChatColor.RED + "Player not found: " + who);
                                return true;
                            }
                            players.set("players." + op.getUniqueId(), false);
                            savePlayers();
                            sendMsg(player, msgFor(player, "messages.resetPlayer").replace("{player}", op.getName() == null ? who : op.getName()), player, namesToDate.size());
                        }
                        return true;
                    }
                    default:
                        sendMsg(player, msgFor(player, "messages.helpHeader"), player, namesToDate.size());
                        sendMsg(player, msgFor(player, "messages.helpLine"), player, namesToDate.size());
                        return true;
                }
            }
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("firstlogin")) return Collections.emptyList();

        // Helper to filter with case-insensitive startsWith
        java.util.function.BiFunction<List<String>, String, List<String>> filter = (list, pref) -> {
            String p = pref == null ? "" : pref.toLowerCase(Locale.ROOT);
            return list.stream().filter(s -> s != null && s.toLowerCase(Locale.ROOT).startsWith(p)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        };

        if (args.length == 1) {
            List<String> base = new ArrayList<>();
            base.add("locale");
            if (sender.hasPermission("firstlogin.admin")) {
                base.addAll(Arrays.asList("reload", "gui", "clearcooldown", "clearflag", "seen", "reset"));
            }
            return filter.apply(base, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "locale": {
                    // Suggest reset + available locale tags from data folder (messages_*.yml)
                    Set<String> opts = new HashSet<>();
                    opts.add("reset");
                    try {
                        File df = getDataFolder();
                        File[] files = df.listFiles((dir, name) -> name.startsWith("messages_") && name.endsWith(".yml"));
                        if (files != null) {
                            for (File f : files) {
                                String n = f.getName();
                                String tag = n.substring("messages_".length(), n.length() - 4);
                                if (!tag.isEmpty()) {
                                    opts.add(tag);
                                    int us = tag.indexOf('_');
                                    if (us > 0) opts.add(tag.substring(0, us));
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return filter.apply(new ArrayList<>(opts), args[1]);
                }
                case "gui":
                    return filter.apply(Arrays.asList("open", "accept", "trigger"), args[1]);
                case "clearcooldown":
                case "clearflag":
                case "seen":
                case "reset": {
                    List<String> names = new ArrayList<>();
                    if (sub.equals("reset")) names.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                    for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null) names.add(p.getName());
                    return filter.apply(names, args[1]);
                }
                default:
                    return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "clearcooldown": {
                    OfflinePlayer op = getOfflineByName(args[1]);
                    if (op == null || op.getUniqueId() == null) return Collections.emptyList();
                    Set<String> keys = new HashSet<>();
                    keys.add("all");
                    String path = "cooldowns." + op.getUniqueId();
                    org.bukkit.configuration.ConfigurationSection cs = players.getConfigurationSection(path);
                    if (cs != null) keys.addAll(cs.getKeys(false));
                    return keys.stream().sorted(String.CASE_INSENSITIVE_ORDER).filter(k -> k.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                }
                case "clearflag": {
                    OfflinePlayer op = getOfflineByName(args[1]);
                    if (op == null || op.getUniqueId() == null) return Collections.emptyList();
                    Set<String> keys = new HashSet<>();
                    keys.add("all");
                    String path = "flags." + op.getUniqueId();
                    org.bukkit.configuration.ConfigurationSection cs = players.getConfigurationSection(path);
                    if (cs != null) keys.addAll(cs.getKeys(false));
                    return keys.stream().sorted(String.CASE_INSENSITIVE_ORDER).filter(k -> k.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                }
                case "gui": {
                    String sub2 = args[1].toLowerCase(Locale.ROOT);
                    if (sub2.equals("open") || sub2.equals("accept")) {
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null) names.add(p.getName());
                        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                    if (sub2.equals("trigger")) {
                        org.bukkit.configuration.ConfigurationSection items = config.getConfigurationSection("welcomeGui.items");
                        List<String> keys = items != null ? new ArrayList<>(items.getKeys(false)) : Collections.emptyList();
                        return keys.stream().sorted(String.CASE_INSENSITIVE_ORDER).filter(k -> k.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                    return Collections.emptyList();
                }
                default:
                    return Collections.emptyList();
            }
        }

        if (args.length == 4) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("gui") && args[1].equalsIgnoreCase("trigger")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null) names.add(p.getName());
                return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    public static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    /**
     * Like colorize(), but also supports hex colors in the forms <#RRGGBB> and &#RRGGBB
     * by converting them to the §x§R§R§G§G§B§B format supported by Spigot 1.16+.
     * Safe to use for inventory titles and item meta strings.
     */
    public static String colorizeWithHex(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = replaceHex(input);
        return colorize(s);
    }

    // Patterns: MiniMessage-like <#RRGGBB> and common &#RRGGBB
    private static final Pattern HEX_MM = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern HEX_AMP = Pattern.compile("&?#([A-Fa-f0-9]{6})");

    private static String replaceHex(String text) {
        String out = text;
        Matcher mm = HEX_MM.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (mm.find()) {
            mm.appendReplacement(sb, toSectionHex(mm.group(1)));
        }
        mm.appendTail(sb);
        out = sb.toString();

        Matcher m2 = HEX_AMP.matcher(out);
        sb.setLength(0);
        while (m2.find()) {
            m2.appendReplacement(sb, toSectionHex(m2.group(1)));
        }
        m2.appendTail(sb);
        return sb.toString();
    }

    private static String toSectionHex(String hex) {
        char[] c = hex.toCharArray();
        return "\u00A7x\u00A7" + c[0] + "\u00A7" + c[1] + "\u00A7" + c[2] + "\u00A7" + c[3] + "\u00A7" + c[4] + "\u00A7" + c[5];
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
                // Open Welcome GUI if enabled
                if (plugin.welcomeGui != null && plugin.welcomeGui.isEnabled()) {
                    plugin.welcomeGui.openFor(player);
                }
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
    @SuppressWarnings("unused")
    private String msg(String path) {
        String s = messages.getString(path, "");
        return s == null ? "" : s;
    }

    // Locale-aware helpers
    private String normalizeLocaleTag(String tag) {
        if (tag == null || tag.isEmpty()) return "default";
        String t = tag.toLowerCase(Locale.ROOT).replace('-', '_');
        return t;
    }

    private YamlConfiguration messagesFor(String localeTag) {
        String tag = normalizeLocaleTag(localeTag);
        if ("default".equals(tag)) return messages;
        if (localeCache.containsKey(tag)) return localeCache.get(tag);
        // Try messages_<tag>.yml, then language-only messages_<lang>.yml
        File byTag = new File(getDataFolder(), "messages_" + tag + ".yml");
        if (byTag.exists()) {
            YamlConfiguration yc = YamlConfiguration.loadConfiguration(byTag);
            localeCache.put(tag, yc);
            return yc;
        }
        int us = tag.indexOf('_');
        if (us > 0) {
            String lang = tag.substring(0, us);
            File byLang = new File(getDataFolder(), "messages_" + lang + ".yml");
            if (byLang.exists()) {
                YamlConfiguration yc = YamlConfiguration.loadConfiguration(byLang);
                localeCache.put(tag, yc);
                return yc;
            }
        }
        // Fallback to default
        localeCache.put(tag, messages);
        return messages;
    }

    // Extract any bundled locale files (messages_*.yml) from the plugin JAR to the data folder
    private void extractBundledLocaleFiles() {
        try {
            File jar = getFile();
            if (jar == null || !jar.isFile()) {
                copyIfAbsent("messages_en_us.yml");
                return;
            }
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    java.util.jar.JarEntry e = en.nextElement();
                    String name = e.getName();
                    if (name.startsWith("messages_") && name.endsWith(".yml") && !e.isDirectory()) {
                        String fileName = name.substring(name.lastIndexOf('/') + 1);
                        File target = new File(getDataFolder(), fileName);
                        if (!target.exists()) {
                            saveResource(name, false);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            copyIfAbsent("messages_en_us.yml");
        }
    }

    private void copyIfAbsent(String resourceName) {
        try {
            File target = new File(getDataFolder(), resourceName);
            if (!target.exists()) {
                saveResource(resourceName, false);
            }
        } catch (Throwable ignored) {}
    }

    private String playerLocaleTag(Player p) {
        // Per-player override in players.yml takes precedence
        String override = players.getString("locale." + p.getUniqueId(), null);
        if (override != null && !override.isEmpty()) return normalizeLocaleTag(override);
        try {
            String loc = p.getLocale();
            return normalizeLocaleTag(loc);
        } catch (Throwable ignored) {
            return "default";
        }
    }

    private String msgFor(Player p, String path) {
        YamlConfiguration yc = messagesFor(playerLocaleTag(p));
        String s = yc.getString(path);
        if (s == null) s = messages.getString(path, "");
        return s == null ? "" : s;
    }

    @SuppressWarnings("unused")
    private List<String> msgListFor(Player p, String path) {
        YamlConfiguration yc = messagesFor(playerLocaleTag(p));
        List<String> list = yc.getStringList(path);
        if (list == null || list.isEmpty()) {
            List<String> def = messages.getStringList(path);
            return def == null ? Collections.emptyList() : def;
        }
        return list;
    }

    private String versionedFlagName(String flag) {
        if (!"rules".equalsIgnoreCase(flag)) return flag;
        int ver = config.getInt("welcomeGui.rulesVersion", 1);
        return "rules_v" + ver;
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

    // Clickable link helper for GUI URLs
    @SuppressWarnings("unused") // Used by WelcomeGui via reflection
    private void sendClickableLink(Player player, String label, String url) {
        Component base = LegacyComponentSerializer.legacySection().deserialize(label);
        Component clickable = base.clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url)));
        adventure.player(player).sendMessage(clickable);
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
