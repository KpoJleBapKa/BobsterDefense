package ua.bobster.defence.strategicstates.model;

public enum StateColor {
    RED("#e74c3c"),
    BLUE("#3498db"),
    GREEN("#2ecc71"),
    YELLOW("#f1c40f"),
    PURPLE("#9b59b6");

    private final String hex;

    StateColor(String hex) {
        this.hex = hex;
    }

    public String hex() {
        return hex;
    }
}
