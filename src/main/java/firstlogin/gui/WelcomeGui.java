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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WelcomeGui implements Listener {
    private final FirstLogin plugin;

    // Track actions per opened GUI by player UUID and slot (thread-safe)
    private final Map<UUID, Map<Integer, GuiAction>> openActions = new ConcurrentHashMap<>();

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
                boolean lacksPerm = permission != null && !permission.isEmpty() && !player.hasPermission(permission);
                if (lacksPerm && hideIfNoPerm) {
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

                // If lacking permission but not hidden, optionally render a disabled variant
                if (lacksPerm) {
                    ConfigurationSection dis = sec.getConfigurationSection("disabledVariant");
                    if (dis != null) {
                        String dMatName = dis.getString("material", materialName);
                        Material dMat = Material.matchMaterial(dMatName == null ? materialName : dMatName.toUpperCase(Locale.ROOT));
                        if (dMat != null) mat = dMat;
                        String dName = resolveLocaleString(player, dis, "name", name);
                        if (dName != null) name = dName;
                        java.util.List<String> dLore = resolveLocaleList(player, dis, "lore");
                        if (dLore != null && !dLore.isEmpty()) lore = dLore;
                    }
                }

                // Item amount (default 1)
                int amount = sec.getInt("amount", 1);
                ItemStack item = new ItemStack(mat, Math.max(1, Math.min(64, amount)));
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
                    
                    // Glow effect (enchantment glint without visible enchantment)
                    if (sec.getBoolean("glow", false)) {
                        meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
                        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                    }
                    
                    // Custom model data for resource packs
                    int customModelData = sec.getInt("customModelData", 0);
                    if (customModelData > 0) {
                        meta.setCustomModelData(customModelData);
                    }
                    
                    // Hide attributes (cleaner look)
                    if (sec.getBoolean("hideAttributes", false)) {
                        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                    }
                    
                    // Hide additional info
                    if (sec.getBoolean("hideFlags", false)) {
                        meta.addItemFlags(
                            org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                            org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                            org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE,
                            org.bukkit.inventory.ItemFlag.HIDE_DESTROYS,
                            org.bukkit.inventory.ItemFlag.HIDE_PLACED_ON,
                            org.bukkit.inventory.ItemFlag.HIDE_POTION_EFFECTS
                        );
                    }
                    
                    // Skull owner for player heads
                    if (mat == Material.PLAYER_HEAD && meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                        String skullOwner = sec.getString("skullOwner", null);
                        if (skullOwner != null && !skullOwner.isEmpty()) {
                            String resolved = plugin.applyPlaceholders(skullOwner, player, total);
                            ((org.bukkit.inventory.meta.SkullMeta) meta).setOwner(resolved);
                        }
                    }
                    
                    // Leather armor color
                    if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta) {
                        String colorStr = sec.getString("color", null);
                        if (colorStr != null && !colorStr.isEmpty()) {
                            try {
                                org.bukkit.Color color;
                                if (colorStr.startsWith("#")) {
                                    int rgb = Integer.parseInt(colorStr.substring(1), 16);
                                    color = org.bukkit.Color.fromRGB(rgb);
                                } else {
                                    // Try named color
                                    java.lang.reflect.Field f = org.bukkit.Color.class.getField(colorStr.toUpperCase(Locale.ROOT));
                                    color = (org.bukkit.Color) f.get(null);
                                }
                                ((org.bukkit.inventory.meta.LeatherArmorMeta) meta).setColor(color);
                            } catch (Throwable ignored) {}
                        }
                    }
                    
                    // Enchantments: list of "ENCHANT_NAME:level" or just "ENCHANT_NAME"
                    List<String> enchants = sec.getStringList("enchantments");
                    if (enchants != null && !enchants.isEmpty()) {
                        for (String e : enchants) {
                            try {
                                String[] parts = e.split(":");
                                String enchName = parts[0].trim().toUpperCase(Locale.ROOT);
                                int level = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                                org.bukkit.enchantments.Enchantment ench = org.bukkit.enchantments.Enchantment.getByKey(
                                    org.bukkit.NamespacedKey.minecraft(enchName.toLowerCase(Locale.ROOT)));
                                if (ench != null) {
                                    meta.addEnchant(ench, level, true);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    
                    // Potion color for potion items
                    if (meta instanceof org.bukkit.inventory.meta.PotionMeta) {
                        String potionColor = sec.getString("potionColor", null);
                        if (potionColor != null && !potionColor.isEmpty()) {
                            try {
                                org.bukkit.Color color;
                                if (potionColor.startsWith("#")) {
                                    int rgb = Integer.parseInt(potionColor.substring(1), 16);
                                    color = org.bukkit.Color.fromRGB(rgb);
                                } else {
                                    java.lang.reflect.Field f = org.bukkit.Color.class.getField(potionColor.toUpperCase(Locale.ROOT));
                                    color = (org.bukkit.Color) f.get(null);
                                }
                                ((org.bukkit.inventory.meta.PotionMeta) meta).setColor(color);
                            } catch (Throwable ignored) {}
                        }
                        // Potion effects: list of "EFFECT_TYPE:duration:amplifier"
                        List<String> potionEffects = sec.getStringList("potionEffects");
                        if (potionEffects != null && !potionEffects.isEmpty()) {
                            for (String pe : potionEffects) {
                                try {
                                    String[] parts = pe.split(":");
                                    org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(parts[0].trim().toUpperCase(Locale.ROOT));
                                    int duration = parts.length > 1 ? Integer.parseInt(parts[1].trim()) * 20 : 600;
                                    int amplifier = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
                                    if (type != null) {
                                        ((org.bukkit.inventory.meta.PotionMeta) meta).addCustomEffect(
                                            new org.bukkit.potion.PotionEffect(type, duration, amplifier), true);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
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
        // Play open sound if configured
        playGuiSound(player, "open");
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
        // Play rules accepted sound
        playGuiSound(player, "rulesAccepted");
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
            // Play close sound if configured
            playGuiSound(p, "close");
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
                    } else if (s.startsWith("sound:")) {
                        // Play a sound: sound:SOUND_NAME or sound:SOUND_NAME:volume:pitch
                        String[] parts = s.substring("sound:".length()).split(":");
                        String soundName = parts[0].trim().toUpperCase(Locale.ROOT);
                        float vol = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
                        float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;
                        try {
                            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName);
                            player.playSound(player.getLocation(), sound, vol, pitch);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[GUI] Invalid sound: " + soundName);
                        }
                    } else if (s.startsWith("teleport:")) {
                        // Teleport player: teleport:world:x:y:z or teleport:x:y:z (same world)
                        String[] parts = s.substring("teleport:".length()).split(":");
                        org.bukkit.Location loc;
                        if (parts.length >= 4) {
                            // world:x:y:z
                            org.bukkit.World w = Bukkit.getWorld(parts[0].trim());
                            if (w == null) w = player.getWorld();
                            loc = new org.bukkit.Location(w, 
                                Double.parseDouble(parts[1].trim()),
                                Double.parseDouble(parts[2].trim()),
                                Double.parseDouble(parts[3].trim()));
                            if (parts.length >= 6) {
                                loc.setYaw(Float.parseFloat(parts[4].trim()));
                                loc.setPitch(Float.parseFloat(parts[5].trim()));
                            }
                        } else if (parts.length >= 3) {
                            // x:y:z (same world)
                            loc = new org.bukkit.Location(player.getWorld(),
                                Double.parseDouble(parts[0].trim()),
                                Double.parseDouble(parts[1].trim()),
                                Double.parseDouble(parts[2].trim()));
                        } else {
                            plugin.getLogger().warning("[GUI] Invalid teleport format: " + s);
                            continue;
                        }
                        player.teleport(loc);
                    } else if (s.startsWith("give:")) {
                        // Give item: give:MATERIAL or give:MATERIAL:amount
                        String[] parts = s.substring("give:".length()).split(":");
                        String matName = parts[0].trim().toUpperCase(Locale.ROOT);
                        int amount = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName);
                        if (mat != null) {
                            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat, amount);
                            player.getInventory().addItem(item);
                        } else {
                            plugin.getLogger().warning("[GUI] Invalid material: " + matName);
                        }
                    } else if (s.equalsIgnoreCase("close")) {
                        // Close the GUI
                        player.closeInventory();
                    } else if (s.startsWith("title:")) {
                        // Show title: title:Title Text or title:Title|Subtitle or title:Title|Subtitle:fadeIn:stay:fadeOut
                        String content = s.substring("title:".length());
                        String[] parts = content.split("\\|");
                        String titleText = plugin.applyPlaceholders(parts[0].trim(), player, plugin.playersToDate());
                        String subtitleText = parts.length > 1 ? plugin.applyPlaceholders(parts[1].split(":")[0].trim(), player, plugin.playersToDate()) : "";
                        int fadeIn = 10, stay = 70, fadeOut = 20;
                        if (parts.length > 1) {
                            String[] timings = parts[1].split(":");
                            if (timings.length >= 4) {
                                fadeIn = Integer.parseInt(timings[1].trim());
                                stay = Integer.parseInt(timings[2].trim());
                                fadeOut = Integer.parseInt(timings[3].trim());
                            }
                        }
                        player.sendTitle(
                            FirstLogin.colorizeWithHex(titleText),
                            FirstLogin.colorizeWithHex(subtitleText),
                            fadeIn, stay, fadeOut
                        );
                    } else if (s.startsWith("actionbar:")) {
                        // Show action bar: actionbar:Message Text
                        String msg = plugin.applyPlaceholders(s.substring("actionbar:".length()), player, plugin.playersToDate());
                        try {
                            plugin.getAdventure().player(player).sendActionBar(plugin.getMiniMessage().deserialize(msg));
                        } catch (Throwable t2) {
                            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(FirstLogin.colorizeWithHex(msg)));
                        }
                    } else if (s.startsWith("effect:")) {
                        // Give potion effect: effect:EFFECT_TYPE:duration:amplifier or effect:EFFECT_TYPE:duration
                        String[] parts = s.substring("effect:".length()).split(":");
                        String effectName = parts[0].trim().toUpperCase(Locale.ROOT);
                        int duration = parts.length > 1 ? Integer.parseInt(parts[1].trim()) * 20 : 200; // seconds to ticks
                        int amplifier = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
                        try {
                            org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(effectName);
                            if (type != null) {
                                player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, duration, amplifier));
                            } else {
                                plugin.getLogger().warning("[GUI] Invalid potion effect: " + effectName);
                            }
                        } catch (Throwable t2) {
                            plugin.getLogger().warning("[GUI] Error applying effect: " + t2.getMessage());
                        }
                    } else if (s.startsWith("broadcast:")) {
                        // Broadcast message to all online players
                        String msg = plugin.applyPlaceholders(s.substring("broadcast:".length()), player, plugin.playersToDate());
                        String colorized = FirstLogin.colorizeWithHex(msg);
                        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                            p.sendMessage(colorized);
                        }
                    } else if (s.startsWith("console:")) {
                        // Run command as console (ignores runAs setting)
                        String cmd = plugin.applyPlaceholders(s.substring("console:".length()), player, plugin.playersToDate());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } else if (s.startsWith("player:")) {
                        // Run command as player (ignores runAs setting)
                        String cmd = plugin.applyPlaceholders(s.substring("player:".length()), player, plugin.playersToDate());
                        player.performCommand(cmd);
                    } else if (s.startsWith("chat:")) {
                        // Make player send a chat message
                        String msg = plugin.applyPlaceholders(s.substring("chat:".length()), player, plugin.playersToDate());
                        player.chat(msg);
                    } else if (s.startsWith("xp:")) {
                        // Give XP: xp:amount or xp:levels:amount
                        String content = s.substring("xp:".length());
                        if (content.toLowerCase(Locale.ROOT).startsWith("levels:")) {
                            int levels = Integer.parseInt(content.substring("levels:".length()).trim());
                            player.giveExpLevels(levels);
                        } else {
                            int xp = Integer.parseInt(content.trim());
                            player.giveExp(xp);
                        }
                    } else if (s.startsWith("random:")) {
                        // Pick random action from list: random:action1|action2|action3
                        String[] options = s.substring("random:".length()).split("\\|");
                        if (options.length > 0) {
                            String picked = options[new java.util.Random().nextInt(options.length)].trim();
                            // Recursively execute the picked action
                            executeAction(player, a, picked);
                        }
                    } else if (s.startsWith("delay:")) {
                        // Delayed action: delay:ticks:action
                        String content = s.substring("delay:".length());
                        int colonIdx = content.indexOf(':');
                        if (colonIdx > 0) {
                            int ticks = Integer.parseInt(content.substring(0, colonIdx).trim());
                            String delayedAction = content.substring(colonIdx + 1).trim();
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (player.isOnline()) {
                                    executeAction(player, a, delayedAction);
                                }
                            }, ticks);
                        }
                    } else if (s.startsWith("if:")) {
                        // Conditional action: if:requirement:action or if:requirement:action:elseAction
                        String content = s.substring("if:".length());
                        String[] parts = content.split(":", 3);
                        if (parts.length >= 2) {
                            String requirement = parts[0].trim();
                            String thenAction = parts[1].trim();
                            String elseAction = parts.length >= 3 ? parts[2].trim() : null;
                            if (checkRequirementNoMessage(player, requirement)) {
                                executeAction(player, a, thenAction);
                            } else if (elseAction != null && !elseAction.isEmpty()) {
                                executeAction(player, a, elseAction);
                            }
                        }
                    } else if (s.startsWith("repeat:")) {
                        // Repeat action: repeat:count:action
                        String content = s.substring("repeat:".length());
                        int colonIdx = content.indexOf(':');
                        if (colonIdx > 0) {
                            int count = Integer.parseInt(content.substring(0, colonIdx).trim());
                            String repeatAction = content.substring(colonIdx + 1).trim();
                            for (int i = 0; i < Math.min(count, 100); i++) { // Cap at 100 to prevent abuse
                                executeAction(player, a, repeatAction);
                            }
                        }
                    } else if (s.startsWith("chance:")) {
                        // Chance-based action: chance:50:action (50% chance)
                        String content = s.substring("chance:".length());
                        int colonIdx = content.indexOf(':');
                        if (colonIdx > 0) {
                            int percent = Integer.parseInt(content.substring(0, colonIdx).trim());
                            String chanceAction = content.substring(colonIdx + 1).trim();
                            if (new java.util.Random().nextInt(100) < percent) {
                                executeAction(player, a, chanceAction);
                            }
                        }
                    } else if (s.startsWith("heal:")) {
                        // Heal player: heal:amount or heal:full
                        String content = s.substring("heal:".length()).trim().toLowerCase(Locale.ROOT);
                        double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                        if (content.equals("full")) {
                            player.setHealth(maxHealth);
                        } else {
                            double amount = Double.parseDouble(content);
                            player.setHealth(Math.min(maxHealth, player.getHealth() + amount));
                        }
                    } else if (s.startsWith("feed:")) {
                        // Feed player: feed:amount or feed:full
                        String content = s.substring("feed:".length()).trim().toLowerCase(Locale.ROOT);
                        if (content.equals("full")) {
                            player.setFoodLevel(20);
                            player.setSaturation(20f);
                        } else {
                            int amount = Integer.parseInt(content);
                            player.setFoodLevel(Math.min(20, player.getFoodLevel() + amount));
                        }
                    } else if (s.startsWith("gamemode:")) {
                        // Set gamemode: gamemode:SURVIVAL
                        String mode = s.substring("gamemode:".length()).trim().toUpperCase(Locale.ROOT);
                        try {
                            player.setGameMode(org.bukkit.GameMode.valueOf(mode));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[GUI] Invalid gamemode: " + mode);
                        }
                    } else if (s.startsWith("firework:")) {
                        // Launch firework: firework:color or firework:color:type:power
                        // Colors: RED, BLUE, GREEN, YELLOW, etc. Types: BALL, BALL_LARGE, BURST, STAR, CREEPER
                        String content = s.substring("firework:".length());
                        String[] parts = content.split(":");
                        try {
                            org.bukkit.Color color = org.bukkit.Color.WHITE;
                            org.bukkit.FireworkEffect.Type type = org.bukkit.FireworkEffect.Type.BALL;
                            int power = 1;
                            if (parts.length >= 1 && !parts[0].isEmpty()) {
                                try {
                                    if (parts[0].startsWith("#")) {
                                        color = org.bukkit.Color.fromRGB(Integer.parseInt(parts[0].substring(1), 16));
                                    } else {
                                        java.lang.reflect.Field f = org.bukkit.Color.class.getField(parts[0].trim().toUpperCase(Locale.ROOT));
                                        color = (org.bukkit.Color) f.get(null);
                                    }
                                } catch (Throwable ignored) {}
                            }
                            if (parts.length >= 2) {
                                try { type = org.bukkit.FireworkEffect.Type.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
                            }
                            if (parts.length >= 3) {
                                try { power = Integer.parseInt(parts[2].trim()); } catch (Throwable ignored) {}
                            }
                            org.bukkit.entity.Firework fw = player.getWorld().spawn(player.getLocation(), org.bukkit.entity.Firework.class);
                            org.bukkit.inventory.meta.FireworkMeta fwm = fw.getFireworkMeta();
                            fwm.addEffect(org.bukkit.FireworkEffect.builder().withColor(color).with(type).withFlicker().build());
                            fwm.setPower(Math.max(0, Math.min(3, power)));
                            fw.setFireworkMeta(fwm);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("[GUI] Error spawning firework: " + t.getMessage());
                        }
                    } else if (s.startsWith("particle:")) {
                        // Spawn particles: particle:PARTICLE_TYPE or particle:PARTICLE_TYPE:count
                        String content = s.substring("particle:".length());
                        String[] parts = content.split(":");
                        try {
                            org.bukkit.Particle particle = org.bukkit.Particle.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
                            int count = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 30;
                            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), count, 0.5, 0.5, 0.5, 0.1);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("[GUI] Invalid particle: " + parts[0]);
                        }
                    } else if (s.startsWith("fly:")) {
                        // Toggle fly: fly:on, fly:off, fly:toggle
                        String mode = s.substring("fly:".length()).trim().toLowerCase(Locale.ROOT);
                        switch (mode) {
                            case "on": case "true": case "enable":
                                player.setAllowFlight(true);
                                player.setFlying(true);
                                break;
                            case "off": case "false": case "disable":
                                player.setFlying(false);
                                player.setAllowFlight(false);
                                break;
                            case "toggle":
                                if (player.getAllowFlight()) {
                                    player.setFlying(false);
                                    player.setAllowFlight(false);
                                } else {
                                    player.setAllowFlight(true);
                                    player.setFlying(true);
                                }
                                break;
                        }
                    } else if (s.startsWith("bossbar:")) {
                        // Show temporary bossbar: bossbar:text:color:seconds or bossbar:text:seconds
                        String content = s.substring("bossbar:".length());
                        String[] parts = content.split(":");
                        String text = parts[0].trim();
                        org.bukkit.boss.BarColor barColor = org.bukkit.boss.BarColor.PURPLE;
                        int seconds = 5;
                        if (parts.length >= 3) {
                            try { barColor = org.bukkit.boss.BarColor.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)); } catch (Throwable ignored) {}
                            try { seconds = Integer.parseInt(parts[2].trim()); } catch (Throwable ignored) {}
                        } else if (parts.length >= 2) {
                            try { seconds = Integer.parseInt(parts[1].trim()); } catch (Throwable ignored) {}
                        }
                        String colorized = plugin.applyPlaceholders(text, player, plugin.playersToDate());
                        org.bukkit.boss.BossBar bar = Bukkit.createBossBar(FirstLogin.colorizeWithHex(colorized), barColor, org.bukkit.boss.BarStyle.SOLID);
                        bar.addPlayer(player);
                        final org.bukkit.boss.BossBar finalBar = bar;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> finalBar.removeAll(), seconds * 20L);
                    } else if (s.equalsIgnoreCase("cleareffects")) {
                        // Clear all potion effects
                        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                            player.removePotionEffect(effect.getType());
                        }
                    } else if (s.startsWith("cleareffect:")) {
                        // Clear specific effect: cleareffect:SPEED
                        String effectName = s.substring("cleareffect:".length()).trim().toUpperCase(Locale.ROOT);
                        org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(effectName);
                        if (type != null) player.removePotionEffect(type);
                    } else if (s.startsWith("velocity:")) {
                        // Apply velocity: velocity:x:y:z or velocity:up:power
                        String content = s.substring("velocity:".length());
                        String[] parts = content.split(":");
                        org.bukkit.util.Vector vel;
                        if (parts[0].equalsIgnoreCase("up")) {
                            double power = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 1.0;
                            vel = new org.bukkit.util.Vector(0, power, 0);
                        } else if (parts.length >= 3) {
                            vel = new org.bukkit.util.Vector(
                                Double.parseDouble(parts[0].trim()),
                                Double.parseDouble(parts[1].trim()),
                                Double.parseDouble(parts[2].trim()));
                        } else {
                            vel = new org.bukkit.util.Vector(0, 1, 0);
                        }
                        player.setVelocity(vel);
                    } else if (s.startsWith("sudo:")) {
                        // Make player run command as if they typed it: sudo:command
                        String cmd = plugin.applyPlaceholders(s.substring("sudo:".length()), player, plugin.playersToDate());
                        player.performCommand(cmd);
                    } else if (s.startsWith("op:")) {
                        // Run command with temporary OP: op:command
                        String cmd = plugin.applyPlaceholders(s.substring("op:".length()), player, plugin.playersToDate());
                        boolean wasOp = player.isOp();
                        try {
                            player.setOp(true);
                            player.performCommand(cmd);
                        } finally {
                            player.setOp(wasOp);
                        }
                    } else if (s.startsWith("money:")) {
                        // Give/take money (requires Vault): money:100 or money:-50
                        // Note: This is a placeholder - actual implementation requires Vault hook
                        plugin.getLogger().info("[GUI] Money action requires Vault integration: " + s);
                    } else if (s.startsWith("permission:add:")) {
                        // Add permission (requires permission plugin): permission:add:node
                        plugin.getLogger().info("[GUI] Permission action requires permission plugin: " + s);
                    } else if (s.startsWith("permission:remove:")) {
                        // Remove permission: permission:remove:node
                        plugin.getLogger().info("[GUI] Permission action requires permission plugin: " + s);
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
    
    // Helper to execute a single action string (used for recursive actions like random, delay, if, etc.)
    private void executeAction(Player player, GuiAction a, String actionStr) {
        if (actionStr == null || actionStr.isEmpty() || player == null || !player.isOnline()) return;
        try {
            String s = actionStr.trim();
            if (s.equalsIgnoreCase("back")) {
                openFor(player, Math.max(1, currentPageOf(player) - 1));
            } else if (s.startsWith("page:")) {
                int to = Integer.parseInt(s.substring("page:".length()).trim());
                openFor(player, Math.max(1, to));
            } else if (s.startsWith("command:")) {
                String cmd = plugin.applyPlaceholders(s.substring("command:".length()), player, plugin.playersToDate());
                dispatchCommand(a.runAs, player, cmd);
            } else if (s.startsWith("console:")) {
                String cmd = plugin.applyPlaceholders(s.substring("console:".length()), player, plugin.playersToDate());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else if (s.startsWith("player:")) {
                String cmd = plugin.applyPlaceholders(s.substring("player:".length()), player, plugin.playersToDate());
                player.performCommand(cmd);
            } else if (s.startsWith("message:")) {
                String path = s.substring("message:".length());
                List<String> list = pluginList(player, path);
                for (String m : list) sendTo(player, m);
            } else if (s.startsWith("sound:")) {
                String[] parts = s.substring("sound:".length()).split(":");
                String soundName = parts[0].trim().toUpperCase(Locale.ROOT);
                float vol = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
                float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;
                try {
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName);
                    player.playSound(player.getLocation(), sound, vol, pitch);
                } catch (IllegalArgumentException ignored) {}
            } else if (s.startsWith("give:")) {
                String[] parts = s.substring("give:".length()).split(":");
                String matName = parts[0].trim().toUpperCase(Locale.ROOT);
                int amount = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName);
                if (mat != null) {
                    player.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, amount));
                }
            } else if (s.startsWith("effect:")) {
                String[] parts = s.substring("effect:".length()).split(":");
                String effectName = parts[0].trim().toUpperCase(Locale.ROOT);
                int duration = parts.length > 1 ? Integer.parseInt(parts[1].trim()) * 20 : 200;
                int amplifier = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
                org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(effectName);
                if (type != null) {
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, duration, amplifier));
                }
            } else if (s.startsWith("xp:")) {
                String content = s.substring("xp:".length());
                if (content.toLowerCase(Locale.ROOT).startsWith("levels:")) {
                    player.giveExpLevels(Integer.parseInt(content.substring("levels:".length()).trim()));
                } else {
                    player.giveExp(Integer.parseInt(content.trim()));
                }
            } else if (s.startsWith("heal:")) {
                String content = s.substring("heal:".length()).trim().toLowerCase(Locale.ROOT);
                if (content.equals("full")) {
                    player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
                } else {
                    double max = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                    player.setHealth(Math.min(max, player.getHealth() + Double.parseDouble(content)));
                }
            } else if (s.startsWith("feed:")) {
                String content = s.substring("feed:".length()).trim().toLowerCase(Locale.ROOT);
                if (content.equals("full")) {
                    player.setFoodLevel(20);
                    player.setSaturation(20f);
                } else {
                    player.setFoodLevel(Math.min(20, player.getFoodLevel() + Integer.parseInt(content)));
                }
            } else if (s.startsWith("broadcast:")) {
                String msg = FirstLogin.colorizeWithHex(plugin.applyPlaceholders(s.substring("broadcast:".length()), player, plugin.playersToDate()));
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
            } else if (s.equalsIgnoreCase("close")) {
                player.closeInventory();
            } else if (s.startsWith("flag:set:")) {
                setFlag(player.getUniqueId(), s.substring("flag:set:".length()), true);
            } else if (s.startsWith("flag:clear:")) {
                setFlag(player.getUniqueId(), s.substring("flag:clear:".length()), false);
            }
            // Note: recursive actions (random, delay, if, repeat, chance) are handled in the main execute loop
        } catch (Throwable t) {
            plugin.getLogger().warning("[GUI] Error in executeAction: " + t.getMessage());
        }
    }

    private boolean checkRequirementNoMessage(Player player, String req) {
        if (req == null || req.isEmpty()) return true;
        String lc = req.toLowerCase(Locale.ROOT);
        
        if (req.startsWith("flag:")) {
            String flag = req.substring("flag:".length());
            return getFlag(player.getUniqueId(), flag);
        } else if (req.startsWith("!flag:")) {
            // Negated flag check
            String flag = req.substring("!flag:".length());
            return !getFlag(player.getUniqueId(), flag);
        } else if (req.startsWith("perm:")) {
            String perm = req.substring("perm:".length());
            return !perm.isEmpty() && player.hasPermission(perm);
        } else if (req.startsWith("!perm:")) {
            // Negated permission check
            String perm = req.substring("!perm:".length());
            return perm.isEmpty() || !player.hasPermission(perm);
        } else if (lc.startsWith("level:")) {
            // Check player XP level: level:>=10, level:<5, level:==20
            String expr = req.substring("level:".length()).trim();
            int playerLevel = player.getLevel();
            return evaluateComparison(playerLevel, expr);
        } else if (lc.startsWith("health:")) {
            // Check player health: health:>=10, health:<5
            String expr = req.substring("health:".length()).trim();
            double health = player.getHealth();
            return evaluateComparisonDouble(health, expr);
        } else if (lc.startsWith("food:")) {
            // Check player food level: food:>=10
            String expr = req.substring("food:".length()).trim();
            int food = player.getFoodLevel();
            return evaluateComparison(food, expr);
        } else if (lc.startsWith("gamemode:")) {
            // Check gamemode: gamemode:SURVIVAL, gamemode:CREATIVE
            String mode = req.substring("gamemode:".length()).trim().toUpperCase(Locale.ROOT);
            return player.getGameMode().name().equals(mode);
        } else if (lc.startsWith("world:")) {
            // Check world: world:world_nether
            String worldName = req.substring("world:".length()).trim();
            return player.getWorld().getName().equalsIgnoreCase(worldName);
        } else if (lc.startsWith("online:")) {
            // Check online player count: online:>=10, online:<50
            String expr = req.substring("online:".length()).trim();
            int online = Bukkit.getOnlinePlayers().size();
            return evaluateComparison(online, expr);
        } else if (lc.startsWith("time:")) {
            // Check world time: time:day, time:night, time:>=12000
            String expr = req.substring("time:".length()).trim().toLowerCase(Locale.ROOT);
            long time = player.getWorld().getTime();
            if (expr.equals("day")) return time >= 0 && time < 12000;
            if (expr.equals("night")) return time >= 12000 && time < 24000;
            return evaluateComparison((int) time, expr);
        } else if (lc.startsWith("weather:")) {
            // Check weather: weather:clear, weather:rain, weather:storm
            String w = req.substring("weather:".length()).trim().toLowerCase(Locale.ROOT);
            org.bukkit.World world = player.getWorld();
            if (w.equals("clear")) return !world.hasStorm() && !world.isThundering();
            if (w.equals("rain")) return world.hasStorm() && !world.isThundering();
            if (w.equals("storm") || w.equals("thunder")) return world.isThundering();
            return false;
        } else if (lc.startsWith("cooldown:")) {
            // Check if a cooldown has expired: cooldown:key:seconds (true if NOT on cooldown)
            String[] parts = req.substring("cooldown:".length()).split(":");
            if (parts.length >= 2) {
                String key = parts[0].trim();
                int seconds = Integer.parseInt(parts[1].trim());
                return cooldownRemaining(player.getUniqueId(), key, seconds) <= 0;
            }
            return true;
        } else if (lc.startsWith("played:")) {
            // Check playtime: played:>=3600 (seconds total playtime)
            String expr = req.substring("played:".length()).trim();
            long playedSeconds = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20;
            return evaluateComparison((int) playedSeconds, expr);
        }
        return true;
    }
    
    private boolean evaluateComparison(int value, String expr) {
        try {
            if (expr.startsWith(">=")) return value >= Integer.parseInt(expr.substring(2).trim());
            if (expr.startsWith("<=")) return value <= Integer.parseInt(expr.substring(2).trim());
            if (expr.startsWith("==")) return value == Integer.parseInt(expr.substring(2).trim());
            if (expr.startsWith("!=")) return value != Integer.parseInt(expr.substring(2).trim());
            if (expr.startsWith(">")) return value > Integer.parseInt(expr.substring(1).trim());
            if (expr.startsWith("<")) return value < Integer.parseInt(expr.substring(1).trim());
            return value == Integer.parseInt(expr.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    private boolean evaluateComparisonDouble(double value, String expr) {
        try {
            if (expr.startsWith(">=")) return value >= Double.parseDouble(expr.substring(2).trim());
            if (expr.startsWith("<=")) return value <= Double.parseDouble(expr.substring(2).trim());
            if (expr.startsWith("==")) return value == Double.parseDouble(expr.substring(2).trim());
            if (expr.startsWith("!=")) return value != Double.parseDouble(expr.substring(2).trim());
            if (expr.startsWith(">")) return value > Double.parseDouble(expr.substring(1).trim());
            if (expr.startsWith("<")) return value < Double.parseDouble(expr.substring(1).trim());
            return value == Double.parseDouble(expr.trim());
        } catch (NumberFormatException e) {
            return false;
        }
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

    // Play configurable GUI sounds (open, close, rulesAccepted)
    private void playGuiSound(Player p, String type) {
        try {
            String basePath = "welcomeGui.sounds." + type;
            if (!FirstLogin.config.getBoolean(basePath + ".enabled", false)) return;
            String name = FirstLogin.config.getString(basePath + ".name", null);
            if (name == null || name.isEmpty()) return;
            float vol = (float) FirstLogin.config.getDouble(basePath + ".volume", 1.0);
            float pitch = (float) FirstLogin.config.getDouble(basePath + ".pitch", 1.0);
            Sound s = Sound.valueOf(name);
            p.playSound(p.getLocation(), s, vol, pitch);
        } catch (Throwable ignored) {}
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
        // Delegate to plugin's centralized method
        return plugin.versionedFlagName(flag);
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
