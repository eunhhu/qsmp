package dev.qsmp.frontier;

final class FrontierMath {
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
