package dev.qsmp.frontier;

import java.util.Locale;

enum SurvivorStat {
    MIGHT("might", "Might"),
    ENDURANCE("endurance", "Endurance"),
    AGILITY("agility", "Agility"),
    WISDOM("wisdom", "Wisdom"),
    COMMAND("command", "Command");

    private final String key;
    private final String display;

    SurvivorStat(String key, String display) {
        this.key = key;
        this.display = display;
    }

    String key() {
        return key;
    }

    String display() {
        return display;
    }

    static SurvivorStat parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (SurvivorStat stat : values()) {
            if (stat.key.equals(normalized) || stat.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return stat;
            }
        }
        return null;
    }
}
