package ua.bobster.defence.strategicstates.combat;

import org.bukkit.Location;
import org.bukkit.entity.Pillager;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.aa.AaTarget;
import ua.bobster.defence.aa.AaTier;
import ua.bobster.defence.aa.AaLauncher;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.MilitaryProductionManager;
import ua.bobster.defence.strategicstates.arsenal.StateArsenal;
import ua.bobster.defence.strategicstates.construction.BuildingStatus;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.ConstructionManager;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.population.StateCitizen;

import java.util.Collection;
import java.util.function.Supplier;

public class StateAirDefenceManager {

    private final BobsterDefence plugin;
    private final ConstructionManager construction;
    private final CitizenManager citizens;
    private final MilitaryProductionManager production;
    private final Supplier<Collection<StrategicState>> states;
    private final StateInstallationManager installations;
    private BukkitTask task;
    private long intervalTicks;

    public StateAirDefenceManager(BobsterDefence plugin, ConstructionManager construction, CitizenManager citizens, MilitaryProductionManager production, StateInstallationManager installations, Supplier<Collection<StrategicState>> states) {
        this.plugin = plugin;
        this.construction = construction;
        this.citizens = citizens;
        this.production = production;
        this.states = states;
        this.installations = installations;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(1L, plugin.getConfig().getLong("strategic-states.air-defence.tick-interval-ticks", 5L));
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (StrategicState state : states.get()) {
            if (state.status() == StateStatus.DESTROYED) {
                continue;
            }
            StateArsenal arsenal = production.arsenal(state.id());
            if (arsenal == null || arsenal.amount(ArsenalItem.AIR_DEFENCE_MISSILE) < 1) {
                continue;
            }
            for (StateBuilding building : construction.workplaces(state.id(), BuildingType.AIR_DEFENCE_SITE)) {
                engage(state, building);
            }
        }
    }

    private void engage(StrategicState state, StateBuilding building) {
        if (building.status() != BuildingStatus.MATERIALIZED || !production.available(state.id(), ArsenalItem.AIR_DEFENCE_MISSILE)) {
            return;
        }
        Location origin = building.origin().location();
        if (origin == null || !origin.getWorld().isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
            return;
        }
        Location center = origin.clone().add(building.sizeX() / 2.0D, 2.0D, building.sizeZ() / 2.0D);
        StateCitizen citizen = citizens.physicalProfession(state.id(), CitizenProfession.AIR_DEFENCE_OPERATOR, center, Math.max(24.0D, building.sizeX() + building.sizeZ()));
        Pillager operator = citizens.physicalEntity(citizen);
        if (operator == null) {
            return;
        }
        for (AaLauncher launcher : installations.airDefence(state, building)) {
            AaTier tier = plugin.aa().tier(launcher.tierId());
            if (tier == null || !production.available(state.id(), ArsenalItem.AIR_DEFENCE_MISSILE)) {
                continue;
            }
            AaTarget target = plugin.aa().engage(CombatPrincipal.state(state.id()), launcher.muzzle(), tier.id(), building.id() + ":" + tier.id());
            if (target == null || !production.consume(state.id(), ArsenalItem.AIR_DEFENCE_MISSILE)) {
                continue;
            }
            operator.lookAt(target.aimPoint());
            operator.swingMainHand();
        }
    }
}
