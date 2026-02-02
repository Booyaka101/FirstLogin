package firstlogin.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a returning player joins the server.
 * Other plugins can listen to this event to perform actions for returning players.
 */
public class ReturningPlayerEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long daysOffline;
    private final int loginStreak;
    private final int anniversaryYears; // 0 if not an anniversary

    public ReturningPlayerEvent(Player player, long daysOffline, int loginStreak, int anniversaryYears) {
        this.player = player;
        this.daysOffline = daysOffline;
        this.loginStreak = loginStreak;
        this.anniversaryYears = anniversaryYears;
    }

    public Player getPlayer() {
        return player;
    }

    /**
     * Get the number of days the player was offline.
     */
    public long getDaysOffline() {
        return daysOffline;
    }

    /**
     * Get the player's current login streak (consecutive days).
     */
    public int getLoginStreak() {
        return loginStreak;
    }

    /**
     * Get the anniversary years (e.g., 1 for first anniversary).
     * Returns 0 if today is not an anniversary.
     */
    public int getAnniversaryYears() {
        return anniversaryYears;
    }

    /**
     * Check if today is the player's join anniversary.
     */
    public boolean isAnniversary() {
        return anniversaryYears > 0;
    }

    /**
     * Check if this is a streak milestone (7, 14, 30, 60, 90, 100, 365 days).
     */
    public boolean isStreakMilestone() {
        return loginStreak == 7 || loginStreak == 14 || loginStreak == 30 ||
               loginStreak == 60 || loginStreak == 90 || loginStreak == 100 ||
               loginStreak == 365 || (loginStreak > 0 && loginStreak % 100 == 0);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
