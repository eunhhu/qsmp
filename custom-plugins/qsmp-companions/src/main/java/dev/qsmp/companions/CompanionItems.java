package dev.qsmp.companions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

final class CompanionItems {
    static final String CRYOPOD_EMPTY = "cryopod_empty";
    static final String CRYOPOD_FILLED = "cryopod_filled";
    static final String ARMOR_IRON = "armor_iron";
    static final String ARMOR_DIAMOND = "armor_diamond";
    static final String ARMOR_NETHERITE = "armor_netherite";
    static final String BONDING_CHARM = "bonding_charm";

    private final QSMPCompanions plugin;
    private final CompanionService companions;
    private final NamespacedKey itemTypeKey;
    private final NamespacedKey entityDataKey;
    private final NamespacedKey podOwnerKey;

    CompanionItems(QSMPCompanions plugin, CompanionService companions) {
        this.plugin = plugin;
        this.companions = companions;
        itemTypeKey = new NamespacedKey(plugin, "item_type");
        entityDataKey = new NamespacedKey(plugin, "entity_data");
        podOwnerKey = new NamespacedKey(plugin, "pod_owner");
    }

    String itemType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(
                itemTypeKey, PersistentDataType.STRING);
    }

    int armorTier(ItemStack item) {
        return switch (String.valueOf(itemType(item))) {
            case ARMOR_IRON -> 1;
            case ARMOR_DIAMOND -> 2;
            case ARMOR_NETHERITE -> 3;
            default -> 0;
        };
    }

    ItemStack create(String type) {
        return switch (type.toLowerCase()) {
            case "cryopod", CRYOPOD_EMPTY -> customItem(
                    Material.ECHO_SHARD,
                    CRYOPOD_EMPTY,
                    ChatColor.AQUA + "Cryopod",
                    List.of(
                            ChatColor.GRAY + "Sneak-right-click your companion to store it.",
                            ChatColor.GRAY + "Right-click a block to release a stored companion."));
            case "iron", ARMOR_IRON -> armorItem(
                    Material.IRON_HORSE_ARMOR, ARMOR_IRON, "Iron", 1);
            case "diamond", ARMOR_DIAMOND -> armorItem(
                    Material.DIAMOND_HORSE_ARMOR, ARMOR_DIAMOND, "Diamond", 2);
            case "netherite", ARMOR_NETHERITE -> armorItem(
                    Material.NETHERITE_HORSE_ARMOR, ARMOR_NETHERITE, "Netherite", 3);
            case "charm", BONDING_CHARM -> customItem(
                    Material.HEART_OF_THE_SEA,
                    BONDING_CHARM,
                    ChatColor.LIGHT_PURPLE + "Ghast Bonding Charm",
                    List.of(
                            ChatColor.GRAY + "Right-click an unowned Happy Ghast.",
                            ChatColor.GRAY + "Forms a permanent companion bond.",
                            ChatColor.DARK_GRAY + "Consumed on a successful bond."));
            default -> null;
        };
    }

    boolean bondHappyGhast(Player player, LivingEntity entity, ItemStack held) {
        if (!(entity instanceof HappyGhast ghast)) {
            player.sendMessage(ChatColor.RED + "This charm only bonds with a Happy Ghast.");
            return false;
        }
        UUID owner = companions.ownerId(ghast);
        if (owner != null) {
            player.sendMessage(owner.equals(player.getUniqueId())
                    ? ChatColor.YELLOW + "This Happy Ghast is already bonded to you."
                    : ChatColor.RED + "This Happy Ghast is already bonded to another player.");
            return false;
        }
        companions.claimHappyGhast(player, ghast);
        consumeOneInMainHand(player, held);
        ghast.getWorld().spawnParticle(
                org.bukkit.Particle.HEART, ghast.getLocation().add(0, 2.0, 0),
                18, 1.0, 0.8, 1.0, 0.05);
        ghast.getWorld().playSound(
                ghast.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.45f);
        player.sendMessage(ChatColor.LIGHT_PURPLE
                + "The Happy Ghast accepted the bond and became your companion.");
        return true;
    }

    boolean capture(Player player, LivingEntity entity, ItemStack held) {
        if (!player.isSneaking()) {
            player.sendMessage(ChatColor.YELLOW + "Sneak while using a cryopod to capture.");
            return false;
        }
        if (!companions.isSupported(entity) || !companions.isOwner(player, entity)) {
            player.sendMessage(ChatColor.RED + "You can only capture your own companion.");
            return false;
        }
        if (entity.isInsideVehicle() || !entity.getPassengers().isEmpty()) {
            player.sendMessage(ChatColor.RED + "Dismount all riders before capturing.");
            return false;
        }

        byte[] serialized;
        try {
            serialized = Bukkit.getUnsafe().serializeEntity(entity);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not serialize " + entity.getUniqueId()
                    + ": " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "That companion could not be stored.");
            return false;
        }

        ItemStack filled = customItem(
                Material.ECHO_SHARD,
                CRYOPOD_FILLED,
                ChatColor.AQUA + "Cryopod: " + ChatColor.WHITE + companions.displayName(entity),
                List.of(
                        ChatColor.GRAY + "Level " + companions.level(entity),
                        ChatColor.GRAY + "Armor: "
                                + companions.armorName(companions.armorTier(entity)),
                        ChatColor.DARK_GRAY + "Right-click a block to release."));
        ItemMeta meta = filled.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(entityDataKey, PersistentDataType.BYTE_ARRAY, serialized);
        data.set(podOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        meta.setMaxStackSize(1);
        filled.setItemMeta(meta);

        entity.remove();
        replaceOneInMainHand(player, held, filled);
        player.sendMessage(ChatColor.AQUA + companions.displayName(entity)
                + " was stored in the cryopod.");
        return true;
    }

    boolean release(
            Player player,
            ItemStack held,
            Block clickedBlock,
            BlockFace clickedFace) {
        ItemMeta meta = held.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String podOwner = data.get(podOwnerKey, PersistentDataType.STRING);
        if (!player.getUniqueId().toString().equals(podOwner)) {
            player.sendMessage(ChatColor.RED + "Only this companion's owner can release it.");
            return false;
        }
        byte[] serialized = data.get(entityDataKey, PersistentDataType.BYTE_ARRAY);
        if (serialized == null || serialized.length == 0) {
            player.sendMessage(ChatColor.RED + "This cryopod is damaged.");
            return false;
        }

        Block destinationBlock = clickedBlock.getRelative(clickedFace);
        Location destination = destinationBlock.getLocation().add(0.5, 0.05, 0.5);
        Entity entity;
        try {
            entity = Bukkit.getUnsafe().deserializeEntity(
                    serialized, destination.getWorld(), false, true);
            if (!(entity instanceof LivingEntity living)
                    || (!companions.isSupported(entity) && !(entity instanceof HappyGhast))
                    || !entity.spawnAt(destination, CreatureSpawnEvent.SpawnReason.CUSTOM)) {
                player.sendMessage(ChatColor.RED + "There is not enough room to release it here.");
                return false;
            }
            companions.initialize(living);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not release a cryopod: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "That companion could not be released.");
            return false;
        }

        replaceOneInMainHand(player, held, create(CRYOPOD_EMPTY));
        player.sendMessage(ChatColor.AQUA + companions.displayName(entity)
                + " was released from the cryopod.");
        return true;
    }

    boolean applyArmor(Player player, LivingEntity entity, ItemStack held, int tier) {
        if (!companions.isSupported(entity) || !companions.isOwner(player, entity)) {
            player.sendMessage(ChatColor.RED + "You can only armor your own companion.");
            return false;
        }
        int current = companions.armorTier(entity);
        if (tier <= current) {
            player.sendMessage(ChatColor.YELLOW + "This companion already has "
                    + companions.armorName(current) + " armor or better.");
            return false;
        }
        companions.setArmorTier(entity, tier);
        consumeOneInMainHand(player, held);
        player.sendMessage(ChatColor.GOLD + companions.displayName(entity) + " equipped "
                + companions.armorName(tier) + " companion armor.");
        return true;
    }

    void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack extra : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    void registerRecipes() {
        ShapedRecipe cryopod = new ShapedRecipe(
                new NamespacedKey(plugin, "cryopod"), create(CRYOPOD_EMPTY));
        cryopod.shape("CAC", "AEA", "CPC");
        cryopod.setIngredient('C', Material.COPPER_INGOT);
        cryopod.setIngredient('A', Material.AMETHYST_SHARD);
        cryopod.setIngredient('E', Material.ECHO_SHARD);
        cryopod.setIngredient('P', Material.ENDER_PEARL);
        Bukkit.addRecipe(cryopod, true);

        ItemStack ironItem = create(ARMOR_IRON);
        ShapedRecipe iron = new ShapedRecipe(new NamespacedKey(plugin, "iron_armor"), ironItem);
        iron.shape("III", "ILI", "III");
        iron.setIngredient('I', Material.IRON_INGOT);
        iron.setIngredient('L', Material.LEATHER);
        Bukkit.addRecipe(iron, true);

        ItemStack diamondItem = create(ARMOR_DIAMOND);
        ShapedRecipe diamond = new ShapedRecipe(
                new NamespacedKey(plugin, "diamond_armor"), diamondItem);
        diamond.shape("DDD", "DID", "DDD");
        diamond.setIngredient('D', Material.DIAMOND);
        diamond.setIngredient('I', new RecipeChoice.ExactChoice(ironItem));
        Bukkit.addRecipe(diamond, true);

        ShapedRecipe netherite = new ShapedRecipe(
                new NamespacedKey(plugin, "netherite_armor"), create(ARMOR_NETHERITE));
        netherite.shape("SGS", "GDG", "SGS");
        netherite.setIngredient('S', Material.NETHERITE_SCRAP);
        netherite.setIngredient('G', Material.GOLD_INGOT);
        netherite.setIngredient('D', new RecipeChoice.ExactChoice(diamondItem));
        Bukkit.addRecipe(netherite, true);

        ShapedRecipe charm = new ShapedRecipe(
                new NamespacedKey(plugin, "bonding_charm"), create(BONDING_CHARM));
        charm.shape("AGA", "LHL", "AEA");
        charm.setIngredient('A', Material.AMETHYST_SHARD);
        charm.setIngredient('G', Material.GHAST_TEAR);
        charm.setIngredient('L', Material.LEAD);
        charm.setIngredient('H', Material.HEART_OF_THE_SEA);
        charm.setIngredient('E', Material.ENDER_EYE);
        Bukkit.addRecipe(charm, true);
    }

    private ItemStack armorItem(Material material, String type, String tierName, int tier) {
        double armor = plugin.getConfig().getDouble(
                "armor." + tierName.toLowerCase() + ".armor", 0.0);
        double toughness = plugin.getConfig().getDouble(
                "armor." + tierName.toLowerCase() + ".toughness", 0.0);
        return customItem(
                material,
                type,
                ChatColor.GOLD + tierName + " Companion Armor",
                List.of(
                        ChatColor.GRAY + "Works on every owned companion.",
                        ChatColor.BLUE + "+" + format(armor) + " Armor",
                        ChatColor.BLUE + "+" + format(toughness) + " Armor Toughness",
                        ChatColor.DARK_GRAY + "Right-click a companion to equip."));
    }

    private ItemStack customItem(
            Material material,
            String type,
            String displayName,
            List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        meta.setLore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(
                itemTypeKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private void replaceOneInMainHand(Player player, ItemStack held, ItemStack replacement) {
        if (held.getAmount() == 1) {
            player.getInventory().setItemInMainHand(replacement);
            return;
        }
        held.setAmount(held.getAmount() - 1);
        give(player, replacement);
    }

    private void consumeOneInMainHand(Player player, ItemStack held) {
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            held.setAmount(held.getAmount() - 1);
        }
    }

    private String format(double value) {
        return value == Math.rint(value)
                ? Integer.toString((int) value)
                : String.format("%.1f", value);
    }
}
