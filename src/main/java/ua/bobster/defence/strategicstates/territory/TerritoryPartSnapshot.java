package ua.bobster.defence.strategicstates.territory;

import java.util.List;

public record TerritoryPartSnapshot(List<TerritoryPointSnapshot> outer, List<List<TerritoryPointSnapshot>> holes) {

    public TerritoryPartSnapshot {
        outer = List.copyOf(outer);
        holes = holes.stream().map(List::copyOf).toList();
    }
}
