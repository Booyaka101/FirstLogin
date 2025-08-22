package me.bush.firstlogin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class FirstLoginListener implements Listener {
    private final FirstLogin plugin;

    public FirstLoginListener(FirstLogin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        boolean isFirstEverJoin = !player.hasPlayedBefore();

        if (isFirstEverJoin) {
            // Global first-join message
            if (FirstLogin.config.getBoolean("messageGlobal.enabled", true)) {
                String msg = FirstLogin.config.getString("messageGlobal.string", "first time user logged in");
                String color = FirstLogin.config.getString("messageGlobal.color", "&f");
                Bukkit.getServer().broadcastMessage(FirstLogin.colorize(color + player.getDisplayName() + ": " + msg));
            }
            // Private first-join message
            if (FirstLogin.config.getBoolean("message.enabled", true)) {
                String msg = FirstLogin.config.getString("message.string", "Welcome to the server");
                String color = FirstLogin.config.getString("message.color", "&f");
                player.sendMessage(FirstLogin.colorize(color + msg));
            }
            return;
        }

        // Returning player: only send once after plugin installed (tracked in players.yml)
        String key = "players." + player.getUniqueId();
        boolean seen = FirstLogin.players.getBoolean(key, false);
        if (!seen && FirstLogin.config.getBoolean("messageBack.enabled", true)) {
            FirstLogin.players.set(key, true);
            plugin.savePlayers();

            String msg = FirstLogin.config.getString("messageBack.string", "first time user logged in");
            String color = FirstLogin.config.getString("messageBack.color", "&f");
            player.sendMessage(FirstLogin.colorize(color + msg));
        }
    }
}
