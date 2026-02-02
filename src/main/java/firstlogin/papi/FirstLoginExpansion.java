package firstlogin.papi;

import firstlogin.FirstLogin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for FirstLogin.
 * Identifier: %firstlogin_...%
 *
 * Provided placeholders:
 * - %firstlogin_player%          -> player name (if online)
 * - %firstlogin_online%          -> online player count
 * - %firstlogin_total%           -> total players to date (from playerdata)
 * - %firstlogin_owner%           -> configured server owner (config World.Owner)
 * - %firstlogin_rules_accepted%  -> true|false for current rules version
 * - %firstlogin_rules_version%   -> current rules version number
 * - %firstlogin_first_join_date% -> formatted first join time using formatting.datePattern
 * - %firstlogin_rules_accepted_date% -> formatted rules accepted time using formatting.datePattern
 * - %firstlogin_days_since_first_join% -> whole days since first join
 * - %firstlogin_first_join_ts%   -> raw epoch millis of first join (0 if unknown)
 * - %firstlogin_rules_accepted_ts% -> raw epoch millis of rules accepted (0 if unknown)
 * - %firstlogin_days_since_rules_accepted% -> whole days since rules acceptance (0 if unknown)
 * - %firstlogin_rules_version_accepted% -> highest rules version the player has accepted (0 if none)
 * - %firstlogin_rules_pending%   -> true if player has NOT accepted current rules version
 * - %firstlogin_gui_opens_today% -> number of Welcome GUI opens recorded today
 * - %firstlogin_rules_accepted_today% -> number of rules accepted recorded today
 * - %firstlogin_item_clicks_today_<key>% -> number of clicks today for GUI item '<key>'
 * - %firstlogin_join_number%     -> 1-based join order across known players
 * - %firstlogin_join_order%      -> alias of join_number
 * - %firstlogin_metrics_reset_date% -> formatted date/time of last telemetry reset
 * - %firstlogin_metrics_last_reset_ts% -> raw epoch millis of last telemetry reset (0 if never)
 * - %firstlogin_metrics_next_reset_date% -> formatted date/time of next scheduled telemetry reset (empty if disabled)
 * - %firstlogin_metrics_next_reset_ts% -> raw epoch millis of next scheduled telemetry reset (0 if disabled)
 * - %firstlogin_metrics_next_reset_in_seconds% -> seconds until next reset (0 if disabled)
 * - %firstlogin_metrics_next_reset_in_minutes% -> minutes until next reset (0 if disabled)
 * - %firstlogin_metrics_next_reset_in_hours% -> hours until next reset (0 if disabled)
 * - %firstlogin_metrics_next_reset_pretty% -> pretty duration until next reset (empty if disabled)
 * - %firstlogin_metrics_last_reset_pretty% -> pretty time since last reset (empty if unknown)
 * - %firstlogin_has_guide% -> true if player has an active animated guide
 * - %firstlogin_bossbar_active% -> true if bossbar feature is enabled
 * - %firstlogin_version% -> plugin version string
 * - %firstlogin_item_clicks_total% -> total item clicks today across all items
 * - %firstlogin_actionbar_active% -> true if player has an active action bar message
 */
public class FirstLoginExpansion extends PlaceholderExpansion {
    private final FirstLogin plugin;

