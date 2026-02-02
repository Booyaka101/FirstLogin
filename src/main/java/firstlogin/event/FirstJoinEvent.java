package firstlogin.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player joins the server for the first time.
 * Other plugins can listen to this event to perform actions on first join.
 */
public class FirstJoinEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int playerNumber; // What number player this is (e.g., 100th player)

    public FirstJoinEvent(Player player, int playerNumber) {
        this.player = player;
        this.playerNumber = playerNumber;
    }

    public Player getPlayer() {
        return player;
    }

    /**
     * Get the player number (e.g., this is the 100th player to join).
     */
    public int getPlayerNumber() {
        return playerNumber;
    }

    /**
     * Check if this is a milestone player (100th, 500th, 1000th, etc.)
     */
    public boolean isMilestone() {
        if (playerNumber <= 0) return false;
        if (playerNumber == 1) return true; // First player ever
        if (playerNumber % 1000 == 0) return true;
        if (playerNumber % 500 == 0) return true;
        if (playerNumber % 100 == 0) return true;
        if (playerNumber <= 10) return true; // First 10 players
        return false;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
