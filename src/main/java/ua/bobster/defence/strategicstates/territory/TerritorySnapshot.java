package ua.bobster.defence.strategicstates.territory;

import java.util.List;
import java.util.UUID;

public record TerritorySnapshot(UUID id, String name, UUID ownerId, String ownerName, TerritoryOwnerKind ownerKind, String world, List<TerritoryPartSnapshot> parts) {

    public TerritorySnapshot {
        parts = List.copyOf(parts);
    }
}
