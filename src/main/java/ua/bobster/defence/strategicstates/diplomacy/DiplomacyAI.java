package ua.bobster.defence.strategicstates.diplomacy;

import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.StrategicStateManager;
import ua.bobster.defence.strategicstates.army.StateArmy;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.StateArsenal;
import ua.bobster.defence.strategicstates.combat.StateThreat;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.territory.TerritoryPartSnapshot;
import ua.bobster.defence.strategicstates.territory.TerritoryPointSnapshot;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DiplomacyAI {

    private record RelationKey(UUID stateId, CombatPrincipal target) {
    }

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final StrategicStateManager states;
    private final Map<RelationKey, StateRelation> relations = new LinkedHashMap<>();
    private BukkitTask task;
    private long intervalTicks;
    private double hostileThreshold;
    private double friendlyThreshold;
    private double alliedThreshold;
    private double minimumAttackScore;

    public DiplomacyAI(BobsterDefence plugin, StateRepository repository, StrategicStateManager states) {
        this.plugin = plugin;
        this.repository = repository;
        this.states = states;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(200L, plugin.getConfig().getLong("strategic-states.diplomacy.tick-interval-ticks", 1200L));
        hostileThreshold = Math.clamp(plugin.getConfig().getDouble("strategic-states.diplomacy.hostile-threshold", -25.0D), -100.0D, 0.0D);
        friendlyThreshold = Math.clamp(plugin.getConfig().getDouble("strategic-states.diplomacy.friendly-threshold", 30.0D), 0.0D, 100.0D);
        alliedThreshold = Math.clamp(plugin.getConfig().getDouble("strategic-states.diplomacy.allied-threshold", 60.0D), friendlyThreshold, 100.0D);
        minimumAttackScore = plugin.getConfig().getDouble("strategic-states.diplomacy.minimum-attack-score", 55.0D);
    }

    public void load(Collection<StateRelation> loaded) {
        relations.clear();
        for (StateRelation relation : loaded) {
            relations.put(new RelationKey(relation.stateId(), relation.target()), relation);
        }
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

    public Collection<StateRelation> relations(UUID stateId) {
        return relations.values().stream().filter(relation -> relation.stateId().equals(stateId)).toList();
    }

    public boolean allied(UUID first, UUID second) {
        if (activeWar(first, CombatPrincipal.state(second)) || activeWar(second, CombatPrincipal.state(first))) {
            return false;
        }
        StateRelation relation = relations.get(new RelationKey(first, CombatPrincipal.state(second)));
        return relation != null && relation.status() == DiplomaticStatus.ALLIED;
    }

    public boolean alliedPlayer(UUID stateId, UUID playerId) {
        StateRelation relation = relations.get(new RelationKey(stateId, CombatPrincipal.player(playerId)));
        return relation != null && relation.status() == DiplomaticStatus.ALLIED;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        List<TerritorySnapshot> territories = states.territories().all();
        for (StrategicState state : states.active()) {
            evaluate(state, territories, now);
        }
        if (!states.attacksEnabled()) {
            return;
        }
        for (StrategicState state : states.active()) {
            selectWar(state, territories);
        }
    }

    private void evaluate(StrategicState state, List<TerritorySnapshot> territories, long now) {
        Map<CombatPrincipal, TerritorySnapshot> owners = new HashMap<>();
        for (TerritorySnapshot territory : territories) {
            CombatPrincipal target = principal(territory);
            if (target != null && !target.equals(CombatPrincipal.state(state.id()))) {
                owners.putIfAbsent(target, territory);
            }
        }
        for (CombatPrincipal target : owners.keySet()) {
            RelationKey key = new RelationKey(state.id(), target);
            StateRelation relation = relations.get(key);
            double desired = desiredScore(state, target);
            boolean war = activeWar(state.id(), target);
            double score = war ? -100.0D : relation == null ? desired : relation.score() * 0.70D + desired * 0.30D;
            DiplomaticStatus status = war ? DiplomaticStatus.WAR : status(score);
            if (relation == null) {
                relation = new StateRelation(state.id(), target, score, status, now);
                relations.put(key, relation);
            } else {
                relation.update(score, status, now);
            }
            repository.saveRelationAsync(relation);
        }
    }

    private double desiredScore(StrategicState state, CombatPrincipal target) {
        double score = 40.0D - state.personality().aggression() * 0.50D - state.personality().expansionFocus() * 0.35D + state.personality().defenceFocus() * 0.10D;
        if (target.type() == CombatPrincipalType.STRATEGIC_STATE) {
            StrategicState other = states.byId(target.id());
            if (other == null) {
                return 0.0D;
            }
            score += 15.0D - Math.abs(state.personality().economyFocus() - other.personality().economyFocus()) * 0.20D;
        }
        StateThreat threat = states.threats(state.id()).stream().filter(candidate -> candidate.attacker().equals(target)).findFirst().orElse(null);
        if (threat != null) {
            score -= Math.min(100.0D, threat.threat() * 2.0D);
        }
        return Math.clamp(score, -100.0D, 100.0D);
    }

    private DiplomaticStatus status(double score) {
        if (score >= alliedThreshold) {
            return DiplomaticStatus.ALLIED;
        }
        if (score >= friendlyThreshold) {
            return DiplomaticStatus.FRIENDLY;
        }
        return score <= hostileThreshold ? DiplomaticStatus.HOSTILE : DiplomaticStatus.NEUTRAL;
    }

    private void selectWar(StrategicState state, List<TerritorySnapshot> territories) {
        if (states.wars().stream().anyMatch(war -> war.status() == WarStatus.ACTIVE && war.attackerStateId().equals(state.id()))) {
            return;
        }
        TerritorySnapshot best = null;
        double bestScore = minimumAttackScore;
        for (TerritorySnapshot territory : territories) {
            CombatPrincipal target = principal(territory);
            if (target == null || target.equals(CombatPrincipal.state(state.id())) || target.type() == CombatPrincipalType.PLAYER && states.isProtected(target.id())) {
                continue;
            }
            StateRelation relation = relations.get(new RelationKey(state.id(), target));
            if (relation == null || relation.status() != DiplomaticStatus.HOSTILE) {
                continue;
            }
            double score = attackScore(state, territory, target, relation);
            if (score > bestScore) {
                bestScore = score;
                best = territory;
            }
        }
        if (best != null) {
            states.startWar(state, best.name());
        }
    }

    private double attackScore(StrategicState state, TerritorySnapshot territory, CombatPrincipal target, StateRelation relation) {
        double distance = distance(state, territory);
        double area = area(territory);
        double score = state.personality().aggression() * 0.35D + state.personality().expansionFocus() * 0.25D;
        score += states.warReadiness(state) * 35.0D;
        score += Math.min(15.0D, area / 5000.0D);
        score -= Math.min(30.0D, distance / 250.0D);
        score += Math.min(30.0D, Math.abs(relation.score()) * 0.30D);
        long previousWars = states.wars().stream().filter(war -> war.attackerStateId().equals(state.id()) && war.target().equals(target)).count();
        score -= previousWars * 8.0D;
        if (target.type() == CombatPrincipalType.PLAYER) {
            score -= plugin.getServer().getPlayer(target.id()) == null ? 0.0D : 5.0D;
        } else {
            StateArmy targetArmy = states.army(target.id());
            score -= targetArmy == null ? 0.0D : Math.min(25.0D, targetArmy.strength() * 1.5D);
            StateEconomy economy = states.economy(target.id());
            score += economy == null ? 0.0D : Math.min(15.0D, economy.resources().values().stream().mapToDouble(Double::doubleValue).sum() / 500.0D);
            StateArsenal arsenal = states.arsenal(target.id());
            if (arsenal != null) {
                score -= arsenal.amount(ArsenalItem.AIR_DEFENCE_MISSILE) * 1.5D;
            }
        }
        return score;
    }

    private boolean activeWar(UUID stateId, CombatPrincipal target) {
        for (StateWar war : states.wars()) {
            if (war.status() == WarStatus.ACTIVE && war.attackerStateId().equals(stateId) && war.target().equals(target)) {
                return true;
            }
            if (war.status() == WarStatus.ACTIVE && target.type() == CombatPrincipalType.STRATEGIC_STATE && war.attackerStateId().equals(target.id()) && war.target().type() == CombatPrincipalType.STRATEGIC_STATE && war.target().id().equals(stateId)) {
                return true;
            }
        }
        return false;
    }

    private CombatPrincipal principal(TerritorySnapshot territory) {
        if (territory.ownerId() == null) {
            return null;
        }
        if (territory.ownerKind() == TerritoryOwnerKind.PLAYER) {
            return CombatPrincipal.player(territory.ownerId());
        }
        return territory.ownerKind() == TerritoryOwnerKind.STRATEGIC_STATE ? CombatPrincipal.state(territory.ownerId()) : null;
    }

    private double distance(StrategicState state, TerritorySnapshot territory) {
        TerritoryPointSnapshot center = center(territory);
        if (center == null || !state.core().world().equals(territory.world())) {
            return Double.MAX_VALUE;
        }
        double dx = state.core().x() - center.x();
        double dz = state.core().z() - center.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private TerritoryPointSnapshot center(TerritorySnapshot territory) {
        double x = 0.0D;
        double z = 0.0D;
        int count = 0;
        for (TerritoryPartSnapshot part : territory.parts()) {
            for (TerritoryPointSnapshot point : part.outer()) {
                x += point.x();
                z += point.z();
                count++;
            }
        }
        return count == 0 ? null : new TerritoryPointSnapshot(x / count, z / count);
    }

    private double area(TerritorySnapshot territory) {
        double result = 0.0D;
        for (TerritoryPartSnapshot part : territory.parts()) {
            result += Math.abs(ringArea(part.outer()));
            for (List<TerritoryPointSnapshot> hole : part.holes()) {
                result -= Math.abs(ringArea(hole));
            }
        }
        return Math.max(0.0D, result);
    }

    private double ringArea(List<TerritoryPointSnapshot> points) {
        if (points.size() < 3) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int index = 0; index < points.size(); index++) {
            TerritoryPointSnapshot first = points.get(index);
            TerritoryPointSnapshot second = points.get((index + 1) % points.size());
            sum += first.x() * second.z() - second.x() * first.z();
        }
        return sum / 2.0D;
    }
}
