package me.bush.firstlogin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FirstLogin extends JavaPlugin {
    public static FileConfiguration config;
    public static FileConfiguration players;

    private File playersFile;
    private final Logger log = Logger.getLogger("Minecraft");

    @Override
    public void onEnable() {
        // config.yml
        saveDefaultConfig();
        config = getConfig();
        ensureDefaultConfigValues();

        // players.yml in plugin data folder
        if (!getDataFolder().exists()) {
            // noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try {
                // noinspection ResultOfMethodCallIgnored
                playersFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create players.yml: " + e.getMessage());
            }
        }
        players = YamlConfiguration.loadConfiguration(playersFile);
        players.options().header("This is a list of players who had joined before but first joined again after the plugin was installed.");
        savePlayers();

        // Register listener
        Bukkit.getPluginManager().registerEvents(new FirstLoginListener(this), this);

        log.info("FirstLogin 1.4 (updated) - Enabled");
    }

    @Override
    public void onDisable() {
        savePlayers();
        log.info("FirstLogin 1.4 (updated) - Disabled");
    }

    void savePlayers() {
        try {
            players.save(playersFile);
        } catch (IOException e) {
            getLogger().severe("Could not save players.yml: " + e.getMessage());
        }
    }

    private void ensureDefaultConfigValues() {
        config.addDefault("World.name", "world");
        config.addDefault("World.Owner", "default");

        config.addDefault("messageGlobal.enabled", true);
        config.addDefault("messageGlobal.string", "first time user logged in");
        config.addDefault("messageGlobal.color", "&f");

        config.addDefault("message.enabled", true);
        config.addDefault("message.string", "Welcome to the server");
        config.addDefault("message.color", "&f");

        config.addDefault("messageBack.enabled", true);
        config.addDefault("messageBack.string", "first time user logged in");
        config.addDefault("messageBack.color", "&f");

        config.options().copyDefaults(true);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        String worldName = config.getString("World.name", "world");

        // Determine playerdata dir for counting / listing players to date
        File worldFolder = Bukkit.getWorld(worldName) != null
                ? Bukkit.getWorld(worldName).getWorldFolder()
                : Bukkit.getWorldContainer().toPath().resolve(worldName).toFile();
        File playerDataDir = new File(worldFolder, "playerdata");
        List<String> namesToDate = new ArrayList<>();
        if (playerDataDir.isDirectory()) {
            File[] files = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files != null) {
                for (File f : files) {
                    String base = f.getName().substring(0, f.getName().length() - 4);
                    try {
                        UUID uuid = UUID.fromString(base);
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        String name = op.getName();
                        if (name != null) namesToDate.add(name);
                    } catch (IllegalArgumentException ignored) {
                        // Not a UUID (unlikely on modern), skip
                    }
                }
            }
        }

        String cmdName = cmd.getName().toLowerCase(Locale.ROOT);
        switch (cmdName) {
            case "listp": {
                int num = namesToDate.size();
                player.sendMessage(ChatColor.DARK_RED + "Number of players joined to date: " + num);
                return true;
            }
            case "pnames": {
                String names = namesToDate.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
                player.sendMessage(ChatColor.DARK_RED + "Names of players joined to date: " + (names.isEmpty() ? "(none)" : names));
                return true;
            }
            case "owner": {
                String owner = config.getString("World.Owner", "default");
                player.sendMessage(owner);
                return true;
            }
            case "onlinep": {
                int online = Bukkit.getOnlinePlayers().size();
                int total = namesToDate.size();
                player.sendMessage("Currently there are " + online + " of " + total + " players online.");
                return true;
            }
            case "firsthelp": {
                player.sendMessage("/listp: Lists the number of players joined to date.");
                player.sendMessage("/pnames: Lists all the names of players that joined the server.");
                player.sendMessage("/owner: Shows the owner of the server's name.");
                player.sendMessage("/onlinep: Shows how many players are currently online.");
                player.sendMessage("/firsthelp: Shows this help.");
                return true;
            }
            default:
                return false;
        }
    }

    static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
