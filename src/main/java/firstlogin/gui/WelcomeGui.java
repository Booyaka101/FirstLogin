package firstlogin.gui;

import firstlogin.FirstLogin;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.*;
import java.util.stream.Collectors;

public class WelcomeGui implements Listener {
    private final FirstLogin plugin;

    // Track actions per opened GUI by player UUID and slot
    private final Map<UUID, Map<Integer, GuiAction>> openActions = new HashMap<>();

    public WelcomeGui(FirstLogin plugin) {
        this.plugin = plugin;
    }

    private void playDeny(Player p) {
        try {
            org.bukkit.configuration.ConfigurationSection ds = FirstLogin.config.getConfigurationSection("welcomeGui.denySound");
            if (ds == null) return;
            String name = ds.getString("name");
            if (name == null || name.isEmpty()) return;
            float vol = (float) ds.getDouble("volume", 1.0);
            float pitch = (float) ds.getDouble("pitch", 1.2);
            Sound s = Sound.valueOf(name);
            p.playSound(p.getLocation(), s, vol, pitch);
        } catch (Throwable ignored) {}
    }

    private String withPlaceholders(Player p, String text) {
        // Not used for core formatting anymore; kept as a safe passthrough helper
        return text == null ? "" : text.replace("{player}", p.getName());
    }

    public boolean isEnabled() {
        return FirstLogin.config.getBoolean("welcomeGui.enabled", false);
    }

