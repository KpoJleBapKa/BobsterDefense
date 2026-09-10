package ua.bobster.defence.strategicstates.war;

import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.StrategicStateManager;
import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.policy.ActionContext;
import ua.bobster.defence.strategicstates.policy.AttackPolicyManager;
import ua.bobster.defence.strategicstates.policy.StrategicAction;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.army.StateArmy;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.StateArsenal;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class WarManager {

    public record StartResult(boolean success, String message, StateWar war) {
    }

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final StrategicStateManager states;
    private final AttackPolicyManager policy;
    private final Map<UUID, StateWar> wars = new LinkedHashMap<>();

    private int minimumLevel;
    private double minimumReadiness;
    private boolean stateVsPlayer;
    private boolean stateVsState;
    private long mobilizationMillis;
    private BukkitTask task;
    private final Map<UUID, WaveCampaign> campaigns = new LinkedHashMap<>();

    private static final class WaveCampaign {

        private final int total;
        private int current;
        private int startingStrength;

        private WaveCampaign(int total, int startingStrength) {
            this.total = total;
            this.current = 1;
            this.startingStrength = startingStrength;
        }
    }

    public WarManager(BobsterDefence plugin, StateRepository repository, StrategicStateManager states) {
        this.plugin = plugin;
        this.repository = repository;
        this.states = states;
        this.policy = new AttackPolicyManager(states);
        reload();
    }

    public void reload() {
        minimumLevel = Math.clamp(plugin.getConfig().getInt("strategic-states.war.minimum-state-level", 3), 1, 5);
        minimumReadiness = Math.clamp(plugin.getConfig().getDouble("strategic-states.war.minimum-readiness", 0.8D), 0.0D, 1.0D);
        stateVsPlayer = plugin.getConfig().getBoolean("strategic-states.war.allow-state-vs-player", true);
        stateVsState = plugin.getConfig().getBoolean("strategic-states.war.allow-state-vs-state", true);
        mobilizationMillis = Math.max(60L, plugin.getConfig().getLong("strategic-states.war.mobilization-seconds", 1200L)) * 1000L;
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        campaigns.clear();
    }

    public void load(Collection<StateWar> loaded) {
        wars.clear();
        for (StateWar war : loaded) {
            wars.put(war.id(), war);
        }
    }

    public Collection<StateWar> all() {
        return List.copyOf(wars.values());
    }

    public Collection<StateWar> active() {
        return wars.values().stream().filter(war -> war.status() == WarStatus.ACTIVE).toList();
    }

    public StateWar activeForTerritory(UUID territoryId) {
        return wars.values().stream().filter(war -> (war.status() == WarStatus.ACTIVE || war.status() == WarStatus.PREPARING) && war.territoryId().equals(territoryId)).findFirst().orElse(null);
    }

    public StartResult start(StrategicState attacker, TerritorySnapshot territory) {
        return start(attacker, territory, false);
    }

    public StartResult retaliate(StrategicState attacker, TerritorySnapshot territory) {
        return start(attacker, territory, true);
    }

    private StartResult start(StrategicState attacker, TerritorySnapshot territory, boolean retaliation) {
        if (attacker == null || territory == null || territory.ownerId() == null) {
            return new StartResult(false, "Некоректний нападник або територія", null);
        }
        CombatPrincipalType targetType = territory.ownerKind() == TerritoryOwnerKind.PLAYER ? CombatPrincipalType.PLAYER : CombatPrincipalType.STRATEGIC_STATE;
        if (territory.ownerKind() != TerritoryOwnerKind.PLAYER && territory.ownerKind() != TerritoryOwnerKind.STRATEGIC_STATE) {
            return new StartResult(false, "Територія не має доступного власника", null);
        }
        CombatPrincipal target = new CombatPrincipal(territory.ownerId(), targetType);
        CombatPrincipal principal = CombatPrincipal.state(attacker.id());
        if (!attacker.core().world().equals(territory.world())) {
            return new StartResult(false, "Міжсвітова наземна війна недоступна", null);
        }
        if (principal.equals(target)) {
            return new StartResult(false, "Держава не може атакувати себе", null);
        }
        if (!retaliation && !policy.canAct(principal, target, ActionContext.OFFENSIVE, StrategicAction.DECLARE_WAR)) {
            return new StartResult(false, "Наступальна дія заборонена політикою", null);
        }
        if ((targetType == CombatPrincipalType.PLAYER && !stateVsPlayer) || (targetType == CombatPrincipalType.STRATEGIC_STATE && !stateVsState)) {
            return new StartResult(false, "Цей тип війни вимкнено в конфігурації", null);
        }
        if (targetType == CombatPrincipalType.STRATEGIC_STATE && states.byId(target.id()) == null) {
            return new StartResult(false, "Цільова Strategic State недоступна", null);
        }
        if (!retaliation && attacker.developmentLevel() < minimumLevel) {
            return new StartResult(false, "Потрібен рівень держави " + minimumLevel, null);
        }
        double readiness = readiness(attacker);
        if (!retaliation && readiness < minimumReadiness) {
            return new StartResult(false, "Готовність " + Math.round(readiness * 100.0D) + "% нижча за необхідну", null);
        }
        if (activeForTerritory(territory.id()) != null) {
            return new StartResult(false, "За цю територію вже триває війна", null);
        }
        if (wars.values().stream().anyMatch(war -> (war.status() == WarStatus.ACTIVE || war.status() == WarStatus.PREPARING) && war.attackerStateId().equals(attacker.id()))) {
            return new StartResult(false, "Держава вже веде наступальну війну", null);
        }
        long now = System.currentTimeMillis();
        StateWar war = new StateWar(UUID.randomUUID(), attacker.id(), target, territory.id(), now, WarStatus.PREPARING, 0L, null);
        try {
            repository.insertWar(war);
            wars.put(war.id(), war);
            return new StartResult(true, "Оголошено мобілізацію на один Minecraft-день", war);
        } catch (SQLException ex) {
            return new StartResult(false, "Не вдалося записати війну: " + ex.getMessage(), null);
        }
    }

    public boolean finish(StateWar war, WarStatus status, String reason) {
        if (war == null || war.status() == WarStatus.ENDED) {
            return false;
        }
        war.finish(status, reason, System.currentTimeMillis());
        repository.saveWarAsync(war);
        return true;
    }

    public void ceasefireAll(String reason) {
        for (StateWar war : List.copyOf(wars.values())) {
            finish(war, WarStatus.CEASEFIRE, reason);
        }
    }

    public void ceasefireTarget(UUID targetId, String reason) {
        for (StateWar war : List.copyOf(wars.values())) {
            if (war.target().id().equals(targetId)) {
                finish(war, WarStatus.CEASEFIRE, reason);
            }
        }
    }

    public double readiness(StrategicState state) {
        StateEconomy economy = states.economy(state.id());
        if (economy == null) {
            return 0.0D;
        }
        int population = Math.max(1, states.population(state.id()));
        double level = state.developmentLevel() / 5.0D;
        double food = Math.min(1.0D, economy.amount(ResourceType.FOOD) / (population * 5.0D));
        double stability = economy.stability() / 100.0D;
        StateArmy army = states.army(state.id());
        double armyReadiness = army == null ? 0.0D : Math.min(1.0D, army.strength() / Math.max(4.0D, population * 0.4D));
        StateArsenal arsenal = states.arsenal(state.id());
        double weapons = arsenal == null ? 0.0D : Math.min(1.0D, (arsenal.amount(ArsenalItem.SMALL_ARMS) + arsenal.amount(ArsenalItem.STRIKE_DRONE) * 2.0D + arsenal.amount(ArsenalItem.BALLISTIC_MISSILE) * 3.0D) / Math.max(4.0D, population * 0.5D));
        return Math.clamp(level * 0.20D + food * 0.20D + stability * 0.20D + armyReadiness * 0.20D + weapons * 0.20D, 0.0D, 1.0D);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (StateWar war : List.copyOf(wars.values())) {
            if (war.status() == WarStatus.PREPARING) {
                mobilize(war, now);
            } else if (war.status() == WarStatus.ACTIVE && war.target().type() == CombatPrincipalType.PLAYER) {
                waves(war, now);
            }
        }
    }

    private void mobilize(StateWar war, long now) {
        StrategicState attacker = states.byId(war.attackerStateId());
        if (attacker == null) {
            finish(war, WarStatus.ENDED, "ATTACKER_DESTROYED");
            return;
        }
        recruit(attacker, force(attacker.developmentLevel()));
        if (war.target().type() == CombatPrincipalType.STRATEGIC_STATE) {
            StrategicState defender = states.byId(war.target().id());
            if (defender != null) {
                recruit(defender, force(defender.developmentLevel()));
            }
        }
        if (now - war.phaseStartedAt() < mobilizationMillis) {
            return;
        }
        war.status(WarStatus.ACTIVE, now);
        repository.saveWarAsync(war);
        WaveCampaign campaign = campaigns.get(war.id());
        if (campaign != null) {
            StateArmy army = states.army(attacker.id());
            campaign.startingStrength = army == null ? 1 : Math.max(1, army.strength());
        }
    }

    private void recruit(StrategicState state, int target) {
        StateArmy army = states.army(state.id());
        int strength = army == null ? 0 : army.strength();
        int perTick = Math.max(1, plugin.getConfig().getInt("strategic-states.war.recruits-per-second", 2));
        for (int index = strength; index < target && index < strength + perTick; index++) {
            if (!states.mobilizeSoldier(state)) {
                break;
            }
        }
    }

    private int force(int level) {
        return switch (level) {
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 30;
            case 4 -> 40;
            default -> 100;
        };
    }

    private void waves(StateWar war, long now) {
        StrategicState attacker = states.byId(war.attackerStateId());
        StateArmy army = attacker == null ? null : states.army(attacker.id());
        if (attacker == null || army == null) {
            return;
        }
        WaveCampaign campaign = campaigns.computeIfAbsent(war.id(), ignored -> new WaveCampaign(ThreadLocalRandom.current().nextInt(3, 8), Math.max(1, army.strength())));
        if (now - war.phaseStartedAt() < 30000L || army.strength() > Math.max(1, (int) Math.floor(campaign.startingStrength * 0.2D))) {
            return;
        }
        campaign.current++;
        if (campaign.current > campaign.total) {
            finish(war, WarStatus.ENDED, "PLAYER_REPELLED_" + campaign.total + "_WAVES");
            campaigns.remove(war.id());
            return;
        }
        war.status(WarStatus.PREPARING, now);
        repository.saveWarAsync(war);
    }
}
