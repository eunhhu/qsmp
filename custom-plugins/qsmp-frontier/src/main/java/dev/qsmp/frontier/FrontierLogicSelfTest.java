package dev.qsmp.frontier;

public final class FrontierLogicSelfTest {
    private FrontierLogicSelfTest() {
    }

    public static void main(String[] args) {
        require(FrontierMath.waveSize(0, 1, 10, 3) == 13, "minimum player scaling");
        require(FrontierMath.waveSize(4, 3, 10, 3) == 26, "wave scaling");
        require(FrontierMath.bossHealth(3, 700.0, 220.0) == 1360.0, "boss scaling");
        require(FrontierMath.sentinelHealth(2, 24.0, 6.0) == 36.0, "expedition scaling");
        require(FrontierMath.gatherMultiplier(1) > 1.0, "gatherer base bonus");
        require(FrontierMath.gatherMultiplier(100) == 2.5, "gatherer cap");
        require(FrontierMath.survivorNextXp(1, 120, 40) == 120, "survivor xp base");
        require(FrontierMath.survivorNextXp(4, 120, 40) == 240, "survivor xp growth");
        require(FrontierMath.legacyNextXp(3, 80, 45) == 170, "legacy xp growth");
        require(FrontierMath.mergedEnchantLevel(5, 5, 10) == 6, "enchant anvil combines equal levels");
        require(FrontierMath.mergedEnchantLevel(9, 10, 10) == 10, "enchant anvil respects configured cap");
        require(FrontierMath.mergedEnchantLevel(1, 1, 1) == 1, "single-level enchants stay single");
        require(FrontierMath.withinWindow(1300L, 1000L, 425L), "active timing window");
        require(!FrontierMath.withinWindow(1500L, 1000L, 425L), "expired timing window");
        int[] firstSite = FrontierMath.siteOffset(123456789L, 700, 1100);
        int[] repeatedSite = FrontierMath.siteOffset(123456789L, 700, 1100);
        double siteDistance = Math.hypot(firstSite[0], firstSite[1]);
        require(siteDistance >= 699.0 && siteDistance <= 1101.0,
                "lazy site stays inside configured wilderness ring");
        require(firstSite[0] == repeatedSite[0] && firstSite[1] == repeatedSite[1],
                "lazy site selection is deterministic without terrain reads");
        System.out.println("QSMPFrontier logic self-test: PASS");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException("Failed: " + label);
        }
    }
}
