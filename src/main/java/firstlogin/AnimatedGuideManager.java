package firstlogin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates animated NPC guides that help new players navigate the server
 */
public class AnimatedGuideManager {

    private final FirstLogin plugin;
    private boolean enabled;
    private String guideName;
    private Location spawnLocation;
    private int guideDuration;
    // Thread-safe maps for concurrent access
    private final Map<UUID, ArmorStand> activeGuides = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> guideTasks = new ConcurrentHashMap<>();

    public AnimatedGuideManager(FirstLogin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("animatedGuide");
        if (config == null) {
            plugin.getLogger().info("No animated guide configuration found, guides disabled");
            this.enabled = false;
            return;
        }

        this.enabled = config.getBoolean("enabled", true);
        this.guideName = config.getString("name", "&6Welcome Guide");
        this.guideDuration = Math.max(30, config.getInt("duration", 120)); // seconds

        // Load spawn location
        ConfigurationSection location = config.getConfigurationSection("spawnLocation");
        if (location != null) {
            String worldName = location.getString("world", "world");
            if (Bukkit.getWorld(worldName) != null) {
                double x = location.getDouble("x", 0);
                double y = location.getDouble("y", 100);
                double z = location.getDouble("z", 0);
                float yaw = (float) location.getDouble("yaw", 0);
                float pitch = (float) location.getDouble("pitch", 0);
                this.spawnLocation = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
            }
        }

        if (enabled && spawnLocation != null) {
            plugin.getLogger().info("Animated guides enabled! Guide: " + guideName);
        } else {
            this.enabled = false;
            plugin.getLogger().info("Animated guides disabled - invalid configuration");
        }
    }

    public void spawnGuideForPlayer(Player player) {
        if (!enabled || spawnLocation == null) return;

        // Remove any existing guide for this player
        removeGuideForPlayer(player);

        try {
            // Create armor stand NPC
            ArmorStand guide = (ArmorStand) spawnLocation.getWorld().spawnEntity(spawnLocation, EntityType.ARMOR_STAND);

            // Configure the guide
            guide.setCustomName(ChatColor.translateAlternateColorCodes('&', guideName));
            guide.setCustomNameVisible(true);
            guide.setGravity(false);
            guide.setInvulnerable(true);
            guide.setVisible(true);
            guide.setSmall(true);
            guide.setArms(true);

            // Give the guide a guide book
            ItemStack guideBook = new ItemStack(Material.BOOK);
            ItemMeta meta = guideBook.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "Server Guide");
                guideBook.setItemMeta(meta);
            }
            guide.getEquipment().setItemInMainHand(guideBook);

            // Store the guide
            activeGuides.put(player.getUniqueId(), guide);

            // Start animation task
            startGuideAnimation(player, guide);

            // Send welcome message
            player.sendMessage(ChatColor.GOLD + "🎯 A guide has appeared to help you!");
            player.sendMessage(ChatColor.YELLOW + "Follow the guide around the spawn area!");

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to spawn guide for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void startGuideAnimation(Player player, ArmorStand guide) {
        BukkitRunnable task = new BukkitRunnable() {
            private int ticks = 0;
            private int phase = 0;
            private Location originalLoc = guide.getLocation().clone();

            @Override
            public void run() {
                if (ticks >= guideDuration * 20 || !player.isOnline() || guide.isDead()) {
                    removeGuideForPlayer(player);
                    this.cancel();
                    return;
                }

                // Animation phases
                switch (phase) {
                    case 0: // Greeting phase
                        if (ticks % 40 == 0) { // Every 2 seconds
                            guide.setCustomName(ChatColor.translateAlternateColorCodes('&', guideName + " &7👋"));
                        } else if (ticks % 40 == 20) {
                            guide.setCustomName(ChatColor.translateAlternateColorCodes('&', guideName));
                        }
                        break;

                    case 1: // Movement phase
                        double angle = (ticks * 0.05) % (2 * Math.PI);
                        double radius = 2.0;
                        double x = Math.cos(angle) * radius;
                        double z = Math.sin(angle) * radius;
                        Location targetLoc = originalLoc.clone().add(x, Math.sin(ticks * 0.1) * 0.3, z);
                        guide.teleport(targetLoc);
                        break;

                    case 2: // Pointing phase
                        if (ticks % 60 == 0) {
                            guide.setRightArmPose(new org.bukkit.util.EulerAngle(-0.5, 0, -0.5));
                        } else if (ticks % 60 == 30) {
                            guide.setRightArmPose(new org.bukkit.util.EulerAngle(-0.2, 0, -0.2));
                        }
                        break;
                }

                // Phase transitions
                if (ticks == 60) phase = 1; // Switch to movement after 3 seconds
                if (ticks == guideDuration * 20 - 60) phase = 2; // Switch to pointing near end

                ticks++;
            }
        };

        int taskId = task.runTaskTimer(plugin, 0L, 1L).getTaskId();
        guideTasks.put(player.getUniqueId(), taskId);
    }

    public void removeGuideForPlayer(Player player) {
        UUID playerId = player.getUniqueId();

        // Cancel animation task
        Integer taskId = guideTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        // Remove armor stand
        ArmorStand guide = activeGuides.remove(playerId);
        if (guide != null && !guide.isDead()) {
            guide.remove();
        }
    }

    public void removeAllGuides() {
        // Remove all active guides
        for (ArmorStand guide : activeGuides.values()) {
            if (guide != null && !guide.isDead()) {
                guide.remove();
            }
        }
        activeGuides.clear();

        // Cancel all animation tasks
        for (Integer taskId : guideTasks.values()) {
            if (taskId != null) {
                Bukkit.getScheduler().cancelTask(taskId);
            }
        }
        guideTasks.clear();
    }

    public boolean hasActiveGuide(Player player) {
        return activeGuides.containsKey(player.getUniqueId());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        removeAllGuides();
        loadConfig();
    }
}
