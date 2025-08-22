package firstlogin.gui;

import firstlogin.FirstLogin;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;

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
        if (text == null) return "";
        try {
            java.lang.reflect.Method count = FirstLogin.class.getDeclaredMethod("countPlayersToDate");
            count.setAccessible(true);
            Object totalObj = count.invoke(plugin);
            int total = (totalObj instanceof Integer) ? (Integer) totalObj : 0;
            java.lang.reflect.Method m = FirstLogin.class.getDeclaredMethod("applyAllPlaceholders", String.class, Player.class, int.class);
            m.setAccessible(true);
            Object out = m.invoke(plugin, text, p, total);
            if (out instanceof String) return (String) out;
        } catch (Throwable ignored) {}
        // Fallback minimal builtin
        return text.replace("{player}", p.getName());
    }

    public boolean isEnabled() {
        return FirstLogin.config.getBoolean("welcomeGui.enabled", false);
    }

    public void openFor(Player player) {
        if (!isEnabled()) return;
        int rows = Math.max(1, Math.min(6, FirstLogin.config.getInt("welcomeGui.rows", 3)));
        String title = FirstLogin.config.getString("welcomeGui.title", "Welcome");
        // Use legacy for inventory title for broad compatibility
        String legacyTitle = FirstLogin.colorizeWithHex(title);

        Inventory inv = Bukkit.createInventory(new WelcomeHolder(), rows * 9, legacyTitle);
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
                    String dn = withPlaceholders(player, name);
                    meta.setDisplayName(FirstLogin.colorizeWithHex(dn));
                    if (lore != null && !lore.isEmpty()) {
                        List<String> lines = lore.stream()
                                .map(s -> FirstLogin.colorizeWithHex(withPlaceholders(player, s)))
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
        if (!opened.isEmpty()) player.sendMessage(FirstLogin.colorizeWithHex(opened));
    }

    private String pluginMsg(Player p, String path) {
        try {
            java.lang.reflect.Method m = FirstLogin.class.getDeclaredMethod("msgFor", org.bukkit.entity.Player.class, String.class);
            m.setAccessible(true);
            Object out = m.invoke(plugin, p, path);
            if (out instanceof String) return (String) out;
        } catch (Throwable ignored) { }
        return "";
    }

    // Public entry points for commands
    public void acceptRules(Player player) {
        setFlag(player.getUniqueId(), versionedFlagName("rules"), true);
        String ok = pluginMsg(player, "messages.gui.accepted");
        if (!ok.isEmpty()) player.sendMessage(FirstLogin.colorizeWithHex(ok));
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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof WelcomeHolder)) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        Map<Integer, GuiAction> actions = openActions.getOrDefault(player.getUniqueId(), Collections.emptyMap());
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return; // only top inv slots

        GuiAction a = actions.get(rawSlot);
        if (a == null) return;
        execute(player, a);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof WelcomeHolder)) return;
        boolean block = FirstLogin.config.getBoolean("welcomeGui.blockCloseUntilAccepted", false);
        if (!block) return;
        Player p = (Player) event.getPlayer();
        if (!getFlag(p.getUniqueId(), "rules")) {
            // Reopen next tick
            Bukkit.getScheduler().runTask(plugin, () -> openFor(p));
        }
    }

    private void execute(Player player, GuiAction a) {
        // Requirement
        if (a.requires != null && !a.requires.isEmpty()) {
            if (a.requires.startsWith("flag:")) {
                String flag = a.requires.substring("flag:".length());
                if (!getFlag(player.getUniqueId(), flag)) {
                    String need = pluginMsg(player, "messages.gui.needAccept");
                    if (!need.isEmpty()) player.sendMessage(FirstLogin.colorizeWithHex(need));
                    playDeny(player);
                    return;
                }
            }
        }

        // Cooldown / once
        if (a.once && getFlag(player.getUniqueId(), a.key)) {
            String msg = pluginMsg(player, "messages.gui.alreadyClaimed");
            if (!msg.isEmpty()) player.sendMessage(FirstLogin.colorizeWithHex(msg));
            playDeny(player);
            return;
        }
        if (a.cooldownSeconds > 0) {
            long rem = cooldownRemaining(player.getUniqueId(), a.key, a.cooldownSeconds);
            if (rem > 0) {
                String msg = pluginMsg(player, "messages.gui.onCooldown");
                if (!msg.isEmpty()) player.sendMessage(FirstLogin.colorizeWithHex(msg.replace("{time}", formatDuration(rem))));
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
                if (!ok.isEmpty()) player.sendMessage(FirstLogin.colorizeWithHex(ok));
                openFor(player);
            } else if (a.action.startsWith("command:")) {
                String cmd = a.action.substring("command:".length());
                cmd = cmd.replace("{player}", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else if (a.action.startsWith("message:")) {
                String path = a.action.substring("message:".length());
                List<String> lines = pluginList(player, path);
                if (!lines.isEmpty()) {
                    for (String line : lines) player.sendMessage(FirstLogin.colorizeWithHex(withPlaceholders(player, line)));
                }
            } else if (a.action.startsWith("url:")) {
                String url = a.action.substring("url:".length());
                // Prefer clickable link via Adventure if available
                try {
                    java.lang.reflect.Method m = FirstLogin.class.getDeclaredMethod("sendClickableLink", Player.class, String.class, String.class);
                    m.setAccessible(true);
                    m.invoke(plugin, player, FirstLogin.colorizeWithHex("&bDiscord"), url);
                } catch (Throwable t) {
                    player.sendMessage(FirstLogin.colorizeWithHex("&bLink: &f" + url));
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
        try {
            java.lang.reflect.Method m = FirstLogin.class.getDeclaredMethod("msgListFor", org.bukkit.entity.Player.class, String.class);
            m.setAccessible(true);
            Object out = m.invoke(plugin, p, path);
            if (out instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) out;
                return list;
            }
        } catch (Throwable ignored) { }
        return Collections.emptyList();
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
        try {
            java.lang.reflect.Method m = FirstLogin.class.getDeclaredMethod("savePlayers");
            m.setAccessible(true);
            m.invoke(plugin);
        } catch (Throwable ignored) { }
    }

    private static class WelcomeHolder implements InventoryHolder {
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
