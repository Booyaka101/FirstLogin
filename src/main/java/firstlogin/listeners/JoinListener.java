package firstlogin.listeners;

import firstlogin.FirstLogin;
import firstlogin.event.FirstJoinEvent;
import firstlogin.event.ReturningPlayerEvent;
import firstlogin.services.PlayersStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles join-time behaviors: timestamping, auto-accept, welcome GUI reopen, and optional effects.
 * Also handles quit-time cleanup for bossbars and guides.
 */
public class JoinListener implements Listener {
    private final FirstLogin plugin;

    public JoinListener(FirstLogin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        try {
            // Check if this is a first-time join (no timestamp recorded yet)
            String fjKey = "timestamps." + p.getUniqueId() + ".first_join";
            long existingTs = FirstLogin.players.getLong(fjKey, 0L);
            boolean isFirstJoin = (existingTs <= 0L);
            
            // Ensure first_join timestamp is recorded once
            try {
                if (isFirstJoin) {
                    long fp = p.getFirstPlayed();
                    FirstLogin.players.set(fjKey, fp > 0 ? fp : System.currentTimeMillis());
                    plugin.queuePlayersSave();
                }
            } catch (Throwable ignored) {}

            // Send messages based on first join vs returning player
            try {
                if (isFirstJoin) {
                    // First join: send personal welcome message
                    if (FirstLogin.config.getBoolean("message.enabled", true)) {
                        String msg = FirstLogin.config.getString("message.string", "Welcome to the server");
                        String color = FirstLogin.config.getString("message.color", "&f");
                        msg = plugin.applyPlaceholders(color + msg, p, plugin.playersToDate());
                        p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                    }
                    
                    // First join: broadcast global message to all players
                    if (FirstLogin.config.getBoolean("messageGlobal.enabled", true)) {
                        String msg = FirstLogin.config.getString("messageGlobal.string", "first time user logged in");
                        String color = FirstLogin.config.getString("messageGlobal.color", "&f");
                        msg = plugin.applyPlaceholders(color + p.getName() + ": " + msg, p, plugin.playersToDate());
                        String finalMsg = org.bukkit.ChatColor.translateAlternateColorCodes('&', msg);
                        Bukkit.getOnlinePlayers().forEach(op -> op.sendMessage(finalMsg));
                    }
                } else {
                    // Returning player: check returningGate and send messageBack
                    if (FirstLogin.config.getBoolean("messageBack.enabled", true)) {
                        int minDaysOffline = FirstLogin.config.getInt("returningGate.minDaysOffline", 0);
                        boolean shouldSend = true;
                        
                        if (minDaysOffline > 0) {
                            // Check how long they've been offline
                            long lastPlayed = p.getLastPlayed();
                            if (lastPlayed > 0) {
                                long daysOffline = (System.currentTimeMillis() - lastPlayed) / (1000L * 60 * 60 * 24);
                                shouldSend = (daysOffline >= minDaysOffline);
                            }
                        }
                        
                        if (shouldSend) {
                            String msg = FirstLogin.config.getString("messageBack.string", "Welcome back!");
                            String color = FirstLogin.config.getString("messageBack.color", "&f");
                            msg = plugin.applyPlaceholders(color + msg, p, plugin.playersToDate());
                            p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Auto-accept rules if player has permission
            try { plugin.autoAcceptIfPerm(p); } catch (Throwable ignored) {}

            boolean reopen = plugin.getConfig().getBoolean("welcomeGui.reopenOnJoinUntilAccepted", false);
            if (reopen && plugin.getWelcomeGui() != null && plugin.getWelcomeGui().isEnabled() && !plugin.hasAcceptedRules(p)) {
                long total = plugin.computeWelcomeOpenDelayTicks();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (plugin.shouldProceedAfterCoordination(p)) {
                        plugin.getWelcomeGui().openFor(p, 1);
                    }
                }, total);
            }

            // Trigger optional join-time extras (particles, guide, bossbar) - only for first join
            if (isFirstJoin) {
                try { plugin.playJoinExtras(p); } catch (Throwable ignored) {}
            }
            
            // Record login for streak tracking and fire custom events
            try {
                PlayersStore store = plugin.getPlayersStore();
                if (store != null) {
                    int streak = store.recordLogin(p.getUniqueId());
                    int anniversary = store.checkAnniversary(p.getUniqueId());
                    
                    if (isFirstJoin) {
                        // Fire FirstJoinEvent
                        int playerNumber = plugin.playersToDate();
                        FirstJoinEvent event = new FirstJoinEvent(p, playerNumber);
                        Bukkit.getPluginManager().callEvent(event);
                        
                        // Send Discord webhook for first join if enabled
                        sendWebhook(p, "first_join", playerNumber, 0, 0);
                    } else {
                        // Calculate days offline
                        long lastPlayed = p.getLastPlayed();
                        long daysOffline = lastPlayed > 0 ? (System.currentTimeMillis() - lastPlayed) / (1000L * 60 * 60 * 24) : 0;
                        
                        // Fire ReturningPlayerEvent
                        ReturningPlayerEvent event = new ReturningPlayerEvent(p, daysOffline, streak, anniversary);
                        Bukkit.getPluginManager().callEvent(event);
                        
                        // Anniversary message
                        if (anniversary > 0 && FirstLogin.config.getBoolean("anniversary.enabled", true)) {
                            String msg = FirstLogin.config.getString("anniversary.message", "&6Happy {years} year anniversary, {player}!");
                            msg = msg.replace("{years}", String.valueOf(anniversary));
                            msg = plugin.applyPlaceholders(msg, p, plugin.playersToDate());
                            p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                            
                            // Broadcast anniversary
                            if (FirstLogin.config.getBoolean("anniversary.broadcast", true)) {
                                String broadcast = FirstLogin.config.getString("anniversary.broadcastMessage", "&6{player} is celebrating their {years} year anniversary!");
                                broadcast = broadcast.replace("{years}", String.valueOf(anniversary));
                                broadcast = plugin.applyPlaceholders(broadcast, p, plugin.playersToDate());
                                String finalBroadcast = org.bukkit.ChatColor.translateAlternateColorCodes('&', broadcast);
                                Bukkit.getOnlinePlayers().forEach(op -> op.sendMessage(finalBroadcast));
                            }
                        }
                        
                        // Streak milestone message
                        if (event.isStreakMilestone() && FirstLogin.config.getBoolean("streaks.milestoneMessage", true)) {
                            String msg = FirstLogin.config.getString("streaks.message", "&a{player} has a {streak} day login streak!");
                            msg = msg.replace("{streak}", String.valueOf(streak));
                            msg = plugin.applyPlaceholders(msg, p, plugin.playersToDate());
                            p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                        }
                        
                        // Send Discord webhook for returning player if enabled
                        sendWebhook(p, "returning", 0, streak, anniversary);
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
    
    private void sendWebhook(Player p, String type, int playerNumber, int streak, int anniversary) {
        if (!FirstLogin.config.getBoolean("webhook.enabled", false)) return;
        String url = FirstLogin.config.getString("webhook.url", "");
        if (url == null || url.isEmpty()) return;
        
        // Run async to avoid blocking
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String username = FirstLogin.config.getString("webhook.username", "FirstLogin");
                String avatarUrl = FirstLogin.config.getString("webhook.avatarUrl", "");
                
                String title, description, color;
                if ("first_join".equals(type)) {
                    title = FirstLogin.config.getString("webhook.firstJoin.title", "New Player!");
                    description = FirstLogin.config.getString("webhook.firstJoin.description", "{player} joined for the first time! (Player #{number})");
                    description = description.replace("{number}", String.valueOf(playerNumber));
                    color = FirstLogin.config.getString("webhook.firstJoin.color", "65280"); // Green
                } else {
                    title = FirstLogin.config.getString("webhook.returning.title", "Player Returned");
                    description = FirstLogin.config.getString("webhook.returning.description", "{player} is back! Streak: {streak} days");
                    description = description.replace("{streak}", String.valueOf(streak));
                    if (anniversary > 0) {
                        description += " (🎂 " + anniversary + " year anniversary!)";
                    }
                    color = FirstLogin.config.getString("webhook.returning.color", "3447003"); // Blue
                }
                
                description = description.replace("{player}", p.getName());
                
                // Build JSON payload
                String json = "{\"username\":\"" + escapeJson(username) + "\"," +
                    (avatarUrl.isEmpty() ? "" : "\"avatar_url\":\"" + escapeJson(avatarUrl) + "\",") +
                    "\"embeds\":[{\"title\":\"" + escapeJson(title) + "\"," +
                    "\"description\":\"" + escapeJson(description) + "\"," +
                    "\"color\":" + color + "," +
                    "\"timestamp\":\"" + java.time.Instant.now().toString() + "\"}]}";
                
                // Send HTTP POST
                java.net.URI webhookUri = java.net.URI.create(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) webhookUri.toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.getOutputStream().write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                conn.getResponseCode(); // Trigger the request
                conn.disconnect();
            } catch (Throwable t) {
                plugin.getLogger().warning("[Webhook] Failed to send: " + t.getMessage());
            }
        });
    }
    
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Clean up any active resources for this player
        try { plugin.cleanupPlayerResources(p); } catch (Throwable ignored) {}
    }
}
