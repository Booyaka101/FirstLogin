package firstlogin.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class GuiActionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String itemKey;
    private final String action;

    public GuiActionEvent(Player player, String itemKey, String action) {
        this.player = player;
        this.itemKey = itemKey;
        this.action = action;
    }

    public GuiActionEvent(Player player, String itemKey) {
        this(player, itemKey, null);
    }

    public Player getPlayer() {
        return player;
    }

    public String getItemKey() {
        return itemKey;
    }

    public String getAction() {
        return action;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