    public void openFor(Player player) {
        if (!isEnabled()) return;
        int rows = Math.max(1, Math.min(6, FirstLogin.config.getInt("welcomeGui.rows", 3)));
        String title = FirstLogin.config.getString("welcomeGui.title", "Welcome");
        // Build Adventure->legacy converted title using placeholders
        int totalForTitle = plugin.playersToDate();
        String legacyTitle = plugin.toLegacyString(title, player, totalForTitle);

        Inventory inv = Bukkit.createInventory(new WelcomeHolder(false), rows * 9, legacyTitle);
        Map<Integer, GuiAction> actions = new HashMap<>();

        ConfigurationSection items = FirstLogin.config.getConfigurationSection("welcomeGui.items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) continue;
                int slot = sec.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) continue;
                String materialName = sec.getString("material", "PAPER");
                Material mat = Material.matchMaterial(materialName == null ? "PAPER" : materialName.toUpperCase(Locale.ROOT));
                if (mat == null) mat = Material.PAPER;

                String name = sec.getString("name", key);
                List<String> lore = sec.getStringList("lore");
                String requires = sec.getString("requires", null);
                String action = sec.getString("action", null);
                boolean closeOnClick = sec.getBoolean("closeOnClick", false);
                int cooldownSeconds = sec.getInt("cooldownSeconds", 0);
                boolean once = sec.getBoolean("once", false);
                // click sound
                String sName = null; float sVol = 1.0f; float sPitch = 1.0f;
                ConfigurationSection snd = sec.getConfigurationSection("clickSound");
                if (snd != null) {
                    sName = snd.getString("name", null);
                    sVol = (float) snd.getDouble("volume", 1.0);
                    sPitch = (float) snd.getDouble("pitch", 1.0);
                }

                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    final int total = plugin.playersToDate();
                    meta.setDisplayName(plugin.toLegacyString(name, player, total));
                    if (lore != null && !lore.isEmpty()) {
                        List<String> lines = lore.stream()
                                .map(s -> plugin.toLegacyString(s, player, total))
                                .collect(Collectors.toList());
                        meta.setLore(lines);
                    }
                    item.setItemMeta(meta);
                }
                inv.setItem(slot, item);
                actions.put(slot, new GuiAction(key, requires, action, closeOnClick, cooldownSeconds, once, sName, sVol, sPitch));
            }
        }

        openActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
        String opened = pluginMsg(player, "messages.gui.opened");
        if (!opened.isEmpty()) sendTo(player, opened);
    }

    // Open a focused Rules view: show rules inside the GUI (title + lore) with a Back button
    private void openRulesFor(Player player, String path) {
        if (!isEnabled()) return;
        int rows = Math.max(1, Math.min(6, FirstLogin.config.getInt("welcomeGui.rows", 3)));
        String title = FirstLogin.config.getString("welcomeGui.title", "Welcome");
        int totalForTitle = plugin.playersToDate();
        String legacyTitle = plugin.toLegacyString(title, player, totalForTitle);

        Inventory inv = Bukkit.createInventory(new WelcomeHolder(true), rows * 9, legacyTitle);
        Map<Integer, GuiAction> actions = new HashMap<>();

        // Fetch rules lines
        List<String> lines = pluginList(player, path);
        String name = "&6Server Rules";
        List<String> lore = Collections.emptyList();
        if (!lines.isEmpty()) {
            String first = lines.get(0);
            name = first;
            lore = lines.stream().skip(1).collect(Collectors.toList());
        }

        // Center slot item for rules
        int center = (rows * 9) / 2;
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            // Use Adventure -> legacy conversion so MiniMessage tags in messages.yml render correctly
            final int total = plugin.playersToDate();
            meta.setDisplayName(plugin.toLegacyString(name, player, total));
            if (!lore.isEmpty()) {
                List<String> loreLegacy = lore.stream().map(s -> plugin.toLegacyString(s, player, total)).collect(Collectors.toList());
                meta.setLore(loreLegacy);
            }
            book.setItemMeta(meta);
        }
        inv.setItem(center, book);

        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bm = back.getItemMeta();
        if (bm != null) {
            bm.setDisplayName(FirstLogin.colorizeWithHex("&cBack"));
            back.setItemMeta(bm);
        }
        int backSlot = center + 9 < rows * 9 ? center + 9 : (rows * 9) - 1;
        inv.setItem(backSlot, back);
        actions.put(backSlot, new GuiAction("back_btn", null, "back", false, 0, false, null, 1.0f, 1.0f));

        openActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
    }

    private String pluginMsg(Player p, String path) {
        return plugin.msgFor(p, path);
    }

    // Public entry points for commands
    public void acceptRules(Player player) {
        setFlag(player.getUniqueId(), versionedFlagName("rules"), true);
        String ok = pluginMsg(player, "messages.gui.accepted");
        if (!ok.isEmpty()) sendTo(player, ok);
        runRulesAcceptedCommands(player);
    }

    public void triggerItem(Player player, String key) {
        ConfigurationSection sec = FirstLogin.config.getConfigurationSection("welcomeGui.items." + key);
        if (sec == null) return;
        String requires = sec.getString("requires", null);
        String action = sec.getString("action", null);
        boolean closeOnClick = sec.getBoolean("closeOnClick", false);
        int cooldownSeconds = sec.getInt("cooldownSeconds", 0);
        boolean once = sec.getBoolean("once", false);
        String sName = null; float sVol = 1.0f; float sPitch = 1.0f;
        ConfigurationSection snd = sec.getConfigurationSection("clickSound");
        if (snd != null) {
            sName = snd.getString("name", null);
            sVol = (float) snd.getDouble("volume", 1.0);
            sPitch = (float) snd.getDouble("pitch", 1.0);
        }
        GuiAction a = new GuiAction(key, requires, action, closeOnClick, cooldownSeconds, once, sName, sVol, sPitch);
        execute(player, a);
    }

    // Enforce cancellation in case other plugins un-cancel later
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClickMonitor(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Map<Integer, GuiAction> actions = openActions.get(player.getUniqueId());
        if (actions == null) return; // our GUI not open for this player

        // Block all interactions while our GUI is open
        event.setCancelled(true);

        // Only process clicks that target the top inventory's slots
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        GuiAction a = actions.get(rawSlot);
        if (a == null) return;
        execute(player, a);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        // Cancel all drags when our GUI is open
        event.setCancelled(true);
    }

    // Enforce drag cancellation at MONITOR as well
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDragMonitor(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        final Player p = (Player) event.getPlayer();
        final UUID uuid = p.getUniqueId();
        if (!openActions.containsKey(uuid)) return;

        final boolean block = FirstLogin.config.getBoolean("welcomeGui.blockCloseUntilAccepted", false);
        // Defer cleanup one tick to let inventory transitions (our GUI -> our GUI) settle
        Bukkit.getScheduler().runTask(plugin, () -> {
            // If player still has our GUI open, keep tracking; do nothing
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top != null && top.getHolder() instanceof WelcomeHolder) return;

            // No longer viewing our GUI: clear actions
            openActions.remove(uuid);

            // Optionally reopen if rules must be accepted
            if (block && !getFlag(uuid, "rules")) {
                openFor(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        if (openActions.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Block creative mode inventory actions as well
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    // Prevent swapping items between hands while GUI is open
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (openActions.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void execute(Player player, GuiAction a) {
        // Requirement
        if (a.requires != null && !a.requires.isEmpty()) {
            if (a.requires.startsWith("flag:")) {
                String flag = a.requires.substring("flag:".length());
                if (!getFlag(player.getUniqueId(), flag)) {
                    String need = pluginMsg(player, "messages.gui.needAccept");
                    if (!need.isEmpty()) sendTo(player, need);
                    playDeny(player);
                    return;
                }
            }
        }

        // Cooldown / once
        if (a.once && getFlag(player.getUniqueId(), a.key)) {
            String msg = pluginMsg(player, "messages.gui.alreadyClaimed");
            if (!msg.isEmpty()) sendTo(player, msg);
            playDeny(player);
            return;
        }
        if (a.cooldownSeconds > 0) {
            long rem = cooldownRemaining(player.getUniqueId(), a.key, a.cooldownSeconds);
            if (rem > 0) {
                String msg = pluginMsg(player, "messages.gui.onCooldown");
                if (!msg.isEmpty()) sendTo(player, msg.replace("{time}", formatDuration(rem)));
                playDeny(player);
                return;
            }
        }

        // Execute
        if (a.action != null) {
            if (a.action.startsWith("flag:set:")) {
                String flag = a.action.substring("flag:set:".length());
                setFlag(player.getUniqueId(), flag, true);
                if (flag.equalsIgnoreCase("rules")) runRulesAcceptedCommands(player);
                String ok = pluginMsg(player, "messages.gui.accepted");
                if (!ok.isEmpty()) sendTo(player, ok);
                openFor(player);
            } else if (a.action.startsWith("command:")) {
                String cmd = a.action.substring("command:".length());
                cmd = cmd.replace("{player}", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else if (a.action.startsWith("message:")) {
                String path = a.action.substring("message:".length());
                if (path.equalsIgnoreCase("messages.rules") || path.toLowerCase(Locale.ROOT).endsWith(".rules")) {
                    openRulesFor(player, path);
                    return;
                }
                // Default behavior: send messages via Adventure
                List<String> lines = pluginList(player, path);
                if (!lines.isEmpty()) {
                    for (String line : lines) sendTo(player, withPlaceholders(player, line));
                }
            } else if (a.action.equals("back")) {
                openFor(player);
                return;
            } else if (a.action.startsWith("url:")) {
                String url = a.action.substring("url:".length());
                // Prefer clickable link via Adventure if available
                try {
                    plugin.sendClickableLink(player, FirstLogin.colorizeWithHex("&bDiscord"), url);
                } catch (Throwable t) {
                    sendTo(player, "&bLink: &f" + url);
                }
                player.closeInventory();
            }
        }

        // Post-effects
        if (a.cooldownSeconds > 0) setCooldownNow(player.getUniqueId(), a.key);
        if (a.once) setFlag(player.getUniqueId(), a.key, true);
        if (a.clickSoundName != null) playClick(player, a.clickSoundName, a.clickSoundVolume, a.clickSoundPitch);
        if (a.closeOnClick) player.closeInventory();
    }

    private void runRulesAcceptedCommands(Player player) {
        List<String> cmds = FirstLogin.config.getStringList("welcomeGui.onRulesAccepted.commands");
        if (cmds == null || cmds.isEmpty()) return;
        for (String c : cmds) {
            String cmd = c.replace("{player}", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    private void playClick(Player p, String name, float vol, float pitch) {
        try {
            Sound s = Sound.valueOf(name);
            p.playSound(p.getLocation(), s, vol, pitch);
        } catch (IllegalArgumentException ignored) {}
    }

    private long cooldownRemaining(UUID uuid, String key, int cooldownSec) {
        long last = FirstLogin.players.getLong("cooldowns." + uuid + "." + key, 0L);
        long now = System.currentTimeMillis();
        long remMs = (last + cooldownSec * 1000L) - now;
        return Math.max(0, remMs / 1000L);
    }

    private void setCooldownNow(UUID uuid, String key) {
        FirstLogin.players.set("cooldowns." + uuid + "." + key, System.currentTimeMillis());
        persist();
    }

    private String formatDuration(long seconds) {
        long h = seconds / 3600; seconds %= 3600;
        long m = seconds / 60; long s = seconds % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private List<String> pluginList(Player p, String path) {
        return plugin.msgListFor(p, path);
    }

    private void sendTo(Player player, String text) {
        if (text == null || text.isEmpty()) return;
        int total = plugin.playersToDate();
        // Send via Adventure/MiniMessage path in FirstLogin
        plugin.sendMsg(player, text, player, total);
    }

    private boolean getFlag(UUID uuid, String flag) {
        String key = "flags." + uuid + "." + versionedFlagName(flag);
        return FirstLogin.players.getBoolean(key, false);
    }

    private void setFlag(UUID uuid, String flag, boolean value) {
        String key = "flags." + uuid + "." + versionedFlagName(flag);
        FirstLogin.players.set(key, value);
        persist();
    }

    private String versionedFlagName(String flag) {
        if (!"rules".equalsIgnoreCase(flag)) return flag;
        int ver = FirstLogin.config.getInt("welcomeGui.rulesVersion", 1);
        return "rules_v" + ver;
    }

    private void persist() {
        plugin.savePlayers();
    }

    private static class WelcomeHolder implements InventoryHolder {
        @SuppressWarnings("unused")
        private final boolean rulesView;
        private WelcomeHolder(boolean rulesView) {
            this.rulesView = rulesView;
        }
        @Override
        public Inventory getInventory() {
            return null; // not used, only for identification
        }
    }

    private static class GuiAction {
        final String key;
        final String requires;
        final String action;
        final boolean closeOnClick;
        final int cooldownSeconds;
        final boolean once;
        final String clickSoundName;
        final float clickSoundVolume;
        final float clickSoundPitch;
        GuiAction(String key, String requires, String action, boolean closeOnClick, int cooldownSeconds, boolean once,
                  String clickSoundName, float clickSoundVolume, float clickSoundPitch) {
            this.key = key;
            this.requires = requires;
            this.action = action;
            this.closeOnClick = closeOnClick;
            this.cooldownSeconds = cooldownSeconds;
            this.once = once;
            this.clickSoundName = clickSoundName;
            this.clickSoundVolume = clickSoundVolume;
            this.clickSoundPitch = clickSoundPitch;
        }
    }
}
