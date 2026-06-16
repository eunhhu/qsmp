package dev.qsmp.frontier;

import java.util.Objects;
import org.bukkit.NamespacedKey;

final class FrontierKeys {
    final NamespacedKey itemType;
    final NamespacedKey outpostType;
    final NamespacedKey outpostOwner;
    final NamespacedKey outpostTier;
    final NamespacedKey companionRole;
    final NamespacedKey whistleRole;
    final NamespacedKey fieldGuideGiven;
    final NamespacedKey warfrontMob;
    final NamespacedKey expeditionMob;
    final NamespacedKey companionOwner;
    final NamespacedKey companionLevel;

    FrontierKeys(QSMPFrontier plugin) {
        itemType = new NamespacedKey(plugin, "item_type");
        outpostType = new NamespacedKey(plugin, "outpost_type");
        outpostOwner = new NamespacedKey(plugin, "outpost_owner");
        outpostTier = new NamespacedKey(plugin, "outpost_tier");
        companionRole = new NamespacedKey(plugin, "companion_role");
        whistleRole = new NamespacedKey(plugin, "whistle_role");
        fieldGuideGiven = new NamespacedKey(plugin, "field_guide_given");
        warfrontMob = new NamespacedKey(plugin, "warfront_mob");
        expeditionMob = new NamespacedKey(plugin, "expedition_mob");
        companionOwner = Objects.requireNonNull(
                NamespacedKey.fromString("qsmpcompanions:owner"));
        companionLevel = Objects.requireNonNull(
                NamespacedKey.fromString("qsmpcompanions:level"));
    }
}