    public FirstLoginExpansion(FirstLogin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "firstlogin";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public boolean persist() {
        // Persist through reloads
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String identifier) {
        String id = identifier.toLowerCase();
        // Dynamic prefix handling for item click counters
        if (id.startsWith("item_clicks_today_")) {
            String key = id.substring("item_clicks_today_".length());
            if (key.isEmpty()) return "0";
            return Integer.toString(plugin.getItemClicksToday(key));
        }
        switch (id) {
            case "rules_pending_count":
                return Integer.toString(plugin.getRulesPendingCount());
            case "rules_accepted_count":
                return Integer.toString(plugin.getRulesAcceptedCount());
            case "player": {
                if (player != null) {
                    String name = player.getName();
                    if (name != null) return name;
                }
                Player online = player != null ? Bukkit.getPlayer(player.getUniqueId()) : null;
                return online != null ? online.getName() : "";
            }
            case "online":
                return Integer.toString(Bukkit.getOnlinePlayers().size());
            case "total":
                return Integer.toString(plugin.playersToDate());
            case "owner":
                return FirstLogin.config.getString("World.Owner", "default");
            case "gui_opens_today":
                return Integer.toString(plugin.getGuiOpensToday());
            case "rules_accepted_today":
                return Integer.toString(plugin.getRulesAcceptedToday());
            case "rules_accepted": {
                if (player == null || player.getUniqueId() == null) return "false";
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p == null) return "false";
                return Boolean.toString(plugin.hasAcceptedRules(p));
            }
            case "rules_version":
                return Integer.toString(FirstLogin.config.getInt("welcomeGui.rulesVersion", 1));
            case "rules_pending": {
                if (player == null || player.getUniqueId() == null) return "true";
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p == null) return "true";
                return Boolean.toString(!plugin.hasAcceptedRules(p));
            }
            case "join_number":
            case "join_order": {
                if (player == null) return "0";
                try { return Integer.toString(plugin.joinNumberOf(player)); } catch (Throwable ignored) { return "0"; }
            }
            case "first_join_date": {
                if (player == null || player.getUniqueId() == null) return "";
                String key = "timestamps." + player.getUniqueId() + ".first_join";
                long ts = FirstLogin.players.getLong(key, 0L);
                if (ts <= 0L) {
                    long fp = player.getFirstPlayed();
                    ts = fp > 0 ? fp : 0L;
                }
                if (ts <= 0L) return "";
                String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                try {
                    return new java.text.SimpleDateFormat(pat).format(new java.util.Date(ts));
                } catch (Throwable ignored) {
                    return Long.toString(ts);
                }
            }
            case "rules_accepted_date": {
                if (player == null || player.getUniqueId() == null) return "";
                String key = "timestamps." + player.getUniqueId() + ".rules_accepted";
                long ts = FirstLogin.players.getLong(key, 0L);
                if (ts <= 0L) return "";
                String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                try {
                    return new java.text.SimpleDateFormat(pat).format(new java.util.Date(ts));
                } catch (Throwable ignored) {
                    return Long.toString(ts);
                }
            }
            case "days_since_first_join": {
                if (player == null || player.getUniqueId() == null) return "0";
                String key = "timestamps." + player.getUniqueId() + ".first_join";
                long ts = FirstLogin.players.getLong(key, 0L);
                if (ts <= 0L) {
                    long fp = player.getFirstPlayed();
                    ts = fp > 0 ? fp : 0L;
                }
                if (ts <= 0L) return "0";
                long now = System.currentTimeMillis();
                long days = (now - ts) / (1000L * 60L * 60L * 24L);
                return Long.toString(Math.max(0L, days));
            }
            case "first_join_ts": {
                if (player == null || player.getUniqueId() == null) return "0";
                String key = "timestamps." + player.getUniqueId() + ".first_join";
                long ts = FirstLogin.players.getLong(key, 0L);
                if (ts <= 0L) {
                    long fp = player.getFirstPlayed();
                    ts = fp > 0 ? fp : 0L;
                }
                return Long.toString(Math.max(0L, ts));
            }
            case "rules_accepted_ts": {
                if (player == null || player.getUniqueId() == null) return "0";
                String key = "timestamps." + player.getUniqueId() + ".rules_accepted";
                long ts = FirstLogin.players.getLong(key, 0L);
                return Long.toString(Math.max(0L, ts));
            }
            case "days_since_rules_accepted": {
                if (player == null || player.getUniqueId() == null) return "0";
                String key = "timestamps." + player.getUniqueId() + ".rules_accepted";
                long ts = FirstLogin.players.getLong(key, 0L);
                if (ts <= 0L) return "0";
                long now = System.currentTimeMillis();
                long days = (now - ts) / (1000L * 60L * 60L * 24L);
                return Long.toString(Math.max(0L, days));
            }
            case "rules_version_accepted": {
                if (player == null || player.getUniqueId() == null) return "0";
                String base = "flags." + player.getUniqueId();
                org.bukkit.configuration.ConfigurationSection cs = FirstLogin.players.getConfigurationSection(base);
                if (cs == null) return "0";
                int max = 0;
                for (String k : cs.getKeys(false)) {
                    if (k != null && k.startsWith("rules_v") && cs.getBoolean(k, false)) {
                        try {
                            int v = Integer.parseInt(k.substring("rules_v".length()));
                            if (v > max) max = v;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                return Integer.toString(max);
            }
            case "metrics_reset_date": {
                long ts = plugin.getTelemetryLastResetTs();
                if (ts <= 0L) return "";
                String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                try {
                    return new java.text.SimpleDateFormat(pat).format(new java.util.Date(ts));
                } catch (Throwable ignored) {
                    return Long.toString(ts);
                }
            }
            case "metrics_last_reset_ts": {
                long ts = plugin.getTelemetryLastResetTs();
                return Long.toString(Math.max(0L, ts));
            }
            case "metrics_next_reset_date": {
                long ts = plugin.getTelemetryNextResetTs();
                if (ts <= 0L) return "";
                String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                try {
                    return new java.text.SimpleDateFormat(pat).format(new java.util.Date(ts));
                } catch (Throwable ignored) {
                    return Long.toString(ts);
                }
            }
            case "metrics_next_reset_ts": {
                long ts = plugin.getTelemetryNextResetTs();
                return Long.toString(Math.max(0L, ts));
            }
            case "metrics_next_reset_in_seconds": {
                long ts = plugin.getTelemetryNextResetTs();
                long now = System.currentTimeMillis();
                long delta = Math.max(0L, ts - now);
                return Long.toString(delta / 1000L);
            }
            case "metrics_next_reset_in_minutes": {
                long ts = plugin.getTelemetryNextResetTs();
                long now = System.currentTimeMillis();
                long delta = Math.max(0L, ts - now);
                return Long.toString(delta / (1000L * 60L));
            }
            case "metrics_next_reset_in_hours": {
                long ts = plugin.getTelemetryNextResetTs();
                long now = System.currentTimeMillis();
                long delta = Math.max(0L, ts - now);
                return Long.toString(delta / (1000L * 60L * 60L));
            }
            case "metrics_next_reset_pretty": {
                long ts = plugin.getTelemetryNextResetTs();
                long now = System.currentTimeMillis();
                long delta = Math.max(0L, ts - now);
                if (ts <= 0L || delta <= 0L) return "";
                return FirstLogin.formatDurationPretty(delta);
            }
            case "metrics_last_reset_pretty": {
                long ts = plugin.getTelemetryLastResetTs();
                if (ts <= 0L) return "";
                long now = System.currentTimeMillis();
                long delta = Math.max(0L, now - ts);
                String pretty = FirstLogin.formatDurationPretty(delta);
                return pretty.isEmpty() ? "" : (pretty + " ago");
            }
            case "has_guide": {
                if (player == null) return "false";
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p == null) return "false";
                return Boolean.toString(plugin.hasActiveGuide(p));
            }
            case "bossbar_active": {
                if (player == null) return "false";
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p == null) return "false";
                return Boolean.toString(plugin.hasBossBarActive(p));
            }
            case "version":
                return plugin.getDescription().getVersion();
            case "item_clicks_total":
                return Integer.toString(plugin.getTotalItemClicksToday());
            case "actionbar_active": {
                if (player == null) return "false";
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p == null) return "false";
                return Boolean.toString(plugin.hasActiveActionBar(p));
            }
            default:
                return null; // unknown
        }
    }
}
