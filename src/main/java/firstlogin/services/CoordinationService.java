package firstlogin.services;

import firstlogin.FirstLogin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Encapsulates coordination settings and claim logic to avoid multi-plugin welcome spam.
 */
public class CoordinationService {
    private final FirstLogin plugin;

    private boolean enabled;
    private String role; // primary | secondary | exclusive
    private long waitTicks;
    private String keyString;
    private NamespacedKey key;

    public CoordinationService(FirstLogin plugin,
                               boolean enabled,
                               String role,
                               long waitTicks,
                               NamespacedKey key,
                               String keyString) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.role = (role == null ? "secondary" : role.toLowerCase(java.util.Locale.ROOT));
        this.waitTicks = Math.max(0L, waitTicks);
        this.key = key;
        this.keyString = keyString;
    }

    public long extraDelayTicks() {
        if (!enabled) return 0L;
        String r = role == null ? "secondary" : role;
        return ("secondary".equals(r)) ? Math.max(0L, waitTicks) : 0L;
    }

    public String getClaimant(Player p) {
        if (p == null) return null;
        try {
            if (key != null) {
                PersistentDataContainer pdc = p.getPersistentDataContainer();
                String v = pdc.get(key, PersistentDataType.STRING);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {}
        try {
            if (keyString != null && p.hasMetadata(keyString)) {
                for (org.bukkit.metadata.MetadataValue mv : p.getMetadata(keyString)) {
                    if (mv != null) {
                        String val = String.valueOf(mv.value());
                        if (val != null && !val.isEmpty()) return val;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void markClaim(Player p) {
        if (p == null) return;
        String me = plugin.getName();
        try {
            if (key != null) {
                p.getPersistentDataContainer().set(key, PersistentDataType.STRING, me);
            }
        } catch (Throwable ignored) {}
        try {
            if (keyString != null) {
                p.setMetadata(keyString, new FixedMetadataValue(plugin, me));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Attempt to claim now according to role; returns true if we should proceed showing welcome.
     */
    public boolean tryClaimNow(Player p) {
        if (!enabled) return true;
        String me = plugin.getName();
        String curr = getClaimant(p);
        String r = role == null ? "secondary" : role;
        switch (r) {
            case "exclusive":
                // We force ownership
                markClaim(p);
                return true;
            case "primary":
                if (curr == null || curr.isEmpty() || me.equals(curr)) {
                    markClaim(p);
                    return true;
                }
                return false;
            case "secondary":
            default:
                if (curr == null || curr.isEmpty()) {
                    markClaim(p);
                    return true;
                }
                return false;
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getRole() { return role; }
    public long getWaitTicks() { return waitTicks; }
    public NamespacedKey getKey() { return key; }
    public String getKeyString() { return keyString; }
}
