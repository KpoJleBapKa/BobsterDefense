package ua.bobster.defence.missile;

import org.bukkit.inventory.ItemStack;

public record MissilePayload(Kind kind, ItemStack potion) {

    public enum Kind {
        EXPLOSIVE,
        POTION,
        EMPTY
    }

    public MissilePayload {
        potion = potion == null ? null : potion.clone();
    }

    public static MissilePayload explosive() {
        return new MissilePayload(Kind.EXPLOSIVE, null);
    }

    public static MissilePayload empty() {
        return new MissilePayload(Kind.EMPTY, null);
    }

    public static MissilePayload potion(ItemStack potion) {
        return new MissilePayload(Kind.POTION, potion);
    }

    @Override
    public ItemStack potion() {
        return potion == null ? null : potion.clone();
    }
}
