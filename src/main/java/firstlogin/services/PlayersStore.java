package firstlogin.services;

import firstlogin.FirstLogin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.Collections;
import java.util.Set;

/**
 * Facade for players.yml access and saves. Delegates to FirstLogin for now.
 */
public class PlayersStore {
    private final FirstLogin plugin;

    public PlayersStore(FirstLogin plugin) {
        this.plugin = plugin;
    }

    public FileConfiguration getPlayers() { return FirstLogin.players; }

    public void queueSave() { plugin.queuePlayersSave(); }

    public void saveSync() { plugin.savePlayers(); }

    public void flushSaves() { plugin.flushPlayersSaves(); }

    public boolean hasAcceptedRules(Player player) { return plugin.hasAcceptedRules(player); }

    public void autoAcceptIfPerm(Player player) { plugin.autoAcceptIfPerm(player); }

    public String versionedFlagName(String base) {
        // Delegate to plugin's public centralized method
        return plugin.versionedFlagName(base);
    }

    public void setFirstJoinTimestamp(UUID uuid, long epochMillis) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("timestamps." + uuid + ".first_join", epochMillis);
        queueSave();
    }

    public void setRulesAcceptedTimestamp(UUID uuid, long epochMillis) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("timestamps." + uuid + ".rules_accepted", epochMillis);
        queueSave();
    }

    // === Convenience helpers for command operations ===
    public void setLocale(UUID uuid, String localeOrNull) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("locale." + uuid, localeOrNull);
        queueSave();
    }

    public String getLocale(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return "(default)";
        return p.getString("locale." + uuid, "(default)");
    }

    public void clearAllCooldowns(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("cooldowns." + uuid, null);
        queueSave();
    }

    public void clearCooldown(UUID uuid, String key) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null || key == null) return;
        p.set("cooldowns." + uuid + "." + key, null);
        queueSave();
    }

    public void clearAllFlags(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("flags." + uuid, null);
        queueSave();
    }

    public void clearFlag(UUID uuid, String flagBase) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null || flagBase == null) return;
        String v = versionedFlagName(flagBase);
        p.set("flags." + uuid + "." + v, null);
        queueSave();
    }

    public boolean isSeen(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return false;
        return p.getBoolean("players." + uuid, false);
    }

    public void resetAllSeen() {
        FileConfiguration p = getPlayers();
        if (p == null) return;
        p.set("players", null);
        queueSave();
    }

    public void resetSeen(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("players." + uuid, false);
        queueSave();
    }

    public Set<String> getFlagKeys(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return Collections.emptySet();
        org.bukkit.configuration.ConfigurationSection cs = p.getConfigurationSection("flags." + uuid);
        return cs != null ? cs.getKeys(false) : Collections.emptySet();
    }

    public Set<String> getCooldownKeys(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return Collections.emptySet();
        org.bukkit.configuration.ConfigurationSection cs = p.getConfigurationSection("cooldowns." + uuid);
        return cs != null ? cs.getKeys(false) : Collections.emptySet();
    }
    
    // ===== Join Streak Tracking =====
    
    /**
     * Record a player's login for streak tracking. Call this on join.
     * Returns the current streak count.
     */
    public int recordLogin(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        
        String base = "stats." + uuid + ".";
        long now = System.currentTimeMillis();
        long lastLogin = p.getLong(base + "last_login", 0L);
        int currentStreak = p.getInt(base + "login_streak", 0);
        int maxStreak = p.getInt(base + "max_login_streak", 0);
        int totalLogins = p.getInt(base + "total_logins", 0);
        
        // Check if this is a new day (using server timezone)
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate lastDay = lastLogin > 0 
            ? java.time.Instant.ofEpochMilli(lastLogin).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            : null;
        
        if (lastDay == null) {
            // First login ever
            currentStreak = 1;
        } else if (today.equals(lastDay)) {
            // Same day, don't increment streak
        } else if (today.equals(lastDay.plusDays(1))) {
            // Consecutive day, increment streak
            currentStreak++;
        } else {
            // Streak broken, reset to 1
            currentStreak = 1;
        }
        
        // Update max streak
        if (currentStreak > maxStreak) {
            maxStreak = currentStreak;
        }
        
        // Save stats
        p.set(base + "last_login", now);
        p.set(base + "login_streak", currentStreak);
        p.set(base + "max_login_streak", maxStreak);
        p.set(base + "total_logins", totalLogins + 1);
        queueSave();
        
        return currentStreak;
    }
    
    public int getLoginStreak(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        return p.getInt("stats." + uuid + ".login_streak", 0);
    }
    
    public int getMaxLoginStreak(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        return p.getInt("stats." + uuid + ".max_login_streak", 0);
    }
    
    public int getTotalLogins(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        return p.getInt("stats." + uuid + ".total_logins", 0);
    }
    
    public long getLastLogin(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0L;
        return p.getLong("stats." + uuid + ".last_login", 0L);
    }
    
    // ===== Anniversary Detection =====
    
    /**
     * Check if today is the player's join anniversary.
     * Returns the number of years (1 for first anniversary, etc.) or 0 if not an anniversary.
     */
    public int checkAnniversary(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        
        long firstJoin = p.getLong("timestamps." + uuid + ".first_join", 0L);
        if (firstJoin <= 0) return 0;
        
        java.time.LocalDate joinDate = java.time.Instant.ofEpochMilli(firstJoin)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now();
        
        // Check if same month and day
        if (joinDate.getMonth() == today.getMonth() && joinDate.getDayOfMonth() == today.getDayOfMonth()) {
            int years = today.getYear() - joinDate.getYear();
            if (years > 0) return years;
        }
        return 0;
    }
    
    /**
     * Get days since first join.
     */
    public long getDaysSinceFirstJoin(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        
        long firstJoin = p.getLong("timestamps." + uuid + ".first_join", 0L);
        if (firstJoin <= 0) return 0;
        
        return (System.currentTimeMillis() - firstJoin) / (1000L * 60 * 60 * 24);
    }
    
    // ===== Referral System =====
    
    public void setReferrer(UUID uuid, UUID referrerUuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("stats." + uuid + ".referred_by", referrerUuid != null ? referrerUuid.toString() : null);
        queueSave();
    }
    
    public UUID getReferrer(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return null;
        String ref = p.getString("stats." + uuid + ".referred_by", null);
        if (ref == null || ref.isEmpty()) return null;
        try { return UUID.fromString(ref); } catch (Throwable t) { return null; }
    }
    
    public int getReferralCount(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return 0;
        
        // Count how many players have this UUID as their referrer
        int count = 0;
        org.bukkit.configuration.ConfigurationSection stats = p.getConfigurationSection("stats");
        if (stats != null) {
            String uuidStr = uuid.toString();
            for (String key : stats.getKeys(false)) {
                String ref = p.getString("stats." + key + ".referred_by", null);
                if (uuidStr.equals(ref)) count++;
            }
        }
        return count;
    }
    
    // ===== Player Notes (Admin) =====
    
    public void addNote(UUID uuid, String adminName, String note) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null || note == null) return;
        
        java.util.List<String> notes = p.getStringList("notes." + uuid);
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        notes.add("[" + timestamp + "] " + adminName + ": " + note);
        p.set("notes." + uuid, notes);
        queueSave();
    }
    
    public java.util.List<String> getNotes(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return Collections.emptyList();
        return p.getStringList("notes." + uuid);
    }
    
    public void clearNotes(UUID uuid) {
        FileConfiguration p = getPlayers();
        if (p == null || uuid == null) return;
        p.set("notes." + uuid, null);
        queueSave();
    }
}
