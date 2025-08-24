package firstlogin.gui;

import firstlogin.FirstLogin;
import firstlogin.event.GuiActionEvent;
import firstlogin.event.RulesAcceptedEvent;
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

    private void debugGui(Player p, String msg) {
        if (FirstLogin.config.getBoolean("debug.gui", false)) {
            plugin.getLogger().info("[GUI][" + p.getName() + "] " + msg);
        }
    }

    private void debugInv(Player p, String msg) {
        if (FirstLogin.config.getBoolean("debug.inventory", false)) {
            plugin.getLogger().info("[INV][" + p.getName() + "] " + msg);
        }
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

    public boolean isEnabled() {
        return FirstLogin.config.getBoolean("welcomeGui.enabled", false);
    }

    public void openFor(Player player) {
        openFor(player, 1);
    }

    public void openFor(Player player, int page) {
        if (!isEnabled()) return;
        int rows = Math.max(1, Math.min(6, FirstLogin.config.getInt("welcomeGui.rows", 3)));
        String title = FirstLogin.config.getString("welcomeGui.title", "Welcome");
        // Build Adventure->legacy converted title using placeholders
        int totalForTitle = plugin.playersToDate();
        String legacyTitle = plugin.toLegacyString(title, player, totalForTitle);

        Inventory inv = Bukkit.createInventory(new WelcomeHolder(false, page), rows * 9, legacyTitle);
        Map<Integer, GuiAction> actions = new HashMap<>();

        ConfigurationSection items = FirstLogin.config.getConfigurationSection("welcomeGui.items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null) continue;
                int itemPage = sec.getInt("page", 1);
                if (itemPage != page) continue;
                int slot = sec.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) continue;
                String materialName = sec.getString("material", "PAPER");
                Material mat = Material.matchMaterial(materialName == null ? "PAPER" : materialName.toUpperCase(Locale.ROOT));
                if (mat == null) mat = Material.PAPER;

                // Locale-aware name/lore (name_<locale>, lore_<locale>) with fallback
                String name = resolveLocaleString(player, sec, "name", key);
                List<String> lore = resolveLocaleList(player, sec, "lore");
                String requires = sec.getString("requires", null);
                // Composite requirements
                List<String> requiresAll = sec.getStringList("requiresAll");
                List<String> requiresAny = sec.getStringList("requiresAny");
                // Single or batched actions
                List<String> actionList = sec.getStringList("actions");
                String actionSingle = sec.getString("action", null);
                if ((actionList == null || actionList.isEmpty()) && actionSingle != null) actionList = java.util.Arrays.asList(actionSingle);
                int delayTicks = sec.getInt("delayTicks", 0);
                boolean closeOnClick = sec.getBoolean("closeOnClick", false);
                int cooldownSeconds = sec.getInt("cooldownSeconds", 0);
                boolean once = sec.getBoolean("once", false);
                // Permission gating
                String permission = sec.getString("permission", null);
                boolean hideIfNoPerm = sec.getBoolean("hideIfNoPermission", false);
                String cooldownBypass = sec.getString("cooldownBypassPermission", null);
                String runAs = sec.getString("runAs", "console").toLowerCase(Locale.ROOT); // console|player|op
                String urlLabel = sec.getString("urlLabel", "&bLink");
                // click sound
                String sName = null; float sVol = 1.0f; float sPitch = 1.0f;
                ConfigurationSection snd = sec.getConfigurationSection("clickSound");
                if (snd != null) {
                    sName = snd.getString("name", null);
                    sVol = (float) snd.getDouble("volume", 1.0);
                    sPitch = (float) snd.getDouble("pitch", 1.0);
                }

                // Optionally hide item if player lacks permission
                if (permission != null && !permission.isEmpty() && !player.hasPermission(permission) && hideIfNoPerm) {
                    continue;
                }

                // Stateful variant swap
                String whenFlag = sec.getString("whenFlag", null);
                if (whenFlag != null && !whenFlag.isEmpty() && getFlag(player.getUniqueId(), whenFlag)) {
                    ConfigurationSection variant = sec.getConfigurationSection("variant");
                    if (variant != null) {
                        String vMatName = variant.getString("material", materialName);
                        Material vMat = Material.matchMaterial(vMatName == null ? materialName : vMatName.toUpperCase(Locale.ROOT));
                        if (vMat != null) mat = vMat;
                        String vName = resolveLocaleString(player, variant, "name", name);
                        if (vName != null) name = vName;
                        List<String> vLore = resolveLocaleList(player, variant, "lore");
                        if (vLore != null && !vLore.isEmpty()) lore = vLore;
                    }
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
                actions.put(slot, new GuiAction(key, requires, requiresAll, requiresAny, actionList, delayTicks, closeOnClick, cooldownSeconds, once, sName, sVol, sPitch, permission, cooldownBypass, runAs, urlLabel));
            }

            // Filler for empty slots (optional)
            ConfigurationSection fill = FirstLogin.config.getConfigurationSection("welcomeGui.filler");
            if (fill != null && fill.getBoolean("enabled", false)) {
                String m = fill.getString("material", "GRAY_STAINED_GLASS_PANE");
                Material fm = Material.matchMaterial(m == null ? "GRAY_STAINED_GLASS_PANE" : m.toUpperCase(Locale.ROOT));
                if (fm == null) fm = Material.GRAY_STAINED_GLASS_PANE;
                String n = fill.getString("name", "&r");
                List<String> ll = fill.getStringList("lore");
                ItemStack fi = new ItemStack(fm);
                ItemMeta im = fi.getItemMeta();
                if (im != null) {
                    final int total = plugin.playersToDate();
                    im.setDisplayName(plugin.toLegacyString(n, player, total));
                    if (ll != null && !ll.isEmpty()) im.setLore(ll.stream().map(s -> plugin.toLegacyString(s, player, total)).collect(Collectors.toList()));
                    fi.setItemMeta(im);
                }
                for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, fi);
            }

            // Pagination controls
            ConfigurationSection pg = FirstLogin.config.getConfigurationSection("welcomeGui.pagination");
            if (pg != null && pg.getBoolean("enabled", false)) {
                int maxPage = 1;
                for (String k : items.getKeys(false)) {
                    ConfigurationSection s = items.getConfigurationSection(k);
                    if (s != null) maxPage = Math.max(maxPage, s.getInt("page", 1));
                }
                if (page > 1) {
                    int prevSlot = pg.getInt("prevSlot", (rows * 9) - 7);
                    ItemStack prev = new ItemStack(Material.ARROW);
                    ItemMeta pm = prev.getItemMeta();
                    if (pm != null) { pm.setDisplayName(FirstLogin.colorizeWithHex("&ePrevious")); prev.setItemMeta(pm); }
                    inv.setItem(prevSlot, prev);
                    actions.put(prevSlot, GuiAction.pageNav("prev", page - 1));
                }
                if (page < maxPage) {
                    int nextSlot = pg.getInt("nextSlot", (rows * 9) - 3);
                    ItemStack next = new ItemStack(Material.ARROW);
                    ItemMeta nm = next.getItemMeta();
                    if (nm != null) { nm.setDisplayName(FirstLogin.colorizeWithHex("&eNext")); next.setItemMeta(nm); }
                    inv.setItem(nextSlot, next);
                    actions.put(nextSlot, GuiAction.pageNav("next", page + 1));
                }
            }
        }

        openActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
        // Telemetry: record GUI open
        try { plugin.recordGuiOpen(); } catch (Throwable ignored) {}
        String opened = pluginMsg(player, "messages.gui.opened");
        if (!opened.isEmpty()) sendTo(player, opened);
        debugGui(player, "Opened Welcome GUI (page=" + page + ", rows=" + rows + ", actions=" + actions.size() + ")");
    }

    // Open a focused Rules view: show rules inside the GUI (title + lore) with a Back button
    private void openRulesFor(Player player, String path) {
        if (!isEnabled()) return;
        int rows = Math.max(1, Math.min(6, FirstLogin.config.getInt("welcomeGui.rows", 3)));
        String title = FirstLogin.config.getString("welcomeGui.title", "Welcome");
        int totalForTitle = plugin.playersToDate();
        String legacyTitle = plugin.toLegacyString(title, player, totalForTitle);

        Inventory inv = Bukkit.createInventory(new WelcomeHolder(true, 1), rows * 9, legacyTitle);
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
        actions.put(backSlot, new GuiAction("back_btn", null, null, null, java.util.Collections.singletonList("back"), 0, false, 0, false, null, 1.0f, 1.0f, null, null, "console", "&cBack"));

        openActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
        // Telemetry: record GUI open
        try { plugin.recordGuiOpen(); } catch (Throwable ignored) {}
    }

    // Open a simple confirm/cancel dialog for accepting rules
    private void openConfirmAccept(Player player) {
        if (!isEnabled()) return;
        int rows = Math.max(1, Math.min(6, FirstLogin.config.getInt("welcomeGui.rows", 3)));
        String title = FirstLogin.config.getString("welcomeGui.title", "Welcome");
        int totalForTitle = plugin.playersToDate();
        String legacyTitle = plugin.toLegacyString(title, player, totalForTitle);

        int returnPage = currentPageOf(player);
        Inventory inv = Bukkit.createInventory(new WelcomeHolder(false, returnPage), rows * 9, legacyTitle);
        Map<Integer, GuiAction> actions = new HashMap<>();

        int center = (rows * 9) / 2;

        // Configurable confirm dialog sections
        org.bukkit.configuration.ConfigurationSection cd = FirstLogin.config.getConfigurationSection("welcomeGui.confirmDialog");
        org.bukkit.configuration.ConfigurationSection yesSec = cd != null ? cd.getConfigurationSection("yes") : null;
        org.bukkit.configuration.ConfigurationSection noSec = cd != null ? cd.getConfigurationSection("no") : null;
        org.bukkit.configuration.ConfigurationSection laterSec = cd != null ? cd.getConfigurationSection("later") : null;

        // ===== YES (Confirm) =====
        String yesMatName = yesSec != null ? yesSec.getString("material", "LIME_WOOL") : "LIME_WOOL";
        Material yesMat = Material.matchMaterial(yesMatName == null ? "LIME_WOOL" : yesMatName.toUpperCase(java.util.Locale.ROOT));
        if (yesMat == null) yesMat = Material.LIME_WOOL;
        String yesName = yesSec != null ? resolveLocaleString(player, yesSec, "name", "&aConfirm") : "&aConfirm";
        java.util.List<String> yesLore = yesSec != null ? resolveLocaleList(player, yesSec, "lore") : java.util.Collections.emptyList();
        String ySName = null; float ySVol = 1.0f; float ySPitch = 1.0f;
        org.bukkit.configuration.ConfigurationSection ySnd = yesSec != null ? yesSec.getConfigurationSection("clickSound") : null;
        if (ySnd != null) {
            ySName = ySnd.getString("name", null);
            ySVol = (float) ySnd.getDouble("volume", 1.0);
            ySPitch = (float) ySnd.getDouble("pitch", 1.0);
        }
        ItemStack yesItem = new ItemStack(yesMat);
        ItemMeta yim = yesItem.getItemMeta();
        if (yim != null) {
            yim.setDisplayName(plugin.toLegacyString(yesName, player, totalForTitle));
            if (yesLore != null && !yesLore.isEmpty()) {
                yim.setLore(yesLore.stream().map(s -> plugin.toLegacyString(s, player, totalForTitle)).collect(java.util.stream.Collectors.toList()));
            }
            yesItem.setItemMeta(yim);
        }
        inv.setItem(center - 1, yesItem);
        actions.put(center - 1, new GuiAction(
                "confirm_accept", null, null, null,
                java.util.Collections.singletonList("acceptRulesNow"),
                0, false, 0, false,
                ySName, ySVol, ySPitch, null, null, "console", yesName
        ));

        // ===== LATER (Remind me later) =====
        boolean laterEnabled = laterSec == null || laterSec.getBoolean("enabled", true);
        if (laterEnabled) {
            String laterMatName = laterSec != null ? laterSec.getString("material", "YELLOW_WOOL") : "YELLOW_WOOL";
            Material laterMat = Material.matchMaterial(laterMatName == null ? "YELLOW_WOOL" : laterMatName.toUpperCase(java.util.Locale.ROOT));
            if (laterMat == null) laterMat = Material.YELLOW_WOOL;
            String laterName = laterSec != null ? resolveLocaleString(player, laterSec, "name", "&eRemind me later") : "&eRemind me later";
            java.util.List<String> laterLore = laterSec != null ? resolveLocaleList(player, laterSec, "lore") : java.util.Collections.emptyList();
            int laterCd = laterSec != null ? laterSec.getInt("cooldownSeconds", 60) : 60;
            String lSName = null; float lSVol = 1.0f; float lSPitch = 1.0f;
            org.bukkit.configuration.ConfigurationSection lSnd = laterSec != null ? laterSec.getConfigurationSection("clickSound") : null;
            if (lSnd != null) {
                lSName = lSnd.getString("name", null);
                lSVol = (float) lSnd.getDouble("volume", 1.0);
                lSPitch = (float) lSnd.getDouble("pitch", 1.0);
            }
            ItemStack laterItem = new ItemStack(laterMat);
            ItemMeta lim = laterItem.getItemMeta();
            if (lim != null) {
                lim.setDisplayName(plugin.toLegacyString(laterName, player, totalForTitle));
                if (laterLore != null && !laterLore.isEmpty()) {
                    lim.setLore(laterLore.stream().map(s -> plugin.toLegacyString(s, player, totalForTitle)).collect(java.util.stream.Collectors.toList()));
                }
                laterItem.setItemMeta(lim);
            }
            inv.setItem(center, laterItem);
            actions.put(center, new GuiAction(
                    "confirm_later", null, null, null,
                    java.util.Collections.emptyList(),
                    0, true, Math.max(0, laterCd), false,
                    lSName, lSVol, lSPitch, null, null, "console", laterName
            ));
        }

        // ===== NO (Cancel) =====
        String noMatName = noSec != null ? noSec.getString("material", "RED_WOOL") : "RED_WOOL";
        Material noMat = Material.matchMaterial(noMatName == null ? "RED_WOOL" : noMatName.toUpperCase(java.util.Locale.ROOT));
        if (noMat == null) noMat = Material.RED_WOOL;
        String noName = noSec != null ? resolveLocaleString(player, noSec, "name", "&cCancel") : "&cCancel";
        java.util.List<String> noLore = noSec != null ? resolveLocaleList(player, noSec, "lore") : java.util.Collections.emptyList();
        String nSName = null; float nSVol = 1.0f; float nSPitch = 1.0f;
        org.bukkit.configuration.ConfigurationSection nSnd = noSec != null ? noSec.getConfigurationSection("clickSound") : null;
        if (nSnd != null) {
            nSName = nSnd.getString("name", null);
            nSVol = (float) nSnd.getDouble("volume", 1.0);
            nSPitch = (float) nSnd.getDouble("pitch", 1.0);
        }
        ItemStack noItem = new ItemStack(noMat);
        ItemMeta nim = noItem.getItemMeta();
        if (nim != null) {
            nim.setDisplayName(plugin.toLegacyString(noName, player, totalForTitle));
            if (noLore != null && !noLore.isEmpty()) {
                nim.setLore(noLore.stream().map(s -> plugin.toLegacyString(s, player, totalForTitle)).collect(java.util.stream.Collectors.toList()));
            }
            noItem.setItemMeta(nim);
        }
        inv.setItem(center + 1, noItem);
        actions.put(center + 1, new GuiAction(
                "cancel_accept", null, null, null,
                java.util.Collections.singletonList("page:" + Math.max(1, returnPage)),
                0, false, 0, false,
                nSName, nSVol, nSPitch, null, null, "console", noName
        ));

        openActions.put(player.getUniqueId(), actions);
        player.openInventory(inv);
        // Telemetry: record GUI open
        try { plugin.recordGuiOpen(); } catch (Throwable ignored) {}
    }

    private String pluginMsg(Player p, String path) {
        return plugin.msgFor(p, path);
    }

    // Public entry points for commands
    public void acceptRules(Player player) {
        setFlag(player.getUniqueId(), versionedFlagName("rules"), true);
        // Store rules acceptance timestamp
        try {
            java.util.UUID u = player.getUniqueId();
            firstlogin.FirstLogin.players.set("timestamps." + u + ".rules_accepted", System.currentTimeMillis());
            persist();
        } catch (Throwable ignored) {}
        // Telemetry: record rules acceptance
        try { plugin.recordRulesAccepted(); } catch (Throwable ignored) {}
        String ok = pluginMsg(player, "messages.gui.accepted");
        if (!ok.isEmpty()) sendTo(player, ok);
        runRulesAcceptedCommands(player);
        // Fire event
        try { org.bukkit.Bukkit.getPluginManager().callEvent(new RulesAcceptedEvent(player)); } catch (Throwable ignored) {}
    }

    public void triggerItem(Player player, String key) {
        ConfigurationSection sec = FirstLogin.config.getConfigurationSection("welcomeGui.items." + key);
        if (sec == null) return;
        String requires = sec.getString("requires", null);
        boolean closeOnClick = sec.getBoolean("closeOnClick", false);
        int cooldownSeconds = sec.getInt("cooldownSeconds", 0);
        boolean once = sec.getBoolean("once", false);
        String permission = sec.getString("permission", null);
        String sName = null; float sVol = 1.0f; float sPitch = 1.0f;
        ConfigurationSection snd = sec.getConfigurationSection("clickSound");
        if (snd != null) {
            sName = snd.getString("name", null);
            sVol = (float) snd.getDouble("volume", 1.0);
            sPitch = (float) snd.getDouble("pitch", 1.0);
        }
        List<String> actions = sec.getStringList("actions");
        if (actions == null || actions.isEmpty()) {
            String single = sec.getString("action", null);
            actions = single == null ? java.util.Collections.emptyList() : java.util.Arrays.asList(single);
        }
        List<String> requiresAll = sec.getStringList("requiresAll");
        List<String> requiresAny = sec.getStringList("requiresAny");
        String cooldownBypass = sec.getString("cooldownBypassPermission", null);
        String runAs = sec.getString("runAs", "console");
        String urlLabel = sec.getString("urlLabel", "&bLink");
        int delayTicks = sec.getInt("delayTicks", 0);
        GuiAction a = new GuiAction(key, requires, requiresAll, requiresAny, actions, delayTicks, closeOnClick, cooldownSeconds, once, sName, sVol, sPitch, permission, cooldownBypass, runAs, urlLabel);
        execute(player, a);
    }

    // Enforce cancellation in case other plugins un-cancel later
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClickMonitor(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        debugInv(player, "Monitor enforced click cancel (rawSlot=" + event.getRawSlot() + ")");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Map<Integer, GuiAction> actions = openActions.get(player.getUniqueId());
        if (actions == null) return; // our GUI not open for this player

        // Block all interactions while our GUI is open
        event.setCancelled(true);
        debugInv(player, "Cancelled inventory click while GUI open");

        // Only process clicks that target the top inventory's slots
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        GuiAction a = actions.get(rawSlot);
        if (a == null) return;
        debugGui(player, "Executing action key='" + a.key + "' at slot=" + rawSlot);
        execute(player, a);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        // Cancel all drags when our GUI is open
        event.setCancelled(true);
        debugInv(player, "Cancelled inventory drag while GUI open");
    }

    // Enforce drag cancellation at MONITOR as well
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDragMonitor(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        debugInv(player, "Monitor enforced drag cancel");
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        final Player p = (Player) event.getPlayer();
        final UUID uuid = p.getUniqueId();
        if (!openActions.containsKey(uuid)) return;

        final boolean block = FirstLogin.config.getBoolean("welcomeGui.blockCloseUntilAccepted", false);
        // Defer cleanup one tick to allow transitions to a new Welcome GUI instance
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
            if (top != null && top.getHolder() instanceof WelcomeHolder) return; // still our GUI

            openActions.remove(uuid);
            debugGui(p, "Closed Welcome GUI");

            if (block && !getFlag(uuid, "rules")) {
                boolean allowBypass = FirstLogin.config.getBoolean("welcomeGui.bypassClosePermission", true);
                if (allowBypass && p.hasPermission("firstlogin.bypass.rules")) {
                    debugGui(p, "Bypassed forced reopen due to permission firstlogin.bypass.rules");
                    return;
                }

                // If player clicked "Remind me later", respect its cooldown to avoid immediate reopen
                int laterCd = 0;
                org.bukkit.configuration.ConfigurationSection laterSec = FirstLogin.config.getConfigurationSection("welcomeGui.confirmDialog.later");
                if (laterSec != null) laterCd = Math.max(0, laterSec.getInt("cooldownSeconds", 60));
                long rem = laterCd > 0 ? cooldownRemaining(uuid, "confirm_later", laterCd) : 0;
                if (rem > 0) {
                    debugGui(p, "Suppressing forced reopen due to 'Remind me later' cooldown (" + rem + "s remaining)");
                    return;
                }

                long reopenDelay = Math.max(1L, FirstLogin.config.getLong("welcomeGui.reopenDelayTicks", 1L));
                debugGui(p, "Reopening GUI due to blockCloseUntilAccepted=true and rules not accepted (delay=" + reopenDelay + "t)");
                Bukkit.getScheduler().runTaskLater(plugin, () -> openFor(p, 1), reopenDelay);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        debugInv(player, "Cancelled creative inventory interaction while GUI open");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        if (!openActions.containsKey(p.getUniqueId())) return;
        event.setCancelled(true);
        debugInv(p, "Cancelled item drop while GUI open");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!openActions.containsKey(p.getUniqueId())) return;
        event.setCancelled(true);
        debugInv(p, "Cancelled swap-hand while GUI open");
    }

    private boolean checkRequirement(Player player, String req) {
        if (req == null || req.isEmpty()) return true;
        boolean neg = req.startsWith("!");
        String r = neg ? req.substring(1) : req;
        boolean ok = checkRequirementNoMessage(player, r);
        if (neg) ok = !ok;
        if (!ok) {
            String need = pluginMsg(player, "messages.gui.needAccept");
            if (!need.isEmpty()) sendTo(player, need);
            playDeny(player);
            debugGui(player, "Blocked action due to requirement not met: '" + req + "'");
        }
        return ok;
    }

    private boolean checkRequirementsComposite(Player player, String single, List<String> all, List<String> any) {
        if (single != null && !checkRequirement(player, single)) return false;
        if (all != null && !all.isEmpty()) {
            for (String s : all) if (!checkRequirement(player, s)) return false;
        }
        if (any != null && !any.isEmpty()) {
            boolean anyOk = false;
            for (String s : any) if (checkRequirementNoMessage(player, s)) { anyOk = true; break; }
            if (!anyOk) {
                String need = pluginMsg(player, "messages.gui.needAccept");
                if (!need.isEmpty()) sendTo(player, need);
                playDeny(player);
                debugGui(player, "Blocked action due to 'requiresAny' not met");
                return false;
            }
        }
        return true;
    }

    private boolean isOnceClaimed(UUID uuid, String key) {
        return FirstLogin.players.getBoolean("once." + uuid + "." + key, false);
    }

    private void setOnceClaimed(UUID uuid, String key) {
        FirstLogin.players.set("once." + uuid + "." + key, true);
        persist();
    }

    private void runRulesAcceptedCommands(Player player) {
        List<String> cmds = FirstLogin.config.getStringList("welcomeGui.onRulesAccepted.commands");
        if (cmds == null || cmds.isEmpty()) return;
        String runAs = FirstLogin.config.getString("welcomeGui.onRulesAccepted.runAs", "console");
        for (String c : cmds) {
            String cmd = plugin.applyPlaceholders(c, player, plugin.playersToDate());
            dispatchCommand(runAs, player, cmd);
            debugGui(player, "Dispatched onRulesAccepted (" + runAs + ") command: /" + cmd);
        }
    }

    private void execute(Player player, GuiAction a) {
        // Per-item permission gate
        if (a.permission != null && !a.permission.isEmpty() && !player.hasPermission(a.permission)) {
            String np = pluginMsg(player, "messages.gui.noPermission");
            if (!np.isEmpty()) sendTo(player, np);
            playDeny(player);
            debugGui(player, "Blocked action '" + a.key + "' due to lack of permission '" + a.permission + "'");
            return;
        }
        // Requirements
        if (!checkRequirementsComposite(player, a.requires, a.requiresAll, a.requiresAny)) return;

        // Cooldown/once
        UUID uuid = player.getUniqueId();
        if (a.once) {
            if (isOnceClaimed(uuid, a.key)) {
                String on = pluginMsg(player, "messages.gui.alreadyClaimed");
                if (!on.isEmpty()) sendTo(player, on);
                playDeny(player);
                return;
            }
        }
        if (a.cooldownSeconds > 0) {
            if (a.cooldownBypassPermission != null && !a.cooldownBypassPermission.isEmpty() && player.hasPermission(a.cooldownBypassPermission)) {
                // bypass
            } else {
                long rem = cooldownRemaining(uuid, a.key, a.cooldownSeconds);
                if (rem > 0) {
                    String cd = pluginMsg(player, "messages.gui.cooldown");
                    if (!cd.isEmpty()) sendTo(player, cd.replace("{time}", formatDuration(rem)));
                    playDeny(player);
                    return;
                }
            }
        }

        // Click sound
        if (a.clickSoundName != null && !a.clickSoundName.isEmpty()) {
            playClick(player, a.clickSoundName, a.clickSoundVolume, a.clickSoundPitch);
        }

        Runnable runner = () -> {
            for (String act : a.actions == null ? java.util.Collections.<String>emptyList() : a.actions) {
                if (act == null) continue;
                String s = act.trim();
                try {
                    if (s.equalsIgnoreCase("back")) {
                        openFor(player, Math.max(1, currentPageOf(player) - 1));
                    } else if (s.startsWith("page:")) {
                        int to = Integer.parseInt(s.substring("page:".length()).trim());
                        openFor(player, Math.max(1, to));
                    } else if (s.startsWith("openRules:")) {
                        String path = s.substring("openRules:".length());
                        openRulesFor(player, path);
                    } else if (s.equalsIgnoreCase("acceptRules")) {
                        boolean confirm = FirstLogin.config.getBoolean("welcomeGui.confirmOnAccept", false);
                        if (confirm) {
                            openConfirmAccept(player);
                        } else {
                            acceptRules(player);
                            player.closeInventory();
                        }
                    } else if (s.equalsIgnoreCase("acceptRulesNow")) {
                        // Bypass confirm path used by confirm GUI
                        acceptRules(player);
                        player.closeInventory();
                    } else if (s.startsWith("flag:set:")) {
                        String flag = s.substring("flag:set:".length());
                        setFlag(uuid, flag, true);
                    } else if (s.startsWith("flag:clear:")) {
                        String flag = s.substring("flag:clear:".length());
                        setFlag(uuid, flag, false);
                    } else if (s.startsWith("command:")) {
                        String raw = s.substring("command:".length());
                        String cmd = plugin.applyPlaceholders(raw, player, plugin.playersToDate());
                        dispatchCommand(a.runAs, player, cmd);
                    } else if (s.startsWith("message:")) {
                        String path = s.substring("message:".length());
                        List<String> list = pluginList(player, path);
                        for (String m : list) sendTo(player, m);
                    } else if (s.startsWith("url:")) {
                        String url = s.substring("url:".length());
                        String label = a.urlLabel == null ? url : plugin.toLegacyString(a.urlLabel, player, plugin.playersToDate());
                        String msg = pluginMsg(player, "messages.gui.clickUrl");
                        if (!msg.isEmpty()) sendTo(player, msg.replace("{label}", label).replace("{url}", url));
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Error executing GUI action '" + s + "' for " + player.getName() + ": " + t.getMessage());
                }
            }

            // Telemetry: record item click for this action key
            try { plugin.recordItemClick(a.key); } catch (Throwable ignored) {}

            if (a.once) setOnceClaimed(uuid, a.key);
            if (a.cooldownSeconds > 0) setCooldownNow(uuid, a.key);
            if (a.closeOnClick) player.closeInventory();

            // Fire action event for extensibility
            try { org.bukkit.Bukkit.getPluginManager().callEvent(new GuiActionEvent(player, a.key)); } catch (Throwable ignored) {}
        };

        if (a.delayTicks > 0) Bukkit.getScheduler().runTaskLater(plugin, runner, a.delayTicks);
        else runner.run();
    }

    private boolean checkRequirementNoMessage(Player player, String req) {
        if (req == null || req.isEmpty()) return true;
        if (req.startsWith("flag:")) {
            String flag = req.substring("flag:".length());
            return getFlag(player.getUniqueId(), flag);
        } else if (req.startsWith("perm:")) {
            String perm = req.substring("perm:".length());
            return !perm.isEmpty() && player.hasPermission(perm);
        }
        return true;
    }

    private void dispatchCommand(String runAs, Player player, String cmd) {
        switch (runAs == null ? "console" : runAs.toLowerCase(Locale.ROOT)) {
            case "player":
                player.performCommand(cmd);
                debugGui(player, "Player ran command: /" + cmd);
                break;
            case "op":
                boolean wasOp = player.isOp();
                try {
                    player.setOp(true);
                    player.performCommand(cmd);
                    debugGui(player, "OP-as-player ran command: /" + cmd);
                } finally {
                    player.setOp(wasOp);
                }
                break;
            default:
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                debugGui(player, "Console ran command: /" + cmd);
        }
    }

    private String resolveLocaleString(Player p, ConfigurationSection sec, String baseKey, String def) {
        String tag = FirstLogin.players.getString("locale." + p.getUniqueId(), null);
        if (tag != null && !tag.isEmpty()) {
            String k = baseKey + "_" + tag.toLowerCase(Locale.ROOT);
            if (sec.isString(k)) return sec.getString(k, def);
        }
        return sec.getString(baseKey, def);
    }

    private List<String> resolveLocaleList(Player p, ConfigurationSection sec, String baseKey) {
        String tag = FirstLogin.players.getString("locale." + p.getUniqueId(), null);
        if (tag != null && !tag.isEmpty()) {
            String k = baseKey + "_" + tag.toLowerCase(Locale.ROOT);
            if (sec.isList(k)) return sec.getStringList(k);
        }
        return sec.getStringList(baseKey);
    }

    private int currentPageOf(Player p) {
        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (top != null && top.getHolder() instanceof WelcomeHolder) {
            return ((WelcomeHolder) top.getHolder()).page;
        }
        return 1;
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
        plugin.queuePlayersSave();
    }

    private static class WelcomeHolder implements InventoryHolder {
        @SuppressWarnings("unused")
        private final boolean rulesView;
        private final int page;
        private WelcomeHolder(boolean rulesView, int page) {
            this.rulesView = rulesView;
            this.page = page;
        }
        @Override
        public Inventory getInventory() {
            return null; // not used, only for identification
        }
    }

    private static class GuiAction {
        final String key;
        final String requires;
        final List<String> requiresAll;
        final List<String> requiresAny;
        final List<String> actions;
        final int delayTicks;
        final boolean closeOnClick;
        final int cooldownSeconds;
        final boolean once;
        final String clickSoundName;
        final float clickSoundVolume;
        final float clickSoundPitch;
        final String permission;
        final String cooldownBypassPermission;
        final String runAs;
        final String urlLabel;
        GuiAction(String key, String requires, List<String> requiresAll, List<String> requiresAny, List<String> actions, int delayTicks,
                  boolean closeOnClick, int cooldownSeconds, boolean once,
                  String clickSoundName, float clickSoundVolume, float clickSoundPitch, String permission,
                  String cooldownBypassPermission, String runAs, String urlLabel) {
            this.key = key;
            this.requires = requires;
            this.requiresAll = requiresAll;
            this.requiresAny = requiresAny;
            this.actions = actions;
            this.delayTicks = delayTicks;
            this.closeOnClick = closeOnClick;
            this.cooldownSeconds = cooldownSeconds;
            this.once = once;
            this.clickSoundName = clickSoundName;
            this.clickSoundVolume = clickSoundVolume;
            this.clickSoundPitch = clickSoundPitch;
            this.permission = permission;
            this.cooldownBypassPermission = cooldownBypassPermission;
            this.runAs = runAs;
            this.urlLabel = urlLabel;
        }

        static GuiAction pageNav(String dir, int toPage) {
            return new GuiAction("page_" + dir, null, null, null,
                    java.util.Collections.singletonList("page:" + toPage), 0, false, 0, false,
                    null, 1.0f, 1.0f, null, null, "console", "&e");
        }
    }
}
