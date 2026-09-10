package ua.bobster.defence.missile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MissilePayloadManager {

    private final BobsterDefence plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey potionKey;
    private final NamespacedKey loreKey;
    private double potionRadiusPerPower;
    private double potionMinimumIntensity;
    private int spentBurnTicks;
    private int spentSmokeTicks;

    public MissilePayloadManager(BobsterDefence plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "missile_payload_kind");
        this.potionKey = new NamespacedKey(plugin, "missile_payload_potion");
        this.loreKey = new NamespacedKey(plugin, "missile_payload_lore");
        reload();
    }

    public void reload() {
        potionRadiusPerPower = Math.max(0.5D, plugin.getConfig().getDouble("missile-payload.potion.radius-per-power", 3.0D));
        potionMinimumIntensity = Math.clamp(plugin.getConfig().getDouble("missile-payload.potion.minimum-intensity", 0.25D), 0.0D, 1.0D);
        spentBurnTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("missile-payload.empty.burn-seconds", 15.0D) * 20.0D));
        spentSmokeTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("missile-payload.empty.smoke-seconds", 60.0D) * 20.0D));
    }

    public MissilePayload read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return MissilePayload.explosive();
        }
        ItemMeta meta = item.getItemMeta();
        String raw = meta.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (raw == null) {
            return MissilePayload.explosive();
        }
        try {
            MissilePayload.Kind kind = MissilePayload.Kind.valueOf(raw);
            if (kind != MissilePayload.Kind.POTION) {
                return new MissilePayload(kind, null);
            }
            byte[] bytes = meta.getPersistentDataContainer().get(potionKey, PersistentDataType.BYTE_ARRAY);
            return bytes == null ? MissilePayload.empty() : MissilePayload.potion(ItemStack.deserializeBytes(bytes));
        } catch (RuntimeException ex) {
            return MissilePayload.empty();
        }
    }

    public ItemStack write(ItemStack item, MissilePayload payload) {
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta.getPersistentDataContainer().has(loreKey, PersistentDataType.BYTE) && meta.lore() != null && meta.lore().size() >= 2) {
            List<Component> trimmed = new ArrayList<>(meta.lore());
            trimmed.removeLast();
            trimmed.removeLast();
            meta.lore(trimmed);
        }
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, payload.kind().name());
        meta.getPersistentDataContainer().set(loreKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().remove(potionKey);
        ItemStack potion = payload.potion();
        if (payload.kind() == MissilePayload.Kind.POTION && potion != null) {
            potion.setAmount(1);
            meta.getPersistentDataContainer().set(potionKey, PersistentDataType.BYTE_ARRAY, potion.serializeAsBytes());
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(payloadLore(payload));
        meta.lore(lore);
        result.setItemMeta(meta);
        return result;
    }

    public String powerText(MissilePayload payload, double power) {
        return switch (payload.kind()) {
            case EXPLOSIVE -> String.valueOf(power);
            case POTION -> String.format(java.util.Locale.US, "зілля, %.1f блоків", potionRadius(power));
            case EMPTY -> "немає";
        };
    }

    public Component payloadLore(MissilePayload payload) {
        String value = switch (payload.kind()) {
            case EXPLOSIVE -> "<red>Боєголовка: <white>TNT";
            case EMPTY -> "<gray>Боєголовка: <white>порожня";
            case POTION -> "<light_purple>Боєголовка: <white>" + potionName(payload.potion());
        };
        return MessageUtil.parse(value).decoration(TextDecoration.ITALIC, false);
    }

    public void applyPotion(MissilePayload payload, Location location, double power) {
        ItemStack potion = payload.potion();
        if (location.getWorld() == null || potion == null || !(potion.getItemMeta() instanceof PotionMeta meta)) {
            return;
        }
        double radius = potionRadius(power);
        List<PotionEffect> effects = effects(meta);
        World world = location.getWorld();
        for (LivingEntity entity : world.getNearbyLivingEntities(location, radius)) {
            double distance = entity.getLocation().distance(location);
            double intensity = Math.max(potionMinimumIntensity, 1.0D - distance / radius);
            for (PotionEffect effect : effects) {
                int duration = effect.getType().isInstant() ? 1 : Math.max(1, (int) Math.round(effect.getDuration() * intensity));
                entity.addPotionEffect(new PotionEffect(effect.getType(), duration, effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon()), true);
            }
        }
        world.spawnParticle(Particle.WITCH, location, Math.max(40, (int) Math.round(radius * 18.0D)), radius * 0.35D, radius * 0.2D, radius * 0.35D, 0.04D, null, true);
        world.spawnParticle(Particle.INSTANT_EFFECT, location, Math.max(20, (int) Math.round(radius * 8.0D)), radius * 0.25D, radius * 0.15D, radius * 0.25D, 0.02D, null, true);
        world.playSound(location, Sound.ENTITY_SPLASH_POTION_BREAK, 4.0F, 0.8F);
    }

    public void leaveSpent(Trident trident, Location impact, Vector heading) {
        if (trident == null || !trident.isValid() || impact.getWorld() == null) {
            return;
        }
        Vector direction = heading == null || heading.lengthSquared() < 1.0E-6D ? new Vector(0, -1, 0) : heading.clone().normalize();
        Location planted = trident.getLocation();
        planted.setX(impact.getX() - direction.getX() * 0.25D);
        planted.setY(impact.getY() - direction.getY() * 0.25D);
        planted.setZ(impact.getZ() - direction.getZ() * 0.25D);
        trident.teleport(planted);
        trident.setVelocity(new Vector());
        trident.setGravity(false);
        trident.setInvulnerable(true);
        trident.setSilent(true);
        World world = impact.getWorld();
        world.playSound(impact, Sound.ITEM_TRIDENT_HIT_GROUND, 2.0F, 0.7F);
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!trident.isValid()) {
                    cancel();
                    return;
                }
                ticks++;
                if (ticks >= spentBurnTicks + spentSmokeTicks) {
                    trident.remove();
                    cancel();
                    return;
                }
                Location engine = trident.getLocation().subtract(direction.clone().multiply(0.85D));
                if (ticks <= spentBurnTicks) {
                    world.spawnParticle(Particle.FLAME, engine, 2, 0.05D, 0.05D, 0.05D, 0.01D, null, true);
                }
                world.spawnParticle(Particle.LARGE_SMOKE, engine, ticks <= spentBurnTicks ? 2 : 1, 0.08D, 0.08D, 0.08D, 0.005D, null, true);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private List<PotionEffect> effects(PotionMeta meta) {
        Map<org.bukkit.potion.PotionEffectType, PotionEffect> effects = new LinkedHashMap<>();
        if (meta.hasBasePotionType() && meta.getBasePotionType() != null) {
            for (PotionEffect effect : meta.getBasePotionType().getPotionEffects()) {
                effects.put(effect.getType(), effect);
            }
        }
        for (PotionEffect effect : meta.getCustomEffects()) {
            effects.put(effect.getType(), effect);
        }
        return new ArrayList<>(effects.values());
    }

    private String potionName(ItemStack potion) {
        if (potion == null || potion.getType() != Material.SPLASH_POTION) {
            return "невідоме зілля";
        }
        if (potion.hasItemMeta() && potion.getItemMeta().hasDisplayName()) {
            return MessageUtil.plain(potion.getItemMeta().displayName());
        }
        if (potion.getItemMeta() instanceof PotionMeta meta && meta.hasBasePotionType() && meta.getBasePotionType() != null) {
            return meta.getBasePotionType().getKey().getKey().replace('_', ' ');
        }
        return "вибухове зілля";
    }

    private double potionRadius(double power) {
        return Math.max(1.0D, power * potionRadiusPerPower);
    }
}
