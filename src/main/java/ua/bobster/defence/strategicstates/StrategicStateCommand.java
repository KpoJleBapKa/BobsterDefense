package ua.bobster.defence.strategicstates;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.combat.StateThreat;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarManager;
import ua.bobster.defence.strategicstates.capture.CaptureSector;
import ua.bobster.defence.strategicstates.army.StateArmy;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.StateArsenal;
import ua.bobster.defence.strategicstates.diplomacy.StateRelation;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class StrategicStateCommand {

    private final BobsterDefence plugin;
    private final StrategicStateManager states;

    public StrategicStateCommand(BobsterDefence plugin, StrategicStateManager states) {
        this.plugin = plugin;
        this.states = states;
    }

    public void execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "info" -> info(sender, args, false);
            case "debug" -> info(sender, args, true);
            case "core" -> core(sender, args);
            case "attacks" -> attacks(sender, args);
            case "guild" -> guild(sender, args);
            case "war" -> war(sender, args);
            case "arsenal" -> arsenal(sender, args);
            case "forcelevel" -> forceLevel(sender, args);
            case "addresource" -> addResource(sender, args);
            case "destroy" -> destroy(sender, args);
            case "stop" -> setPaused(sender, args, true);
            case "start" -> setPaused(sender, args, false);
            case "reload" -> reload(sender);
            default -> usage(sender);
        }
    }

    public List<String> tab(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return matches(args.length == 0 ? "" : args[0], List.of("list", "info", "debug", "core", "attacks", "guild", "war", "arsenal", "forcelevel", "addresource", "stop", "start", "destroy", "reload"));
        }
        if (args[0].equalsIgnoreCase("core") && args.length == 2) {
            return matches(args[1], List.of("give"));
        }
        if (args[0].equalsIgnoreCase("attacks") && args.length == 2) {
            return matches(args[1], List.of("on", "off", "status"));
        }
        if (args[0].equalsIgnoreCase("guild") && args.length == 2) {
            return matches(args[1], List.of("exclude", "include", "excluded"));
        }
        if (args[0].equalsIgnoreCase("war") && args.length == 2) {
            return matches(args[1], List.of("list", "start", "stop"));
        }
        if (args[0].equalsIgnoreCase("war") && args.length == 3 && args[1].equalsIgnoreCase("start")) {
            return matches(args[2], states.all().stream().map(StrategicState::shortName).toList());
        }
        if (args[0].equalsIgnoreCase("war") && args.length == 4 && args[1].equalsIgnoreCase("start")) {
            return matches(args[3], states.territories().all().stream().map(territory -> territory.name()).toList());
        }
        if (args[0].equalsIgnoreCase("war") && args.length == 3 && args[1].equalsIgnoreCase("stop")) {
            return matches(args[2], states.wars().stream().map(war -> war.id().toString()).toList());
        }
        if (args[0].equalsIgnoreCase("arsenal") && args.length == 2) {
            return matches(args[1], states.all().stream().map(StrategicState::shortName).toList());
        }
        if (args[0].equalsIgnoreCase("arsenal") && args.length == 3) {
            return matches(args[2], Arrays.stream(ArsenalItem.values()).map(Enum::name).toList());
        }
        if ((args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("debug")
                || args[0].equalsIgnoreCase("forcelevel") || args[0].equalsIgnoreCase("addresource")
                || args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("start")
                || args[0].equalsIgnoreCase("destroy")) && args.length == 2) {
            List<String> names = states.all().stream().map(StrategicState::shortName).toList();
            return matches(args[1], names);
        }
        if (args[0].equalsIgnoreCase("addresource") && args.length == 3) {
            return matches(args[2], Arrays.stream(ResourceType.values()).map(Enum::name).toList());
        }
        if (args[0].equalsIgnoreCase("guild") && args.length == 3) {
            List<String> owners = states.territories().all().stream()
                    .filter(territory -> territory.ownerName() != null)
                    .map(territory -> territory.ownerName())
                    .distinct()
                    .toList();
            return matches(args[2], owners);
        }
        return List.of();
    }

    private void list(CommandSender sender) {
        send(sender, "<gold><bold>Strategic States</bold></gold> <gray>(" + states.active().size() + "/" + states.config().maxStates() + ")");
        if (states.all().isEmpty()) {
            send(sender, "<gray>Держав ще немає.");
            return;
        }
        for (StrategicState state : states.all()) {
            send(sender, "<gray>- <" + colorTag(state) + ">" + state.name() + "</" + colorTag(state) + "> <dark_gray>[" + state.shortName() + "]</dark_gray> <gray>Lv." + state.developmentLevel() + " | населення " + states.population(state.id()) + " | " + state.status());
        }
    }

    private void info(CommandSender sender, String[] args, boolean debug) {
        if (debug && !sender.hasPermission("bobsterdefence.states.debug")) {
            denied(sender);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        StrategicState state = states.find(join(args, 1));
        if (state == null) {
            send(sender, "<red>Державу не знайдено.");
            return;
        }
        send(sender, "<gold><bold>" + state.name() + "</bold></gold> <dark_gray>[" + state.shortName() + "]</dark_gray>");
        send(sender, "<gray>UUID: <white>" + state.id());
        send(sender, "<gray>Статус: <white>" + state.status() + "</white> | Рівень: <white>" + state.developmentLevel());
        send(sender, "<gray>Населення: <white>" + states.population(state.id()) + "</white> | Фізично: <white>" + states.physicalPopulation(state.id()));
        StateEconomy economy = states.economy(state.id());
        if (economy != null) {
            send(sender, "<gray>Їжа: <white>" + rounded(economy.amount(ResourceType.FOOD)) + "</white> | Житло: <white>" + states.population(state.id()) + "/" + economy.housingCapacity() + "</white> | Стабільність: <white>" + rounded(economy.stability()));
        }
        send(sender, "<gray>Core: <white>" + state.core().world() + " " + (int) state.core().x() + " " + (int) state.core().y() + " " + (int) state.core().z());
        if (debug) {
            send(sender, "<gray>Territory: <white>" + state.territoryId());
            send(sender, "<gray>AI: <white>DEVELOP</white> | Attacks: <white>" + (states.attacksEnabled() ? "ON" : "OFF"));
            send(sender, "<gray>Simulation: <white>" + (states.physicalPopulation(state.id()) > 0 ? "PHYSICAL" : "VIRTUAL"));
            send(sender, "<gray>Professions: <white>" + states.professions(state.id()));
            send(sender, "<gray>Sectors: <white>" + states.sectors(state.territoryId()).size() + " own territory | " + states.controlledSectors(state.id()) + " controlled globally");
            send(sender, "<gray>War readiness: <white>" + rounded(states.warReadiness(state) * 100.0D) + "%");
            StateArmy army = states.army(state.id());
            if (army != null) {
                send(sender, "<gray>Army: <white>" + army.mode() + " | strength " + army.strength() + " | war " + (army.warId() == null ? "none" : army.warId()) + " | position " + rounded(army.position().x()) + ":" + rounded(army.position().z()));
            }
            StateArsenal arsenal = states.arsenal(state.id());
            send(sender, "<gray>Arsenal: <white>" + (arsenal == null ? "empty" : arsenal.amounts()));
            send(sender, "<gray>Personality: <white>A" + state.personality().aggression() + " E" + state.personality().economyFocus() + " T" + state.personality().technologyFocus() + " D" + state.personality().defenceFocus() + " X" + state.personality().expansionFocus() + " P" + state.personality().populationFocus());
            if (states.relations(state.id()).isEmpty()) {
                send(sender, "<gray>Diplomacy: <white>none");
            } else {
                for (StateRelation relation : states.relations(state.id())) {
                    send(sender, "<gray>Relation: <white>" + relation.target().type() + ":" + relation.target().id() + " | " + relation.status() + " | " + rounded(relation.score()));
                }
            }
            if (economy != null) {
                send(sender, "<gray>Resources: <white>" + economy.resources());
                send(sender, "<gray>Population progress: <white>" + rounded(economy.populationProgress()));
            }
            if (states.threats(state.id()).isEmpty()) {
                send(sender, "<gray>Threats: <white>none");
            } else {
                for (StateThreat threat : states.threats(state.id())) {
                    boolean protectedOwner = threat.attacker().type() == ua.bobster.defence.combat.CombatPrincipalType.PLAYER && states.isProtected(threat.attacker().id());
                    String response = states.attacksEnabled() && !protectedOwner ? "RETALIATION_ALLOWED" : "DEFENCE_ONLY";
                    send(sender, "<gray>Threat: <white>" + threat.attacker().type() + " " + threat.attacker().id() + " | " + rounded(threat.threat()) + " | B" + threat.ballisticHits() + " D" + threat.droneHits() + " | " + response);
                }
            }
            if (states.buildings(state.id()).isEmpty()) {
                send(sender, "<gray>Buildings: <white>none");
            } else {
                for (StateBuilding building : states.buildings(state.id())) {
                    String blocked = building.blockedReason() == null ? "" : " | " + building.blockedReason();
                    send(sender, "<gray>Building: <white>" + building.type() + " | " + building.status() + " | virtual " + rounded(building.progress() * 100.0D) + "% | blocks " + building.materializedBlocks() + " | integrity " + rounded(building.integrity() * 100.0D) + "%" + blocked);
                }
            }
        }
    }

    private void core(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.create")) {
            denied(sender);
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("give") || !(sender instanceof Player player)) {
            usage(sender);
            return;
        }
        ItemStack core = states.coreItem().create();
        player.getInventory().addItem(core).values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        send(sender, "<green>Strategic State Core видано.");
    }

    private void attacks(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.attacks")) {
            denied(sender);
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            send(sender, "<gray>Наступальні операції: " + (states.attacksEnabled() ? "<green>ON" : "<red>OFF"));
            return;
        }
        boolean enabled;
        if (args[1].equalsIgnoreCase("on")) {
            enabled = true;
        } else if (args[1].equalsIgnoreCase("off")) {
            enabled = false;
        } else {
            usage(sender);
            return;
        }
        if (states.attacksEnabled(enabled)) {
            send(sender, "<green>Наступальні операції: " + (enabled ? "ON" : "OFF"));
        } else {
            send(sender, "<red>Не вдалося зберегти налаштування.");
        }
    }

    private void guild(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.guildprotection")) {
            denied(sender);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("excluded")) {
            send(sender, "<gold>Protected territory owners:</gold>");
            if (states.protectedOwners().isEmpty()) {
                send(sender, "<gray>Список порожній.");
            }
            for (UUID owner : states.protectedOwners()) {
                send(sender, "<gray>- <white>" + states.ownerName(owner) + "</white> <dark_gray>" + owner);
            }
            return;
        }
        if (args.length < 3) {
            usage(sender);
            return;
        }
        UUID owner = states.resolvePlayerOwner(join(args, 2));
        if (owner == null) {
            send(sender, "<red>Гравця або його територію не знайдено.");
            return;
        }
        boolean changed;
        if (args[1].equalsIgnoreCase("exclude")) {
            changed = states.protect(owner);
        } else if (args[1].equalsIgnoreCase("include")) {
            changed = states.unprotect(owner);
        } else {
            usage(sender);
            return;
        }
        send(sender, changed ? "<green>Зміни збережено для " + states.ownerName(owner) : "<yellow>Стан не змінився.");
    }

    private void war(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            send(sender, "<gold>Війни Strategic States:</gold>");
            if (states.wars().isEmpty()) {
                send(sender, "<gray>Записів немає.");
            }
            for (StateWar war : states.wars()) {
                send(sender, "<gray>- <white>" + war.id() + "</white> | " + war.status() + " | " + war.attackerStateId() + " -> " + war.target().type() + ":" + war.target().id() + " | territory " + war.territoryId());
                CaptureSector frontline = states.warFrontline(war);
                if (frontline != null) {
                    send(sender, "  <dark_gray>front " + frontline.gridX() + ":" + frontline.gridZ() + " | " + frontline.status() + " | " + rounded(frontline.progress() * 100.0D) + "%");
                }
            }
            return;
        }
        if (args[1].equalsIgnoreCase("start") && args.length >= 4) {
            StrategicState attacker = states.find(args[2]);
            WarManager.StartResult result = states.startWar(attacker, args[3]);
            send(sender, result.success() ? "<green>" + result.message() + ": " + result.war().id() : "<red>" + result.message());
            return;
        }
        if (args[1].equalsIgnoreCase("stop") && args.length >= 3) {
            try {
                send(sender, states.stopWar(UUID.fromString(args[2])) ? "<green>Війну завершено." : "<yellow>Активну війну не знайдено.");
            } catch (IllegalArgumentException ex) {
                send(sender, "<red>Некоректний UUID війни.");
            }
            return;
        }
        usage(sender);
    }

    private void arsenal(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        StrategicState state = states.find(args[1]);
        if (state == null) {
            send(sender, "<red>Державу не знайдено.");
            return;
        }
        if (args.length == 2) {
            StateArsenal arsenal = states.arsenal(state.id());
            send(sender, "<gold>Арсенал " + state.name() + ":</gold> <white>" + (arsenal == null ? "empty" : arsenal.amounts()));
            if (arsenal != null) {
                for (ArsenalItem item : ArsenalItem.values()) {
                    if (arsenal.progress(item) > 0.0D) {
                        send(sender, "<gray>- " + item + " production: <white>" + rounded(arsenal.progress(item) * 100.0D) + "%");
                    }
                }
            }
            return;
        }
        if (args.length >= 4) {
            try {
                ArsenalItem item = ArsenalItem.valueOf(args[2].toUpperCase(Locale.ROOT));
                int amount = Integer.parseInt(args[3]);
                send(sender, states.addArsenal(state, item, amount) ? "<green>Арсенал змінено." : "<red>Не вдалося змінити арсенал.");
            } catch (IllegalArgumentException ex) {
                send(sender, "<red>Некоректний тип або кількість.");
            }
            return;
        }
        usage(sender);
    }

    private void forceLevel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        if (args.length < 3) {
            usage(sender);
            return;
        }
        StrategicState state = states.find(args[1]);
        if (state == null) {
            send(sender, "<red>Державу не знайдено.");
            return;
        }
        try {
            state.developmentLevel(Integer.parseInt(args[2]));
            states.save(state);
            send(sender, "<green>Рівень " + state.name() + ": " + state.developmentLevel());
        } catch (NumberFormatException ex) {
            send(sender, "<red>Рівень має бути числом 1-5.");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        plugin.reloadAll();
        send(sender, "<green>Конфіг Strategic States перечитано.");
    }

    private void addResource(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        if (args.length < 4) {
            usage(sender);
            return;
        }
        StrategicState state = states.find(args[1]);
        if (state == null) {
            send(sender, "<red>Державу не знайдено.");
            return;
        }
        try {
            ResourceType type = ResourceType.valueOf(args[2].toUpperCase(Locale.ROOT));
            double amount = Double.parseDouble(args[3]);
            send(sender, states.addResource(state, type, amount) ? "<green>Ресурс змінено." : "<red>Не вдалося змінити ресурс.");
        } catch (IllegalArgumentException ex) {
            send(sender, "<red>Невідомий ресурс або некоректна кількість.");
        }
    }

    private void destroy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        StrategicState state = states.find(join(args, 1));
        if (state == null) {
            send(sender, "<red>Державу не знайдено.");
            return;
        }
        send(sender, states.destroy(state) ? "<green>Державу знищено." : "<yellow>Держава вже знищена.");
    }

    private void setPaused(CommandSender sender, String[] args, boolean paused) {
        if (!sender.hasPermission("bobsterdefence.states.admin")) {
            denied(sender);
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        StrategicState state = states.find(join(args, 1));
        if (state == null) {
            send(sender, "<red>Державу не знайдено.");
            return;
        }
        boolean changed = paused ? states.pause(state) : states.resume(state);
        if (!changed) {
            send(sender, paused ? "<yellow>Держава вже зупинена або знищена." : "<yellow>Держава не перебуває у стані PAUSED.");
            return;
        }
        send(sender, paused ? "<green>Державу зупинено та віртуалізовано. Стан PAUSED збережено в БД." : "<green>Державу запущено знову.");
    }

    private void usage(CommandSender sender) {
        send(sender, "<yellow>/bobster states <list|info|debug|core|attacks|guild|war|arsenal|forcelevel|addresource|stop|start|destroy|reload>");
    }

    private void denied(CommandSender sender) {
        send(sender, "<red>Недостатньо прав.");
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(MessageUtil.parse(message));
    }

    private String colorTag(StrategicState state) {
        return state.color().name().toLowerCase(Locale.ROOT);
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    private List<String> matches(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                result.add(value);
            }
        }
        return result;
    }

    private String rounded(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
