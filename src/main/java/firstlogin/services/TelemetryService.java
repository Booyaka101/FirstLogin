package firstlogin.services;

import firstlogin.FirstLogin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Telemetry service that owns telemetry counters, persistence, and daily reset scheduling.
 * FirstLogin delegates to this service.
 */
public class TelemetryService {
    private final FirstLogin plugin;
    // Config keys and constants
    private static final String CFG_DEBUG_TELEMETRY = "debug.telemetry";
    private static final String CFG_PERSIST_ENABLED = "telemetry.persist.enabled";
    private static final String CFG_PERSIST_RETENTION = "telemetry.persist.retentionDays";
    private static final String CFG_RESET_ENABLED = "telemetry.reset.enabled";
    private static final String CFG_RESET_TIME = "telemetry.reset.time";
    private static final java.time.format.DateTimeFormatter DATE_FMT = java.time.format.DateTimeFormatter.ISO_DATE;

    // Counters and state
    private int guiOpensToday = 0;
    private int rulesAcceptedToday = 0;
    private String metricsDay = java.time.LocalDate.now().format(DATE_FMT);
    private final Map<String, Integer> itemClicksToday = new HashMap<>();

    // Persistence
    private File telemetryFile;
    private YamlConfiguration telemetry;
    private boolean persistEnabled;
    private int retentionDays;

    // Reset scheduling
    private boolean resetEnabled = true;
    private String resetTime = "04:00"; // HH:mm local
    private int resetTaskId = -1;
    private long lastResetTs = 0L;
    private long nextResetTs = 0L;
    private boolean debugTelemetry = false;

    // Debounced persistence
    private final Object telemetrySaveLock = new Object();
    private volatile boolean telemetryDirty = false;
    private volatile boolean telemetrySaveScheduled = false;
    private volatile int telemetrySaveTaskId = -1;
    private long telemetrySaveDebounceTicks = 40L; // default ~2s

    public TelemetryService(FirstLogin plugin) {
        this.plugin = plugin;
    }

    // ===== Orchestration =====
    public void initPersistenceAndScheduling() {
        // Load config toggles
        try { debugTelemetry = plugin.getConfig().getBoolean(CFG_DEBUG_TELEMETRY, false); } catch (Throwable ignored) {}
        try { telemetrySaveDebounceTicks = Math.max(1L, plugin.getConfig().getLong("telemetry.persist.debounceTicks", 40L)); } catch (Throwable ignored) {}
        initPersistence();
        ensureDay();
        queueSave();
        pruneRetention();
        scheduleReset();
    }

    public void rescheduleDailyReset() { scheduleReset(); }

    public void shutdown() {
        flushSaves();
        try { if (resetTaskId != -1) { Bukkit.getScheduler().cancelTask(resetTaskId); resetTaskId = -1; } } catch (Throwable ignored) {}
        nextResetTs = 0L;
    }

    // ===== Public API used by plugin/commands/PAPI =====
    public void recordGuiOpen() {
        ensureDay();
        guiOpensToday++;
        queueSave();
    }

    public void recordRulesAccepted() {
        ensureDay();
        rulesAcceptedToday++;
        queueSave();
    }

    public void recordItemClick(String key) {
        if (key == null || key.isEmpty()) return;
        ensureDay();
        itemClicksToday.merge(key, 1, Integer::sum);
        queueSave();
    }

    public void resetMetrics() {
        guiOpensToday = 0;
        rulesAcceptedToday = 0;
        itemClicksToday.clear();
        metricsDay = java.time.LocalDate.now().format(DATE_FMT);
        try { saveToday(); markReset(); } catch (Throwable ignored) {}
    }

    public int getGuiOpensToday() { return guiOpensToday; }
    public int getRulesAcceptedToday() { return rulesAcceptedToday; }
    public int getItemClicksToday(String key) { return itemClicksToday.getOrDefault(key, 0); }
    public long getTelemetryLastResetTs() { return lastResetTs; }
    public long getTelemetryNextResetTs() { return nextResetTs; }
    public Map<String, Integer> getItemClicksTodaySnapshot() { return new java.util.HashMap<>(itemClicksToday); }

    // Immutable snapshot for simplified exposure (bStats/PAPI/commands)
    public TelemetrySnapshot getSnapshot() {
        return new TelemetrySnapshot(
                metricsDay,
                guiOpensToday,
                rulesAcceptedToday,
                new java.util.HashMap<>(itemClicksToday),
                lastResetTs,
                nextResetTs
        );
    }

    public static final class TelemetrySnapshot {
        public final String day;
        public final int guiOpensToday;
        public final int rulesAcceptedToday;
        public final Map<String, Integer> itemClicksToday;
        public final long lastResetTs;
        public final long nextResetTs;
        private TelemetrySnapshot(String day, int gui, int rules, Map<String, Integer> clicks, long last, long next) {
            this.day = day;
            this.guiOpensToday = gui;
            this.rulesAcceptedToday = rules;
            this.itemClicksToday = clicks;
            this.lastResetTs = last;
            this.nextResetTs = next;
        }
    }

