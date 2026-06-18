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
        require(FrontierMath.enchantCap(1, false, false, 10, 5) == 1,
                "vanilla single-rank enchant cap stays one");
        require(FrontierMath.enchantCap(5, false, true, 10, 5) == 10,
                "mainline enchant cap uses global maximum");
        require(FrontierMath.enchantCap(3, false, false, 10, 5) == 5,
                "utility enchant cap uses side maximum");
        require(FrontierMath.enchantCap(1, false, true, 10, 5) == 1,
                "single-rank cap beats high-cap configuration");
        require(!FrontierMath.hasAnvilOperation(false, false), "unchanged anvil input has no cost");
        require(FrontierMath.hasAnvilOperation(true, false), "changed enchantment is an anvil operation");
        require(FrontierMath.hasAnvilOperation(false, true), "vanilla rename or repair is an anvil operation");
        require(FrontierMath.anvilMaximumRepairCost(999999) == 38, "anvil hard cap stays below 39");
        require(FrontierMath.boundedAnvilCost(0, 14, 38, false) == 0, "unchanged anvil input stays free");
        require(FrontierMath.boundedAnvilCost(120, 14, 38, true) == 38, "anvil cost is capped below 39");
        require(FrontierMath.anvilOutputRepairPenalty() == 0, "anvil output prior-work penalty is reset");
        double maxTempo = FrontierMath.legacyBonus(10, 5, 1, 0.012, 0.018, 0.05);
        require(maxTempo >= 0.24, "max legacy tempo is noticeable");
        double maxWork = FrontierMath.legacyBonus(10, 5, 1, 0.008, 0.020, 0.07);
        require(maxWork >= 0.24, "max legacy mining speed is noticeable");
        require(FrontierMath.legacyMiningEfficiencyBonus(maxWork) >= 1.0,
                "legacy tools also gain mining efficiency attribute");
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
