/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.ChatColor
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.entity.Player
 *  org.bukkit.event.Event$Priority
 *  org.bukkit.event.Event$Type
 *  org.bukkit.event.Listener
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.PluginManager
 *  org.bukkit.plugin.java.JavaPlugin
 *  org.bukkit.util.config.Configuration
 */
package me.bush.firstlogin;

import java.io.File;
import java.util.ArrayList;
import java.util.logging.Logger;
import me.bush.firstlogin.FirstLoginPlayerListener;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

public class FirstLogin
extends JavaPlugin {
    public static Configuration config;
    public static Configuration players;
    Logger log = Logger.getLogger("Minecraft");
    final FirstLoginPlayerListener playerListener = new FirstLoginPlayerListener(this);

    public void onEnable() {
        players = this.getConfiguration();
        File configs = new File(this.getDataFolder() + File.separator + "players.yml");
        players = new Configuration(configs);
        players.load();
        players.setHeader("#This is a list of players joined after the plugin was installed.");
        players.save();
        System.out.println("FirstLogin 1.4 - Enabled");
        config = this.getConfiguration();
        config.load();
        config.setHeader("#Made by Bush \n#For GlobalMessage the player name will be displayed before the message, ex <player>: message. \n#This plugin is still in its beta phase and will be updated shortly.\n#Set the World name property to your current worlds name: \n#Set the World owner property to your name:");
        config.getString("World.name", "map101");
        config.getString("World.Owner", "deafult");
        config.getString("messageGlobal.string", "first time user logged in");
        config.getString("messageGlobal.color", "&f");
        config.getBoolean("messageGlobal.enabled", true);
        config.getString("messageBack.string", "first time user logged in");
        config.getString("messageBack.color", "&f");
        config.getBoolean("message.enabled", true);
        config.getString("message.string", "Welcome to the server");
        config.getString("message.color", "&f");
        config.getBoolean("message.enabled", false);
        config.save();
        PluginManager pm = this.getServer().getPluginManager();
        pm.registerEvent(Event.Type.PLAYER_JOIN, (Listener)this.playerListener, Event.Priority.Normal, (Plugin)this);
    }

    public void onDisable() {
        System.out.println("FirstLogin 1.4 - Disabled");
    }

    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        if (sender instanceof Player) {
            int num;
            Player player = (Player)sender;
            ArrayList<String> userlist = new ArrayList<String>();
            String worldname = config.getString("World.name", "server101");
            File[] fileArray = new File(String.valueOf(worldname) + "/players").listFiles();
            int n = fileArray.length;
            int n2 = 0;
            while (n2 < n) {
                File dat = fileArray[n2];
                userlist.add(dat.getName().replaceAll("(\\.dat)", ""));
                ++n2;
            }
            if (cmd.getName().equalsIgnoreCase("listp")) {
                num = userlist.size();
                player.sendMessage(ChatColor.DARK_RED + "Number of players joined to date: " + String.valueOf(num));
            }
            if (cmd.getName().equalsIgnoreCase("pnames")) {
                String names = userlist.toString();
                player.sendMessage(ChatColor.DARK_RED + "Names of players joined to date: " + names);
            }
            String owner = config.getString("World.Owner", "defualt");
            if (cmd.getName().equalsIgnoreCase("owner")) {
                player.sendMessage(owner);
            }
            if (cmd.getName().equalsIgnoreCase("onlinep")) {
                int online = player.getServer().getOnlinePlayers().length;
                num = userlist.size();
                player.sendMessage("Currently there are " + online + " of " + num + " players online.");
            }
            if (cmd.getName().equalsIgnoreCase("firsthelp")) {
                player.sendMessage("/listp: Lists the number of players joined to date.");
                player.sendMessage("/pnames: Lists all the names off players that joined the server.");
                player.sendMessage("/owner: Shows the owner of the servers name.");
                player.sendMessage("/onlinep: Shows how many players are currently online.");
                player.sendMessage("/firsthelp: Shows this to the player.");
            }
        }
        return false;
    }
}

