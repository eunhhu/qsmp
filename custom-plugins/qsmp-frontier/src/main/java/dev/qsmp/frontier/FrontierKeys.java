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
    final NamespacedKey codexGiven;
    final NamespacedKey warfrontMob;
    final NamespacedKey expeditionMob;
    final NamespacedKey legacyId;
    final NamespacedKey legacyOwner;
    final NamespacedKey legacyOwnerName;
    final NamespacedKey legacyLevel;
    final NamespacedKey legacyXp;
    final NamespacedKey legacyKills;
    final NamespacedKey legacyBlocks;
    final NamespacedKey legacyRaids;
    final NamespacedKey legacyRefine;
    final NamespacedKey legacyAwakened;
    final NamespacedKey dragonScaled;
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
        codexGiven = new NamespacedKey(plugin, "codex_given");
        warfrontMob = new NamespacedKey(plugin, "warfront_mob");
        expeditionMob = new NamespacedKey(plugin, "expedition_mob");
        legacyId = new NamespacedKey(plugin, "legacy_id");
        legacyOwner = new NamespacedKey(plugin, "legacy_owner");
        legacyOwnerName = new NamespacedKey(plugin, "legacy_owner_name");
        legacyLevel = new NamespacedKey(plugin, "legacy_level");
        legacyXp = new NamespacedKey(plugin, "legacy_xp");
        legacyKills = new NamespacedKey(plugin, "legacy_kills");
        legacyBlocks = new NamespacedKey(plugin, "legacy_blocks");
        legacyRaids = new NamespacedKey(plugin, "legacy_raids");
        legacyRefine = new NamespacedKey(plugin, "legacy_refine");
        legacyAwakened = new NamespacedKey(plugin, "legacy_awakened");
        dragonScaled = new NamespacedKey(plugin, "dragon_scaled");
        companionOwner = Objects.requireNonNull(
                NamespacedKey.fromString("qsmpcompanions:owner"));
        companionLevel = Objects.requireNonNull(
                NamespacedKey.fromString("qsmpcompanions:level"));
    }
}
