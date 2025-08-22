/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.ChatColor
 *  org.bukkit.entity.Player
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerListener
 */
package me.bush.firstlogin;

import java.io.File;
import me.bush.firstlogin.FirstLogin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerListener;

public class FirstLoginPlayerListener
extends PlayerListener {
    public static FirstLogin plugin;

    public FirstLoginPlayerListener(FirstLogin instance) {
        plugin = instance;
    }

    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean config3;
        boolean sc;
        String color;
        String configmessage;
        Player player = event.getPlayer();
        String name = player.getName();
        String worldname = FirstLogin.config.getString("World.name", "server101");
        File playerDat = new File(String.valueOf(worldname) + "/players/" + player.getName() + ".dat");
        if (!playerDat.exists()) {
            boolean config2;
            boolean config1 = FirstLogin.config.getBoolean("messageGlobal.enabled", true);
            if (config1) {
                String configmessageGlobal = FirstLogin.config.getString("messageGlobal.string");
                String colorGlobal = FirstLogin.config.getString("messageGlobal.color", "&3");
                player.getServer().broadcastMessage(FirstLoginPlayerListener.colorizeText(String.valueOf(colorGlobal) + player.getDisplayName() + ": " + configmessageGlobal));
            }
            if (config2 = FirstLogin.config.getBoolean("message.enabled", true)) {
                configmessage = FirstLogin.config.getString("message.string");
                color = FirstLogin.config.getString("message.color");
                player.sendMessage(FirstLoginPlayerListener.colorizeText(String.valueOf(color) + configmessage));
            }
        }
        if (!(sc = FirstLogin.players.getBoolean(String.valueOf(worldname) + ".players." + name, false)) && playerDat.exists() && (config3 = FirstLogin.config.getBoolean("messageBack.enabled", true))) {
            FirstLogin.players.setProperty(String.valueOf(worldname) + ".players." + name, (Object)true);
            FirstLogin.players.save();
            configmessage = FirstLogin.config.getString("messageBack.string");
            color = FirstLogin.config.getString("messageBack.color");
            player.sendMessage(FirstLoginPlayerListener.colorizeText(String.valueOf(color) + configmessage));
        }
    }

    public static String colorizeText(String string) {
        string = string.replaceAll("&0", "" + ChatColor.BLACK);
        string = string.replaceAll("&1", "" + ChatColor.DARK_BLUE);
        string = string.replaceAll("&2", "" + ChatColor.DARK_GREEN);
        string = string.replaceAll("&3", "" + ChatColor.DARK_AQUA);
        string = string.replaceAll("&4", "" + ChatColor.DARK_RED);
        string = string.replaceAll("&5", "" + ChatColor.DARK_PURPLE);
        string = string.replaceAll("&6", "" + ChatColor.GOLD);
        string = string.replaceAll("&7", "" + ChatColor.GRAY);
        string = string.replaceAll("&8", "" + ChatColor.DARK_GRAY);
        string = string.replaceAll("&9", "" + ChatColor.BLUE);
        string = string.replaceAll("&a", "" + ChatColor.GREEN);
        string = string.replaceAll("&b", "" + ChatColor.AQUA);
        string = string.replaceAll("&c", "" + ChatColor.RED);
        string = string.replaceAll("&d", "" + ChatColor.LIGHT_PURPLE);
        string = string.replaceAll("&e", "" + ChatColor.YELLOW);
        string = string.replaceAll("&f", "" + ChatColor.WHITE);
        return string;
    }
}

