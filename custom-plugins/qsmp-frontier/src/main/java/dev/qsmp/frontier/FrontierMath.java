package dev.qsmp.frontier;

final class FrontierMath {
    private static final int ANVIL_HARD_MAXIMUM_REPAIR_COST = 38;
    private static final double MINING_EFFICIENCY_ATTRIBUTE_SCALE = 5.0;

    private FrontierMath() {
    }

    static int activePlayers(int players) {
        return Math.max(1, players);
    }

    static int waveSize(int players, int wave, int base, int perPlayer) {
        return Math.max(1, base)
                + activePlayers(players) * Math.max(0, perPlayer)
                + Math.max(0, wave - 1) * 2;
    }

    static double bossHealth(int players, double base, double perPlayer) {
        return Math.max(1.0, base)
                + activePlayers(players) * Math.max(0.0, perPlayer);
    }

    static double sentinelHealth(int players, double base, double perPlayer) {
        return Math.max(1.0, base)
                + activePlayers(players) * Math.max(0.0, perPlayer);
    }

    static double gatherMultiplier(int companionLevel) {
        int level = Math.max(1, companionLevel);
        return Math.min(2.5, 1.25 + level * 0.04);
    }

    static int survivorNextXp(int level, int base, int growth) {
        int safeLevel = Math.max(1, level);
        return Math.max(1, base + (safeLevel - 1) * Math.max(0, growth));
    }

    static int legacyNextXp(int level, int base, int growth) {
        int safeLevel = Math.max(1, level);
        return Math.max(1, base + (safeLevel - 1) * Math.max(0, growth));
    }

    static int mergedEnchantLevel(int leftLevel, int rightLevel, int maximumLevel) {
        int maximum = Math.max(1, maximumLevel);
        if (leftLevel <= 0) {
            return Math.min(Math.max(0, rightLevel), maximum);
        }
        if (rightLevel <= 0) {
            return Math.min(leftLevel, maximum);
        }
        int merged = leftLevel == rightLevel
                ? leftLevel + 1
                : Math.max(leftLevel, rightLevel);
        return Math.min(merged, maximum);
    }

    static int enchantCap(
            int vanillaMaxLevel,
            boolean configuredSingleLevel,
            boolean highCap,
            int globalMaxLevel,
            int sideMaxLevel) {
        int globalMaximum = Math.max(1, globalMaxLevel);
        if (configuredSingleLevel || vanillaMaxLevel <= 1) {
            return 1;
        }
        if (highCap) {
            return globalMaximum;
        }
        return Math.min(globalMaximum, Math.max(1, sideMaxLevel));
    }

    static boolean hasAnvilOperation(boolean itemChanged, boolean vanillaOperation) {
        return itemChanged || vanillaOperation;
    }

    static int anvilMaximumRepairCost(int configuredMaximum) {
        return Math.max(1, Math.min(ANVIL_HARD_MAXIMUM_REPAIR_COST, configuredMaximum));
    }

    static int boundedAnvilCost(
            int vanillaCost,
            int frontierCost,
            int maximumCost,
            boolean hasOperation) {
        if (!hasOperation) {
            return 0;
        }
        int cost = Math.max(vanillaCost, frontierCost);
        if (cost <= 0) {
            return 0;
        }
        return Math.min(cost, anvilMaximumRepairCost(maximumCost));
    }

    static int anvilOutputRepairPenalty() {
        return 0;
    }

    static double legacyBonus(
            int level,
            int refine,
            int awakened,
            double perLevel,
            double perRefine,
            double awakenedBonus) {
        return Math.max(0, level - 1) * Math.max(0.0, perLevel)
                + Math.max(0, refine) * Math.max(0.0, perRefine)
                + Math.max(0, awakened) * Math.max(0.0, awakenedBonus);
    }

    static double legacyMiningEfficiencyBonus(double miningSpeedBonus) {
        return Math.max(0.0, miningSpeedBonus) * MINING_EFFICIENCY_ATTRIBUTE_SCALE;
    }

    static boolean withinWindow(long now, long startedAt, long durationMs) {
        return startedAt > 0L && now >= startedAt && now - startedAt <= durationMs;
    }

    static int[] siteOffset(long worldSeed, int minimumDistance, int maximumDistance) {
        int minimum = Math.max(1, minimumDistance);
        int maximum = Math.max(minimum, maximumDistance);
        long mixed = worldSeed ^ Long.rotateLeft(worldSeed, 21) ^ 0x5DEECE66DL;
        int range = maximum - minimum + 1;
        int distance = minimum + (int) Math.floorMod(mixed, range);
        double angle = Math.floorMod(mixed >>> 17, 3600L) * Math.PI / 1800.0;
        return new int[] {
            (int) Math.round(Math.cos(angle) * distance),
            (int) Math.round(Math.sin(angle) * distance)
        };
    }
}
