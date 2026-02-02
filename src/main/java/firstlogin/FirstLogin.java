package firstlogin;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import firstlogin.gui.WelcomeGui;
import firstlogin.event.RulesAcceptedEvent;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import firstlogin.listeners.JoinListener;
import firstlogin.services.CoordinationService;
import firstlogin.services.TelemetryService;
import firstlogin.services.PlayersStore;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Coordination to avoid multi-plugin welcome spam (encapsulated in service)
    // Extracted service wrapper
    private CoordinationService coordinationService;
    // Facades for modularization
    private TelemetryService telemetryService;
    private PlayersStore playersStore;
    // PlaceholderAPI expansion instance (if registered)
    private firstlogin.papi.FirstLoginExpansion papiExpansion;

    // Welcome GUI
    WelcomeGui welcomeGui;

    // Particle effects for amazing first joins
    private ParticleManager particleManager;

    // Animated NPC guides for new players
    private AnimatedGuideManager guideManager;

    // BossBar welcome (optional)
    private BossBarWelcomeManager bossBarManager;

    // Action bar welcome (optional)
    private ActionBarManager actionBarManager;

    // Telemetry fully handled by TelemetryService

    // Cached count of players to-date to avoid repeated filesystem scans on main thread
    private volatile int cachedPlayersToDate = -1;
    private volatile long cachedPlayersToDateAt = 0L;
    private static final long PLAYERS_TO_DATE_TTL_MS = 30_000L; // 30s
    private volatile boolean computingPlayersToDate = false;

    // Cached rules counts to avoid expensive iteration on every call
    private volatile int cachedRulesAcceptedCount = -1;
    private volatile int cachedRulesPendingCount = -1;
    private volatile long cachedRulesCountsAt = 0L;
    private static final long RULES_COUNTS_TTL_MS = 60_000L; // 60s
    private volatile boolean computingRulesCounts = false;

    // ===== Asynchronous players.yml save queue =====
    private final Object playersSaveLock = new Object();
    private volatile boolean playersDirty = false;
    private volatile boolean saveScheduled = false;
    private volatile int scheduledSaveTaskId = -1;
    // Configurable debounce and debug
    private long playersSaveDebounceTicks = 20L; // default ~1s
    private boolean debugSaves = false;

    // (legacy telemetry fields removed)

    @Override
    public void onEnable() {
        // config.yml
        saveDefaultConfig();
        config = getConfig();
        ensureDefaultConfigValues();
        // Validate configuration and warn about issues
        validateConfig();
        // Load runtime toggles
        try {
            playersSaveDebounceTicks = Math.max(1L, config.getLong("asyncSave.players.debounceTicks", 20L));
            debugSaves = config.getBoolean("debug.saves", false);
        } catch (Throwable ignored) {}
        // Load coordination settings
        try { loadCoordinationConfig(); } catch (Throwable ignored) {}

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

        // PlaceholderAPI detection + config toggle
        boolean papiPresent = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean papiToggle = true;
        try { papiToggle = config.getBoolean("placeholderapi.enabled", true); } catch (Throwable ignored) {}
        papiAvailable = papiPresent && papiToggle;

        // Initialize service facades (delegating to existing logic for now)
        try { telemetryService = new TelemetryService(this); } catch (Throwable ignored) { telemetryService = null; }
        try { playersStore = new PlayersStore(this); } catch (Throwable ignored) { playersStore = null; }

        // Initialize telemetry via service
        if (telemetryService != null) {
            try { telemetryService.initPersistenceAndScheduling(); } catch (Throwable t) {
                getLogger().warning("Telemetry initialization via service failed: " + t.getMessage());
            }
        } else {
            getLogger().warning("TelemetryService not available; telemetry disabled");
        }

        // Register PlaceholderAPI expansion (optional)
        if (papiAvailable) {
            try {
                papiExpansion = new firstlogin.papi.FirstLoginExpansion(this);
                // Explicitly register with PAPI so placeholders become available
                papiExpansion.register();
                getLogger().info("PlaceholderAPI expansion registered");
            } catch (Throwable t) {
                getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
            }
        } else if (papiPresent && !papiToggle) {
            getLogger().info("PlaceholderAPI detected but expansion disabled by config (placeholderapi.enabled=false)");
        }

        // Register join listener (extracted class)
        Bukkit.getPluginManager().registerEvents(new JoinListener(this), this);

        // Welcome GUI listener
        welcomeGui = new WelcomeGui(this);
        Bukkit.getPluginManager().registerEvents(welcomeGui, this);

        // Initialize spectacular particle effects (lazy-init; disabled by default)
        try {
            if (config.getBoolean("particles.enabled", false)) {
                particleManager = new ParticleManager(this);
            }
        } catch (Throwable ignored) {}

        // Initialize animated NPC guides (lazy-init; disabled by default)
        try {
            if (config.getBoolean("animatedGuide.enabled", false)) {
                guideManager = new AnimatedGuideManager(this);
            }
        } catch (Throwable ignored) {}

        // Initialize BossBar welcome (lazy-init; disabled by default)
        try {
            if (config.getBoolean("bossbar.enabled", false)) {
                bossBarManager = new BossBarWelcomeManager(this);
            }
        } catch (Throwable ignored) {}

        // Initialize Action bar welcome (lazy-init; disabled by default)
        try {
            if (config.getBoolean("actionbar.enabled", false)) {
                actionBarManager = new ActionBarManager(this);
            }
        } catch (Throwable ignored) {}

        // Initialize metrics (bStats) if enabled and pluginId > 0
        if (config.getBoolean("metrics.enabled", true)) {
            int pluginId = config.getInt("metrics.pluginId", 0);
            if (pluginId > 0) {
                try {
                    Metrics m = new Metrics(this, pluginId);
                    // Simple charts for today's counters
                    m.addCustomChart(new SingleLineChart("gui_opens_today", () -> telemetryService != null ? telemetryService.getGuiOpensToday() : 0));
                    m.addCustomChart(new SingleLineChart("rules_accepted_today", () -> telemetryService != null ? telemetryService.getRulesAcceptedToday() : 0));
                    // Per-item click distribution
                    m.addCustomChart(new AdvancedPie("clicked_items_today", () -> {
                        if (telemetryService != null) return new java.util.HashMap<>(telemetryService.getItemClicksTodaySnapshot());
                        return new java.util.HashMap<>();
                    }));
                } catch (Throwable t) {
                    getLogger().warning("Could not start bStats metrics: " + t.getMessage());
                }
            }
        }

        String ver = getDescription().getVersion();
        log.info("FirstLogin " + ver + " - Enabled");

        // Warm up caches asynchronously to avoid first-use stall
        Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshPlayersToDate);
        Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshRulesCounts);

        // Register command executors and tab completers (migrated handler class)
        try {
            firstlogin.commands.FirstLoginCommand cmd = new firstlogin.commands.FirstLoginCommand(this);
            if (getCommand("firstlogin") != null) {
                getCommand("firstlogin").setExecutor(cmd);
                getCommand("firstlogin").setTabCompleter(cmd);
            }
            if (getCommand("welcome") != null) {
                getCommand("welcome").setExecutor(cmd);
                getCommand("welcome").setTabCompleter(cmd);
            }
        } catch (Throwable ignored) {}
    }

    // Re-evaluate optional managers (particles, animated guide, bossbar) after config reload
    public void reloadOptionalManagers() {
        // Particles
        try {
            boolean want = getConfig().getBoolean("particles.enabled", false);
            if (want) {
                if (particleManager == null) particleManager = new ParticleManager(this);
                else particleManager.reload();
            } else {
                particleManager = null; // GC; safe as tasks are scheduled on manager instances
            }
        } catch (Throwable ignored) {}

        // Animated guide
        try {
            boolean want = getConfig().getBoolean("animatedGuide.enabled", false);
            if (want) {
                if (guideManager == null) guideManager = new AnimatedGuideManager(this);
                else guideManager.reload();
            } else {
                if (guideManager != null) {
                    try { guideManager.removeAllGuides(); } catch (Throwable ignored) {}
                    guideManager = null;
                }
            }
        } catch (Throwable ignored) {}

        // BossBar welcome
        try {
            boolean want = getConfig().getBoolean("bossbar.enabled", false);
            if (want) {
                if (bossBarManager == null) bossBarManager = new BossBarWelcomeManager(this);
                else bossBarManager.reload();
            } else {
                if (bossBarManager != null) {
                    try { bossBarManager.shutdown(); } catch (Throwable ignored) {}
                    bossBarManager = null;
                }
            }
        } catch (Throwable ignored) {}

        // Action bar welcome
        try {
            boolean want = getConfig().getBoolean("actionbar.enabled", false);
            if (want) {
                if (actionBarManager == null) actionBarManager = new ActionBarManager(this);
                else actionBarManager.reload();
            } else {
                if (actionBarManager != null) {
                    try { actionBarManager.shutdown(); } catch (Throwable ignored) {}
                    actionBarManager = null;
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDisable() {
        // Flush any queued saves before shutdown, then do a final sync save
        try { flushPlayersSaves(); } catch (Throwable ignored) {}
        savePlayers();
        // Telemetry shutdown via service
        if (telemetryService != null) {
            try { telemetryService.shutdown(); } catch (Throwable ignored) {}
        }
        // Unregister PAPI expansion if present
        if (papiExpansion != null) {
            try {
                papiExpansion.unregister();
            } catch (Throwable t) {
                getLogger().fine("PlaceholderAPI expansion unregister suppressed: " + t.getMessage());
            }
            papiExpansion = null;
        }

        // Clean up animated guides
        if (guideManager != null) {
            guideManager.removeAllGuides();
        }

        // Clean up boss bars
        if (bossBarManager != null) {
            try { bossBarManager.shutdown(); } catch (Throwable ignored) {}
            bossBarManager = null;
        }

        // Clean up action bars
        if (actionBarManager != null) {
            try { actionBarManager.shutdown(); } catch (Throwable ignored) {}
            actionBarManager = null;
        }

        if (adventure != null) {
            adventure.close();
            adventure = null;
        }
        String ver = getDescription().getVersion();
        log.info("FirstLogin " + ver + " - Disabled");
    }

    // Public config reload hook used by commands/admins
    public void reloadFirstLoginConfig() {
        try {
            reloadConfig();
            config = getConfig();
            ensureDefaultConfigValues();
        } catch (Throwable ignored) {}
        // Re-read runtime toggles
        try {
            playersSaveDebounceTicks = Math.max(1L, config.getLong("asyncSave.players.debounceTicks", 20L));
            debugSaves = config.getBoolean("debug.saves", false);
        } catch (Throwable ignored) {}
        // Reload coordination configuration
        try { loadCoordinationConfig(); } catch (Throwable ignored) {}
        // Reschedule telemetry and apply persistence debounce
        if (telemetryService != null) {
            try { telemetryService.reloadConfigAndReschedule(); } catch (Throwable ignored) {}
        }
        // Re-evaluate PlaceholderAPI availability and (un)register expansion accordingly
        try {
            boolean papiPresent = org.bukkit.Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
            boolean papiToggle = true;
            try { papiToggle = config.getBoolean("placeholderapi.enabled", true); } catch (Throwable ignored) {}
            boolean wantPapi = papiPresent && papiToggle;
            if (wantPapi && papiExpansion == null) {
                try {
                    papiExpansion = new firstlogin.papi.FirstLoginExpansion(this);
                    papiExpansion.register();
                    getLogger().info("PlaceholderAPI expansion registered (reload)");
                } catch (Throwable t) {
                    getLogger().warning("Could not register PlaceholderAPI expansion on reload: " + t.getMessage());
                    papiExpansion = null;
                }
            } else if (!wantPapi && papiExpansion != null) {
                try { papiExpansion.unregister(); } catch (Throwable ignored) {}
                papiExpansion = null;
                getLogger().info("PlaceholderAPI expansion unregistered (reload)");
            }
            papiAvailable = wantPapi;
        } catch (Throwable ignored) {}

        // Re-evaluate optional managers based on new config
        try { reloadOptionalManagers(); } catch (Throwable ignored) {}
    }

    public void savePlayers() {
        try {
            players.save(playersFile);
        } catch (IOException e) {
            getLogger().severe("Could not save players.yml: " + e.getMessage());
        }
    }

    // Expose WelcomeGui for command handlers
    public WelcomeGui getWelcomeGui() {
        return welcomeGui;
    }

    // Expose Adventure and MiniMessage for managers
    public BukkitAudiences getAdventure() { return adventure; }
    public MiniMessage getMiniMessage() { return mm; }

    // Pretty duration formatting shared across plugin and PAPI expansion
    public static String formatDurationPretty(long millis) {
        if (millis <= 0L) return "";
        long totalSeconds = millis / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d').append(' ');
        if (hours > 0) sb.append(hours).append('h').append(' ');
        if (minutes > 0) sb.append(minutes).append('m').append(' ');
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append('s');
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') sb.setLength(len - 1);
        return sb.toString();
    }

    // Queue a debounced asynchronous save of players.yml to avoid blocking the main thread.
    public void queuePlayersSave() {
        playersDirty = true;
        if (saveScheduled) return;
        saveScheduled = true;
        try {
            long delay = Math.max(1L, playersSaveDebounceTicks);
            if (debugSaves) getLogger().info("[debug.saves] Scheduling async players.yml save in " + delay + " ticks");
            scheduledSaveTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::doPlayersSaveAsync, delay).getTaskId();
        } catch (Throwable t) {
            // Fallback: if async scheduling fails, save synchronously
            saveScheduled = false;
            savePlayers();
            playersDirty = false;
            if (debugSaves) getLogger().info("[debug.saves] Async schedule failed; performed synchronous save immediately");
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
            if (debugSaves) getLogger().info("[debug.saves] players.yml saved asynchronously");
        } catch (Throwable e) {
            getLogger().warning("Async save of players.yml failed: " + e.getMessage());
        }
        // If new writes happened while saving, schedule another save shortly
        if (playersDirty && !saveScheduled) {
            try {
                saveScheduled = true;
                long delay = Math.max(1L, playersSaveDebounceTicks);
                if (debugSaves) getLogger().info("[debug.saves] Re-scheduling async save in " + delay + " ticks due to new writes during save");
                scheduledSaveTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::doPlayersSaveAsync, delay).getTaskId();
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
                if (debugSaves) getLogger().info("[debug.saves] Flushed pending players.yml save synchronously on shutdown/reload");
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

        // GUI sounds
        config.addDefault("welcomeGui.sounds.open.enabled", false);
        config.addDefault("welcomeGui.sounds.open.name", "BLOCK_CHEST_OPEN");
        config.addDefault("welcomeGui.sounds.open.volume", 0.5);
        config.addDefault("welcomeGui.sounds.open.pitch", 1.2);
        config.addDefault("welcomeGui.sounds.close.enabled", false);
        config.addDefault("welcomeGui.sounds.close.name", "BLOCK_CHEST_CLOSE");
        config.addDefault("welcomeGui.sounds.close.volume", 0.5);
        config.addDefault("welcomeGui.sounds.close.pitch", 1.2);
        config.addDefault("welcomeGui.sounds.rulesAccepted.enabled", true);
        config.addDefault("welcomeGui.sounds.rulesAccepted.name", "ENTITY_PLAYER_LEVELUP");
        config.addDefault("welcomeGui.sounds.rulesAccepted.volume", 1.0);
        config.addDefault("welcomeGui.sounds.rulesAccepted.pitch", 1.0);

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
        // Debounce saves of telemetry.yml to reduce disk IO
        config.addDefault("telemetry.persist.debounceTicks", 40);
        // Telemetry daily reset scheduling
        config.addDefault("telemetry.reset.enabled", true);
        config.addDefault("telemetry.reset.time", "04:00");
        // Async save debounce + debug
        config.addDefault("asyncSave.players.debounceTicks", 20);
        config.addDefault("debug.saves", false);
        config.addDefault("debug.telemetry", false);
        // bStats metrics defaults
        config.addDefault("metrics.enabled", true);
        config.addDefault("metrics.pluginId", 0);

        // Formatting defaults (used by PlaceholderAPI date formatting)
        config.addDefault("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");

        // PlaceholderAPI toggle
        config.addDefault("placeholderapi.enabled", true);

        // Join-message coordination (to avoid multi-plugin welcome spam)
        config.addDefault("coordination.enabled", true);
        config.addDefault("coordination.role", "secondary"); // primary | secondary | exclusive
        config.addDefault("coordination.waitTicks", 40);
        config.addDefault("coordination.key", "firstlogin:welcome_claim");

        // ===== SPECTACULAR FEATURES =====
        // Particle effects configuration (experimental; disabled by default)
        config.addDefault("particles.enabled", false);
        config.addDefault("particles.effectType", "welcome_burst");
        config.addDefault("particles.duration", 60);
        config.addDefault("particles.radius", 2.0);
        config.addDefault("particles.particleCount", 50);
        config.addDefault("particles.colors.primary.red", 255);
        config.addDefault("particles.colors.primary.green", 255);
        config.addDefault("particles.colors.primary.blue", 0);
        config.addDefault("particles.colors.secondary.red", 0);
        config.addDefault("particles.colors.secondary.green", 255);
        config.addDefault("particles.colors.secondary.blue", 255);

        // Animated guide configuration (experimental; disabled by default)
        config.addDefault("animatedGuide.enabled", false);
        config.addDefault("animatedGuide.name", "&6Welcome Guide");
        config.addDefault("animatedGuide.duration", 120);
        config.addDefault("animatedGuide.spawnLocation.world", "world");
        config.addDefault("animatedGuide.spawnLocation.x", 0.0);
        config.addDefault("animatedGuide.spawnLocation.y", 100.0);
        config.addDefault("animatedGuide.spawnLocation.z", 0.0);
        config.addDefault("animatedGuide.spawnLocation.yaw", 0.0);
        config.addDefault("animatedGuide.spawnLocation.pitch", 0.0);

        // BossBar welcome (optional; disabled by default)
        config.addDefault("bossbar.enabled", false);
        config.addDefault("bossbar.text", "<gradient:#ffd54f:#ff9100><bold>Welcome, {player}!</bold></gradient>");
        config.addDefault("bossbar.color", "PURPLE");
        config.addDefault("bossbar.overlay", "PROGRESS");
        config.addDefault("bossbar.durationSeconds", 8);

        // Action bar welcome message (optional; disabled by default)
        config.addDefault("actionbar.enabled", false);
        config.addDefault("actionbar.text", "<gradient:#00ff88:#00aaff>Welcome to the server, {player}!</gradient>");
        config.addDefault("actionbar.durationSeconds", 5);
        config.addDefault("actionbar.refreshTicks", 20);
    }

    // Validate configuration and log warnings for common issues (returns count of warnings)
    public int validateConfig() {
        return validateConfig(null);
    }

    // Validate configuration with optional player to send messages to
    public int validateConfig(Player recipient) {
        int warnings = 0;
        // Validate GUI items
        org.bukkit.configuration.ConfigurationSection items = config.getConfigurationSection("welcomeGui.items");
        if (items != null) {
            int rows = Math.max(1, Math.min(6, config.getInt("welcomeGui.rows", 3)));
            int maxSlot = rows * 9 - 1;
            for (String key : items.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection item = items.getConfigurationSection(key);
                if (item == null) continue;
                // Check material
                String matName = item.getString("material", "PAPER");
                if (matName != null && org.bukkit.Material.matchMaterial(matName.toUpperCase(java.util.Locale.ROOT)) == null) {
                    getLogger().warning("[Config] Invalid material '" + matName + "' for GUI item '" + key + "'");
                    warnings++;
                }
                // Check slot bounds
                int slot = item.getInt("slot", -1);
                if (slot < 0 || slot > maxSlot) {
                    getLogger().warning("[Config] Slot " + slot + " out of bounds (0-" + maxSlot + ") for GUI item '" + key + "'");
                    warnings++;
                }
                // Check click sound
                String soundName = null;
                org.bukkit.configuration.ConfigurationSection snd = item.getConfigurationSection("clickSound");
                if (snd != null) soundName = snd.getString("name");
                if (soundName != null && !soundName.isEmpty()) {
                    try { org.bukkit.Sound.valueOf(soundName); }
                    catch (IllegalArgumentException e) {
                        getLogger().warning("[Config] Invalid sound '" + soundName + "' for GUI item '" + key + "'");
                        warnings++;
                    }
                }
            }
        }
        // Validate world name
        String worldName = config.getString("World.name", "world");
        if (Bukkit.getWorld(worldName) == null) {
            getLogger().warning("[Config] World '" + worldName + "' not found. Some features may not work correctly.");
            warnings++;
        }
        // Validate bossbar color/overlay
        String bbColor = config.getString("bossbar.color", "PURPLE");
        try { net.kyori.adventure.bossbar.BossBar.Color.valueOf(bbColor.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            getLogger().warning("[Config] Invalid bossbar.color '" + bbColor + "'. Using PURPLE.");
            warnings++;
        }
        String bbOverlay = config.getString("bossbar.overlay", "PROGRESS");
        try { net.kyori.adventure.bossbar.BossBar.Overlay.valueOf(bbOverlay.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            getLogger().warning("[Config] Invalid bossbar.overlay '" + bbOverlay + "'. Using PROGRESS.");
            warnings++;
        }
        // Summary
        if (warnings > 0) {
            String msg = "[Config] Found " + warnings + " configuration issue(s). Please review your config.yml.";
            getLogger().warning(msg);
            if (recipient != null) recipient.sendMessage(org.bukkit.ChatColor.RED + msg);
        } else {
            String msg = "Configuration validated successfully.";
            getLogger().info(msg);
            if (recipient != null) recipient.sendMessage(org.bukkit.ChatColor.GREEN + msg);
        }
        return warnings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            // Only handle player senders here; console will be handled by registered executors
            if (!(sender instanceof Player)) return true;

            // If a command executor is registered for this command (FirstLoginCommand), delegate to it
            try {
                org.bukkit.command.PluginCommand pc = getCommand(cmd.getName());
                if (pc != null && pc.getExecutor() != null && pc.getExecutor() != this) {
                    return pc.getExecutor().onCommand(sender, cmd, label, args);
                }
            } catch (Throwable ignored) {}
            // No fallback here; let Bukkit handle others
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    

    public void recordGuiOpen() {
        if (telemetryService != null) telemetryService.recordGuiOpen();
    }

    public void recordRulesAccepted() {
        if (telemetryService != null) telemetryService.recordRulesAccepted();
    }

    public void recordItemClick(String key) {
        if (key == null || key.isEmpty()) return;
        if (telemetryService != null) telemetryService.recordItemClick(key);
    }

    // ===== Coordination config and helpers =====
    private void loadCoordinationConfig() {
        boolean enabled;
        String role;
        long waitTicks;
        String keyString;
        NamespacedKey key;
        try { enabled = config.getBoolean("coordination.enabled", true); } catch (Throwable ignored) { enabled = true; }
        try {
            String r = config.getString("coordination.role", "secondary");
            role = (r == null ? "secondary" : r.toLowerCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) { role = "secondary"; }
        try { waitTicks = Math.max(0L, config.getLong("coordination.waitTicks", 40L)); } catch (Throwable ignored) { waitTicks = 40L; }
        String keyConfig;
        try { keyConfig = config.getString("coordination.key", "firstlogin:welcome_claim"); } catch (Throwable ignored) { keyConfig = "firstlogin:welcome_claim"; }
        if (keyConfig == null || keyConfig.isEmpty()) keyConfig = "firstlogin:welcome_claim";
        try {
            String ks = keyConfig;
            String ns;
            String k;
            int colon = ks.indexOf(':');
            if (colon > 0 && colon < ks.length() - 1) {
                ns = ks.substring(0, colon);
                k = ks.substring(colon + 1);
            } else {
                ns = getName().toLowerCase(java.util.Locale.ROOT);
                k = ks;
            }
            key = new NamespacedKey(this, k);
            keyString = ((ns == null ? getName().toLowerCase(java.util.Locale.ROOT) : ns) + "_" + k).replace(':', '_');
        } catch (Throwable ignored) {
            try { key = new NamespacedKey(this, "welcome_claim"); } catch (Throwable t2) { key = null; }
            keyString = "firstlogin_welcome_claim";
        }
        try { coordinationService = new CoordinationService(this, enabled, role, waitTicks, key, keyString); }
        catch (Throwable ignored) { coordinationService = null; }
    }

    public void resetMetrics() { if (telemetryService != null) telemetryService.resetMetrics(); }

    // (legacy telemetry helpers removed; TelemetryService owns persistence and scheduling)

    // ===== Helpers and stubs to complete build =====
    public String msgFor(Player player, String path) {
        try {
            String s = null;
            // Try player's locale-specific messages file first
            if (player != null) {
                String locale = players.getString("locale." + player.getUniqueId(), null);
                if (locale != null && !locale.isEmpty()) {
                    // Try to load locale-specific messages file
                    java.io.File localeFile = new java.io.File(getDataFolder(), "messages_" + locale.toLowerCase(java.util.Locale.ROOT) + ".yml");
                    if (localeFile.exists()) {
                        org.bukkit.configuration.file.YamlConfiguration localeMsgs = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(localeFile);
                        s = localeMsgs.getString(path);
                    }
                }
            }
            // Fall back to default messages
            if (s == null && messages != null) {
                s = messages.getString(path);
            }
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
        // Save known bundled locale files if missing.
        try {
            java.util.List<String> locales = java.util.Arrays.asList(
                    "messages_en_us.yml"
            );
            for (String res : locales) {
                try {
                    java.io.File out = new java.io.File(getDataFolder(), res);
                    if (!out.exists()) {
                        saveResource(res, false);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    

    // Public utility to get versioned flag name - centralizes logic for all classes
    public String versionedFlagName(String base) {
        int v = getRulesVersion();
        return v <= 1 ? base : base + "_v" + v;
    }

    // Get current rules version from config
    public int getRulesVersion() {
        int v = 1;
        try {
            // Support both camelCase and lower-case keys
            if (config.contains("welcomeGui.rulesVersion")) {
                v = Math.max(1, config.getInt("welcomeGui.rulesVersion"));
            } else if (config.contains("welcomegui.rulesversion")) {
                v = Math.max(1, config.getInt("welcomegui.rulesversion"));
            }
        } catch (Throwable t) {
            getLogger().fine("Error reading rulesVersion: " + t.getMessage());
        }
        return v;
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
    public int getGuiOpensToday() { return telemetryService != null ? telemetryService.getGuiOpensToday() : 0; }
    public int getRulesAcceptedToday() { return telemetryService != null ? telemetryService.getRulesAcceptedToday() : 0; }
    public int getItemClicksToday(String key) { return telemetryService != null ? telemetryService.getItemClicksToday(key) : 0; }
    public int getTotalItemClicksToday() {
        if (telemetryService == null) return 0;
        int total = 0;
        for (int v : telemetryService.getItemClicksTodaySnapshot().values()) total += v;
        return total;
    }

    // Public accessor for last telemetry reset timestamp (epoch millis), 0 if never
    public long getTelemetryLastResetTs() { return telemetryService != null ? telemetryService.getTelemetryLastResetTs() : 0L; }

    // Public accessor for next scheduled telemetry reset timestamp (epoch millis), 0 if disabled/unknown
    public long getTelemetryNextResetTs() { return telemetryService != null ? telemetryService.getTelemetryNextResetTs() : 0L; }

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
    // Respects formatting.useMiniMessage config option.
    public String toLegacyString(String input, Player player, int totalPlayersToDate) {
        if (input == null) return "";
        try {
            String with = applyPlaceholders(input, player, totalPlayersToDate);
            // Check if MiniMessage parsing is enabled
            boolean useMiniMessage = config.getBoolean("formatting.useMiniMessage", true);
            if (useMiniMessage && mm != null) {
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
    // Respects formatting.usePlaceholders config option.
    public String applyPlaceholders(String input, Player player, int totalPlayersToDate) {
        if (input == null) return "";
        String out = input;
        try {
            // Check if built-in placeholders are enabled
            boolean usePlaceholders = config.getBoolean("formatting.usePlaceholders", true);
            if (usePlaceholders) {
                String name = player != null ? player.getName() : "player";
                int online = Bukkit.getOnlinePlayers().size();
                String owner = config.getString("World.Owner", "default");
                
                // Basic placeholders
                out = out.replace("{player}", name)
                         .replace("{name}", name)
                         .replace("%player_name%", name)
                         .replace("{online}", Integer.toString(online))
                         .replace("{total}", Integer.toString(totalPlayersToDate))
                         .replace("{totalPlayers}", Integer.toString(totalPlayersToDate))
                         .replace("{players_to_date}", Integer.toString(totalPlayersToDate))
                         .replace("{owner}", owner);
                
                // Player state placeholders (only if player is available)
                if (player != null) {
                    // Health and food
                    out = out.replace("{health}", String.format("%.1f", player.getHealth()))
                             .replace("{max_health}", String.format("%.1f", player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()))
                             .replace("{food}", Integer.toString(player.getFoodLevel()))
                             .replace("{level}", Integer.toString(player.getLevel()))
                             .replace("{xp}", Integer.toString((int)(player.getExp() * 100)));
                    
                    // Location
                    out = out.replace("{world}", player.getWorld().getName())
                             .replace("{x}", Integer.toString(player.getLocation().getBlockX()))
                             .replace("{y}", Integer.toString(player.getLocation().getBlockY()))
                             .replace("{z}", Integer.toString(player.getLocation().getBlockZ()));
                    
                    // Gamemode
                    out = out.replace("{gamemode}", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
                    
                    // Time-based
                    out = out.replace("{time}", player.getWorld().getTime() < 12000 ? "day" : "night")
                             .replace("{weather}", player.getWorld().hasStorm() ? (player.getWorld().isThundering() ? "storm" : "rain") : "clear");
                    
                    // Playtime (in hours)
                    long playedTicks = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
                    long playedHours = playedTicks / 20 / 3600;
                    long playedMinutes = (playedTicks / 20 / 60) % 60;
                    out = out.replace("{playtime_hours}", Long.toString(playedHours))
                             .replace("{playtime}", playedHours + "h " + playedMinutes + "m");
                    
                    // UUID (short form)
                    out = out.replace("{uuid}", player.getUniqueId().toString())
                             .replace("{uuid_short}", player.getUniqueId().toString().substring(0, 8));
                    
                    // Ping (if available)
                    try {
                        out = out.replace("{ping}", Integer.toString(player.getPing()));
                    } catch (Throwable ignored) {
                        out = out.replace("{ping}", "?");
                    }
                    
                    // Progress bars - {bar_health}, {bar_food}, {bar_xp}, {bar_level_10} (10 chars wide)
                    out = out.replace("{bar_health}", generateProgressBar(player.getHealth(), player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue(), 10, "&c", "&7"))
                             .replace("{bar_food}", generateProgressBar(player.getFoodLevel(), 20, 10, "&6", "&7"))
                             .replace("{bar_xp}", generateProgressBar(player.getExp() * 100, 100, 10, "&a", "&7"));
                    
                    // Custom progress bar: {progress:current:max:width:filledColor:emptyColor}
                    out = resolveCustomProgressBars(out);
                }
            }
            // Resolve PAPI placeholders if plugin present and enabled
            if (papiAvailable && player != null) {
                try {
                    // Attempt direct PlaceholderAPI resolution
                    out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, out);
                } catch (Throwable ignored) {
                    // Best-effort: keep the unprocessed string if PAPI call fails
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
    
    // Generate a visual progress bar string
    private String generateProgressBar(double current, double max, int width, String filledColor, String emptyColor) {
        if (max <= 0) max = 1;
        double ratio = Math.max(0, Math.min(1, current / max));
        int filled = (int) Math.round(ratio * width);
        int empty = width - filled;
        StringBuilder sb = new StringBuilder();
        sb.append(filledColor);
        for (int i = 0; i < filled; i++) sb.append("█");
        sb.append(emptyColor);
        for (int i = 0; i < empty; i++) sb.append("█");
        return sb.toString();
    }
    
    // Resolve custom progress bars: {progress:current:max:width:filledColor:emptyColor}
    private String resolveCustomProgressBars(String input) {
        if (input == null || !input.contains("{progress:")) return input;
        String out = input;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{progress:(\\d+(?:\\.\\d+)?):(\\d+(?:\\.\\d+)?):(\\d+):([^:]+):([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            try {
                double current = Double.parseDouble(matcher.group(1));
                double max = Double.parseDouble(matcher.group(2));
                int width = Integer.parseInt(matcher.group(3));
                String filledColor = matcher.group(4);
                String emptyColor = matcher.group(5);
                String bar = generateProgressBar(current, max, Math.min(width, 50), filledColor, emptyColor);
                matcher.appendReplacement(sb, bar.replace("$", "\\$"));
            } catch (Throwable t) {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
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

    
    // Service getters for command executors and listeners
    public TelemetryService getTelemetryService() { return telemetryService; }
    public PlayersStore getPlayersStore() { return playersStore; }

    // Determine the rules flag key for the current version
    private String rulesFlagKey() {
        int v = 1;
        try {
            if (config.contains("welcomeGui.rulesVersion")) {
                v = Math.max(1, config.getInt("welcomeGui.rulesVersion"));
            } else if (config.contains("welcomegui.rulesversion")) {
                v = Math.max(1, config.getInt("welcomegui.rulesversion"));
            }
        } catch (Throwable ignored) {}
        return v <= 1 ? "rules" : ("rules_v" + v);
    }

    // Count players who have accepted current rules version (cached for performance)
    public int getRulesAcceptedCount() {
        long now = System.currentTimeMillis();
        if (cachedRulesAcceptedCount >= 0 && (now - cachedRulesCountsAt) <= RULES_COUNTS_TTL_MS) {
            return cachedRulesAcceptedCount;
        }
        // Kick async refresh if not already computing
        if (!computingRulesCounts) {
            try { Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshRulesCounts); } catch (Throwable ignored) {}
        }
        return Math.max(0, cachedRulesAcceptedCount);
    }

    // Count players who have NOT accepted current rules version (cached for performance)
    public int getRulesPendingCount() {
        long now = System.currentTimeMillis();
        if (cachedRulesPendingCount >= 0 && (now - cachedRulesCountsAt) <= RULES_COUNTS_TTL_MS) {
            return cachedRulesPendingCount;
        }
        // Kick async refresh if not already computing
        if (!computingRulesCounts) {
            try { Bukkit.getScheduler().runTaskAsynchronously(this, this::refreshRulesCounts); } catch (Throwable ignored) {}
        }
        return Math.max(0, cachedRulesPendingCount);
    }

    // Refresh rules counts asynchronously
    private void refreshRulesCounts() {
        if (computingRulesCounts) return;
        computingRulesCounts = true;
        try {
            String rk = rulesFlagKey();
            int accepted = 0;
            int pending = 0;
            for (org.bukkit.OfflinePlayer op : org.bukkit.Bukkit.getOfflinePlayers()) {
                java.util.UUID u = op.getUniqueId();
                if (players.getBoolean("flags." + u + "." + rk, false)) {
                    accepted++;
                } else {
                    pending++;
                }
            }
            cachedRulesAcceptedCount = accepted;
            cachedRulesPendingCount = pending;
            cachedRulesCountsAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
        } finally {
            computingRulesCounts = false;
        }
    }

    // Compute welcome GUI open delay with coordination role extra delay
    public long computeWelcomeOpenDelayTicks() {
        long baseDelay;
        try { baseDelay = getConfig().getLong("welcomeGui.openDelayTicks", 40L); }
        catch (Throwable ignored) { baseDelay = 40L; }
        long extra = 0L;
        try { extra = (coordinationService != null ? coordinationService.extraDelayTicks() : 0L); } catch (Throwable ignored) {}
        long total = baseDelay + Math.max(0L, extra);
        return Math.max(0L, total);
    }

    // Check if we can proceed after coordination claim logic
    public boolean shouldProceedAfterCoordination(Player p) {
        try { return coordinationService != null ? coordinationService.tryClaimNow(p) : true; }
        catch (Throwable ignored) { return true; }
    }

    // Trigger optional join-time extras (particles, guide, bossbar, title, sound) with coordination gating where applicable
    public void playJoinExtras(Player p) {
        // First join visuals: title
        try {
            if (config.getBoolean("firstJoinVisuals.title.enabled", false)) {
                String titleText = config.getString("firstJoinVisuals.title.title", "<green>Welcome, {player}!");
                String subtitleText = config.getString("firstJoinVisuals.title.subtitle", "<gray>Enjoy your stay.");
                int fadeIn = config.getInt("firstJoinVisuals.title.fadeIn", 10);
                int stay = config.getInt("firstJoinVisuals.title.stay", 60);
                int fadeOut = config.getInt("firstJoinVisuals.title.fadeOut", 10);
                
                // Apply placeholders
                titleText = applyPlaceholders(titleText, p, playersToDate());
                subtitleText = applyPlaceholders(subtitleText, p, playersToDate());
                
                // Send title using Adventure
                try {
                    net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(fadeIn * 50L),
                        java.time.Duration.ofMillis(stay * 50L),
                        java.time.Duration.ofMillis(fadeOut * 50L)
                    );
                    net.kyori.adventure.text.Component titleComp = mm.deserialize(titleText);
                    net.kyori.adventure.text.Component subtitleComp = mm.deserialize(subtitleText);
                    net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(titleComp, subtitleComp, times);
                    adventure.player(p).showTitle(title);
                } catch (Throwable t) {
                    // Fallback to legacy title
                    p.sendTitle(
                        org.bukkit.ChatColor.translateAlternateColorCodes('&', titleText.replace("<green>", "&a").replace("<gray>", "&7")),
                        org.bukkit.ChatColor.translateAlternateColorCodes('&', subtitleText.replace("<green>", "&a").replace("<gray>", "&7")),
                        fadeIn, stay, fadeOut
                    );
                }
            }
        } catch (Throwable ignored) {}
        
        // First join visuals: actionbar (one-time message, different from ActionBarManager's repeating)
        try {
            if (config.getBoolean("firstJoinVisuals.actionbar.enabled", false)) {
                String msg = config.getString("firstJoinVisuals.actionbar.message", "<yellow>First time here!");
                msg = applyPlaceholders(msg, p, playersToDate());
                try {
                    adventure.player(p).sendActionBar(mm.deserialize(msg));
                } catch (Throwable t) {
                    p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                            org.bukkit.ChatColor.translateAlternateColorCodes('&', msg.replace("<yellow>", "&e"))
                        ));
                }
            }
        } catch (Throwable ignored) {}
        
        // First join visuals: sound
        try {
            if (config.getBoolean("firstJoinVisuals.sound.enabled", false)) {
                String soundName = config.getString("firstJoinVisuals.sound.name", "ENTITY_PLAYER_LEVELUP");
                float volume = (float) config.getDouble("firstJoinVisuals.sound.volume", 1.0);
                float pitch = (float) config.getDouble("firstJoinVisuals.sound.pitch", 1.0);
                try {
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName);
                    p.playSound(p.getLocation(), sound, volume, pitch);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("[Config] Invalid firstJoinVisuals.sound.name: " + soundName);
                }
            }
        } catch (Throwable ignored) {}
        
        // Particles
        try {
            if (particleManager != null && particleManager.isEnabled()) {
                particleManager.playFirstJoinEffect(p);
            }
        } catch (Throwable ignored) {}
        try {
            if (guideManager != null && guideManager.isEnabled()) {
                guideManager.spawnGuideForPlayer(p);
            }
        } catch (Throwable ignored) {}
        try {
            if (bossBarManager != null && bossBarManager.isEnabled()) {
                long extra = 0L;
                try { extra = Math.max(0L, (coordinationService != null ? coordinationService.extraDelayTicks() : 0L)); } catch (Throwable ignored) {}
                long delay = extra;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (shouldProceedAfterCoordination(p)) {
                        try { bossBarManager.showWelcomeBar(p); } catch (Throwable ignored) {}
                    }
                }, delay);
            }
        } catch (Throwable ignored) {}
        // Action bar welcome
        try {
            if (actionBarManager != null && actionBarManager.isEnabled()) {
                actionBarManager.showWelcome(p);
            }
        } catch (Throwable ignored) {}
    }

    // Clean up player resources on quit (bossbars, guides, etc.)
    public void cleanupPlayerResources(Player p) {
        if (p == null) return;
        // Hide any active bossbar
        try {
            if (bossBarManager != null) {
                bossBarManager.hide(p);
            }
        } catch (Throwable ignored) {}
        // Remove any active guide
        try {
            if (guideManager != null) {
                guideManager.removeGuideForPlayer(p);
            }
        } catch (Throwable ignored) {}
        // Hide any active action bar
        try {
            if (actionBarManager != null) {
                actionBarManager.hide(p);
            }
        } catch (Throwable ignored) {}
    }

    // Expose managers for PAPI placeholders
    public boolean hasActiveGuide(Player p) {
        return guideManager != null && guideManager.hasActiveGuide(p);
    }

    public boolean hasBossBarActive(Player p) {
        return bossBarManager != null && bossBarManager.isEnabled();
    }

    public boolean hasActiveActionBar(Player p) {
        return actionBarManager != null && actionBarManager.hasActiveActionBar(p);
    }
}