    // ===== Internal helpers =====
    private void initPersistence() {
        try { persistEnabled = plugin.getConfig().getBoolean(CFG_PERSIST_ENABLED, true); } catch (Throwable ignored) { persistEnabled = true; }
        try { retentionDays = Math.max(0, plugin.getConfig().getInt(CFG_PERSIST_RETENTION, 14)); } catch (Throwable ignored) { retentionDays = 14; }
        telemetryFile = new File(plugin.getDataFolder(), "telemetry.yml");
        if (!telemetryFile.exists()) {
            try { telemetryFile.createNewFile(); } catch (IOException e) { plugin.getLogger().warning("Could not create telemetry.yml: " + e.getMessage()); }
        }
        telemetry = YamlConfiguration.loadConfiguration(telemetryFile);
        String today = java.time.LocalDate.now().format(DATE_FMT);
        metricsDay = today;
        try { lastResetTs = telemetry.getLong("lastReset.ts", 0L); } catch (Throwable ignored) {}
        if (persistEnabled) {
            guiOpensToday = telemetry.getInt("days." + today + ".guiOpens", guiOpensToday);
            rulesAcceptedToday = telemetry.getInt("days." + today + ".rulesAccepted", rulesAcceptedToday);
            org.bukkit.configuration.ConfigurationSection ic = telemetry.getConfigurationSection("days." + today + ".itemClicks");
            itemClicksToday.clear();
            if (ic != null) for (String k : ic.getKeys(false)) itemClicksToday.put(k, ic.getInt(k, 0));
            try { saveToday(); pruneRetention(); } catch (Throwable ignored) {}
        }
    }

    // Re-read persistence config and apply
    public void reloadConfigAndReschedule() {
        try { debugTelemetry = plugin.getConfig().getBoolean(CFG_DEBUG_TELEMETRY, false); } catch (Throwable ignored) {}
        try { telemetrySaveDebounceTicks = Math.max(1L, plugin.getConfig().getLong("telemetry.persist.debounceTicks", 40L)); } catch (Throwable ignored) {}
        reloadPersistenceConfig();
        scheduleReset();
    }

    private void reloadPersistenceConfig() {
        try { persistEnabled = plugin.getConfig().getBoolean(CFG_PERSIST_ENABLED, true); } catch (Throwable ignored) {}
        try { retentionDays = Math.max(0, plugin.getConfig().getInt(CFG_PERSIST_RETENTION, 14)); } catch (Throwable ignored) {}
        ensureDay();
        pruneRetention();
        queueSave();
    }

