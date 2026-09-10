package ua.bobster.defence.strategicstates;

import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenProfession;

import java.util.Map;

public class StateSimulationEngine {

    public void tick(StrategicState state, StateEconomy economy, CitizenManager citizens, StrategicStateConfig config, long now) {
        if (state.status() == StateStatus.DESTROYED || state.status() == StateStatus.PAUSED) {
            return;
        }
        if (now - economy.lastEconomyTick() >= config.economyTickSeconds() * 1000L) {
            economy(state, economy, citizens, now);
        }
        population(state, economy, citizens, config, now);
        state.lastSimulation(now);
    }

    private void economy(StrategicState state, StateEconomy economy, CitizenManager citizens, long now) {
        long elapsed = Math.max(0L, now - economy.lastEconomyTick());
        if (elapsed == 0L) {
            return;
        }
        double minutes = elapsed / 60_000.0D;
        Map<CitizenProfession, Integer> professions = citizens.professions(state.id());
        economy.add(ResourceType.FOOD, professions.getOrDefault(CitizenProfession.FARMER, 0) * 12.0D * minutes);
        economy.add(ResourceType.WOOD, professions.getOrDefault(CitizenProfession.LUMBERJACK, 0) * 8.0D * minutes);
        economy.add(ResourceType.STONE, professions.getOrDefault(CitizenProfession.MINER, 0) * 4.0D * minutes);
        economy.add(ResourceType.IRON, professions.getOrDefault(CitizenProfession.MINER, 0) * 1.3D * minutes);
        economy.add(ResourceType.COAL, professions.getOrDefault(CitizenProfession.MINER, 0) * minutes);
        economy.add(ResourceType.REDSTONE, professions.getOrDefault(CitizenProfession.MINER, 0) * 0.25D * minutes);
        int engineers = professions.getOrDefault(CitizenProfession.ENGINEER, 0);
        economy.add(ResourceType.GUNPOWDER, engineers * 0.4D * minutes);
        economy.add(ResourceType.FUEL, engineers * 0.5D * minutes);
        economy.add(ResourceType.ADVANCED_COMPONENTS, engineers * 0.2D * minutes);
        double consumption = citizens.livingPopulation(state.id()) * 0.5D * minutes;
        if (!economy.consume(ResourceType.FOOD, consumption)) {
            double available = economy.amount(ResourceType.FOOD);
            economy.consume(ResourceType.FOOD, available);
            economy.stability(economy.stability() - Math.max(1.0D, consumption - available));
        } else {
            economy.stability(economy.stability() + 0.15D * minutes);
        }
        economy.lastEconomyTick(now);
    }

    private void population(StrategicState state, StateEconomy economy, CitizenManager citizens, StrategicStateConfig config, long now) {
        long elapsed = Math.max(0L, now - economy.lastPopulationTick());
        if (elapsed < config.populationTickSeconds() * 1000L) {
            return;
        }
        double minutes = elapsed / 60_000.0D;
        int population = citizens.livingPopulation(state.id());
        if (economy.amount(ResourceType.FOOD) <= 0.01D) {
            int deaths = Math.min(population, Math.max(1, (int) Math.floor(minutes)));
            for (int index = 0; index < deaths; index++) {
                citizens.starveOne(state.id());
            }
            economy.stability(economy.stability() - 5.0D * minutes);
            economy.populationProgress(0.0D);
            economy.lastPopulationTick(now);
            return;
        }
        boolean canGrow = population < economy.housingCapacity()
                && population < config.maxPopulation()
                && economy.amount(ResourceType.FOOD) > population * 20.0D
                && economy.stability() >= 50.0D;
        if (canGrow) {
            double focus = state.personality().populationFocus() / 100.0D;
            double rate = 0.04D + focus * 0.06D + state.developmentLevel() * 0.01D;
            economy.populationProgress(economy.populationProgress() + rate * minutes);
            while (economy.populationProgress() >= 1.0D
                    && citizens.livingPopulation(state.id()) < economy.housingCapacity()
                    && citizens.livingPopulation(state.id()) < config.maxPopulation()
                    && economy.consume(ResourceType.FOOD, 20.0D)) {
                citizens.grow(state);
                economy.populationProgress(economy.populationProgress() - 1.0D);
            }
        } else {
            economy.populationProgress(Math.max(0.0D, economy.populationProgress() - 0.05D * minutes));
        }
        economy.lastPopulationTick(now);
    }
}
