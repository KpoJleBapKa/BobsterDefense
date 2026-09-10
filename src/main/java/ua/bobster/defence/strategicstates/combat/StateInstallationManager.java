package ua.bobster.defence.strategicstates.combat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.aa.AaLauncher;
import ua.bobster.defence.aa.AaTier;
import ua.bobster.defence.ballistic.BallisticLauncher;
import ua.bobster.defence.ballistic.LauncherTier;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.model.StrategicState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StateInstallationManager {

    private final BobsterDefence plugin;

    public StateInstallationManager(BobsterDefence plugin) {
        this.plugin = plugin;
    }

    public List<AaLauncher> airDefence(StrategicState state, StateBuilding building) {
        List<AaTier> tiers = plugin.aa().tiers().stream().sorted(Comparator.comparingInt(AaTier::level)).limit(3).toList();
        List<AaLauncher> result = new ArrayList<>();
        for (int index = 0; index < tiers.size(); index++) {
            Location location = roof(building, index + 1, tiers.size() + 1);
            Block block = launcherBlock(location);
            if (block == null) {
                continue;
            }
            AaLauncher launcher = plugin.aa().wrap(block);
            launcher.tierId(tiers.get(index).id());
            launcher.owner(state.id());
            plugin.aa().refreshVisuals(launcher);
            result.add(launcher);
        }
        return List.copyOf(result);
    }

    public List<BallisticLauncher> missileLaunchers(StrategicState state, StateBuilding building) {
        LauncherTier tier = plugin.ballistic().tiers().stream().max(Comparator.comparingDouble(LauncherTier::range).thenComparingDouble(LauncherTier::explosionPower)).orElse(null);
        if (tier == null) {
            return List.of();
        }
        int amount = state.developmentLevel() >= 5 ? 4 : 2;
        List<BallisticLauncher> result = new ArrayList<>();
        for (int index = 0; index < amount; index++) {
            Location location = roof(building, index + 1, amount + 1);
            Block block = launcherBlock(location);
            if (block == null) {
                continue;
            }
            BallisticLauncher launcher = plugin.ballistic().wrap(block);
            launcher.tierId(tier.id());
            launcher.owner(state.id());
            plugin.ballistic().refreshDisplay(launcher);
            result.add(launcher);
        }
        return List.copyOf(result);
    }

    private Location roof(StateBuilding building, int numerator, int denominator) {
        Location origin = building.origin().location();
        double x = Math.clamp(building.sizeX() * numerator / (double) denominator, 1.5D, building.sizeX() - 1.5D);
        return origin.add(x, building.sizeY() + 1.0D, building.sizeZ() / 2.0D);
    }

    private Block launcherBlock(Location location) {
        if (location == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }
        Block block = location.getBlock();
        if (block.getType() != Material.DISPENSER && !block.isPassable()) {
            return null;
        }
        if (block.getType() != Material.DISPENSER) {
            block.setType(Material.DISPENSER, false);
        }
        return block;
    }
}