    private void ensureDay() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        if (metricsDay != null && metricsDay.equals(today)) return;
        metricsDay = today;
        guiOpensToday = 0;
        rulesAcceptedToday = 0;
        itemClicksToday.clear();
        if (persistEnabled && telemetry != null) {
            guiOpensToday = telemetry.getInt("days." + today + ".guiOpens", 0);
            rulesAcceptedToday = telemetry.getInt("days." + today + ".rulesAccepted", 0);
            org.bukkit.configuration.ConfigurationSection ic = telemetry.getConfigurationSection("days." + today + ".itemClicks");
            if (ic != null) for (String k : ic.getKeys(false)) itemClicksToday.put(k, ic.getInt(k, 0));
            try { saveToday(); pruneRetention(); } catch (Throwable ignored) {}
        }
    }

    private void saveToday() {
        if (!persistEnabled || telemetry == null) return;
        telemetry.set("days." + metricsDay + ".guiOpens", guiOpensToday);
        telemetry.set("days." + metricsDay + ".rulesAccepted", rulesAcceptedToday);
        telemetry.set("days." + metricsDay + ".itemClicks", null);
        for (Map.Entry<String, Integer> e : itemClicksToday.entrySet()) {
            telemetry.set("days." + metricsDay + ".itemClicks." + e.getKey(), e.getValue());
        }
        try { telemetry.save(telemetryFile); telemetryDirty = false; } catch (IOException e) { plugin.getLogger().warning("Failed to save telemetry.yml: " + e.getMessage()); }
    }

    private void queueSave() {
        if (!persistEnabled || telemetry == null) return;
        telemetryDirty = true;
        if (telemetrySaveScheduled) return;
        telemetrySaveScheduled = true;
        long delay = Math.max(1L, telemetrySaveDebounceTicks);
        if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] Scheduling debounced telemetry save in " + delay + " ticks");
        try {
            telemetrySaveTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::doAsyncSave, delay).getTaskId();
        } catch (Throwable t) {
            // If async scheduling fails, do a synchronous save immediately
            telemetrySaveScheduled = false;
            try { saveToday(); } catch (Throwable ignored) {}
        }
    }

    private void doAsyncSave() {
        boolean hadDirty = telemetryDirty;
        telemetrySaveScheduled = false;
        if (!hadDirty) return;
        try {
            synchronized (telemetrySaveLock) {
                saveToday();
            }
            if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] telemetry.yml saved asynchronously");
        } catch (Throwable e) {
            plugin.getLogger().warning("Async save of telemetry.yml failed: " + e.getMessage());
        }
        if (telemetryDirty && !telemetrySaveScheduled) {
            try {
                telemetrySaveScheduled = true;
                long delay = Math.max(1L, telemetrySaveDebounceTicks);
                if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] Re-scheduling telemetry save in " + delay + " ticks due to new writes during save");
                telemetrySaveTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::doAsyncSave, delay).getTaskId();
            } catch (Throwable ignored) {}
        }
    }

    public void flushSaves() {
        try {
            if (telemetrySaveTaskId != -1) {
                Bukkit.getScheduler().cancelTask(telemetrySaveTaskId);
                telemetrySaveTaskId = -1;
                telemetrySaveScheduled = false;
            }
        } catch (Throwable ignored) {}
        try {
            if (telemetryDirty) {
                synchronized (telemetrySaveLock) { saveToday(); }
                if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] Flushed pending telemetry save synchronously");
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Flush save of telemetry.yml failed: " + e.getMessage());
        }
    }

    private void pruneRetention() {
        if (!persistEnabled || telemetry == null) return;
        org.bukkit.configuration.ConfigurationSection days = telemetry.getConfigurationSection("days");
        if (days == null) return;
        try {
            java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(Math.max(0, retentionDays - 1L));
            for (String key : new java.util.HashSet<>(days.getKeys(false))) {
                try {
                    java.time.LocalDate d = java.time.LocalDate.parse(key);
                    if (d.isBefore(cutoff)) telemetry.set("days." + key, null);
                } catch (Throwable ignored) {}
            }
            telemetry.save(telemetryFile);
        } catch (IOException ignored) {}
    }

    private void scheduleReset() {
        try {
            resetEnabled = plugin.getConfig().getBoolean(CFG_RESET_ENABLED, true);
        } catch (Throwable ignored) {}
        try {
            resetTime = plugin.getConfig().getString(CFG_RESET_TIME, "04:00");
        } catch (Throwable ignored) {}
        try { if (resetTaskId != -1) { Bukkit.getScheduler().cancelTask(resetTaskId); resetTaskId = -1; } } catch (Throwable ignored) {}
        nextResetTs = 0L;
        if (!resetEnabled) return;

        java.time.LocalTime at;
        try { at = java.time.LocalTime.parse(resetTime); } catch (Throwable t) { if (debugTelemetry) plugin.getLogger().warning("[debug.telemetry] Invalid telemetry.reset.time '" + resetTime + "', defaulting to 04:00"); at = java.time.LocalTime.of(4, 0); }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        java.time.ZonedDateTime todayReset = now.withHour(at.getHour()).withMinute(at.getMinute()).withSecond(0).withNano(0);

        boolean didResetToday = false;
        try {
            if (lastResetTs > 0) {
                java.time.LocalDate last = java.time.Instant.ofEpochMilli(lastResetTs).atZone(zone).toLocalDate();
                didResetToday = last.equals(now.toLocalDate());
            }
        } catch (Throwable ignored) {}

        if (!didResetToday && !now.isBefore(todayReset)) {
            if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] Performing immediate telemetry reset (missed scheduled time)");
            try { performReset(); } catch (Throwable ignored) {}
            now = java.time.ZonedDateTime.now(zone);
            todayReset = now.plusDays(1).withHour(at.getHour()).withMinute(at.getMinute()).withSecond(0).withNano(0);
        } else if (now.isAfter(todayReset)) {
            todayReset = todayReset.plusDays(1);
        }

        long delayTicks = Math.max(1L, java.time.Duration.between(now, todayReset).getSeconds() * 20L);
        nextResetTs = todayReset.toInstant().toEpochMilli();
        if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] Scheduling telemetry reset in " + delayTicks + " ticks at " + todayReset);
        resetTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { performReset(); } catch (Throwable ignored) {}
            try { scheduleReset(); } catch (Throwable ignored) {}
        }, delayTicks).getTaskId();
    }

    private void performReset() {
        resetMetrics();
        if (debugTelemetry) plugin.getLogger().info("[debug.telemetry] Telemetry counters reset and persisted.");
    }

    private void markReset() {
        lastResetTs = System.currentTimeMillis();
        if (telemetry == null) return;
        String date = java.time.Instant.ofEpochMilli(lastResetTs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE_FMT);
        telemetry.set("lastReset.ts", lastResetTs);
        telemetry.set("lastReset.date", date);
        try { telemetry.save(telemetryFile); } catch (IOException ignored) {}
    }
}
