package firstlogin;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Creates spectacular particle effects for first joins and special events
 */
public class ParticleManager {

    private final FirstLogin plugin;
    private boolean enabled;
    private String effectType;
    private int duration;
    private double radius;
    private int particleCount;
    private Color primaryColor;
    private Color secondaryColor;

    public ParticleManager(FirstLogin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("particles");
        if (config == null) {
            plugin.getLogger().info("No particle configuration found, particles disabled");
            this.enabled = false;
            return;
        }

        this.enabled = config.getBoolean("enabled", true);
        this.effectType = config.getString("effectType", "welcome_burst");
        this.duration = Math.max(1, config.getInt("duration", 60)); // ticks
        this.radius = Math.max(0.5, config.getDouble("radius", 2.0));
        this.particleCount = Math.max(1, config.getInt("particleCount", 50));

        // Load colors
        ConfigurationSection colors = config.getConfigurationSection("colors");
        if (colors != null) {
            this.primaryColor = Color.fromRGB(
                colors.getInt("primary.red", 255),
                colors.getInt("primary.green", 255),
                colors.getInt("primary.blue", 0)
            );
            this.secondaryColor = Color.fromRGB(
                colors.getInt("secondary.red", 0),
                colors.getInt("secondary.green", 255),
                colors.getInt("secondary.blue", 255)
            );
        } else {
            this.primaryColor = Color.YELLOW;
            this.secondaryColor = Color.AQUA;
        }

        if (enabled) {
            plugin.getLogger().info("Particle effects enabled with " + effectType + " effect");
        }
    }

    public void playFirstJoinEffect(Player player) {
        if (!enabled) return;

        Location center = player.getLocation().clone().add(0, 1, 0);

        switch (effectType.toLowerCase()) {
            case "welcome_burst":
                playWelcomeBurst(center);
                break;
            case "spiral_ascension":
                playSpiralAscension(center);
                break;
            case "rainbow_ring":
                playRainbowRing(center);
                break;
            case "firework_show":
                playFireworkShow(center);
                break;
            default:
                playWelcomeBurst(center);
                break;
        }
    }

    private void playWelcomeBurst(Location center) {
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = duration;

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    this.cancel();
                    return;
                }

                double progress = (double) ticks / maxTicks;
                double currentRadius = radius * progress;

                // Create expanding ring effect
                for (int i = 0; i < particleCount; i++) {
                    double angle = (2 * Math.PI * i) / particleCount;
                    double x = Math.cos(angle) * currentRadius;
                    double z = Math.sin(angle) * currentRadius;
                    Location particleLoc = center.clone().add(x, progress * 2, z);

                    // Alternate between primary and secondary colors
                    Color color = (i % 2 == 0) ? primaryColor : secondaryColor;
                    spawnColoredParticle(particleLoc, color);
                }

                // Add some sparkle effects
                for (int i = 0; i < 10; i++) {
                    Location sparkleLoc = center.clone().add(
                        (Math.random() - 0.5) * radius * 2,
                        Math.random() * 3,
                        (Math.random() - 0.5) * radius * 2
                    );
                    center.getWorld().spawnParticle(Particle.FIREWORKS_SPARK, sparkleLoc, 1, 0, 0, 0, 0.1);
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void playSpiralAscension(Location center) {
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = duration;

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    this.cancel();
                    return;
                }

                double progress = (double) ticks / maxTicks;
                double y = progress * 4; // Rise up to 4 blocks

                // Create spiral pattern
                for (int i = 0; i < particleCount / 2; i++) {
                    double angle = (2 * Math.PI * i * 4) / particleCount + (progress * Math.PI * 2);
                    double x = Math.cos(angle) * radius * (1 - progress * 0.5);
                    double z = Math.sin(angle) * radius * (1 - progress * 0.5);
                    Location particleLoc = center.clone().add(x, y, z);

                    Color color = (i % 2 == 0) ? primaryColor : secondaryColor;
                    spawnColoredParticle(particleLoc, color);
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void playRainbowRing(Location center) {
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = duration;

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    this.cancel();
                    return;
                }

                // Create rainbow ring
                for (int i = 0; i < particleCount; i++) {
                    double angle = (2 * Math.PI * i) / particleCount;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location particleLoc = center.clone().add(x, Math.sin(angle * 2) * 0.5, z);

                    // Create rainbow effect
                    float hue = (float) ((angle + ticks * 0.1) / (2 * Math.PI));
                    Color rainbowColor = Color.fromRGB(
                        (int) (Math.sin(hue * 2 * Math.PI) * 127 + 128),
                        (int) (Math.sin(hue * 2 * Math.PI + 2 * Math.PI / 3) * 127 + 128),
                        (int) (Math.sin(hue * 2 * Math.PI + 4 * Math.PI / 3) * 127 + 128)
                    );
                    spawnColoredParticle(particleLoc, rainbowColor);
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void playFireworkShow(Location center) {
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = duration;

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    this.cancel();
                    return;
                }

                // Create fireworks bursting effect
                for (int i = 0; i < 5; i++) {
                    Location burstLoc = center.clone().add(
                        (Math.random() - 0.5) * radius * 2,
                        Math.random() * 2 + 1,
                        (Math.random() - 0.5) * radius * 2
                    );

                    // Firework particles with different colors
                    Color color = (i % 2 == 0) ? primaryColor : secondaryColor;
                    spawnColoredParticle(burstLoc, color);

                    // Add explosion effect
                    burstLoc.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, burstLoc, 1, 0, 0, 0, 0.1);
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 2L); // Slower for firework effect
    }

    private void spawnColoredParticle(Location location, Color color) {
        World world = location.getWorld();
        if (world == null) return;

        try {
            // Spawn dust particle with color
            world.spawnParticle(Particle.REDSTONE, location, 1, new Particle.DustOptions(color, 1.0f));
        } catch (Exception e) {
            // Fallback to simple particle if DustOptions fails
            world.spawnParticle(Particle.REDSTONE, location, 1);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        loadConfig();
    }
}
