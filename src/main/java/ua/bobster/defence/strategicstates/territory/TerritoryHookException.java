package ua.bobster.defence.strategicstates.territory;

public class TerritoryHookException extends Exception {

    public TerritoryHookException(String message) {
        super(message);
    }

    public TerritoryHookException(String message, Throwable cause) {
        super(message, cause);
    }
}
