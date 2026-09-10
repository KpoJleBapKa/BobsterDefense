package ua.bobster.defence.strategicstates.population;

import ua.bobster.defence.strategicstates.model.StatePosition;

import java.util.UUID;

public class StateCitizen {

    private final UUID id;
    private final UUID stateId;
    private CitizenProfession profession;
    private int age;
    private double combatExperience;
    private double health;
    private String homeBuilding;
    private String workBuilding;
    private String currentTask;
    private StatePosition position;
    private CitizenMode mode;
    private UUID entityId;

    public StateCitizen(UUID id, UUID stateId, CitizenProfession profession, int age, double combatExperience, double health, String homeBuilding, String workBuilding, String currentTask, StatePosition position, CitizenMode mode, UUID entityId) {
        this.id = id;
        this.stateId = stateId;
        this.profession = profession;
        this.age = age;
        this.combatExperience = combatExperience;
        this.health = health;
        this.homeBuilding = homeBuilding;
        this.workBuilding = workBuilding;
        this.currentTask = currentTask;
        this.position = position;
        this.mode = mode;
        this.entityId = entityId;
    }

    public UUID id() {
        return id;
    }

    public UUID stateId() {
        return stateId;
    }

    public CitizenProfession profession() {
        return profession;
    }

    public void profession(CitizenProfession profession) {
        this.profession = profession;
    }

    public int age() {
        return age;
    }

    public void age(int age) {
        this.age = Math.max(0, age);
    }

    public double combatExperience() {
        return combatExperience;
    }

    public void combatExperience(double combatExperience) {
        this.combatExperience = Math.max(0.0D, combatExperience);
    }

    public double health() {
        return health;
    }

    public void health(double health) {
        this.health = Math.max(0.0D, health);
    }

    public String homeBuilding() {
        return homeBuilding;
    }

    public void homeBuilding(String homeBuilding) {
        this.homeBuilding = homeBuilding;
    }

    public String workBuilding() {
        return workBuilding;
    }

    public void workBuilding(String workBuilding) {
        this.workBuilding = workBuilding;
    }

    public String currentTask() {
        return currentTask;
    }

    public void currentTask(String currentTask) {
        this.currentTask = currentTask;
    }

    public StatePosition position() {
        return position;
    }

    public void position(StatePosition position) {
        this.position = position;
    }

    public CitizenMode mode() {
        return mode;
    }

    public void mode(CitizenMode mode) {
        this.mode = mode;
    }

    public UUID entityId() {
        return entityId;
    }

    public void entityId(UUID entityId) {
        this.entityId = entityId;
    }
}
