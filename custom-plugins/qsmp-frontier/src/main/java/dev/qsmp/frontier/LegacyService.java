package dev.qsmp.frontier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

final class LegacyService {
    private static final EquipmentSlot[] RAID_XP_SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    };

    private final QSMPFrontier plugin;
    private final FrontierKeys keys;
    private final ProgressionService progression;
    private final FrontierItems items;
    private final NamespacedKey legacyAttackSpeedModifier;
    private final NamespacedKey legacyMiningModifier;

    LegacyService(
            QSMPFrontier plugin,
            FrontierKeys keys,
            ProgressionService progression,
            FrontierItems items) {
        this.plugin = plugin;
        this.keys = keys;
        this.progression = progression;
        this.items = items;
        this.legacyAttackSpeedModifier = new NamespacedKey(plugin, "legacy_attack_speed");
        this.legacyMiningModifier = new NamespacedKey(plugin, "legacy_mining_efficiency");
    }

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("bind")) {
            ensureInventory(player);
            player.sendActionBar(ChatColor.LIGHT_PURPLE
                    + "Legacy is automatic for weapons, tools, armor, shields, and elytra.");
            open(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("refine")) {
            return refine(player);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("awaken")) {
            return awaken(player);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("transfer")) {
            return transfer(player);
        }
        open(player);
        return true;
    }

    List<String> tab(String[] args) {
        if (args.length == 1) {
            return List.of("refine", "awaken", "transfer").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    void onCombat(EntityDamageByEntityEvent event, Player attacker, LivingEntity target) {
        if (event.isCancelled()) {
            return;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!weaponLike(weapon)) {
            return;
        }
        double bonus = weaponBonus(weapon);
        if (bonus > 0.0) {
            event.setDamage(event.getDamage() * (1.0 + bonus));
        }
        boolean burst = awakened(weapon) > 0
                && ThreadLocalRandom.current().nextDouble() < plugin.getConfig()
                        .getDouble("legacy.awakening.void-burst-chance", 0.18);
        if (burst) {
            event.setDamage(event.getDamage() * (1.0 + plugin.getConfig()
                    .getDouble("legacy.awakening.void-burst-damage-multiplier", 0.20)));
        }
        playStrikeEffect(attacker, target, weapon, burst);
        int xp = Math.max(1, (int) Math.ceil(event.getDamage()
                * plugin.getConfig().getDouble("legacy.combat-xp-per-damage", 0.35)));
        if (target.getScoreboardTags().contains("qsmp_frontier")) {
            xp *= 2;
        }
        grant(weapon, attacker, xp, LegacyActivity.COMBAT, "combat");
    }

    void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (!weaponLike(weapon)) {
            return;
        }
        int xp = plugin.getConfig().getInt("legacy.kill-xp", 8);
        if (event.getEntity() instanceof org.bukkit.entity.EnderDragon
                || event.getEntity() instanceof org.bukkit.entity.Wither) {
            xp += plugin.getConfig().getInt("legacy.boss-kill-xp", 180);
        } else if (event.getEntity().getScoreboardTags().contains("qsmp_frontier")) {
            xp += plugin.getConfig().getInt("legacy.frontier-kill-xp", 10);
        }
        grant(weapon, killer, xp, LegacyActivity.KILL, "kill");
    }

    void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (!toolLike(tool)) {
            return;
        }
        int xp = blockXp(event.getBlock().getType());
        if (xp <= 0) {
            return;
        }
        grant(tool, event.getPlayer(), xp, LegacyActivity.WORK, "field work");
        playWorkEffect(event.getPlayer(), tool);
    }

    void onBlockDamage(BlockDamageEvent event) {
        applyHeldEffects(event.getPlayer());
    }

    void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (!hasLegacy(item)) {
            return;
        }
        double chance = durabilitySaveChance(item);
        if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().spawnParticle(
                Particle.ENCHANT,
                event.getPlayer().getLocation().add(0.0, 1.0, 0.0),
                10,
                0.35,
                0.45,
                0.35,
                0.02);
        event.getPlayer().playSound(
                event.getPlayer().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.6f);
    }

    void onIncomingDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isCancelled()) {
            return;
        }
        double totalReduction = 0.0;
        for (EquipmentSlot slot : List.of(
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack item = player.getInventory().getItem(slot);
            if (armorLike(item)) {
                totalReduction += armorReduction(item);
            }
        }
        if (totalReduction > 0.0) {
            double cap = plugin.getConfig().getDouble("legacy.armor-reduction-cap", 0.25);
            double reduction = Math.min(cap, totalReduction);
            event.setDamage(event.getDamage() * (1.0 - reduction));
        }
        int xp = Math.max(1, (int) Math.ceil(event.getFinalDamage()
                * plugin.getConfig().getDouble("legacy.armor-xp-per-damage", 0.25)));
        if (xp <= 0) {
            return;
        }
        for (EquipmentSlot slot : List.of(
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack item = player.getInventory().getItem(slot);
            if (armorLike(item)) {
                grant(item, player, xp, LegacyActivity.COMBAT, "survival");
                player.getInventory().setItem(slot, item);
            }
        }
        playArmorEffect(player, totalReduction);
    }

    void grantRaidXp(Player player, int amount, String reason) {
        if (amount <= 0) {
            return;
        }
        for (EquipmentSlot slot : RAID_XP_SLOTS) {
            ItemStack item = player.getInventory().getItem(slot);
            if (eligible(item)) {
                grant(item, player, amount, LegacyActivity.RAID, reason);
                player.getInventory().setItem(slot, item);
            }
        }
    }

    void ensureInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (ItemStack item : storage) {
            ensureLegacy(item, player);
        }
        inventory.setStorageContents(storage);
        for (EquipmentSlot slot : RAID_XP_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (ensureLegacy(item, player)) {
                inventory.setItem(slot, item);
            }
        }
    }

    void tickPlayer(Player player) {
        ensureInventory(player);
        applyHeldEffects(player);
    }

    List<String> statusLore(Player player) {
        ensureInventory(player);
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!eligible(held)) {
            return List.of(
                    ChatColor.RED + "No Legacy item in main hand.",
                    ChatColor.DARK_GRAY + "Weapons, tools, armor, shields, elytra, bows,",
                    ChatColor.DARK_GRAY + "tridents, and maces bind automatically.");
        }
        if (!hasLegacy(held)) {
            return List.of(ChatColor.YELLOW + "Legacy binding will initialize automatically.");
        }
        List<String> lore = new ArrayList<>(legacyLines(held));
        lore.add(ChatColor.DARK_GRAY + "Open Legacy Arsenal for refine/awaken/transfer.");
        return lore;
    }

    void open(Player player) {
        ensureInventory(player);
        ItemStack held = player.getInventory().getItemInMainHand();
        FrontierMenuHolder holder =
                new FrontierMenuHolder(FrontierMenuHolder.Menu.LEGACY, 45,
                        ChatColor.DARK_PURPLE + "Legacy Arsenal");
        Inventory inventory = holder.getInventory();
        inventory.setItem(4, heldIcon(held));
        inventory.setItem(20, icon(
                Material.NETHERITE_SCRAP,
                ChatColor.LIGHT_PURPLE + "Refine",
                refineLore(held)));
        inventory.setItem(22, icon(
                Material.DRAGON_BREATH,
                ChatColor.DARK_PURPLE + "Awaken",
                awakenLore(held)));
        inventory.setItem(24, icon(
                Material.AMETHYST_SHARD,
                ChatColor.AQUA + "Transfer Memory",
                transferLore(player)));
        inventory.setItem(40, icon(
                Material.BARRIER,
                ChatColor.RED + "Close",
                List.of(ChatColor.GRAY + "Return to the world.")));
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 0.85f);
    }

    void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof FrontierMenuHolder holder)
                || holder.menu() != FrontierMenuHolder.Menu.LEGACY) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        switch (event.getRawSlot()) {
            case 20 -> {
                refine(player);
                refresh(player);
            }
            case 22 -> {
                awaken(player);
                refresh(player);
            }
            case 24 -> {
                transfer(player);
                refresh(player);
            }
            case 40 -> player.closeInventory();
            default -> {
            }
        }
    }

    private boolean refine(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!hasLegacy(held)) {
            fail(player, "Hold a Legacy item to refine.");
            return true;
        }
        ItemMeta meta = held.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        int refine = data.getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0);
        int maximum = Math.max(0, plugin.getConfig().getInt("legacy.refine.max", 5));
        if (refine >= maximum) {
            fail(player, "This Legacy is already fully refined.");
            return true;
        }
        int cost = Math.max(1, plugin.getConfig().getInt("legacy.refine.base-scrap-cost", 1) + refine);
        int voidScaleCost = voidScaleCost(refine + 1);
        ItemStack scrap = new ItemStack(Material.NETHERITE_SCRAP, cost);
        if (!player.getInventory().containsAtLeast(scrap, cost)) {
            fail(player, "Refine requires " + cost + " Netherite Scrap.");
            return true;
        }
        if (voidScaleCost > 0 && customCount(player, FrontierItems.VOID_SCALE) < voidScaleCost) {
            fail(player, "Refine +" + (refine + 1) + " requires " + voidScaleCost + " Void Scale.");
            return true;
        }
        player.getInventory().removeItem(scrap);
        if (voidScaleCost > 0) {
            consumeCustom(player, FrontierItems.VOID_SCALE, voidScaleCost);
        }
        double failureChance = refineFailureChance(refine);
        if (ThreadLocalRandom.current().nextDouble() < failureChance) {
            damageItem(held, plugin.getConfig().getInt("legacy.refine.failure-durability-damage", 18));
            grant(held, player, plugin.getConfig().getInt("legacy.refine.failure-xp", 35),
                    LegacyActivity.NONE, "refine backlash");
            player.sendTitle(
                    ChatColor.DARK_RED + "REFINE BACKLASH",
                    ChatColor.GRAY + "The item endured the fracture",
                    4, 34, 8);
            player.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    player.getLocation().add(0.0, 1.1, 0.0),
                    28,
                    0.45,
                    0.55,
                    0.45,
                    0.05);
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 0.9f);
            return true;
        }
        meta = held.getItemMeta();
        data = meta.getPersistentDataContainer();
        data.set(keys.legacyRefine, PersistentDataType.INTEGER, refine + 1);
        updateLore(held, meta, data);
        held.setItemMeta(meta);
        int newRefine = refine + 1;
        player.getWorld().spawnParticle(
                Particle.ENCHANTED_HIT,
                player.getLocation().add(0.0, 1.2, 0.0),
                36 + refine * 8,
                0.5,
                0.7,
                0.5,
                0.05);
        if (newRefine >= 3) {
            player.getWorld().strikeLightningEffect(player.getLocation());
            player.getWorld().spawnParticle(
                    Particle.SONIC_BOOM,
                    player.getLocation().add(0.0, 1.2, 0.0),
                    1);
        }
        player.sendTitle(
                ChatColor.LIGHT_PURPLE + "REFINE +" + newRefine,
                ChatColor.GRAY + "Damage, tempo, work, and temper rose",
                4, 42, 10);
        success(player, "Legacy refined to +" + newRefine + ".");
        return true;
    }

    private boolean awaken(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!hasLegacy(held)) {
            fail(player, "Hold a max-level Legacy item to awaken.");
            return true;
        }
        ItemMeta meta = held.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        int level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        if (level < maxLevel()) {
            fail(player, "Awakening requires Legacy Lv. " + maxLevel() + ".");
            return true;
        }
        if (data.getOrDefault(keys.legacyAwakened, PersistentDataType.INTEGER, 0) > 0) {
            fail(player, "This Legacy is already awakened.");
            return true;
        }
        if (!consumeCustom(player, FrontierItems.DRAGON_HEART, 1)) {
            fail(player, "Awakening requires one Dragon Heart.");
            return true;
        }
        data.set(keys.legacyAwakened, PersistentDataType.INTEGER, 1);
        updateLore(held, meta, data);
        held.setItemMeta(meta);
        player.sendTitle(ChatColor.LIGHT_PURPLE + "LEGACY AWAKENED",
                ChatColor.GRAY + "Dragon Heart bound", 6, 40, 12);
        player.getWorld().spawnParticle(
                Particle.SCULK_SOUL,
                player.getLocation().add(0.0, 1.1, 0.0),
                42,
                0.55,
                0.75,
                0.55,
                0.04);
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.75f);
        return true;
    }

    private boolean transfer(Player player) {
        ItemStack source = player.getInventory().getItemInMainHand();
        ItemStack target = player.getInventory().getItemInOffHand();
        if (!hasLegacy(source)) {
            fail(player, "Hold the source Legacy item in your main hand.");
            return true;
        }
        if (!eligible(target)) {
            fail(player, "Hold a compatible target item in your off hand.");
            return true;
        }
        if (!consumeCustom(player, FrontierItems.MEMORY_SHARD, 1)) {
            fail(player, "Memory transfer requires one Memory Shard.");
            return true;
        }
        ItemMeta sourceMeta = source.getItemMeta();
        PersistentDataContainer sourceData = sourceMeta.getPersistentDataContainer();
        ItemMeta targetMeta = target.getItemMeta();
        PersistentDataContainer targetData = targetMeta.getPersistentDataContainer();
        int penalty = Math.max(0, plugin.getConfig().getInt("legacy.transfer.level-penalty", 2));
        int level = Math.max(1,
                sourceData.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 1) - penalty);
        int refine = Math.max(0,
                sourceData.getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0) - 1);
        targetData.set(keys.legacyId, PersistentDataType.STRING, UUID.randomUUID().toString());
        targetData.set(keys.legacyOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        targetData.set(keys.legacyOwnerName, PersistentDataType.STRING, player.getName());
        targetData.set(keys.legacyLevel, PersistentDataType.INTEGER, level);
        targetData.set(keys.legacyXp, PersistentDataType.INTEGER, 0);
        targetData.set(keys.legacyKills, PersistentDataType.INTEGER,
                sourceData.getOrDefault(keys.legacyKills, PersistentDataType.INTEGER, 0));
        targetData.set(keys.legacyBlocks, PersistentDataType.INTEGER,
                sourceData.getOrDefault(keys.legacyBlocks, PersistentDataType.INTEGER, 0));
        targetData.set(keys.legacyRaids, PersistentDataType.INTEGER,
                sourceData.getOrDefault(keys.legacyRaids, PersistentDataType.INTEGER, 0));
        targetData.set(keys.legacyRefine, PersistentDataType.INTEGER, refine);
        targetData.set(keys.legacyAwakened, PersistentDataType.INTEGER, 0);
        updateLore(target, targetMeta, targetData);
        target.setItemMeta(targetMeta);
        success(player, "Legacy memory imprinted into off hand at Lv. " + level + ".");
        return true;
    }

    private boolean ensureLegacy(ItemStack item, Player player) {
        if (!eligible(item)) {
            return false;
        }
        if (hasLegacy(item)) {
            refreshExisting(item);
            return true;
        }
        return grant(item, player, 0, LegacyActivity.NONE, "automatic");
    }

    private ItemStack heldIcon(ItemStack held) {
        if (!eligible(held)) {
            return icon(
                    Material.BARRIER,
                    ChatColor.RED + "No Legacy Item Held",
                    List.of(
                            ChatColor.GRAY + "Hold a weapon, tool, armor piece,",
                            ChatColor.GRAY + "shield, elytra, bow, trident, or mace.",
                            ChatColor.DARK_GRAY + "Eligible items bind automatically."));
        }
        if (!hasLegacy(held)) {
            return icon(
                    Material.ANVIL,
                    ChatColor.YELLOW + "Preparing Legacy",
                    List.of(ChatColor.GRAY + "This item will bind automatically."));
        }
        ItemStack display = held.clone();
        display.setAmount(1);
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return display;
        }
        meta.setLore(legacyLines(held));
        display.setItemMeta(meta);
        return display;
    }

    private List<String> refineLore(ItemStack held) {
        if (!hasLegacy(held)) {
            return List.of(ChatColor.GRAY + "Hold a Legacy item in main hand.");
        }
        PersistentDataContainer data = held.getItemMeta().getPersistentDataContainer();
        int refine = data.getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0);
        int maximum = Math.max(0, plugin.getConfig().getInt("legacy.refine.max", 5));
        int cost = Math.max(1, plugin.getConfig().getInt("legacy.refine.base-scrap-cost", 1) + refine);
        List<String> lore = new ArrayList<>(List.of(
                ChatColor.GRAY + "Refine +" + refine + "/" + maximum,
                ChatColor.DARK_PURPLE + "Damage, speed, utility, and durability grow."));
        if (refine >= maximum) {
            lore.add(ChatColor.RED + "Fully refined.");
        } else {
            lore.add(ChatColor.YELLOW + "Cost: " + cost + " Netherite Scrap.");
            int voidScaleCost = voidScaleCost(refine + 1);
            if (voidScaleCost > 0) {
                lore.add(ChatColor.DARK_PURPLE + "Void Scale: " + voidScaleCost);
            }
            lore.add(ChatColor.RED + "Failure: " + percent(refineFailureChance(refine)));
            lore.add(ChatColor.GREEN + "Click to refine.");
        }
        return lore;
    }

    private List<String> awakenLore(ItemStack held) {
        if (!hasLegacy(held)) {
            return List.of(ChatColor.GRAY + "Hold a max-level Legacy item.");
        }
        PersistentDataContainer data = held.getItemMeta().getPersistentDataContainer();
        int level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        int awakened = data.getOrDefault(keys.legacyAwakened, PersistentDataType.INTEGER, 0);
        List<String> lore = new ArrayList<>(List.of(
                ChatColor.GRAY + "Requires Legacy Lv. " + maxLevel(),
                ChatColor.DARK_PURPLE + "Cost: 1 Dragon Heart."));
        if (awakened > 0) {
            lore.add(ChatColor.RED + "Already awakened.");
        } else if (level < maxLevel()) {
            lore.add(ChatColor.YELLOW + "Current Lv. " + level + "/" + maxLevel() + ".");
        } else {
            lore.add(ChatColor.GREEN + "Click to awaken.");
        }
        return lore;
    }

    private List<String> transferLore(Player player) {
        ItemStack source = player.getInventory().getItemInMainHand();
        ItemStack target = player.getInventory().getItemInOffHand();
        List<String> lore = new ArrayList<>(List.of(
                ChatColor.GRAY + "Main hand source -> off-hand target.",
                ChatColor.DARK_GRAY + "Overwrites off-hand Legacy memory.",
                ChatColor.DARK_PURPLE + "Cost: 1 Memory Shard."));
        if (!hasLegacy(source)) {
            lore.add(ChatColor.RED + "Main hand needs a Legacy item.");
            return lore;
        }
        if (!eligible(target)) {
            lore.add(ChatColor.RED + "Off hand needs a compatible item.");
            return lore;
        }
        PersistentDataContainer data = source.getItemMeta().getPersistentDataContainer();
        int penalty = Math.max(0, plugin.getConfig().getInt("legacy.transfer.level-penalty", 2));
        int level = Math.max(1,
                data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 1) - penalty);
        lore.add(ChatColor.AQUA + "Result Lv. " + level + ", awakened state reset.");
        lore.add(ChatColor.GREEN + "Click to imprint.");
        return lore;
    }

    private List<String> legacyLines(ItemStack item) {
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        int level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 1);
        int xp = data.getOrDefault(keys.legacyXp, PersistentDataType.INTEGER, 0);
        int required = level >= maxLevel() ? 0 : nextXp(level);
        List<String> lines = new ArrayList<>(List.of(
                ChatColor.DARK_PURPLE + "Legacy Lv. " + level + "/" + maxLevel(),
                ChatColor.GRAY + "XP " + xp + "/" + required,
                ChatColor.GRAY + "Kills "
                        + data.getOrDefault(keys.legacyKills, PersistentDataType.INTEGER, 0)
                        + " | Work "
                        + data.getOrDefault(keys.legacyBlocks, PersistentDataType.INTEGER, 0)
                        + " | Raids "
                        + data.getOrDefault(keys.legacyRaids, PersistentDataType.INTEGER, 0)));
        addBonusLore(item, data, lines);
        lines.addAll(List.of(
                ChatColor.GRAY + "Refine +"
                        + data.getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0)
                        + " | "
                        + (data.getOrDefault(keys.legacyAwakened, PersistentDataType.INTEGER, 0) > 0
                                ? "Awakened" : "Dormant")));
        return lines;
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void success(Player player, String message) {
        player.sendActionBar(ChatColor.LIGHT_PURPLE + message);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.35f);
    }

    private void fail(Player player, String message) {
        player.sendActionBar(ChatColor.RED + message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
    }

    private void refresh(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                open(player);
            }
        });
    }

    private void refreshExisting(ItemStack item) {
        if (!hasLegacy(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        updateLore(item, meta, meta.getPersistentDataContainer());
        item.setItemMeta(meta);
    }

    private void addBonusLore(ItemStack item, PersistentDataContainer data, List<String> lore) {
        if (weaponLike(item)) {
            lore.add(ChatColor.DARK_PURPLE + "Legacy bonus: +"
                    + percent(weaponBonus(item, data)) + " final damage");
        }
        if (meleeLike(item) || toolLike(item)) {
            lore.add(ChatColor.DARK_PURPLE + "Legacy tempo: +"
                    + percent(attackSpeedBonus(item, data)) + " attack speed");
        }
        if (toolLike(item)) {
            lore.add(ChatColor.AQUA + "Legacy work: +"
                    + percent(miningSpeedBonus(item, data)) + " mining speed");
        }
        if (armorLike(item)) {
            lore.add(ChatColor.BLUE + "Legacy guard: -"
                    + percent(armorReduction(item, data)) + " incoming damage");
        }
        if (item.getType().getMaxDurability() > 0) {
            lore.add(ChatColor.GREEN + "Legacy temper: "
                    + percent(durabilitySaveChance(item, data)) + " durability save");
        }
    }

    private void applyAttributeModifiers(ItemStack item, ItemMeta meta, PersistentDataContainer data) {
        removeLegacyModifier(meta, Attribute.ATTACK_SPEED, legacyAttackSpeedModifier);
        removeLegacyModifier(meta, Attribute.MINING_EFFICIENCY, legacyMiningModifier);
        double attackSpeed = attackSpeedBonus(item, data);
        if ((meleeLike(item) || toolLike(item)) && attackSpeed > 0.0) {
            meta.addAttributeModifier(
                    Attribute.ATTACK_SPEED,
                    new AttributeModifier(
                            legacyAttackSpeedModifier,
                            attackSpeed,
                            AttributeModifier.Operation.ADD_SCALAR,
                            EquipmentSlotGroup.MAINHAND));
        }
        double miningSpeed = miningSpeedBonus(item, data);
        if (toolLike(item) && miningSpeed > 0.0) {
            meta.addAttributeModifier(
                    Attribute.MINING_EFFICIENCY,
                    new AttributeModifier(
                            legacyMiningModifier,
                            miningSpeed,
                            AttributeModifier.Operation.ADD_SCALAR,
                            EquipmentSlotGroup.MAINHAND));
        }
    }

    private void removeLegacyModifier(
            ItemMeta meta,
            Attribute attribute,
            NamespacedKey modifierKey) {
        Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(attribute);
        if (modifiers == null || modifiers.isEmpty()) {
            return;
        }
        for (AttributeModifier modifier : List.copyOf(modifiers)) {
            if (modifierKey.equals(modifier.getKey())) {
                meta.removeAttributeModifier(attribute, modifier);
            }
        }
    }

    private void applyHeldEffects(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!toolLike(held) || !hasLegacy(held)) {
            return;
        }
        double mining = miningSpeedBonus(held);
        if (mining <= 0.0) {
            return;
        }
        int amplifier = Math.min(2, Math.max(0, (int) Math.ceil(mining / 0.20) - 1));
        PotionEffect current = player.getPotionEffect(PotionEffectType.HASTE);
        if (current != null && current.getAmplifier() >= amplifier && current.getDuration() > 30) {
            return;
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.HASTE,
                80,
                amplifier,
                true,
                false,
                true));
    }

    private void playStrikeEffect(
            Player attacker,
            LivingEntity target,
            ItemStack weapon,
            boolean burst) {
        int refine = refineLevel(weapon);
        int awakened = awakened(weapon);
        int level = level(weapon);
        if (refine <= 0 && awakened <= 0 && level < 5) {
            return;
        }
        target.getWorld().spawnParticle(
                awakened > 0
                        ? Particle.SCULK_SOUL
                        : refine >= 3 ? Particle.ENCHANTED_HIT : Particle.CRIT,
                target.getLocation().add(0.0, target.getHeight() * 0.55, 0.0),
                4 + refine * 5 + Math.min(12, level / 2),
                0.35,
                0.35,
                0.35,
                0.08);
        if (burst) {
            target.getWorld().spawnParticle(
                    Particle.SCULK_SOUL,
                    target.getLocation().add(0.0, target.getHeight() * 0.65, 0.0),
                    24,
                    0.45,
                    0.55,
                    0.45,
                    0.06);
            target.getWorld().playSound(target.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 0.8f, 0.8f);
            attacker.sendActionBar(ChatColor.DARK_PURPLE + "Void Burst");
        }
    }

    private void playWorkEffect(Player player, ItemStack tool) {
        int refine = refineLevel(tool);
        int awakened = awakened(tool);
        int level = level(tool);
        if (refine < 3 && awakened <= 0 && level < 5) {
            return;
        }
        player.getWorld().spawnParticle(
                awakened > 0 ? Particle.SCRAPE : Particle.ENCHANT,
                player.getLocation().add(0.0, 0.8, 0.0),
                4 + refine * 3 + Math.min(10, level / 3),
                0.4,
                0.25,
                0.4,
                0.03);
    }

    private void playArmorEffect(Player player, double reduction) {
        if (reduction <= 0.0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > 0.25) {
            return;
        }
        player.getWorld().spawnParticle(
                Particle.ENCHANT,
                player.getLocation().add(0.0, 1.0, 0.0),
                12,
                0.45,
                0.55,
                0.45,
                0.02);
    }

    private void damageItem(ItemStack item, int amount) {
        if (amount <= 0 || item.getType().getMaxDurability() <= 0) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return;
        }
        int maximum = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
        if (maximum <= 1) {
            return;
        }
        damageable.setDamage(Math.min(maximum - 1, damageable.getDamage() + amount));
        item.setItemMeta(damageable);
    }

    private int customCount(Player player, String itemType) {
        int available = 0;
        for (ItemStack candidate : player.getInventory().getContents()) {
            if (itemType.equals(items.itemType(candidate))) {
                available += candidate.getAmount();
            }
        }
        return available;
    }

    private boolean grant(
            ItemStack item,
            Player player,
            int baseAmount,
            LegacyActivity activity,
            String reason) {
        if (!eligible(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (!data.has(keys.legacyId, PersistentDataType.STRING)) {
            data.set(keys.legacyId, PersistentDataType.STRING, UUID.randomUUID().toString());
            data.set(keys.legacyOwner, PersistentDataType.STRING, player.getUniqueId().toString());
            data.set(keys.legacyOwnerName, PersistentDataType.STRING, player.getName());
            data.set(keys.legacyLevel, PersistentDataType.INTEGER, 1);
            data.set(keys.legacyXp, PersistentDataType.INTEGER, 0);
            data.set(keys.legacyKills, PersistentDataType.INTEGER, 0);
            data.set(keys.legacyBlocks, PersistentDataType.INTEGER, 0);
            data.set(keys.legacyRaids, PersistentDataType.INTEGER, 0);
            data.set(keys.legacyRefine, PersistentDataType.INTEGER, 0);
            data.set(keys.legacyAwakened, PersistentDataType.INTEGER, 0);
        }
        int amount = (int) Math.round(baseAmount * progression.legacyXpMultiplier(player));
        int level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 1);
        int xp = data.getOrDefault(keys.legacyXp, PersistentDataType.INTEGER, 0) + Math.max(0, amount);
        int leveled = 0;
        while (level < maxLevel() && xp >= nextXp(level)) {
            xp -= nextXp(level);
            level++;
            leveled++;
        }
        if (level >= maxLevel()) {
            xp = 0;
        }
        data.set(keys.legacyLevel, PersistentDataType.INTEGER, level);
        data.set(keys.legacyXp, PersistentDataType.INTEGER, xp);
        increment(data, activity);
        updateLore(item, meta, data);
        item.setItemMeta(meta);
        if (leveled > 0) {
            player.sendTitle(
                    ChatColor.DARK_PURPLE + "LEGACY LV. " + level,
                    ChatColor.GRAY + reason,
                    4, 32, 8);
            player.getWorld().spawnParticle(
                    Particle.TOTEM_OF_UNDYING,
                    player.getLocation().add(0.0, 1.0, 0.0),
                    22 + Math.min(18, level),
                    0.45,
                    0.6,
                    0.45,
                    0.04);
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.1f);
        }
        return true;
    }

    private void updateLore(ItemStack item, ItemMeta meta, PersistentDataContainer data) {
        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();
        lore.removeIf(line -> {
            String stripped = ChatColor.stripColor(line);
            return stripped != null && stripped.startsWith("Legacy ");
        });
        int level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 1);
        int xp = data.getOrDefault(keys.legacyXp, PersistentDataType.INTEGER, 0);
        int required = level >= maxLevel() ? 0 : nextXp(level);
        lore.add(ChatColor.DARK_PURPLE + "Legacy Lv. " + level + "/" + maxLevel()
                + ChatColor.GRAY + " - " + xp + "/" + required + " XP");
        lore.add(ChatColor.GRAY + "Legacy owner: "
                + data.getOrDefault(keys.legacyOwnerName, PersistentDataType.STRING, "unknown"));
        lore.add(ChatColor.DARK_GRAY + "Legacy record: K "
                + data.getOrDefault(keys.legacyKills, PersistentDataType.INTEGER, 0)
                + " | W "
                + data.getOrDefault(keys.legacyBlocks, PersistentDataType.INTEGER, 0)
                + " | R "
                + data.getOrDefault(keys.legacyRaids, PersistentDataType.INTEGER, 0));
        int refine = data.getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0);
        int awakened = data.getOrDefault(keys.legacyAwakened, PersistentDataType.INTEGER, 0);
        addBonusLore(item, data, lore);
        lore.add(ChatColor.DARK_PURPLE + "Legacy refine: +" + refine
                + (awakened > 0 ? " | Awakened" : " | Dormant"));
        if (refine >= 3) {
            lore.add(ChatColor.LIGHT_PURPLE + "Legacy technique: resonance sparks on hit/work");
        } else if (level >= 5) {
            lore.add(ChatColor.LIGHT_PURPLE + "Legacy technique: low resonance on hit/work");
        }
        if (awakened > 0) {
            lore.add(ChatColor.DARK_PURPLE + "Legacy technique: Void Burst can erupt in combat");
        }
        meta.setLore(lore);
        meta.setEnchantmentGlintOverride(true);
        applyAttributeModifiers(item, meta, data);
    }

    private void increment(PersistentDataContainer data, LegacyActivity activity) {
        switch (activity) {
            case KILL -> data.set(keys.legacyKills, PersistentDataType.INTEGER,
                    data.getOrDefault(keys.legacyKills, PersistentDataType.INTEGER, 0) + 1);
            case WORK -> data.set(keys.legacyBlocks, PersistentDataType.INTEGER,
                    data.getOrDefault(keys.legacyBlocks, PersistentDataType.INTEGER, 0) + 1);
            case RAID -> data.set(keys.legacyRaids, PersistentDataType.INTEGER,
                    data.getOrDefault(keys.legacyRaids, PersistentDataType.INTEGER, 0) + 1);
            default -> {
            }
        }
    }

    private int level(ItemStack item) {
        if (!eligible(item) || !item.hasItemMeta()) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
    }

    private double weaponBonus(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0.0;
        }
        return weaponBonus(item, item.getItemMeta().getPersistentDataContainer());
    }

    private double weaponBonus(ItemStack item, PersistentDataContainer data) {
        int level = level(item);
        if (data != null) {
            level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        }
        if (level <= 0) {
            return 0.0;
        }
        return Math.max(0, level - 1)
                        * plugin.getConfig().getDouble("legacy.weapon-damage-per-level", 0.04)
                + legacyRefine(data, item)
                        * plugin.getConfig().getDouble("legacy.refine.weapon-damage-per-refine", 0.05)
                + legacyAwakened(data, item)
                        * plugin.getConfig().getDouble("legacy.awakening.weapon-damage", 0.15);
    }

    private double attackSpeedBonus(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0.0;
        }
        return attackSpeedBonus(item, item.getItemMeta().getPersistentDataContainer());
    }

    private double attackSpeedBonus(ItemStack item, PersistentDataContainer data) {
        int level = level(item);
        if (data != null) {
            level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        }
        if (level <= 0) {
            return 0.0;
        }
        return Math.max(0, level - 1)
                        * plugin.getConfig().getDouble("legacy.attack-speed-per-level", 0.012)
                + legacyRefine(data, item)
                        * plugin.getConfig().getDouble("legacy.refine.attack-speed-per-refine", 0.02)
                + legacyAwakened(data, item)
                        * plugin.getConfig().getDouble("legacy.awakening.attack-speed", 0.08);
    }

    private double miningSpeedBonus(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0.0;
        }
        return miningSpeedBonus(item, item.getItemMeta().getPersistentDataContainer());
    }

    private double miningSpeedBonus(ItemStack item, PersistentDataContainer data) {
        int level = level(item);
        if (data != null) {
            level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        }
        if (level <= 0 || !toolLike(item)) {
            return 0.0;
        }
        return Math.max(0, level - 1)
                        * plugin.getConfig().getDouble("legacy.mining-speed-per-level", 0.035)
                + legacyRefine(data, item)
                        * plugin.getConfig().getDouble("legacy.refine.mining-speed-per-refine", 0.05)
                + legacyAwakened(data, item)
                        * plugin.getConfig().getDouble("legacy.awakening.mining-speed", 0.15);
    }

    private double durabilitySaveChance(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0.0;
        }
        return durabilitySaveChance(item, item.getItemMeta().getPersistentDataContainer());
    }

    private double durabilitySaveChance(ItemStack item, PersistentDataContainer data) {
        int level = level(item);
        if (data != null) {
            level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        }
        if (level <= 0 || item.getType().getMaxDurability() <= 0) {
            return 0.0;
        }
        double chance = Math.max(0, level - 1)
                        * plugin.getConfig().getDouble("legacy.durability-save-per-level", 0.015)
                + legacyRefine(data, item)
                        * plugin.getConfig().getDouble("legacy.refine.durability-save-per-refine", 0.04)
                + legacyAwakened(data, item)
                        * plugin.getConfig().getDouble("legacy.awakening.durability-save", 0.12);
        return Math.min(plugin.getConfig().getDouble("legacy.durability-save-cap", 0.55), chance);
    }

    private double armorReduction(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0.0;
        }
        return armorReduction(item, item.getItemMeta().getPersistentDataContainer());
    }

    private double armorReduction(ItemStack item, PersistentDataContainer data) {
        int level = level(item);
        if (data != null) {
            level = data.getOrDefault(keys.legacyLevel, PersistentDataType.INTEGER, 0);
        }
        if (level <= 0) {
            return 0.0;
        }
        return Math.max(0, level - 1)
                        * plugin.getConfig().getDouble("legacy.armor-reduction-per-level", 0.01)
                + legacyRefine(data, item)
                        * plugin.getConfig().getDouble("legacy.refine.armor-reduction-per-refine", 0.012)
                + legacyAwakened(data, item)
                        * plugin.getConfig().getDouble("legacy.awakening.armor-reduction", 0.05);
    }

    private int legacyRefine(PersistentDataContainer data, ItemStack fallback) {
        if (data != null) {
            return data.getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0);
        }
        return refineLevel(fallback);
    }

    private int legacyAwakened(PersistentDataContainer data, ItemStack fallback) {
        if (data != null) {
            return data.getOrDefault(keys.legacyAwakened, PersistentDataType.INTEGER, 0);
        }
        return awakened(fallback);
    }

    private int refineLevel(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keys.legacyRefine, PersistentDataType.INTEGER, 0);
    }

    private int awakened(ItemStack item) {
        if (!hasLegacy(item)) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keys.legacyAwakened, PersistentDataType.INTEGER, 0);
    }

    private boolean hasLegacy(ItemStack item) {
        return eligible(item)
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(
                        keys.legacyId, PersistentDataType.STRING);
    }

    private int voidScaleCost(int targetRefine) {
        int from = Math.max(1, plugin.getConfig().getInt("legacy.refine.void-scale-cost-from", 3));
        if (targetRefine < from) {
            return 0;
        }
        int perTier = Math.max(1, plugin.getConfig().getInt("legacy.refine.void-scale-cost-per-tier", 1));
        return (targetRefine - from + 1) * perTier;
    }

    private double refineFailureChance(int currentRefine) {
        double base = plugin.getConfig().getDouble("legacy.refine.failure-chance-base", 0.10);
        double growth = plugin.getConfig().getDouble("legacy.refine.failure-chance-per-refine", 0.07);
        return Math.min(0.75, Math.max(0.0, base + Math.max(0, currentRefine) * growth));
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private boolean consumeCustom(Player player, String itemType, int amount) {
        int available = 0;
        for (ItemStack candidate : player.getInventory().getContents()) {
            if (itemType.equals(items.itemType(candidate))) {
                available += candidate.getAmount();
            }
        }
        if (available < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack candidate = contents[i];
            if (!itemType.equals(items.itemType(candidate))) {
                continue;
            }
            int taken = Math.min(remaining, candidate.getAmount());
            remaining -= taken;
            candidate.setAmount(candidate.getAmount() - taken);
            if (candidate.getAmount() <= 0) {
                player.getInventory().setItem(i, null);
            }
        }
        return remaining == 0;
    }

    private int blockXp(Material material) {
        if (material == Material.ANCIENT_DEBRIS) {
            return plugin.getConfig().getInt("legacy.block-xp.ancient-debris", 24);
        }
        if (material.name().endsWith("_ORE")) {
            return plugin.getConfig().getInt("legacy.block-xp.ore", 8);
        }
        if (Tag.LOGS.isTagged(material)) {
            return plugin.getConfig().getInt("legacy.block-xp.log", 2);
        }
        return 0;
    }

    private boolean eligible(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getType().getMaxStackSize() == 1
                && (weaponLike(item) || toolLike(item) || armorLike(item) || item.getType() == Material.SHIELD);
    }

    private boolean weaponLike(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || item.getType() == Material.BOW
                || item.getType() == Material.CROSSBOW
                || item.getType() == Material.TRIDENT
                || item.getType() == Material.MACE;
    }

    private boolean meleeLike(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || item.getType() == Material.TRIDENT
                || item.getType() == Material.MACE;
    }

    private boolean toolLike(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || item.getType() == Material.SHEARS
                || item.getType() == Material.FISHING_ROD;
    }

    private boolean armorLike(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || item.getType() == Material.ELYTRA
                || item.getType() == Material.SHIELD;
    }

    private int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt("legacy.max-level", 10));
    }

    private int nextXp(int level) {
        return FrontierMath.legacyNextXp(
                level,
                plugin.getConfig().getInt("legacy.base-next-level-xp", 80),
                plugin.getConfig().getInt("legacy.next-level-xp-growth", 45));
    }

    private enum LegacyActivity {
        NONE,
        COMBAT,
        KILL,
        WORK,
        RAID
    }
}
