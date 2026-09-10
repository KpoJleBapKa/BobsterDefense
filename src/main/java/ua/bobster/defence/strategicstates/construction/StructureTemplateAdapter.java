package ua.bobster.defence.strategicstates.construction;

import org.bukkit.Location;

public interface StructureTemplateAdapter {

    record TemplateInfo(String name, int sizeX, int sizeY, int sizeZ) {
    }

    record MaterializationResult(boolean success, boolean waiting, String message) {
    }

    TemplateInfo inspect(String name);

    StructureTemplatePlan plan(String name);

    MaterializationResult validate(StateBuilding building, StructureTemplatePlan plan, int completedBlocks);

    int recoverCompletedBlocks(StateBuilding building, StructureTemplatePlan plan, int completedBlocks);

    Location place(StateBuilding building, StructureBlockPlacement placement);
}
