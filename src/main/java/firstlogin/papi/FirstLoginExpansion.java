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
            default:
                return null; // unknown
        }
    }
}
