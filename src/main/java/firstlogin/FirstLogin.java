package firstlogin;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import firstlogin.gui.WelcomeGui;
import firstlogin.event.RulesAcceptedEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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
    // PlaceholderAPI expansion instance (if registered)
    private firstlogin.papi.FirstLoginExpansion papiExpansion;

    // Welcome GUI
    WelcomeGui welcomeGui;

    // Simple in-memory metrics (per JVM day)
    private int metricsGuiOpensToday = 0;
    private int metricsRulesAcceptedToday = 0;
    private String metricsDay = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
    private final java.util.Map<String, Integer> telemetryItemClicksToday = new java.util.HashMap<>();

    // Telemetry persistence
    private File telemetryFile;
    private YamlConfiguration telemetry;
    private boolean telemetryPersistEnabled;
    private int telemetryRetentionDays;

    // Cached count of players to-date to avoid repeated filesystem scans on main thread
    private volatile int cachedPlayersToDate = -1;
    private volatile long cachedPlayersToDateAt = 0L;
    private static final long PLAYERS_TO_DATE_TTL_MS = 30_000L; // 30s
    private volatile boolean computingPlayersToDate = false;

    // ===== Asynchronous players.yml save queue =====
    private final Object playersSaveLock = new Object();
    private volatile boolean playersDirty = false;
    private volatile boolean saveScheduled = false;
    private volatile int scheduledSaveTaskId = -1;

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

        // Initialize telemetry persistence
        initTelemetryPersistence();

        // Register PlaceholderAPI expansion (optional)
        if (papiAvailable) {
            try {
                papiExpansion = new firstlogin.papi.FirstLoginExpansion(this);
                papiExpansion.register();
            } catch (Throwable t) {
                getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
            }
        }

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
                    Metrics m = new Metrics(this, pluginId);
                    // Simple charts for today's counters
                    m.addCustomChart(new SingleLineChart("gui_opens_today", () -> metricsGuiOpensToday));
                    m.addCustomChart(new SingleLineChart("rules_accepted_today", () -> metricsRulesAcceptedToday));
                    // Per-item click distribution
                    m.addCustomChart(new AdvancedPie("clicked_items_today", () -> new java.util.HashMap<>(telemetryItemClicksToday)));
                } catch (Throwable t) {
                    getLogger().warning("Could not start bStats metrics: " + t.getMessage());
                }
            }
        }

        String ver = getDescription().getVersion();
        log.info("FirstLogin " + ver + " - Enabled");

        // Warm up players-to-date cache asynchronously to avoid first-use stall
        Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshPlayersToDate);
    }

    @Override
    public void onDisable() {
        // Flush any queued saves before shutdown, then do a final sync save
        try { flushPlayersSaves(); } catch (Throwable ignored) {}
        savePlayers();
        // Persist telemetry for today
        try { saveTelemetryToday(); } catch (Throwable ignored) {}
        // Unregister PAPI expansion if present
        if (papiExpansion != null) {
            try { papiExpansion.unregister(); } catch (Throwable ignored) {}
            papiExpansion = null;
        }
        if (adventure != null) {
            adventure.close();
            adventure = null;
        }
        String ver = getDescription().getVersion();
        log.info("FirstLogin " + ver + " - Disabled");
    }

    public void savePlayers() {
        try {
            players.save(playersFile);
        } catch (IOException e) {
            getLogger().severe("Could not save players.yml: " + e.getMessage());
        }
    }

    // Queue a debounced asynchronous save of players.yml to avoid blocking the main thread.
    public void queuePlayersSave() {
        playersDirty = true;
        if (saveScheduled) return;
        saveScheduled = true;
        try {
            scheduledSaveTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::doPlayersSaveAsync, 20L).getTaskId(); // ~1s debounce
        } catch (Throwable t) {
            // Fallback: if async scheduling fails, save synchronously
            saveScheduled = false;
            savePlayers();
            playersDirty = false;
        }
    }

    private void doPlayersSaveAsync() {
        // Snapshot dirty flag and attempt a save; if dirtied again during save, schedule another
        boolean hadDirty = playersDirty;
        saveScheduled = false;
        if (!hadDirty) return;
        try {
            synchronized (playersSaveLock) {
                players.save(playersFile);
            }
            playersDirty = false;
        } catch (Throwable e) {
            getLogger().warning("Async save of players.yml failed: " + e.getMessage());
        }
        // If new writes happened while saving, schedule another save shortly
        if (playersDirty && !saveScheduled) {
            try {
                saveScheduled = true;
                scheduledSaveTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::doPlayersSaveAsync, 20L).getTaskId();
            } catch (Throwable ignored) {}
        }
    }

    public void flushPlayersSaves() {
        // Cancel any scheduled task and perform a synchronous save
        try {
            if (scheduledSaveTaskId != -1) {
                Bukkit.getScheduler().cancelTask(scheduledSaveTaskId);
                scheduledSaveTaskId = -1;
                saveScheduled = false;
            }
        } catch (Throwable ignored) {}
        try {
            if (playersDirty) {
                synchronized (playersSaveLock) {
                    players.save(playersFile);
                }
                playersDirty = false;
            }
        } catch (Throwable e) {
            getLogger().warning("Flush save of players.yml failed: " + e.getMessage());
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

        // Debug toggles
        config.addDefault("debug.gui", false);
        config.addDefault("debug.inventory", false);

        // GUI behavior
        config.addDefault("welcomeGui.reopenOnJoinUntilAccepted", false);
        config.addDefault("welcomeGui.openDelayTicks", 40);
        config.addDefault("welcomeGui.blockCloseUntilAccepted", false);
        config.addDefault("welcomeGui.confirmOnAccept", false);
        // Allow players with permission to bypass forced reopen on close
        config.addDefault("welcomeGui.bypassClosePermission", true);
        // Configurable bypass permission node (kept alongside legacy boolean for compatibility)
        config.addDefault("welcomeGui.bypassClosePermissionNode", "firstlogin.bypass.rules");
        // Delay (in ticks) before forced reopen happens after closing
        config.addDefault("welcomeGui.reopenDelayTicks", 1L);

        // Confirm dialog defaults
        // YES button
        config.addDefault("welcomeGui.confirmDialog.yes.material", "LIME_WOOL");
        config.addDefault("welcomeGui.confirmDialog.yes.name", "&aConfirm");
        config.addDefault("welcomeGui.confirmDialog.yes.lore", java.util.Arrays.asList());
        config.addDefault("welcomeGui.confirmDialog.yes.clickSound.name", "UI_BUTTON_CLICK");
        config.addDefault("welcomeGui.confirmDialog.yes.clickSound.volume", 1.0);
        config.addDefault("welcomeGui.confirmDialog.yes.clickSound.pitch", 1.0);
        // LATER button
        config.addDefault("welcomeGui.confirmDialog.later.enabled", true);
        config.addDefault("welcomeGui.confirmDialog.later.material", "YELLOW_WOOL");
        config.addDefault("welcomeGui.confirmDialog.later.name", "&eRemind me later");
        config.addDefault("welcomeGui.confirmDialog.later.lore", java.util.Arrays.asList());
        config.addDefault("welcomeGui.confirmDialog.later.cooldownSeconds", 60);
        config.addDefault("welcomeGui.confirmDialog.later.clickSound.name", "UI_BUTTON_CLICK");
        config.addDefault("welcomeGui.confirmDialog.later.clickSound.volume", 1.0);
        config.addDefault("welcomeGui.confirmDialog.later.clickSound.pitch", 1.0);
        // NO button
        config.addDefault("welcomeGui.confirmDialog.no.material", "RED_WOOL");
        config.addDefault("welcomeGui.confirmDialog.no.name", "&cCancel");
        config.addDefault("welcomeGui.confirmDialog.no.lore", java.util.Arrays.asList());
        config.addDefault("welcomeGui.confirmDialog.no.clickSound.name", "UI_BUTTON_CLICK");
        config.addDefault("welcomeGui.confirmDialog.no.clickSound.volume", 1.0);
        config.addDefault("welcomeGui.confirmDialog.no.clickSound.pitch", 1.0);

        // Telemetry persistence
        config.addDefault("telemetry.persist.enabled", true);
        config.addDefault("telemetry.persist.retentionDays", 14);
        // bStats metrics defaults
        config.addDefault("metrics.enabled", true);
        config.addDefault("metrics.pluginId", 0);

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
            case "welcome": {
                if (welcomeGui == null || !welcomeGui.isEnabled()) {
                    player.sendMessage(ChatColor.RED + "Welcome GUI is not enabled.");
                    return true;
                }
                int page = 1;
                if (args.length >= 1) {
                    try { page = Math.max(1, Integer.parseInt(args[0])); } catch (NumberFormatException ignored) {}
                }
                welcomeGui.openFor(player, page);
                return true;
            }
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
                if (args.length == 0) {
                    sendMsg(player, msgFor(player, "messages.helpHeader"), player, namesToDate.size());
                    sendMsg(player, msgFor(player, "messages.helpLine"), player, namesToDate.size());
                    return true;
                }
                String sub = args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "reload": {
                        if (!hasAdminSub(player, "reload")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
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
                        if (!hasAdminSub(player, "gui")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
                        if (welcomeGui == null) {
                            player.sendMessage(ChatColor.RED + "Welcome GUI not initialized.");
                            return true;
                        }
                        if (args.length == 1) {
                            player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin gui <open|accept|trigger>");
                            return true;
                        }
                        String sub2 = args[1].toLowerCase(Locale.ROOT);
                        switch (sub2) {
                            case "open": {
                                Player target = player;
                                int page = 1;
                                if (args.length >= 3) {
                                    // Support either: open <player> [page] OR open <page> (self)
                                    try {
                                        page = Math.max(1, Integer.parseInt(args[2]));
                                    } catch (NumberFormatException nfe) {
                                        Player p = Bukkit.getPlayerExact(args[2]);
                                        if (p != null) target = p; else { player.sendMessage(ChatColor.RED + "Player not found."); return true; }
                                    }
                                }
                                if (args.length >= 4) {
                                    try { page = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException nfe) { player.sendMessage(ChatColor.YELLOW + "Page must be a number."); return true; }
                                }
                                welcomeGui.openFor(target, page);
                                player.sendMessage(ChatColor.GREEN + "Opened GUI (page " + page + ") for " + target.getName());
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
                        if (!hasAdminSub(player, "clearcooldown")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
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
                        if (!hasAdminSub(player, "clearflag")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
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
                        if (!hasAdminSub(player, "seen")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
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
                        if (!hasAdminSub(player, "reset")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
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
                    case "status": {
                        if (!hasAdminSub(player, "status")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
                        OfflinePlayer target = player;
                        if (args.length >= 2) {
                            OfflinePlayer op = getOfflineByName(args[1]);
                            if (op == null || op.getUniqueId() == null) { player.sendMessage(ChatColor.RED + "Player not found: " + args[1]); return true; }
                            target = op;
                        }
                        UUID tu = target.getUniqueId();
                        String tName = target.getName() == null ? (args.length >= 2 ? args[1] : player.getName()) : target.getName();
                        String loc = players.getString("locale." + tu, "(default)");
                        boolean accepted = players.getBoolean("flags." + tu + "." + versionedFlagName("rules"), false);
                        org.bukkit.configuration.ConfigurationSection fcs = players.getConfigurationSection("flags." + tu);
                        org.bukkit.configuration.ConfigurationSection ccs = players.getConfigurationSection("cooldowns." + tu);
                        Set<String> flags = fcs != null ? fcs.getKeys(false) : java.util.Collections.emptySet();
                        Set<String> cds = ccs != null ? ccs.getKeys(false) : java.util.Collections.emptySet();
                        player.sendMessage(ChatColor.AQUA + "== FirstLogin Status: " + ChatColor.WHITE + tName + ChatColor.AQUA + " ==");
                        player.sendMessage(ChatColor.GRAY + "Locale: " + ChatColor.YELLOW + loc);
                        player.sendMessage(ChatColor.GRAY + "Rules accepted: " + (accepted ? ChatColor.GREEN + "yes" : ChatColor.RED + "no"));
                        player.sendMessage(ChatColor.GRAY + "Flags(" + flags.size() + "): " + ChatColor.YELLOW + (flags.isEmpty() ? "(none)" : String.join(", ", flags)));
                        player.sendMessage(ChatColor.GRAY + "Cooldown keys(" + cds.size() + "): " + ChatColor.YELLOW + (cds.isEmpty() ? "(none)" : String.join(", ", cds)));
                        return true;
                    }
                    case "set": {
                        if (!hasAdminSub(player, "set")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
                        if (args.length < 3) {
                            player.sendMessage(ChatColor.YELLOW + "Usage: /firstlogin set <key> <value>");
                            return true;
                        }
                        String key = args[1];
                        String value = args[2];
                        try {
                            switch (key.toLowerCase(Locale.ROOT)) {
                                case "welcomegui.reopenonjoinuntilaccepted":
                                case "debug.gui":
                                case "debug.inventory":
                                case "welcomegui.blockcloseuntilaccepted":
                                case "welcomegui.confirmonaccept": {
                                    boolean b = Boolean.parseBoolean(value);
                                    config.set(key, b);
                                    saveConfig();
                                    player.sendMessage(ChatColor.GREEN + "Set " + key + " = " + b);
                                    break;
                                }
                                case "welcomegui.rulesversion": {
                                    int v = Integer.parseInt(value);
                                    if (v < 1) v = 1;
                                    config.set(key, v);
                                    saveConfig();
                                    player.sendMessage(ChatColor.GREEN + "Set " + key + " = " + v);
                                    break;
                                }
                                default:
                                    player.sendMessage(ChatColor.RED + "Unknown key: " + key);
                            }
                        } catch (NumberFormatException nfe) {
                            player.sendMessage(ChatColor.RED + "Value must be a number: " + value);
                        }
                        return true;
                    }
                    case "metrics": {
                        if (!hasAdminSub(player, "metrics")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
                        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                            resetMetrics();
                            player.sendMessage(ChatColor.YELLOW + "Telemetry counters reset for today.");
                            return true;
                        }
                        player.sendMessage(ChatColor.AQUA + "== FirstLogin Telemetry (today) ==");
                        player.sendMessage(ChatColor.GRAY + "GUI opens: " + ChatColor.YELLOW + metricsGuiOpensToday);
                        player.sendMessage(ChatColor.GRAY + "Rules accepted: " + ChatColor.YELLOW + metricsRulesAcceptedToday);
                        return true;
                    }
                    case "forceopen": {
                        if (!hasAdminSub(player, "forceopen")) { sendMsg(player, msgFor(player, "messages.noPermission"), player, namesToDate.size()); return true; }
                        if (welcomeGui == null || !welcomeGui.isEnabled()) { player.sendMessage(ChatColor.RED + "Welcome GUI is not enabled."); return true; }
                        int count = 0;
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (!hasAcceptedRules(p)) {
                                welcomeGui.openFor(p, 1);
                                count++;
                            }
                        }
                        player.sendMessage(ChatColor.GREEN + "Reopened Welcome GUI for " + count + " player(s) who have not accepted current rules version.");
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
                base.addAll(Arrays.asList("reload", "gui", "clearcooldown", "clearflag", "seen", "reset", "status", "set", "metrics", "forceopen"));
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
                case "status": {
                    List<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                    for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null) names.add(p.getName());
                    return filter.apply(names, args[1]);
                }
                case "set": {
                    List<String> keys = Arrays.asList(
                            "welcomegui.reopenonjoinuntilaccepted",
                            "welcomegui.blockcloseuntilaccepted",
                            "welcomegui.confirmonaccept",
                            "welcomegui.rulesversion",
                            "debug.gui",
                            "debug.inventory"
                    );
                    return filter.apply(keys, args[1]);
                }
                case "metrics": {
                    return filter.apply(Collections.singletonList("reset"), args[1]);
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
                case "set": {
                    String key = args[1].toLowerCase(Locale.ROOT);
                    if (key.equals("welcomegui.rulesversion")) {
                        return filter.apply(Arrays.asList("1", "2", "3"), args[2]);
                    }
                    // booleans
                    return filter.apply(Arrays.asList("true", "false"), args[2]);
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

    // Granular admin permission helper
    private boolean hasAdminSub(Player player, String sub) {
        if (player.hasPermission("firstlogin.admin")) return true;
        // If lacking admin permission, we still allow action-specific perms, but this helper
        // is used for admin subcommands; default deny.
        return false;
    }

    public void recordGuiOpen() {
        ensureMetricsDay();
        metricsGuiOpensToday++;
        try { saveTelemetryToday(); } catch (Throwable ignored) {}
    }

    public void recordRulesAccepted() {
        ensureMetricsDay();
        metricsRulesAcceptedToday++;
        try { saveTelemetryToday(); } catch (Throwable ignored) {}
    }

    public void recordItemClick(String key) {
        if (key == null || key.isEmpty()) return;
        ensureMetricsDay();
        telemetryItemClicksToday.merge(key, 1, Integer::sum);
        try { saveTelemetryToday(); } catch (Throwable ignored) {}
    }

    public void resetMetrics() {
        metricsGuiOpensToday = 0;
        metricsRulesAcceptedToday = 0;
        telemetryItemClicksToday.clear();
        metricsDay = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        try { saveTelemetryToday(); } catch (Throwable ignored) {}
    }

    // ===== Telemetry persistence (telemetry.yml) =====
    private void initTelemetryPersistence() {
        telemetryPersistEnabled = config.getBoolean("telemetry.persist.enabled", true);
        telemetryRetentionDays = Math.max(0, config.getInt("telemetry.persist.retentionDays", 14));
        telemetryFile = new File(getDataFolder(), "telemetry.yml");
        if (!telemetryFile.exists()) {
            try { // noinspection ResultOfMethodCallIgnored
                telemetryFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create telemetry.yml: " + e.getMessage());
            }
        }
        telemetry = YamlConfiguration.loadConfiguration(telemetryFile);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        metricsDay = today;
        if (telemetryPersistEnabled) {
            metricsGuiOpensToday = telemetry.getInt("days." + today + ".guiOpens", metricsGuiOpensToday);
            metricsRulesAcceptedToday = telemetry.getInt("days." + today + ".rulesAccepted", metricsRulesAcceptedToday);
            org.bukkit.configuration.ConfigurationSection ic = telemetry.getConfigurationSection("days." + today + ".itemClicks");
            telemetryItemClicksToday.clear();
            if (ic != null) {
                for (String k : ic.getKeys(false)) {
                    telemetryItemClicksToday.put(k, ic.getInt(k, 0));
                }
            }
            try { saveTelemetryToday(); pruneTelemetryRetention(); } catch (Throwable ignored) {}
        }
    }

    private void ensureMetricsDay() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        if (metricsDay != null && metricsDay.equals(today)) return;
        metricsDay = today;
        // Reset in-memory counters and load persisted values for today if any
        metricsGuiOpensToday = 0;
        metricsRulesAcceptedToday = 0;
        telemetryItemClicksToday.clear();
        if (telemetryPersistEnabled && telemetry != null) {
            metricsGuiOpensToday = telemetry.getInt("days." + today + ".guiOpens", 0);
            metricsRulesAcceptedToday = telemetry.getInt("days." + today + ".rulesAccepted", 0);
            org.bukkit.configuration.ConfigurationSection ic = telemetry.getConfigurationSection("days." + today + ".itemClicks");
            if (ic != null) for (String k : ic.getKeys(false)) telemetryItemClicksToday.put(k, ic.getInt(k, 0));
            try { saveTelemetryToday(); pruneTelemetryRetention(); } catch (Throwable ignored) {}
        }
    }

    private void saveTelemetryToday() {
        if (!telemetryPersistEnabled || telemetry == null) return;
        telemetry.set("days." + metricsDay + ".guiOpens", metricsGuiOpensToday);
        telemetry.set("days." + metricsDay + ".rulesAccepted", metricsRulesAcceptedToday);
        // Overwrite itemClicks section
        telemetry.set("days." + metricsDay + ".itemClicks", null);
        for (java.util.Map.Entry<String, Integer> e : telemetryItemClicksToday.entrySet()) {
            telemetry.set("days." + metricsDay + ".itemClicks." + e.getKey(), e.getValue());
        }
        try {
            telemetry.save(telemetryFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save telemetry.yml: " + e.getMessage());
        }
    }

    private void pruneTelemetryRetention() {
        if (!telemetryPersistEnabled || telemetry == null) return;
        org.bukkit.configuration.ConfigurationSection days = telemetry.getConfigurationSection("days");
        if (days == null) return;
        try {
            java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(Math.max(0, telemetryRetentionDays - 1L));
            for (String key : new java.util.HashSet<>(days.getKeys(false))) {
                try {
                    java.time.LocalDate d = java.time.LocalDate.parse(key);
                    if (d.isBefore(cutoff)) telemetry.set("days." + key, null);
                } catch (Throwable ignored) {}
            }
            telemetry.save(telemetryFile);
        } catch (IOException ignored) {}
    }

    // ===== Helpers and stubs to complete build =====
    public String msgFor(Player player, String path) {
        try {
            String s = messages != null ? messages.getString(path) : null;
            if (s == null) s = path;
            return ChatColor.translateAlternateColorCodes('&', s);
        } catch (Throwable t) {
            return path;
        }
    }

    public void sendMsg(Player to, String message, Object... ignoredArgs) {
        if (to == null || message == null || message.isEmpty()) return;
        to.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void extractBundledLocaleFiles() {
        // Minimal no-op to avoid build failure; messages.yml is already copied on first run.
        // If additional locale files are bundled (messages_*.yml), server admins can copy them manually.
        // A fuller implementation could iterate plugin JAR resources and save them into data folder.
    }

    private OfflinePlayer getOfflineByName(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        }
        return null;
    }

    private String versionedFlagName(String base) {
        int v = 1;
        try {
            // Support both camelCase and lower-case keys
            if (config.contains("welcomeGui.rulesVersion")) {
                v = Math.max(1, config.getInt("welcomeGui.rulesVersion"));
            } else if (config.contains("welcomegui.rulesversion")) {
                v = Math.max(1, config.getInt("welcomegui.rulesversion"));
            }
        } catch (Throwable ignored) {}
        return v <= 1 ? base : base + "_v" + v;
    }

    public boolean hasAcceptedRules(Player player) {
        if (player == null) return false;
        UUID u = player.getUniqueId();
        String key = "flags." + u + "." + versionedFlagName("rules");
        return players != null && players.getBoolean(key, false);
    }

    // Mark current rules version accepted for player, set timestamp, fire event, record telemetry, run configured commands.
    public void autoAcceptIfPerm(Player player) {
        if (player == null) return;
        try {
            if (!player.hasPermission("firstlogin.autoaccept")) return;
            if (hasAcceptedRules(player)) return;
            UUID u = player.getUniqueId();
            String flagKey = "flags." + u + "." + versionedFlagName("rules");
            players.set(flagKey, true);
            players.set("timestamps." + u + ".rules_accepted", System.currentTimeMillis());
            queuePlayersSave();
            try { recordRulesAccepted(); } catch (Throwable ignored) {}
            // Fire event and run configured commands (silent; no chat message)
            try { org.bukkit.Bukkit.getPluginManager().callEvent(new RulesAcceptedEvent(player)); } catch (Throwable ignored) {}
            try { runRulesAcceptedCommands(player); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    // Run onRulesAccepted commands honoring runAs and placeholders
    public void runRulesAcceptedCommands(Player player) {
        if (player == null) return;
        java.util.List<String> cmds = config.getStringList("welcomeGui.onRulesAccepted.commands");
        if (cmds == null || cmds.isEmpty()) return;
        String runAs = config.getString("welcomeGui.onRulesAccepted.runAs", "console");
        for (String raw : cmds) {
            String cmd = applyPlaceholders(raw, player, playersToDate());
            switch (runAs == null ? "console" : runAs.toLowerCase(java.util.Locale.ROOT)) {
                case "player":
                    player.performCommand(cmd);
                    break;
                case "op": {
                    boolean wasOp = player.isOp();
                    try { player.setOp(true); player.performCommand(cmd); } finally { player.setOp(wasOp); }
                    break;
                }
                default:
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    // Public getters for telemetry counters used by PlaceholderAPI
    public int getGuiOpensToday() { return metricsGuiOpensToday; }
    public int getRulesAcceptedToday() { return metricsRulesAcceptedToday; }
    public int getItemClicksToday(String key) { return telemetryItemClicksToday.getOrDefault(key, 0); }

    // Compute 1-based join order number based on firstPlayed timestamps across known players
    public int joinNumberOf(org.bukkit.OfflinePlayer target) {
        if (target == null) return 0;
        long tfp = Math.max(0L, target.getFirstPlayed());
        if (tfp <= 0L) return 0;
        int rank = 0;
        try {
            for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                long fp = Math.max(0L, op.getFirstPlayed());
                if (fp <= 0L) continue;
                if (fp <= tfp) rank++;
            }
        } catch (Throwable ignored) {}
        return Math.max(1, rank);
    }

    // Public accessor used by WelcomeGui for dynamic titles/lore.
    // Returns a cached value and schedules a background refresh if stale.
    public int playersToDate() {
        long now = System.currentTimeMillis();
        if (cachedPlayersToDate >= 0 && (now - cachedPlayersToDateAt) <= PLAYERS_TO_DATE_TTL_MS) {
            return cachedPlayersToDate;
        }
        // Kick an async refresh if not already computing
        if (!computingPlayersToDate) {
            try { Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshPlayersToDate); } catch (Throwable ignored) {}
        }
        // Fallback to last known value, or 0 if none yet
        return Math.max(0, cachedPlayersToDate);
    }

    // Apply placeholders and convert MiniMessage -> legacy '§' string, also honoring & codes and hex colors.
    public String toLegacyString(String input, Player player, int totalPlayersToDate) {
        if (input == null) return "";
        try {
            String with = applyPlaceholders(input, player, totalPlayersToDate);
            if (mm != null) {
                Component c = mm.deserialize(with);
                String legacy = LegacyComponentSerializer.legacySection().serialize(c);
                return colorizeWithHex(legacy);
            }
            return colorizeWithHex(with);
        } catch (Throwable t) {
            return colorizeWithHex(input);
        }
    }

    // Static colorizer: supports & codes and basic hex (#RRGGBB or &#RRGGBB -> §x§R§R§G§G§B§B)
    public static String colorizeWithHex(String s) {
        if (s == null) return "";
        try {
            String out = s;
            try {
                Pattern p = Pattern.compile("(?i)&?#([0-9A-F]{6})");
                Matcher m = p.matcher(out);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String hex = m.group(1);
                    StringBuilder rep = new StringBuilder("§x");
                    for (char c : hex.toCharArray()) rep.append('§').append(c);
                    m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
                }
                m.appendTail(sb);
                out = sb.toString();
            } catch (Throwable ignored) {}
            return ChatColor.translateAlternateColorCodes('&', out);
        } catch (Throwable ignored) {
            return s;
        }
    }

    // Return a colorized list of messages for a given path, with placeholders applied.
    public List<String> msgListFor(Player player, String path) {
        try {
            List<String> raw = messages != null ? messages.getStringList(path) : java.util.Collections.emptyList();
            int total = playersToDate();
            List<String> out = new ArrayList<>(raw.size());
            for (String r : raw) out.add(toLegacyString(r, player, total));
            return out;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    // Placeholder application with optional PlaceholderAPI resolution when available.
    public String applyPlaceholders(String input, Player player, int totalPlayersToDate) {
        if (input == null) return "";
        String out = input;
        try {
            String name = player != null ? player.getName() : "player";
            out = out.replace("{player}", name)
                     .replace("{name}", name)
                     .replace("%player_name%", name)
                     .replace("{totalPlayers}", Integer.toString(totalPlayersToDate))
                     .replace("{players_to_date}", Integer.toString(totalPlayersToDate));
            // Resolve PAPI placeholders if plugin present
            if (papiAvailable && player != null) {
                try { out = PlaceholderAPI.setPlaceholders(player, out); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void refreshPlayersToDate() {
        if (computingPlayersToDate) return;
        computingPlayersToDate = true;
        try {
            String worldName = config.getString("World.name", "world");
            File worldFolder = Bukkit.getWorld(worldName) != null
                    ? Bukkit.getWorld(worldName).getWorldFolder()
                    : Bukkit.getWorldContainer().toPath().resolve(worldName).toFile();
            File playerDataDir = new File(worldFolder, "playerdata");
            int count = 0;
            if (playerDataDir.isDirectory()) {
                File[] files = playerDataDir.listFiles((dir, n) -> n.endsWith(".dat"));
                if (files != null) count = files.length;
            }
            cachedPlayersToDate = count;
            cachedPlayersToDateAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
        } finally {
            computingPlayersToDate = false;
        }
    }

    // Minimal join listener to handle GUI reopen behavior
    public static class JoinListener implements Listener {
        private final FirstLogin plugin;
        public JoinListener(FirstLogin plugin) { this.plugin = plugin; }

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            Player p = e.getPlayer();
            try {
                // Ensure first_join timestamp is recorded once
                try {
                    String fjKey = "timestamps." + p.getUniqueId() + ".first_join";
                    long ts = players.getLong(fjKey, 0L);
                    if (ts <= 0L) {
                        long fp = p.getFirstPlayed();
                        players.set(fjKey, fp > 0 ? fp : System.currentTimeMillis());
                        plugin.queuePlayersSave();
                    }
                } catch (Throwable ignored) {}

                // Auto-accept rules if player has permission
                try { plugin.autoAcceptIfPerm(p); } catch (Throwable ignored) {}

                boolean reopen = plugin.getConfig().getBoolean("welcomeGui.reopenOnJoinUntilAccepted", false);
                if (reopen && plugin.welcomeGui != null && plugin.welcomeGui.isEnabled() && !plugin.hasAcceptedRules(p)) {
                    long delay = plugin.getConfig().getLong("welcomeGui.openDelayTicks", 40L);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.welcomeGui.openFor(p, 1), Math.max(0L, delay));
                }
            } catch (Throwable ignored) {}
        }
    }
}
