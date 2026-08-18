package ua.bobster.defence.raid;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Подія вмикання або відбою повітряної тривоги над територією.
 * <p>
 * Потрібна не нам, а назовні: на неї можна повісити alerts.yml DiscordSRV або будь-який
 * інший плагін, не чіпаючи код BobsterDefence.
 */
public class AirRaidEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String territory;
    private final String owner;
    private final String threat;
    private final boolean started;

    public AirRaidEvent(String territory, String owner, String threat, boolean started) {
        super(false);
        this.territory = territory;
        this.owner = owner;
        this.threat = threat;
        this.started = started;
    }

    /** Назва території, напр. «Solaria». */
    public String getTerritory() {
        return territory;
    }

    public String getOwner() {
        return owner;
    }

    /** Тип загрози: «ракета» або «дрон». Для відбою — порожній рядок. */
    public String getThreat() {
        return threat;
    }

    /** true — тривога почалася, false — відбій. */
    public boolean isStarted() {
        return started;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
