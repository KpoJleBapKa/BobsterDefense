package ua.bobster.defence.strategicstates.construction;

import java.util.List;

public record StructureTemplatePlan(String name, int sizeX, int sizeY, int sizeZ, List<StructureBlockPlacement> blocks) {

    public StructureTemplatePlan {
        blocks = List.copyOf(blocks);
    }
}
