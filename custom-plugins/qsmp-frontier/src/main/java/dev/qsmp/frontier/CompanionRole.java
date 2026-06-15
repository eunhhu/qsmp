package dev.qsmp.frontier;

import java.util.Locale;

enum CompanionRole {
    VANGUARD("Vanguard", "frontline damage and protection"),
    RANGER("Ranger", "backline arrows against your target"),
    MEDIC("Medic", "periodic healing for nearby allies"),
    GATHERER("Gatherer", "extra drops and outpost production");

    private final String display;
    private final String description;

    CompanionRole(String display, String description) {
        this.display = display;
        this.description = description;
    }

    String display() {
        return display;
    }

    String description() {
        return description;
    }

    static CompanionRole parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
