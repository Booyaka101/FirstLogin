package firstlogin.commands;

import firstlogin.FirstLogin;
import firstlogin.gui.WelcomeGui;
import firstlogin.services.TelemetryService;
import firstlogin.services.PlayersStore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Command handler that will gradually absorb logic from FirstLogin.
 */
public class FirstLoginCommand implements CommandExecutor, TabCompleter {
    private final FirstLogin plugin;

    public FirstLoginCommand(FirstLogin plugin) {
        this.plugin = plugin;
    }

    private java.util.List<String> listPendingRulesAcceptanceFiltered(String filter) {
        String f = (filter == null ? "all" : filter.toLowerCase(java.util.Locale.ROOT));
        java.util.Set<java.util.UUID> online = new java.util.HashSet<>();
        if (f.equals("online")) {
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        String rulesKey = rulesFlagKey();
        for (org.bukkit.OfflinePlayer op : org.bukkit.Bukkit.getOfflinePlayers()) {
            java.util.UUID u = op.getUniqueId();
            boolean accepted = FirstLogin.players.getBoolean("flags." + u + "." + rulesKey, false);
            if (accepted) continue;
            if (f.equals("online") && !online.contains(u)) continue;
            if (f.equals("offline") && online.contains(u)) continue;
            String name = op.getName();
            if (name == null || name.isEmpty()) name = u.toString();
            result.add(name);
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private boolean hasAdmin(Player player, String specificPerm) {
        return player.hasPermission("firstlogin.admin") || player.hasPermission(specificPerm);
    }

    private org.bukkit.OfflinePlayer resolveOffline(String name) {
        if (name == null || name.isEmpty()) return null;
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (org.bukkit.OfflinePlayer op : org.bukkit.Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        }
        return null;
    }

    private String rulesFlagKey() {
        // Delegate to plugin's centralized method
        return plugin.versionedFlagName("rules");
    }

    private String versionedFlagName(String base) {
        // Delegate to plugin's centralized method
        return plugin.versionedFlagName(base);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase(java.util.Locale.ROOT);
        // welcome command - allows players to open the Welcome GUI
        if (cmdName.equals("welcome")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                return true;
            }
            Player player = (Player) sender;
            // Check permission for regular players
            if (!player.hasPermission("firstlogin.gui.open") && !hasAdmin(player, "firstlogin.admin.gui")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            WelcomeGui gui = plugin.getWelcomeGui();
            if (gui == null || !gui.isEnabled()) {
                player.sendMessage(ChatColor.RED + "Welcome GUI is not enabled.");
                return true;
            }
            // If no args, just open the GUI for the player
            if (args.length == 0) {
                int page = 1;
                gui.openFor(player, page);
                return true;
            }
            // If first arg is a number, treat it as page number
            if (args.length == 1 && args[0].matches("\\d+")) {
                int page = Math.max(1, Integer.parseInt(args[0]));
                gui.openFor(player, page);
                return true;
            }
            // /firstlogin gui <additem|set|move|remove|open|clone|swap|undo|list|listpage|fill|normalize|validate|preview|export|jsonexport|import|fixduplicates|massset|rename|movepage|clearpage> ...
            if (args.length > 0 && args[0].equalsIgnoreCase("gui")) {
                if (!hasAdmin(player, "firstlogin.admin.gui")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui <additem|set|move|remove|open|clone|swap|undo|list|listpage|fill|normalize|validate|preview|export|jsonexport|import|fixduplicates|massset|rename|movepage|clearpage> ...");
                    return true;
                }
                String sub = args[1].toLowerCase(java.util.Locale.ROOT);
                org.bukkit.configuration.file.FileConfiguration cfg = FirstLogin.config;
                org.bukkit.configuration.ConfigurationSection items = cfg.getConfigurationSection("welcomeGui.items");
                if (items == null) items = cfg.createSection("welcomeGui.items");
                final org.bukkit.configuration.ConfigurationSection itemsRef = items;
                // helpers
                java.util.Set<String> itemKeys = itemsRef.getKeys(false);
                int rows = Math.max(1, Math.min(6, cfg.getInt("welcomeGui.rows", 3)));
                int invSize = rows * 9;
                Runnable backup = () -> {
                    try {
                        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                        java.io.File out = new java.io.File(plugin.getDataFolder(), "config.backup-" + ts + ".yml");
                        cfg.save(out);
                    } catch (Exception ignored) {}
                };
                java.util.function.Function<String, org.bukkit.configuration.ConfigurationSection> getItem = key -> {
                    org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                    if (s == null) s = itemsRef.createSection(key);
                    return s;
                };
                // additem <key> [slot] [material]
                if (sub.equals("additem")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui additem <key> [slot] [material]"); return true; }
                    String key = args[2];
                    org.bukkit.configuration.ConfigurationSection s = getItem.apply(key);
                    if (args.length >= 4) {
                        try {
                            int slot = Math.max(0, Integer.parseInt(args[3]));
                            if (slot >= invSize) { player.sendMessage(org.bukkit.ChatColor.RED + "Slot out of bounds (0-" + (invSize - 1) + ")"); return true; }
                            s.set("slot", slot);
                        } catch (NumberFormatException ex) {
                            player.sendMessage(org.bukkit.ChatColor.RED + "Invalid slot number.");
                            return true;
                        }
                    }
                    if (args.length >= 5) {
                        String matName = args[4];
                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName.toUpperCase(java.util.Locale.ROOT));
                        if (mat == null) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown material: " + matName); return true; }
                        s.set("material", mat.name());
                    }
                    backup.run(); FirstLogin.config = cfg;
                    plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Item '" + key + "' created.");
                    return true;
                }
                // set <key> <path> <value>
                if (sub.equals("set")) {
                    if (args.length < 5) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui set <key> <path> <value>"); return true; }
                    String key = args[2];
                    String path = args[3];
                    String value = java.util.Arrays.stream(args).skip(4).collect(java.util.stream.Collectors.joining(" "));
                    if (!itemKeys.contains(key)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key: " + key); return true; }
                    org.bukkit.configuration.ConfigurationSection s = getItem.apply(key);
                    // smart-parse lists and numbers/bools
                    Object toSet = value;
                    String lp = path.toLowerCase(java.util.Locale.ROOT);
                    if (lp.endsWith(".lore") || lp.endsWith(".requiresall") || lp.endsWith(".requiresany") || lp.endsWith(".actions")) {
                        java.util.List<String> list = java.util.Arrays.stream(value.split("\\|"))
                                .map(String::trim).filter(str -> !str.isEmpty()).collect(java.util.stream.Collectors.toList());
                        toSet = list;
                    } else if (lp.endsWith(".slot") || lp.endsWith(".page") || lp.endsWith(".cooldownseconds") || lp.endsWith(".delayticks")) {
                        try { toSet = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                    } else if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                        toSet = Boolean.parseBoolean(value);
                    } else if (lp.endsWith("material")) {
                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(value.toUpperCase(java.util.Locale.ROOT));
                        if (mat == null) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown material: " + value); return true; }
                        toSet = mat.name();
                    }
                    s.set(path, toSet);
                    backup.run(); FirstLogin.config = cfg;
                    plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Set '" + key + "." + path + "' to '" + value + "'.");
                    return true;
                }
                // move <key> <slot>
                if (sub.equals("move")) {
                    if (args.length < 4) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui move <key> <slot>"); return true; }
                    String key = args[2];
                    if (!itemKeys.contains(key)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key: " + key); return true; }
                    int slot;
                    try { slot = Math.max(0, Integer.parseInt(args[3])); } catch (NumberFormatException ex) { player.sendMessage(org.bukkit.ChatColor.RED + "Invalid slot."); return true; }
                    if (slot >= invSize) { player.sendMessage(org.bukkit.ChatColor.RED + "Slot out of bounds (0-" + (invSize - 1) + ")"); return true; }
                    org.bukkit.configuration.ConfigurationSection s = getItem.apply(key);
                    s.set("slot", slot);
                    backup.run(); FirstLogin.config = cfg;
                    plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Moved '" + key + "' to slot " + slot + ".");
                    return true;
                }
                // remove <key>
                if (sub.equals("remove")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui remove <key>"); return true; }
                    String key = args[2];
                    itemsRef.set(key, null);
                    backup.run(); FirstLogin.config = cfg;
                    plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Removed item '" + key + "'.");
                    return true;
                }
                // open [player] [page]
                if (sub.equals("open")) {
                    firstlogin.gui.WelcomeGui wgui = plugin.getWelcomeGui();
                    if (wgui == null || !wgui.isEnabled()) { player.sendMessage(org.bukkit.ChatColor.RED + "Welcome GUI is not enabled."); return true; }
                    org.bukkit.entity.Player target = player;
                    int page = 1;
                    if (args.length >= 3) {
                        org.bukkit.OfflinePlayer op = resolveOffline(args[2]);
                        if (op != null && op.isOnline()) target = (org.bukkit.entity.Player) op;
                        else {
                            try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
                        }
                    }
                    if (args.length >= 4) { try { page = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {} }
                    wgui.openFor(target, page);
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Opened Welcome GUI for " + target.getName() + " (page " + page + ")");
                    return true;
                }
                // list
                if (sub.equals("list")) {
                    String prefix = null;
                    if (args.length >= 3 && args[2].toLowerCase(java.util.Locale.ROOT).startsWith("filter=")) {
                        prefix = args[2].substring("filter=".length());
                    }
                    java.util.SortedSet<String> keysSorted = new java.util.TreeSet<>(itemKeys);
                    if (keysSorted.isEmpty()) { player.sendMessage(org.bukkit.ChatColor.GRAY + "No items configured under welcomeGui.items."); return true; }
                    player.sendMessage(org.bukkit.ChatColor.AQUA + (prefix == null ? "== Welcome GUI Items ==" : ("== Welcome GUI Items (filter='" + prefix + "') ==")));
                    for (String key : keysSorted) {
                        if (prefix != null && !key.startsWith(prefix)) continue;
                        org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                        int slot = s.getInt("slot", -1);
                        int page = s.getInt("page", 1);
                        String mat = s.getString("material", "PAPER");
                        player.sendMessage(org.bukkit.ChatColor.YELLOW + key + org.bukkit.ChatColor.DARK_GRAY + " | slot=" + slot + ", page=" + page + ", mat=" + mat);
                    }
                    return true;
                }
                // clone <srcKey> <dstKey>
                if (sub.equals("clone")) {
                    if (args.length < 4) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui clone <srcKey> <dstKey>"); return true; }
                    String src = args[2];
                    String dst = args[3];
                    if (!itemKeys.contains(src)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key: " + src); return true; }
                    org.bukkit.configuration.ConfigurationSection srcSec = itemsRef.getConfigurationSection(src);
                    org.bukkit.configuration.ConfigurationSection dstSec = itemsRef.createSection(dst);
                    for (String k : srcSec.getKeys(true)) {
                        Object val = srcSec.get(k);
                        dstSec.set(k, val);
                    }
                    backup.run(); FirstLogin.config = cfg; plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Cloned '" + src + "' -> '" + dst + "'.");
                    return true;
                }
                // swap <keyA> <keyB>
                if (sub.equals("swap")) {
                    if (args.length < 4) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui swap <keyA> <keyB>"); return true; }
                    String a = args[2]; String b = args[3];
                    if (!itemKeys.contains(a) || !itemKeys.contains(b)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key."); return true; }
                    org.bukkit.configuration.ConfigurationSection aSec = itemsRef.getConfigurationSection(a);
                    org.bukkit.configuration.ConfigurationSection bSec = itemsRef.getConfigurationSection(b);
                    // Deep copy
                    java.util.Map<String, Object> aMap = new java.util.HashMap<>();
                    for (String k : aSec.getKeys(true)) aMap.put(k, aSec.get(k));
                    java.util.Map<String, Object> bMap = new java.util.HashMap<>();
                    for (String k : bSec.getKeys(true)) bMap.put(k, bSec.get(k));
                    itemsRef.set(a, null); itemsRef.set(b, null);
                    org.bukkit.configuration.ConfigurationSection aNew = itemsRef.createSection(a);
                    org.bukkit.configuration.ConfigurationSection bNew = itemsRef.createSection(b);
                    for (java.util.Map.Entry<String,Object> e : aMap.entrySet()) aNew.set(e.getKey(), e.getValue());
                    for (java.util.Map.Entry<String,Object> e : bMap.entrySet()) bNew.set(e.getKey(), e.getValue());
                    backup.run(); FirstLogin.config = cfg; plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Swapped '" + a + "' with '" + b + "'.");
                    return true;
                }
                // undo (restore latest config.backup-*.yml)
                if (sub.equals("undo")) {
                    java.io.File dir = plugin.getDataFolder();
                    java.io.File[] backups = dir.listFiles((d, name) -> name.startsWith("config.backup-") && name.endsWith(".yml"));
                    if (backups == null || backups.length == 0) { player.sendMessage(org.bukkit.ChatColor.RED + "No backups found."); return true; }
                    java.util.Arrays.sort(backups, java.util.Comparator.comparingLong(java.io.File::lastModified).reversed());
                    java.io.File latest = backups[0];
                    java.io.File cfgFile = new java.io.File(dir, "config.yml");
                    try {
                        java.nio.file.Files.copy(latest.toPath(), cfgFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        plugin.reloadFirstLoginConfig();
                        plugin.reloadOptionalManagers();
                        player.sendMessage(org.bukkit.ChatColor.GREEN + "Restored backup: " + latest.getName());
                    } catch (Exception ex) {
                        player.sendMessage(org.bukkit.ChatColor.RED + "Failed to restore backup: " + ex.getMessage());
                    }
                    return true;
                }
                // fill <material> [name] [lore with | separator]
                if (sub.equals("fill")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui fill <material> [name] [lore1|lore2|...]"); return true; }
                    String matName = args[2];
                    org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName.toUpperCase(java.util.Locale.ROOT));
                    if (mat == null) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown material: " + matName); return true; }
                    String name = (args.length >= 4 ? args[3] : "&r");
                    java.util.List<String> lore = java.util.Collections.emptyList();
                    if (args.length >= 5) {
                        lore = java.util.Arrays.stream(args[4].split("\\|"))
                                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList());
                    }
                    org.bukkit.configuration.ConfigurationSection fill = cfg.getConfigurationSection("welcomeGui.filler");
                    if (fill == null) fill = cfg.createSection("welcomeGui.filler");
                    fill.set("enabled", true);
                    fill.set("material", mat.name());
                    fill.set("name", name);
                    fill.set("lore", lore);
                    backup.run(); FirstLogin.config = cfg; plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Filler enabled with material=" + mat.name());
                    return true;
                }
                // normalize: clamp all slots to inventory size
                if (sub.equals("normalize")) {
                    int changed = 0;
                    for (String key : itemKeys) {
                        org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                        int slot = s.getInt("slot", -1);
                        if (slot >= invSize) {
                            s.set("slot", invSize - 1);
                            changed++;
                        } else if (slot < 0) {
                            s.set("slot", 0);
                            changed++;
                        }
                    }
                    if (changed > 0) { backup.run(); FirstLogin.config = cfg; plugin.saveConfig(); }
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Normalized slots for " + changed + " item(s). Inventory size=" + invSize);
                    return true;
                }
                // validate [actions|layout|all|pages]: checks for common issues or page usage stats
                if (sub.equals("validate")) {
                    String mode = (args.length >= 3 ? args[2].toLowerCase(java.util.Locale.ROOT) : "all");
                    boolean strictActions = (mode.equals("actions") && args.length >= 4 && args[3].equalsIgnoreCase("strict"));
                    java.util.List<String> issues = new java.util.ArrayList<>();
                    java.util.Map<Integer, java.util.Set<Integer>> pageSlots = new java.util.HashMap<>();
                    if (mode.equals("pages")) {
                        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
                        int maxPage = 1;
                        for (String key : itemKeys) {
                            org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                            int page = Math.max(1, s.getInt("page", 1));
                            counts.merge(page, 1, Integer::sum);
                            if (page > maxPage) maxPage = page;
                        }
                        player.sendMessage(org.bukkit.ChatColor.AQUA + "== GUI Pages Summary ==");
                        for (int p = 1; p <= Math.max(1, maxPage); p++) {
                            int c = counts.getOrDefault(p, 0);
                            player.sendMessage(org.bukkit.ChatColor.GRAY + "Page " + p + ": " + (c == 0 ? org.bukkit.ChatColor.YELLOW + "(empty)" : (org.bukkit.ChatColor.GREEN + Integer.toString(c) + org.bukkit.ChatColor.GRAY + " item(s)")));
                        }
                        return true;
                    }
                    for (String key : itemKeys) {
                        org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                        int page = Math.max(1, s.getInt("page", 1));
                        int slot = s.getInt("slot", -1);
                        String matName = s.getString("material", "PAPER");
                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName.toUpperCase(java.util.Locale.ROOT));
                        boolean doLayout = mode.equals("layout") || mode.equals("all");
                        boolean doActions = mode.equals("actions") || mode.equals("all");
                        if (doLayout) {
                            if (mat == null) issues.add("Invalid material for '" + key + "': " + matName);
                            if (slot < 0 || slot >= invSize) issues.add("Slot out of bounds for '" + key + "': " + slot + " (size=" + invSize + ")");
                            java.util.Set<Integer> set = pageSlots.computeIfAbsent(page, k -> new java.util.HashSet<>());
                            if (!set.add(slot)) issues.add("Duplicate slot=" + slot + " on page=" + page + "); key '" + key + "'");
                            // Simple MiniMessage heuristic for name/lore tags
                            String name = s.getString("name", "");
                            if (!name.isEmpty()) { if (!isMiniMessageBalanced(name)) issues.add("Unbalanced MiniMessage tags in name for '" + key + "'"); }
                            java.util.List<String> lore = s.getStringList("lore");
                            if (lore != null) {
                                for (String line : lore) if (!line.isEmpty() && !isMiniMessageBalanced(line)) { issues.add("Unbalanced MiniMessage in lore for '" + key + "'"); break; }
                            }
                        }
                        if (doActions) {
                            // Basic actions presence check
                            java.util.List<String> acts = s.getStringList("actions");
                            String single = s.getString("action", null);
                            if ((acts == null || acts.isEmpty()) && (single == null || single.isEmpty())) {
                                issues.add("No actions defined for '" + key + "' (set 'actions' list or 'action')");
                            }
                            // Warn invalid runAs
                            String runAs = s.getString("runAs", "console").toLowerCase(java.util.Locale.ROOT);
                            if (!runAs.equals("console") && !runAs.equals("player") && !runAs.equals("op")) {
                                issues.add("Invalid runAs for '" + key + "': " + runAs + " (use console|player|op)");
                            }
                            // Warn on unknown action prefixes
                            java.util.List<String> toCheck = (acts != null && !acts.isEmpty()) ? acts : (single == null ? java.util.Collections.emptyList() : java.util.Arrays.asList(single));
                            for (String a : toCheck) {
                                String lc = a == null ? "" : a.toLowerCase(java.util.Locale.ROOT).trim();
                                if (lc.isEmpty()) continue;
                                if (!(lc.startsWith("message:") || lc.startsWith("command:") || lc.startsWith("url:") || lc.startsWith("flag:set:") || lc.startsWith("page:") || lc.equals("acceptrulesnow") || lc.equals("back"))) {
                                    issues.add("Unknown action prefix for '" + key + "': " + a);
                                }
                                if (strictActions) {
                                    if (lc.startsWith("command:") && lc.equals("command:")) issues.add("Empty command for '" + key + "'");
                                    if (lc.startsWith("url:") && !(lc.startsWith("url:http://") || lc.startsWith("url:https://"))) issues.add("URL should start with http(s):// for '" + key + "': " + a);
                                    if (lc.startsWith("page:")) {
                                        try { Integer.parseInt(lc.substring("page:".length())); } catch (NumberFormatException ex) { issues.add("Invalid page: action for '" + key + "': " + a); }
                                    }
                                    if (lc.startsWith("flag:set:") && lc.length() <= "flag:set:".length()) issues.add("flag:set requires a value for '" + key + "'");
                                }
                            }
                        }
                    }
                    if (issues.isEmpty()) {
                        player.sendMessage(org.bukkit.ChatColor.GREEN + "Validation OK: no issues found.");
                    } else {
                        player.sendMessage(org.bukkit.ChatColor.AQUA + "== GUI Validation Issues (" + issues.size() + ") ==");
                        for (String msg : issues) player.sendMessage(org.bukkit.ChatColor.RED + "- " + msg);
                    }
                    return true;
                }
                // preview <key> [player]
                if (sub.equals("preview")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui preview <key> [player]"); return true; }
                    String key = args[2];
                    if (!itemKeys.contains(key)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key: " + key); return true; }
                    org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                    int page = Math.max(1, s.getInt("page", 1));
                    firstlogin.gui.WelcomeGui wgui = plugin.getWelcomeGui();
                    if (wgui == null || !wgui.isEnabled()) { player.sendMessage(org.bukkit.ChatColor.RED + "Welcome GUI is not enabled."); return true; }
                    org.bukkit.entity.Player target = player;
                    if (args.length >= 4) {
                        org.bukkit.OfflinePlayer op = resolveOffline(args[3]);
                        if (op != null && op.isOnline()) target = (org.bukkit.entity.Player) op;
                    }
                    wgui.openFor(target, page);
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Opened page " + page + " to preview item '" + key + "' (slot=" + s.getInt("slot", -1) + ") for " + target.getName());
                    return true;
                }
                // export <key>
                if (sub.equals("export")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui export <key>"); return true; }
                    String key = args[2];
                    if (!itemKeys.contains(key)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key: " + key); return true; }
                    org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                    org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
                    for (String k : s.getKeys(true)) y.set(k, s.get(k));
                    java.io.File dir = new java.io.File(plugin.getDataFolder(), "exports"); if (!dir.exists()) dir.mkdirs();
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                    java.io.File out = new java.io.File(dir, key + "-" + ts + ".yml");
                    try { y.save(out); player.sendMessage(org.bukkit.ChatColor.GREEN + "Exported '" + key + "' to plugins/" + plugin.getDataFolder().getName() + "/exports/" + out.getName()); }
                    catch (Exception ex) { player.sendMessage(org.bukkit.ChatColor.RED + "Failed to export: " + ex.getMessage()); }
                    return true;
                }
                // import <file.yml> [key] [dry] [overwrite]
                if (sub.equals("import")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui import <file.yml> [key] [dry] [overwrite]"); return true; }
                    String fileName = args[2];
                    java.io.File dir = new java.io.File(plugin.getDataFolder(), "exports");
                    java.io.File f = new java.io.File(dir, fileName);
                    if (!f.exists()) { player.sendMessage(org.bukkit.ChatColor.RED + "File not found in exports/: " + fileName); return true; }
                    String key = null; boolean dryImp = false; boolean overwrite = false;
                    int idx = 3;
                    if (args.length >= 4 && !args[3].equalsIgnoreCase("dry") && !args[3].equalsIgnoreCase("overwrite")) { key = args[3]; idx = 4; }
                    if (key == null) key = fileName.replaceFirst("\\.yml$", "");
                    for (int i = idx; i < args.length; i++) {
                        String t = args[i].toLowerCase(java.util.Locale.ROOT);
                        if (t.equals("dry")) dryImp = true; else if (t.equals("overwrite")) overwrite = true;
                    }
                    org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                    if (itemsRef.isConfigurationSection(key) && !overwrite) {
                        player.sendMessage(org.bukkit.ChatColor.YELLOW + "Key '" + key + "' already exists. Re-run with 'overwrite' to replace.");
                        return true;
                    }
                    int entries = y.getKeys(true).size();
                    if (dryImp) {
                        player.sendMessage(org.bukkit.ChatColor.YELLOW + "[DRY] Would import '" + key + "' with " + entries + " entries from exports/" + fileName + (overwrite ? " (overwrite)" : ""));
                        return true;
                    }
                    org.bukkit.configuration.ConfigurationSection dst = itemsRef.createSection(key);
                    for (String k : y.getKeys(true)) dst.set(k, y.get(k));
                    backup.run(); FirstLogin.config = cfg; plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Imported '" + key + "' from exports/" + fileName + (overwrite ? " (overwrote)" : ""));
                    return true;
                }
                // rename <oldKey> <newKey>
                if (sub.equals("rename")) {
                    if (args.length < 4) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui rename <oldKey> <newKey>"); return true; }
                    String oldK = args[2]; String newK = args[3];
                    if (!itemKeys.contains(oldK)) { player.sendMessage(org.bukkit.ChatColor.RED + "Unknown item key: " + oldK); return true; }
                    if (itemKeys.contains(newK)) { player.sendMessage(org.bukkit.ChatColor.RED + "Target key already exists: " + newK); return true; }
                    org.bukkit.configuration.ConfigurationSection src = itemsRef.getConfigurationSection(oldK);
                    org.bukkit.configuration.ConfigurationSection dst = itemsRef.createSection(newK);
                    for (String k : src.getKeys(true)) dst.set(k, src.get(k));
                    itemsRef.set(oldK, null);
                    backup.run(); FirstLogin.config = cfg; plugin.saveConfig();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Renamed '" + oldK + "' -> '" + newK + "'.");
                    return true;
                }
                // movepage <from> <to>
                if (sub.equals("movepage")) {
                    if (args.length < 4) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui movepage <from> <to>"); return true; }
                    int from, to;
                    try { from = Math.max(1, Integer.parseInt(args[2])); to = Math.max(1, Integer.parseInt(args[3])); }
                    catch (NumberFormatException ex) { player.sendMessage(org.bukkit.ChatColor.RED + "Invalid page numbers."); return true; }
                    int changed = 0;
                    for (String key : itemKeys) {
                        org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                        if (Math.max(1, s.getInt("page", 1)) == from) { s.set("page", to); changed++; }
                    }
                    if (changed > 0) { backup.run(); FirstLogin.config = cfg; plugin.saveConfig(); }
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Moved " + changed + " item(s) from page " + from + " to " + to + ".");
                    return true;
                }
                // clearpage <n> confirm
                if (sub.equals("clearpage")) {
                    if (args.length < 3) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui clearpage <n> confirm"); return true; }
                    int pageN; try { pageN = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ex) { player.sendMessage(org.bukkit.ChatColor.RED + "Invalid page number."); return true; }
                    boolean confirmed = (args.length >= 4 && args[3].equalsIgnoreCase("confirm"));
                    if (!confirmed) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "This will remove all items on page " + pageN + ". Re-run with 'confirm' to apply."); return true; }
                    int removed = 0;
                    for (String key : new java.util.ArrayList<>(itemKeys)) {
                        org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                        if (Math.max(1, s.getInt("page", 1)) == pageN) { itemsRef.set(key, null); removed++; }
                    }
                    if (removed > 0) { backup.run(); FirstLogin.config = cfg; plugin.saveConfig(); }
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Removed " + removed + " item(s) from page " + pageN + ".");
                    return true;
                }
                // fixduplicates [verbose]: move items with duplicate slots in a page to nearest free slot
                if (sub.equals("fixduplicates")) {
                    boolean verbose = (args.length >= 3 && args[2].equalsIgnoreCase("verbose"));
                    int moves = 0;
                    java.util.List<String> moved = verbose ? new java.util.ArrayList<>() : java.util.Collections.emptyList();
                    for (int page = 1; page <= 10; page++) { // scan first 10 pages (typical)
                        boolean[] used = new boolean[invSize];
                        java.util.List<org.bukkit.configuration.ConfigurationSection> pageItems = new java.util.ArrayList<>();
                        for (String key : itemKeys) {
                            org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                            int p = Math.max(1, s.getInt("page", 1));
                            if (p != page) continue;
                            pageItems.add(s);
                        }
                        for (org.bukkit.configuration.ConfigurationSection s : pageItems) {
                            int slot = s.getInt("slot", -1);
                            if (slot >= 0 && slot < invSize && !used[slot]) { used[slot] = true; continue; }
                            // find nearest free slot
                            int best = -1; int dist = Integer.MAX_VALUE;
                            for (int i = 0; i < invSize; i++) if (!used[i]) { int d = Math.abs(i - Math.max(0, Math.min(invSize-1, slot))); if (d < dist) { dist = d; best = i; } }
                            if (best != -1) {
                                if (verbose) {
                                    // attempt to find the key by reverse lookup (best-effort)
                                    String kFound = null;
                                    for (String kx : itemKeys) if (itemsRef.getConfigurationSection(kx) == s) { kFound = kx; break; }
                                    moved.add((kFound == null ? "(unknown)" : kFound) + " page=" + page + " " + slot + "->" + best);
                                }
                                s.set("slot", best); used[best] = true; moves++;
                            }
                        }
                    }
                    if (moves > 0) { backup.run(); FirstLogin.config = cfg; plugin.saveConfig(); }
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Resolved duplicate slots with " + moves + " move(s).");
                    if (verbose && !moved.isEmpty()) {
                        player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "Moved: ");
                        for (String m : moved) player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + " - " + m);
                    }
                    return true;
                }
                // massset <path> <value> [filter=<prefix>] [page=<n>] [dry|confirm]
                if (sub.equals("massset")) {
                    if (args.length < 4) { player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui massset <path> <value> [filter=<prefix>] [page=<n>] [dry|confirm]"); return true; }
                    String path = args[2];
                    String value = java.util.Arrays.stream(args).skip(3).takeWhile(tok -> {
                        String lt = tok.toLowerCase(java.util.Locale.ROOT);
                        return !lt.startsWith("filter=") && !lt.startsWith("page=") && !lt.equals("dry") && !lt.equals("confirm");
                    }).collect(java.util.stream.Collectors.joining(" "));
                    String filter = null; Integer pageFilter = null; boolean dry = false; boolean confirm = false;
                    for (int i = 3; i < args.length; i++) {
                        String t = args[i]; String lt = t.toLowerCase(java.util.Locale.ROOT);
                        if (lt.startsWith("filter=")) filter = t.substring("filter=".length());
                        else if (lt.startsWith("page=")) { try { pageFilter = Integer.parseInt(t.substring("page=".length())); } catch (NumberFormatException ignored) {} }
                        else if (lt.equals("dry")) dry = true;
                        else if (lt.equals("confirm")) confirm = true;
                    }
                    int changed = 0;
                    for (String key : itemKeys) {
                        if (filter != null && !key.startsWith(filter)) continue;
                        org.bukkit.configuration.ConfigurationSection s = itemsRef.getConfigurationSection(key);
                        if (pageFilter != null && Math.max(1, s.getInt("page", 1)) != pageFilter) continue;
                        Object toSet = value;
                        String lp = path.toLowerCase(java.util.Locale.ROOT);
                        if (lp.endsWith(".lore") || lp.endsWith(".requiresall") || lp.endsWith(".requiresany") || lp.endsWith(".actions")) {
                            java.util.List<String> list = java.util.Arrays.stream(value.split("\\|")).map(String::trim).filter(str -> !str.isEmpty()).collect(java.util.stream.Collectors.toList());
                            toSet = list;
                        } else if (lp.endsWith(".slot") || lp.endsWith(".page") || lp.endsWith(".cooldownseconds") || lp.endsWith(".delayticks")) {
                            try { toSet = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                        } else if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                            toSet = Boolean.parseBoolean(value);
                        } else if (lp.endsWith("material")) {
                            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(value.toUpperCase(java.util.Locale.ROOT));
                            if (mat == null) continue; else toSet = mat.name();
                        }
                        if (!dry && confirm) s.set(path, toSet);
                        changed++;
                    }
                    if (!dry && confirm && changed > 0) { backup.run(); FirstLogin.config = cfg; plugin.saveConfig(); }
                    if (!dry && !confirm) {
                        player.sendMessage(org.bukkit.ChatColor.YELLOW + "[DRY by default] Preview: would mass set '" + path + "' for " + changed + " item(s). Re-run with 'confirm' to apply, or add 'dry' to keep preview mode.");
                    } else {
                        player.sendMessage((dry ? org.bukkit.ChatColor.YELLOW : org.bukkit.ChatColor.GREEN) + (dry ? "[DRY] " : "") + "Mass set '" + path + "' for " + changed + " item(s)" + (filter != null ? (" (filter=" + filter + ")") : "") + (pageFilter != null ? (" (page=" + pageFilter + ")") : ""));
                    }
                    return true;
                }
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin gui <additem|set|move|remove|open|clone|swap|undo|list|listpage|fill|normalize|validate|preview|export|jsonexport|import|fixduplicates|massset|rename|movepage|clearpage> ...");
                return true;
            }
            int page = 1;
            if (args.length >= 1) {
                try { page = Math.max(1, Integer.parseInt(args[0])); } catch (NumberFormatException ignored) {}
            }
            gui.openFor(player, page);
            return true;
        }

        // listp: number of players joined to date
        if (cmdName.equals("listp")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.hasPermission("firstlogin.command.listp")) {
                plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                return true;
            }
            java.util.List<String> names = getPlayersToDate();
            player.sendMessage(ChatColor.DARK_RED + "Number of players joined to date: " + names.size());
            return true;
        }

        // pnames: list names of players joined to date
        if (cmdName.equals("pnames")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.hasPermission("firstlogin.command.pnames")) {
                plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                return true;
            }
            java.util.List<String> names = getPlayersToDate();
            String joined = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(java.util.stream.Collectors.joining(", "));
            player.sendMessage(ChatColor.DARK_RED + "Names of players joined to date: " + (joined.isEmpty() ? "(none)" : joined));
            return true;
        }

        // owner: show server owner from config
        if (cmdName.equals("owner")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.hasPermission("firstlogin.command.owner")) {
                plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                return true;
            }
            String owner = FirstLogin.config.getString("World.Owner", "default");
            player.sendMessage(owner);
            return true;
        }

        // onlinep: show online/total counts
        if (cmdName.equals("onlinep")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.hasPermission("firstlogin.command.onlinep")) {
                plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                return true;
            }
            int online = org.bukkit.Bukkit.getOnlinePlayers().size();
            int total = getPlayersToDate().size();
            player.sendMessage("Currently there are " + online + " of " + total + " players online.");
            return true;
        }

        // firsthelp
        if (cmdName.equals("firsthelp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("FirstLogin Commands:");
                sender.sendMessage("/welcome [page] - Open the Welcome GUI");
                sender.sendMessage("/firstlogin help - Show admin commands");
                sender.sendMessage("/firstlogin version - Show plugin version");
                return true;
            }
            Player player = (Player) sender;
            player.sendMessage(org.bukkit.ChatColor.GOLD + "══════ FirstLogin Help ══════");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/welcome [page]" + org.bukkit.ChatColor.GRAY + " - Open the Welcome GUI");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin help" + org.bukkit.ChatColor.GRAY + " - Show all commands");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin version" + org.bukkit.ChatColor.GRAY + " - Show plugin version");
            player.sendMessage(org.bukkit.ChatColor.AQUA + "── Legacy Commands ──");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/listp" + org.bukkit.ChatColor.GRAY + " - Total players joined to date");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/pnames" + org.bukkit.ChatColor.GRAY + " - List all player names");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/owner" + org.bukkit.ChatColor.GRAY + " - Show server owner");
            player.sendMessage(org.bukkit.ChatColor.YELLOW + "/onlinep" + org.bukkit.ChatColor.GRAY + " - Current online count");
            player.sendMessage(org.bukkit.ChatColor.GRAY + "Use " + org.bukkit.ChatColor.YELLOW + "/firstlogin help" + org.bukkit.ChatColor.GRAY + " for admin commands.");
            return true;
        }
        // Handle a subset of /firstlogin here: 'locale', 'status', 'metrics'
        if (cmdName.equals("firstlogin")) {
            // /firstlogin version - works for console too
            if (args.length > 0 && args[0].equalsIgnoreCase("version")) {
                String ver = plugin.getDescription().getVersion();
                String apiVer = plugin.getDescription().getAPIVersion();
                sender.sendMessage(ChatColor.GOLD + "FirstLogin " + ChatColor.GREEN + "v" + ver);
                sender.sendMessage(ChatColor.GRAY + "API: " + (apiVer != null ? apiVer : "N/A") + " | Authors: " + String.join(", ", plugin.getDescription().getAuthors()));
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Most commands require a player. Use '/firstlogin version' from console.");
                return true;
            }
            Player player = (Player) sender;
            // /firstlogin reload
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!hasAdmin(player, "firstlogin.admin.reload")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                try {
                    plugin.reloadFirstLoginConfig();
                    plugin.reloadOptionalManagers();
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "FirstLogin configuration reloaded.");
                } catch (Throwable t) {
                    player.sendMessage(org.bukkit.ChatColor.RED + "Failed to reload: " + t.getMessage());
                }
                return true;
            }
            // /firstlogin report pending [page]
            // /firstlogin report pending csv [online|offline|all]
            if (args.length > 0 && args[0].equalsIgnoreCase("report")) {
                if (!hasAdmin(player, "firstlogin.admin.report")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                String type = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
                if (!type.equals("pending")) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin report pending [page] | /firstlogin report pending csv [online|offline|all]");
                    return true;
                }
                // CSV export path
                if (args.length >= 3 && args[2].equalsIgnoreCase("csv")) {
                    String filter = (args.length >= 4 ? args[3].toLowerCase(java.util.Locale.ROOT) : "all");
                    java.util.List<String> pending = listPendingRulesAcceptanceFiltered(filter);
                    java.io.File dir = new java.io.File(plugin.getDataFolder(), "reports");
                    if (!dir.exists()) dir.mkdirs();
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                    java.io.File out = new java.io.File(dir, "pending-" + filter + "-" + ts + ".csv");
                    try (java.io.PrintWriter pw = new java.io.PrintWriter(out, java.nio.charset.StandardCharsets.UTF_8.name())) {
                        pw.println("name,status");
                        for (String name : pending) pw.println(name + ",pending");
                    } catch (Exception ex) {
                        player.sendMessage(org.bukkit.ChatColor.RED + "Failed to write CSV: " + ex.getMessage());
                        return true;
                    }
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "Saved: " + out.getName() + org.bukkit.ChatColor.DARK_GRAY + " (plugins/" + plugin.getDataFolder().getName() + "/reports)");
                    return true;
                }

                // Paged list path with optional filter token in position 2
                String filter = "all";
                int page = 1;
                if (args.length >= 3) {
                    String tok = args[2].toLowerCase(java.util.Locale.ROOT);
                    if (tok.equals("online") || tok.equals("offline") || tok.equals("all")) {
                        filter = tok;
                        if (args.length >= 4) { try { page = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {} }
                    } else {
                        try { page = Math.max(1, Integer.parseInt(tok)); } catch (NumberFormatException ignored) {}
                    }
                }
                java.util.List<String> pending = listPendingRulesAcceptanceFiltered(filter);
                final int pageSize = 10;
                int totalPages = Math.max(1, (int) Math.ceil(pending.size() / (double) pageSize));
                page = Math.min(page, totalPages);
                int start = (page - 1) * pageSize;
                int end = Math.min(start + pageSize, pending.size());
                player.sendMessage(org.bukkit.ChatColor.AQUA + "== Pending Rules Acceptance [" + filter + "] (" + page + "/" + totalPages + ") ==");
                if (pending.isEmpty()) {
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "All players have accepted the current rules version.");
                } else {
                    for (int i = start; i < end; i++) {
                        player.sendMessage(org.bukkit.ChatColor.YELLOW + pending.get(i));
                    }
                    if (page < totalPages) player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "Use /firstlogin report pending " + filter + " " + (page + 1) + " for next page.");
                }
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("locale")) {
                if (args.length < 2) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.usageLocale"), player, 0);
                    return true;
                }
                String sub = args[1];
                if (sub.equalsIgnoreCase("reset")) {
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.setLocale(player.getUniqueId(), null);
                    else { FirstLogin.players.set("locale." + player.getUniqueId(), null); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.localeReset"), player, 0);
                } else {
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.setLocale(player.getUniqueId(), sub);
                    else { FirstLogin.players.set("locale." + player.getUniqueId(), sub); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.localeSet").replace("{locale}", sub), player, 0);
                }
                return true;
            }

            // /firstlogin debug [gui|inventory|saves|telemetry|all] [on|off]
            if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
                if (!hasAdmin(player, "firstlogin.admin")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 2) {
                    // Show current debug status
                    boolean gui = FirstLogin.config.getBoolean("debug.gui", false);
                    boolean inv = FirstLogin.config.getBoolean("debug.inventory", false);
                    boolean saves = FirstLogin.config.getBoolean("debug.saves", false);
                    boolean tele = FirstLogin.config.getBoolean("debug.telemetry", false);
                    player.sendMessage(org.bukkit.ChatColor.AQUA + "== Debug Status ==");
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "gui: " + (gui ? org.bukkit.ChatColor.GREEN + "ON" : org.bukkit.ChatColor.RED + "OFF"));
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "inventory: " + (inv ? org.bukkit.ChatColor.GREEN + "ON" : org.bukkit.ChatColor.RED + "OFF"));
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "saves: " + (saves ? org.bukkit.ChatColor.GREEN + "ON" : org.bukkit.ChatColor.RED + "OFF"));
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "telemetry: " + (tele ? org.bukkit.ChatColor.GREEN + "ON" : org.bukkit.ChatColor.RED + "OFF"));
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin debug <gui|inventory|saves|telemetry|all> [on|off]");
                    return true;
                }
                String mode = args[1].toLowerCase(java.util.Locale.ROOT);
                boolean newValue = true;
                if (args.length >= 3) {
                    String val = args[2].toLowerCase(java.util.Locale.ROOT);
                    newValue = val.equals("on") || val.equals("true") || val.equals("1");
                } else {
                    // Toggle current value
                    if (mode.equals("all")) {
                        newValue = !FirstLogin.config.getBoolean("debug.gui", false);
                    } else {
                        newValue = !FirstLogin.config.getBoolean("debug." + mode, false);
                    }
                }
                java.util.List<String> keys = new java.util.ArrayList<>();
                if (mode.equals("all")) {
                    keys.addAll(java.util.Arrays.asList("gui", "inventory", "saves", "telemetry"));
                } else if (mode.equals("gui") || mode.equals("inventory") || mode.equals("saves") || mode.equals("telemetry")) {
                    keys.add(mode);
                } else {
                    player.sendMessage(org.bukkit.ChatColor.RED + "Unknown debug mode: " + mode);
                    return true;
                }
                for (String k : keys) {
                    FirstLogin.config.set("debug." + k, newValue);
                }
                plugin.saveConfig();
                player.sendMessage(org.bukkit.ChatColor.GREEN + "Debug " + (keys.size() > 1 ? "all modes" : mode) + " set to " + (newValue ? "ON" : "OFF"));
                return true;
            }

            // /firstlogin info <player> - detailed player information
            if (args.length > 0 && args[0].equalsIgnoreCase("info")) {
                if (!hasAdmin(player, "firstlogin.admin.status")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin info <player>");
                    return true;
                }
                org.bukkit.OfflinePlayer op = resolveOffline(args[1]);
                if (op == null || op.getUniqueId() == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + args[1]);
                    return true;
                }
                java.util.UUID tu = op.getUniqueId();
                String targetName = op.getName() == null ? args[1] : op.getName();
                org.bukkit.configuration.file.FileConfiguration p = FirstLogin.players;
                
                player.sendMessage(org.bukkit.ChatColor.GOLD + "══════ Player Info: " + org.bukkit.ChatColor.WHITE + targetName + org.bukkit.ChatColor.GOLD + " ══════");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "UUID: " + org.bukkit.ChatColor.WHITE + tu);
                
                // Timestamps
                long firstJoin = p.getLong("timestamps." + tu + ".first_join", 0L);
                long rulesAccepted = p.getLong("timestamps." + tu + ".rules_accepted", 0L);
                String datePattern = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(datePattern);
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Timestamps ──");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "First join: " + (firstJoin > 0 ? org.bukkit.ChatColor.WHITE + sdf.format(new java.util.Date(firstJoin)) : org.bukkit.ChatColor.DARK_GRAY + "(unknown)"));
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules accepted: " + (rulesAccepted > 0 ? org.bukkit.ChatColor.WHITE + sdf.format(new java.util.Date(rulesAccepted)) : org.bukkit.ChatColor.DARK_GRAY + "(never)"));
                
                // Flags
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Flags ──");
                org.bukkit.configuration.ConfigurationSection fcs = p.getConfigurationSection("flags." + tu);
                if (fcs != null && !fcs.getKeys(false).isEmpty()) {
                    for (String fk : fcs.getKeys(false)) {
                        boolean fv = fcs.getBoolean(fk, false);
                        player.sendMessage(org.bukkit.ChatColor.GRAY + "  " + fk + ": " + (fv ? org.bukkit.ChatColor.GREEN + "true" : org.bukkit.ChatColor.RED + "false"));
                    }
                } else {
                    player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "  (none)");
                }
                
                // Cooldowns
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Cooldowns ──");
                org.bukkit.configuration.ConfigurationSection ccs = p.getConfigurationSection("cooldowns." + tu);
                if (ccs != null && !ccs.getKeys(false).isEmpty()) {
                    long now = System.currentTimeMillis();
                    for (String ck : ccs.getKeys(false)) {
                        long cv = ccs.getLong(ck, 0L);
                        String ago = FirstLogin.formatDurationPretty(now - cv);
                        player.sendMessage(org.bukkit.ChatColor.GRAY + "  " + ck + ": " + org.bukkit.ChatColor.WHITE + ago + " ago");
                    }
                } else {
                    player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "  (none)");
                }
                
                // Once claims
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Once Claims ──");
                org.bukkit.configuration.ConfigurationSection ocs = p.getConfigurationSection("once." + tu);
                if (ocs != null && !ocs.getKeys(false).isEmpty()) {
                    for (String ok : ocs.getKeys(false)) {
                        player.sendMessage(org.bukkit.ChatColor.GRAY + "  " + ok + ": " + org.bukkit.ChatColor.GREEN + "claimed");
                    }
                } else {
                    player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "  (none)");
                }
                
                // Locale
                String loc = p.getString("locale." + tu, null);
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Settings ──");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Locale: " + (loc != null ? org.bukkit.ChatColor.WHITE + loc : org.bukkit.ChatColor.DARK_GRAY + "(default)"));
                
                return true;
            }

            // /firstlogin help - show all available commands
            if (args.length == 0 || (args.length > 0 && args[0].equalsIgnoreCase("help"))) {
                player.sendMessage(org.bukkit.ChatColor.GOLD + "══════ FirstLogin Commands ══════");
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "/welcome [page]" + org.bukkit.ChatColor.GRAY + " - Open the Welcome GUI");
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin version" + org.bukkit.ChatColor.GRAY + " - Show plugin version");
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin stats" + org.bukkit.ChatColor.GRAY + " - Show server statistics");
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin status [player]" + org.bukkit.ChatColor.GRAY + " - Show player status");
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin info <player>" + org.bukkit.ChatColor.GRAY + " - Detailed player info");
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin locale <code|reset>" + org.bukkit.ChatColor.GRAY + " - Set your locale");
                if (hasAdmin(player, "firstlogin.admin")) {
                    player.sendMessage(org.bukkit.ChatColor.AQUA + "── Admin Commands ──");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin reload" + org.bukkit.ChatColor.GRAY + " - Reload configuration");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin validate" + org.bukkit.ChatColor.GRAY + " - Validate config.yml");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin debug <mode> [on|off]" + org.bukkit.ChatColor.GRAY + " - Toggle debug modes");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin metrics [when|reset|now]" + org.bukkit.ChatColor.GRAY + " - Telemetry info");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin report pending [filter]" + org.bukkit.ChatColor.GRAY + " - Pending rules report");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin forceopen <player>" + org.bukkit.ChatColor.GRAY + " - Force open GUI for player");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin reset <player>" + org.bukkit.ChatColor.GRAY + " - Reset player data");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin clearcooldown <player> <key|all>" + org.bukkit.ChatColor.GRAY + " - Clear cooldowns");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin clearflag <player> <flag|all>" + org.bukkit.ChatColor.GRAY + " - Clear flags");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin set <player> <type> <key> [value]" + org.bukkit.ChatColor.GRAY + " - Set player data");
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "/firstlogin gui ..." + org.bukkit.ChatColor.GRAY + " - GUI editor commands");
                }
                return true;
            }

            // /firstlogin validate - on-demand config validation
            if (args.length > 0 && args[0].equalsIgnoreCase("validate")) {
                if (!hasAdmin(player, "firstlogin.admin")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                player.sendMessage(org.bukkit.ChatColor.AQUA + "Validating configuration...");
                int warnings = plugin.validateConfig(player);
                if (warnings == 0) {
                    player.sendMessage(org.bukkit.ChatColor.GREEN + "No issues found!");
                } else {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Check console for detailed warnings.");
                }
                return true;
            }

            // /firstlogin stats - server-wide statistics
            if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
                if (!hasAdmin(player, "firstlogin.admin.status")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                player.sendMessage(org.bukkit.ChatColor.GOLD + "══════ FirstLogin Statistics ══════");
                
                // Player counts
                int totalPlayers = plugin.playersToDate();
                int rulesAccepted = plugin.getRulesAcceptedCount();
                int rulesPending = plugin.getRulesPendingCount();
                int onlinePlayers = org.bukkit.Bukkit.getOnlinePlayers().size();
                
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Players ──");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Total joined: " + org.bukkit.ChatColor.WHITE + totalPlayers);
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Online now: " + org.bukkit.ChatColor.WHITE + onlinePlayers);
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules accepted: " + org.bukkit.ChatColor.GREEN + rulesAccepted);
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules pending: " + org.bukkit.ChatColor.YELLOW + rulesPending);
                
                // Telemetry stats
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Today's Metrics ──");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "GUI opens: " + org.bukkit.ChatColor.WHITE + plugin.getGuiOpensToday());
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules accepted: " + org.bukkit.ChatColor.WHITE + plugin.getRulesAcceptedToday());
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Item clicks: " + org.bukkit.ChatColor.WHITE + plugin.getTotalItemClicksToday());
                
                // Config info
                player.sendMessage(org.bukkit.ChatColor.AQUA + "── Configuration ──");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules version: " + org.bukkit.ChatColor.WHITE + plugin.getRulesVersion());
                player.sendMessage(org.bukkit.ChatColor.GRAY + "GUI enabled: " + (plugin.getWelcomeGui() != null && plugin.getWelcomeGui().isEnabled() ? org.bukkit.ChatColor.GREEN + "yes" : org.bukkit.ChatColor.RED + "no"));
                player.sendMessage(org.bukkit.ChatColor.GRAY + "BossBar enabled: " + (FirstLogin.config.getBoolean("bossbar.enabled", false) ? org.bukkit.ChatColor.GREEN + "yes" : org.bukkit.ChatColor.RED + "no"));
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Particles enabled: " + (FirstLogin.config.getBoolean("particles.enabled", false) ? org.bukkit.ChatColor.GREEN + "yes" : org.bukkit.ChatColor.RED + "no"));
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Guide enabled: " + (FirstLogin.config.getBoolean("animatedGuide.enabled", false) ? org.bukkit.ChatColor.GREEN + "yes" : org.bukkit.ChatColor.RED + "no"));
                
                return true;
            }

            // /firstlogin clearcooldown <player> <key|all>
            if (args.length > 0 && args[0].equalsIgnoreCase("clearcooldown")) {
                if (!hasAdmin(player, "firstlogin.admin.clearcooldown")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin clearcooldown <player> <key|all>");
                    return true;
                }
                org.bukkit.OfflinePlayer op = resolveOffline(args[1]);
                if (op == null || op.getUniqueId() == null) { player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + args[1]); return true; }
                String key = args[2];
                if (key.equalsIgnoreCase("all")) {
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.clearAllCooldowns(op.getUniqueId());
                    else { FirstLogin.players.set("cooldowns." + op.getUniqueId(), null); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.clearedCooldownAll").replace("{player}", op.getName() == null ? args[1] : op.getName()), player, 0);
                } else {
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.clearCooldown(op.getUniqueId(), key);
                    else { FirstLogin.players.set("cooldowns." + op.getUniqueId() + "." + key, null); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.clearedCooldown").replace("{player}", op.getName() == null ? args[1] : op.getName()).replace("{key}", key), player, 0);
                }
                return true;
            }

            // /firstlogin clearflag <player> <flag|all>
            if (args.length > 0 && args[0].equalsIgnoreCase("clearflag")) {
                if (!hasAdmin(player, "firstlogin.admin.clearflag")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin clearflag <player> <flag|all>");
                    return true;
                }
                org.bukkit.OfflinePlayer op = resolveOffline(args[1]);
                if (op == null || op.getUniqueId() == null) { player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + args[1]); return true; }
                String flag = args[2];
                if (flag.equalsIgnoreCase("all")) {
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.clearAllFlags(op.getUniqueId());
                    else { FirstLogin.players.set("flags." + op.getUniqueId(), null); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.clearedAllFlags").replace("{player}", op.getName() == null ? args[1] : op.getName()), player, 0);
                } else {
                    PlayersStore store = plugin.getPlayersStore();
                    String v = (store != null ? store.versionedFlagName(flag) : versionedFlagName(flag));
                    if (store != null) store.clearFlag(op.getUniqueId(), flag);
                    else { FirstLogin.players.set("flags." + op.getUniqueId() + "." + v, null); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.clearedFlag").replace("{player}", op.getName() == null ? args[1] : op.getName()).replace("{flag}", v), player, 0);
                }
                return true;
            }

            // /firstlogin status [player]
            if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                if (!hasAdmin(player, "firstlogin.admin.status")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                org.bukkit.OfflinePlayer target = player;
                String targetName = player.getName();
                if (args.length >= 2) {
                    org.bukkit.OfflinePlayer op = resolveOffline(args[1]);
                    if (op == null || op.getUniqueId() == null) {
                        player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + args[1]);
                        return true;
                    }
                    target = op;
                    targetName = op.getName() == null ? args[1] : op.getName();
                }
                java.util.UUID tu = target.getUniqueId();
                PlayersStore store = plugin.getPlayersStore();
                org.bukkit.configuration.file.FileConfiguration playersCfg = (store != null ? store.getPlayers() : FirstLogin.players);
                String loc = playersCfg.getString("locale." + tu, "(default)");
                String rulesKey = rulesFlagKey();
                boolean accepted = playersCfg.getBoolean("flags." + tu + "." + rulesKey, false);
                org.bukkit.configuration.ConfigurationSection fcs = playersCfg.getConfigurationSection("flags." + tu);
                org.bukkit.configuration.ConfigurationSection ccs = playersCfg.getConfigurationSection("cooldowns." + tu);
                java.util.Set<String> flags = fcs != null ? fcs.getKeys(false) : java.util.Collections.emptySet();
                java.util.Set<String> cds = ccs != null ? ccs.getKeys(false) : java.util.Collections.emptySet();
                player.sendMessage(org.bukkit.ChatColor.AQUA + "== FirstLogin Status: " + org.bukkit.ChatColor.WHITE + targetName + org.bukkit.ChatColor.AQUA + " ==");
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Locale: " + org.bukkit.ChatColor.YELLOW + loc);
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules accepted: " + (accepted ? org.bukkit.ChatColor.GREEN + "yes" : org.bukkit.ChatColor.RED + "no"));
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Flags(" + flags.size() + "): " + org.bukkit.ChatColor.YELLOW + (flags.isEmpty() ? "(none)" : String.join(", ", flags)));
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Cooldown keys(" + cds.size() + "): " + org.bukkit.ChatColor.YELLOW + (cds.isEmpty() ? "(none)" : String.join(", ", cds)));
                return true;
            }

            // /firstlogin seen <player>
            if (args.length > 0 && args[0].equalsIgnoreCase("seen")) {
                if (!hasAdmin(player, "firstlogin.admin.seen")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 2) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.usageSeen"), player, 0);
                    return true;
                }
                String targetName = args[1];
                org.bukkit.OfflinePlayer op = resolveOffline(targetName);
                if (op == null || op.getUniqueId() == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + targetName);
                    return true;
                }
                PlayersStore store = plugin.getPlayersStore();
                boolean seen = (store != null ? store.isSeen(op.getUniqueId()) : FirstLogin.players.getBoolean("players." + op.getUniqueId(), false));
                plugin.sendMsg(player, plugin.msgFor(player, seen ? "messages.seenTrue" : "messages.seenFalse").replace("{player}", op.getName() == null ? targetName : op.getName()), player, 0);
                return true;
            }

            // /firstlogin reset <player|all>
            if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
                if (!hasAdmin(player, "firstlogin.admin.reset")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 2) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.usageReset"), player, 0);
                    return true;
                }
                String who = args[1];
                if (who.equalsIgnoreCase("all")) {
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.resetAllSeen();
                    else { FirstLogin.players.set("players", null); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.resetAll"), player, 0);
                } else {
                    org.bukkit.OfflinePlayer op = resolveOffline(who);
                    if (op == null || op.getUniqueId() == null) {
                        player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + who);
                        return true;
                    }
                    PlayersStore store = plugin.getPlayersStore();
                    if (store != null) store.resetSeen(op.getUniqueId());
                    else { FirstLogin.players.set("players." + op.getUniqueId(), false); plugin.savePlayers(); }
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.resetPlayer").replace("{player}", op.getName() == null ? who : op.getName()), player, 0);
                }
                return true;
            }

            // /firstlogin metrics [reset|now|when]
            if (args.length > 0 && args[0].equalsIgnoreCase("metrics")) {
                if (!hasAdmin(player, "firstlogin.admin.metrics")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length >= 2) {
                    String sub = args[1].toLowerCase(java.util.Locale.ROOT);
                    // State-mutating actions handled here
                    if (sub.equals("reset") || sub.equals("now")) {
                        TelemetryService ts = plugin.getTelemetryService();
                        if (ts != null) ts.resetMetrics(); else plugin.resetMetrics();
                        player.sendMessage(org.bukkit.ChatColor.GREEN + "Telemetry counters reset.");
                        return true;
                    }
                    if (sub.equals("when")) {
                        TelemetryService ts = plugin.getTelemetryService();
                        long last = (ts != null ? ts.getTelemetryLastResetTs() : plugin.getTelemetryLastResetTs());
                        long next = (ts != null ? ts.getTelemetryNextResetTs() : plugin.getTelemetryNextResetTs());
                        String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                        if (last > 0L) {
                            String whenLast;
                            try { whenLast = new java.text.SimpleDateFormat(pat).format(new java.util.Date(last)); }
                            catch (Throwable ignored) { whenLast = Long.toString(last); }
                            player.sendMessage(org.bukkit.ChatColor.GRAY + "Last reset: " + org.bukkit.ChatColor.YELLOW + whenLast);
                        } else {
                            player.sendMessage(org.bukkit.ChatColor.GRAY + "Last reset: " + org.bukkit.ChatColor.YELLOW + "(never)");
                        }
                        if (next > 0L) {
                            String whenNext;
                            try { whenNext = new java.text.SimpleDateFormat(pat).format(new java.util.Date(next)); }
                            catch (Throwable ignored) { whenNext = Long.toString(next); }
                            long now = System.currentTimeMillis();
                            long diff = Math.max(0L, next - now);
                            long days = diff / (24L*60*60*1000L);
                            diff %= (24L*60*60*1000L);
                            long hours = diff / (60L*60*1000L);
                            diff %= (60L*60*1000L);
                            long mins = diff / (60L*1000L);
                            diff %= (60L*1000L);
                            long secs = diff / 1000L;
                            StringBuilder in = new StringBuilder();
                            if (days > 0) in.append(days).append("d ");
                            if (hours > 0) in.append(hours).append("h ");
                            if (mins > 0) in.append(mins).append("m ");
                            in.append(secs).append("s");
                            player.sendMessage(org.bukkit.ChatColor.GRAY + "Next reset: " + org.bukkit.ChatColor.YELLOW + whenNext + org.bukkit.ChatColor.DARK_GRAY + " (in " + in.toString().trim() + ")");
                        } else {
                            player.sendMessage(org.bukkit.ChatColor.GRAY + "Next reset: " + org.bukkit.ChatColor.YELLOW + "(disabled)");
                        }
                        return true;
                    }
                }
                // Default summary (today)
                player.sendMessage(org.bukkit.ChatColor.AQUA + "== FirstLogin Telemetry (today) ==");
                TelemetryService ts = plugin.getTelemetryService();
                int opens = ts != null ? ts.getGuiOpensToday() : plugin.getGuiOpensToday();
                int accepted = ts != null ? ts.getRulesAcceptedToday() : plugin.getRulesAcceptedToday();
                player.sendMessage(org.bukkit.ChatColor.GRAY + "GUI opens: " + org.bukkit.ChatColor.YELLOW + opens);
                player.sendMessage(org.bukkit.ChatColor.GRAY + "Rules accepted: " + org.bukkit.ChatColor.YELLOW + accepted);
                long last = ts != null ? ts.getTelemetryLastResetTs() : plugin.getTelemetryLastResetTs();
                if (last > 0L) {
                    String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                    String when;
                    try { when = new java.text.SimpleDateFormat(pat).format(new java.util.Date(last)); }
                    catch (Throwable ignored) { when = Long.toString(last); }
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "Last reset: " + org.bukkit.ChatColor.YELLOW + when);
                }
                long next = ts != null ? ts.getTelemetryNextResetTs() : plugin.getTelemetryNextResetTs();
                if (next > 0L) {
                    String pat = FirstLogin.config.getString("formatting.datePattern", "yyyy-MM-dd HH:mm:ss");
                    String whenNext;
                    try { whenNext = new java.text.SimpleDateFormat(pat).format(new java.util.Date(next)); }
                    catch (Throwable ignored) { whenNext = Long.toString(next); }
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "Next reset: " + org.bukkit.ChatColor.YELLOW + whenNext);
                }
                return true;
            }

            // /firstlogin set <player> <flag|cooldown|locale> <key> <value>
            if (args.length > 0 && args[0].equalsIgnoreCase("set")) {
                if (!hasAdmin(player, "firstlogin.admin.set")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                if (args.length < 4) {
                    player.sendMessage(org.bukkit.ChatColor.YELLOW + "Usage: /firstlogin set <player> <flag|cooldown|locale|timestamp> <key> [value]");
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "Examples:");
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "  /firstlogin set Player1 flag rules true");
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "  /firstlogin set Player1 cooldown kit_claim 0");
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "  /firstlogin set Player1 locale en_us");
                    return true;
                }
                org.bukkit.OfflinePlayer op = resolveOffline(args[1]);
                if (op == null || op.getUniqueId() == null) {
                    player.sendMessage(org.bukkit.ChatColor.RED + "Player not found: " + args[1]);
                    return true;
                }
                String type = args[2].toLowerCase(java.util.Locale.ROOT);
                String key = args[3];
                String value = args.length >= 5 ? args[4] : null;
                java.util.UUID uuid = op.getUniqueId();
                String targetName = op.getName() == null ? args[1] : op.getName();
                
                switch (type) {
                    case "flag": {
                        if (value == null) {
                            player.sendMessage(org.bukkit.ChatColor.RED + "Usage: /firstlogin set <player> flag <key> <true|false>");
                            return true;
                        }
                        boolean val = value.equalsIgnoreCase("true") || value.equals("1");
                        String versionedKey = plugin.versionedFlagName(key);
                        FirstLogin.players.set("flags." + uuid + "." + versionedKey, val);
                        plugin.savePlayers();
                        player.sendMessage(org.bukkit.ChatColor.GREEN + "Set flag '" + versionedKey + "' to " + val + " for " + targetName);
                        break;
                    }
                    case "cooldown": {
                        long ts = 0L;
                        if (value != null && !value.equals("0")) {
                            try { ts = Long.parseLong(value); }
                            catch (NumberFormatException e) { ts = System.currentTimeMillis(); }
                        }
                        if (ts == 0L) {
                            FirstLogin.players.set("cooldowns." + uuid + "." + key, null);
                            player.sendMessage(org.bukkit.ChatColor.GREEN + "Cleared cooldown '" + key + "' for " + targetName);
                        } else {
                            FirstLogin.players.set("cooldowns." + uuid + "." + key, ts);
                            player.sendMessage(org.bukkit.ChatColor.GREEN + "Set cooldown '" + key + "' to " + ts + " for " + targetName);
                        }
                        plugin.savePlayers();
                        break;
                    }
                    case "locale": {
                        if (key.equalsIgnoreCase("reset") || key.equalsIgnoreCase("null") || key.equalsIgnoreCase("default")) {
                            FirstLogin.players.set("locale." + uuid, null);
                            player.sendMessage(org.bukkit.ChatColor.GREEN + "Reset locale for " + targetName);
                        } else {
                            FirstLogin.players.set("locale." + uuid, key);
                            player.sendMessage(org.bukkit.ChatColor.GREEN + "Set locale to '" + key + "' for " + targetName);
                        }
                        plugin.savePlayers();
                        break;
                    }
                    case "timestamp": {
                        if (value == null) {
                            player.sendMessage(org.bukkit.ChatColor.RED + "Usage: /firstlogin set <player> timestamp <key> <epochMillis|now|clear>");
                            return true;
                        }
                        long ts;
                        if (value.equalsIgnoreCase("now")) {
                            ts = System.currentTimeMillis();
                        } else if (value.equalsIgnoreCase("clear") || value.equals("0")) {
                            FirstLogin.players.set("timestamps." + uuid + "." + key, null);
                            plugin.savePlayers();
                            player.sendMessage(org.bukkit.ChatColor.GREEN + "Cleared timestamp '" + key + "' for " + targetName);
                            return true;
                        } else {
                            try { ts = Long.parseLong(value); }
                            catch (NumberFormatException e) {
                                player.sendMessage(org.bukkit.ChatColor.RED + "Invalid timestamp value: " + value);
                                return true;
                            }
                        }
                        FirstLogin.players.set("timestamps." + uuid + "." + key, ts);
                        plugin.savePlayers();
                        player.sendMessage(org.bukkit.ChatColor.GREEN + "Set timestamp '" + key + "' to " + ts + " for " + targetName);
                        break;
                    }
                    default:
                        player.sendMessage(org.bukkit.ChatColor.RED + "Unknown type: " + type + ". Use: flag, cooldown, locale, or timestamp");
                }
                return true;
            }

            // /firstlogin forceopen
            if (args.length > 0 && args[0].equalsIgnoreCase("forceopen")) {
                if (!hasAdmin(player, "firstlogin.admin.forceopen")) {
                    plugin.sendMsg(player, plugin.msgFor(player, "messages.noPermission"), player, 0);
                    return true;
                }
                WelcomeGui gui = plugin.getWelcomeGui();
                if (gui == null || !gui.isEnabled()) {
                    player.sendMessage(ChatColor.RED + "Welcome GUI is not enabled.");
                    return true;
                }
                int count = 0;
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    PlayersStore store = plugin.getPlayersStore();
                    boolean accepted = (store != null ? store.hasAcceptedRules(p) : plugin.hasAcceptedRules(p));
                    if (!accepted) {
                        gui.openFor(p, 1);
                        count++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "Reopened Welcome GUI for " + count + " player(s) who have not accepted current rules version.");
                return true;
            }
        }
        // Unhandled commands: let Bukkit handle or other executors if any
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (cmdName.equals("welcome")) {
            if (args.length == 1) return Arrays.asList("1", "2", "3", "4", "5");
            return Collections.emptyList();
        }
        if (cmdName.equals("firstlogin")) {
            if (args.length == 1) {
                return Arrays.asList("help", "reload", "locale", "status", "metrics", "clearcooldown", "clearflag", "seen", "reset", "forceopen", "report", "version", "debug", "info", "validate", "stats", "set");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
                return Arrays.asList("pending");
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("report") && args[1].equalsIgnoreCase("pending")) {
                return Arrays.asList("online", "offline", "all", "1", "2", "3", "4", "5");
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("report") && args[1].equalsIgnoreCase("pending")) {
                String tok = args[2].toLowerCase(java.util.Locale.ROOT);
                if (tok.equals("csv")) return Arrays.asList("online", "offline", "all");
                if (tok.equals("online") || tok.equals("offline") || tok.equals("all")) return Arrays.asList("1", "2", "3", "4", "5");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("metrics")) {
                return Arrays.asList("when", "reset", "now");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
                return Arrays.asList("gui", "inventory", "saves", "telemetry", "all");
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("debug")) {
                return Arrays.asList("on", "off");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
                // Return online player names
                java.util.List<String> names = new java.util.ArrayList<>();
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) names.add(p.getName());
                return names;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                // Return online player names
                java.util.List<String> names = new java.util.ArrayList<>();
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) names.add(p.getName());
                return names;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                return Arrays.asList("flag", "cooldown", "locale", "timestamp");
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("set")) {
                String type = args[2].toLowerCase(java.util.Locale.ROOT);
                if (type.equals("flag")) return Arrays.asList("rules", "welcomed", "tutorial");
                if (type.equals("cooldown")) return Arrays.asList("kit_claim", "daily_reward");
                if (type.equals("timestamp")) return Arrays.asList("first_join", "rules_accepted");
                if (type.equals("locale")) return Arrays.asList("en_us", "es_es", "de_de", "reset");
            }
            if (args.length == 5 && args[0].equalsIgnoreCase("set")) {
                String type = args[2].toLowerCase(java.util.Locale.ROOT);
                if (type.equals("flag")) return Arrays.asList("true", "false");
                if (type.equals("timestamp")) return Arrays.asList("now", "clear");
            }
            // GUI editor tab completion
            if (args[0].equalsIgnoreCase("gui")) {
                org.bukkit.configuration.file.FileConfiguration cfg = firstlogin.FirstLogin.config;
                org.bukkit.configuration.ConfigurationSection items = (cfg != null ? cfg.getConfigurationSection("welcomeGui.items") : null);
                java.util.List<String> subs = java.util.Arrays.asList(
                        "additem","set","move","remove","open","clone","swap","undo","list","listpage",
                        "fill","normalize","validate","preview","export","jsonexport","import","fixduplicates",
                        "massset","rename","movepage","clearpage"
                );
                java.util.Set<String> keys = (items != null ? items.getKeys(false) : java.util.Collections.emptySet());
                java.util.List<String> commonPaths = java.util.Arrays.asList(
                        "material","name","lore","actions","permission","hideIfNoPermission",
                        "requires","requiresAll","requiresAny","page","slot","cooldownSeconds","delayTicks",
                        "runAs","clickSound.name","clickSound.volume","clickSound.pitch",
                        "disabledVariant.material","disabledVariant.name","disabledVariant.lore"
                );
                if (args.length == 2) {
                    return subs;
                }
                String sub = args[1].toLowerCase(java.util.Locale.ROOT);
                if (args.length == 3) {
                    switch (sub) {
                        case "set":
                        case "move":
                        case "remove":
                        case "open":
                        case "clone":
                        case "swap":
                        case "export":
                        case "jsonexport":
                        case "preview":
                        case "rename":
                            return new java.util.ArrayList<>(keys);
                        case "validate":
                            return java.util.Arrays.asList("actions","layout","pages","all");
                        case "massset":
                            return commonPaths;
                        case "listpage":
                            return java.util.Arrays.asList("1","2","3","4","5");
                        case "movepage":
                            return java.util.Arrays.asList("1","2","3","4","5");
                        case "clearpage":
                            return java.util.Arrays.asList("1","2","3","4","5");
                        case "list":
                            return java.util.Arrays.asList("filter=");
                        default:
                            return java.util.Collections.emptyList();
                    }
                }
                if (args.length == 4 && sub.equals("validate") && args[2].equalsIgnoreCase("actions")) {
                    return java.util.Arrays.asList("strict");
                }
                if (args.length == 4) {
                    switch (sub) {
                        case "set":
                            return commonPaths;
                        case "massset":
                            return java.util.Arrays.asList(
                                    "true","false","PAPER","DIAMOND","CHEST","BARRIER",
                                    "&aName","Line1|Line2|Line3","console","player","op","1","0"
                            );
                        case "rename":
                            return java.util.Arrays.asList("new_key_here");
                        case "move":
                            return java.util.Arrays.asList("0","1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37","38","39","40","41","42","43","44","45","46","47","48","49","50");
                        case "clone":
                        case "swap":
                            return new java.util.ArrayList<>(keys);
                        case "open":
                            return java.util.Arrays.asList("1","2","3","4","5");
                        case "import":
                            return java.util.Arrays.asList("dry","overwrite");
                        default:
                            return java.util.Collections.emptyList();
                    }
                }
                if (args.length == 5 && sub.equals("set")) {
                    String path = args[3].toLowerCase(java.util.Locale.ROOT);
                    if (path.endsWith("material") || path.equals("material")) {
                        // Suggest a few common materials
                        return java.util.Arrays.asList("PAPER","BOOK","STONE","DIAMOND","CHEST","BARRIER","LIME_WOOL","YELLOW_WOOL","RED_WOOL");
                    }
                    if (path.endsWith("lore") || path.endsWith("requiresall") || path.endsWith("requiresany") || path.endsWith("actions")) {
                        return java.util.Arrays.asList("Line1|Line2|Line3");
                    }
                    if (path.endsWith("hideifnopermission") || path.endsWith("closeonclick") || path.endsWith("once")) {
                        return java.util.Arrays.asList("true","false");
                    }
                    if (path.endsWith("runas")) {
                        return java.util.Arrays.asList("console","player","op");
                    }
                }
                if (args.length >= 5 && sub.equals("massset")) {
                    return java.util.Arrays.asList("filter=","page=","dry","confirm");
                }
                if (args.length == 3 && sub.equals("clearpage")) {
                    return java.util.Arrays.asList("confirm");
                }
            }
        }
        return Collections.emptyList();
    }

    // Simple heuristic to catch obviously unbalanced MiniMessage tags like '<bold>' without '>' or stray closing '>'
    private boolean isMiniMessageBalanced(String s) {
        if (s == null || s.isEmpty()) return true;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') {
                depth--;
                if (depth < 0) return false;
            }
        }
        return depth == 0;
    }

    private java.util.List<String> getPlayersToDate() {
        String worldName = FirstLogin.config.getString("World.name", "world");
        java.io.File worldFolder = org.bukkit.Bukkit.getWorld(worldName) != null
                ? org.bukkit.Bukkit.getWorld(worldName).getWorldFolder()
                : org.bukkit.Bukkit.getWorldContainer().toPath().resolve(worldName).toFile();
        java.io.File playerDataDir = new java.io.File(worldFolder, "playerdata");
        java.util.List<String> result = new java.util.ArrayList<>();
        if (playerDataDir.isDirectory()) {
            java.io.File[] files = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
            if (files != null) {
                for (java.io.File f : files) {
                    String base = f.getName().substring(0, f.getName().length() - 4);
                    try {
                        java.util.UUID uuid = java.util.UUID.fromString(base);
                        org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(uuid);
                        String name = op.getName();
                        if (name != null) result.add(name);
                    } catch (IllegalArgumentException ignored) {
                        // Not a UUID (unlikely on modern), skip
                    }
                }
            }
        }
        return result;
    }
}
