package firstlogin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages action bar welcome messages for first-time players.
 * Shows a configurable message in the action bar for a set duration.
 */
public class ActionBarManager {
    private final FirstLogin plugin;
    private boolean enabled;
    private String text;
    private int durationSeconds;
    private int refreshTicks;

    // Track active action bar tasks by player UUID
    private final Map<UUID, BukkitTask> activeTasks = new ConcurrentHashMap<>();

    public ActionBarManager(FirstLogin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = FirstLogin.config.getBoolean("actionbar.enabled", false);
        this.text = FirstLogin.config.getString("actionbar.text", "<gradient:#00ff88:#00aaff>Welcome to the server, {player}!</gradient>");
        this.durationSeconds = Math.max(1, FirstLogin.config.getInt("actionbar.durationSeconds", 5));
        this.refreshTicks = Math.max(1, FirstLogin.config.getInt("actionbar.refreshTicks", 20));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void showWelcome(Player player) {
        if (!enabled || player == null) return;

        // Cancel any existing task for this player
        hide(player);

        UUID uuid = player.getUniqueId();
        String personalizedText = text.replace("{player}", player.getName());
        Component component;
        try {
            component = plugin.getMiniMessage().deserialize(personalizedText);
        } catch (Throwable t) {
            // Fallback to plain text
            component = Component.text(personalizedText);
        }

        final Component finalComponent = component;
        final long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);

        // Schedule repeating task to refresh action bar
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || System.currentTimeMillis() >= endTime) {
                hide(player);
                return;
            }
            try {
                plugin.getAdventure().player(player).sendActionBar(finalComponent);
            } catch (Throwable ignored) {}
        }, 0L, refreshTicks);

        activeTasks.put(uuid, task);
    }

    public void hide(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) {
            try { task.cancel(); } catch (Throwable ignored) {}
        }
        // Clear action bar
        try {
            plugin.getAdventure().player(player).sendActionBar(Component.empty());
        } catch (Throwable ignored) {}
    }

    public void shutdown() {
        for (Map.Entry<UUID, BukkitTask> entry : activeTasks.entrySet()) {
            try { entry.getValue().cancel(); } catch (Throwable ignored) {}
        }
        activeTasks.clear();
    }

    public boolean hasActiveActionBar(Player player) {
        return player != null && activeTasks.containsKey(player.getUniqueId());
    }
}
