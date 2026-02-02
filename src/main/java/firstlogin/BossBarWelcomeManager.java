package firstlogin;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the optional BossBar welcome shown to players on their first join.
 *
 * Config keys (under bossbar.):
 *  - enabled (boolean)
 *  - text (string, MiniMessage supported; placeholders resolved via FirstLogin.applyPlaceholders)
 *  - color (string; one of BossBar.Color)
 *  - overlay (string; one of BossBar.Overlay)
 *  - durationSeconds (int; how long to show, default 8)
 */
public class BossBarWelcomeManager {
    private final FirstLogin plugin;
    private volatile boolean enabled;
    private volatile String text;
    private volatile BossBar.Color color;
    private volatile BossBar.Overlay overlay;
    private volatile int durationSeconds;

    // Track active bars so we can hide them on shutdown/reload
    private final Map<UUID, BossBar> activeBars = new ConcurrentHashMap<>();

    private final MiniMessage mm = MiniMessage.miniMessage();

    public BossBarWelcomeManager(FirstLogin plugin) {
        this.plugin = plugin;
        reload();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        try {
            enabled = plugin.getConfig().getBoolean("bossbar.enabled", false);
            text = plugin.getConfig().getString("bossbar.text", "<gradient:#ffd54f:#ff9100><bold>Welcome, {player}!</bold></gradient>");
            String colorStr = plugin.getConfig().getString("bossbar.color", "PURPLE");
            String overlayStr = plugin.getConfig().getString("bossbar.overlay", "PROGRESS");
            durationSeconds = Math.max(1, plugin.getConfig().getInt("bossbar.durationSeconds", 8));
            color = parseColor(colorStr);
            overlay = parseOverlay(overlayStr);
        } catch (Throwable ignored) {
            enabled = false;
        }
    }

    public void shutdown() {
        try {
            // Hide any active bars
            for (Map.Entry<UUID, BossBar> e : activeBars.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline() && plugin.getAdventure() != null) {
                    try { plugin.getAdventure().player(p).hideBossBar(e.getValue()); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        } finally {
            activeBars.clear();
        }
    }

    public void showWelcomeBar(Player player) {
        if (player == null || !enabled || plugin.getAdventure() == null) return;
        try {
            int total = plugin.playersToDate();
            String with = plugin.applyPlaceholders(text, player, total);
            Component comp = mm.deserialize(with);
            BossBar bar = BossBar.bossBar(comp, 1.0f, color, overlay);

            // Show bar
            plugin.getAdventure().player(player).showBossBar(bar);
            activeBars.put(player.getUniqueId(), bar);

            // Hide after configured duration (no animation for simplicity and stability)
            int ticks = Math.max(1, durationSeconds * 20);
            Bukkit.getScheduler().runTaskLater(plugin, () -> hide(player), ticks);
        } catch (Throwable ignored) {
        }
    }

    public void hide(Player player) {
        if (player == null || plugin.getAdventure() == null) return;
        BossBar bar = activeBars.remove(player.getUniqueId());
        if (bar != null) {
            try { plugin.getAdventure().player(player).hideBossBar(bar); } catch (Throwable ignored) {}
        }
    }

    private static BossBar.Color parseColor(String s) {
        if (s == null) return BossBar.Color.PURPLE;
        try { return BossBar.Color.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
        return BossBar.Color.PURPLE;
    }

    private static BossBar.Overlay parseOverlay(String s) {
        if (s == null) return BossBar.Overlay.PROGRESS;
        try { return BossBar.Overlay.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
        return BossBar.Overlay.PROGRESS;
    }
}
