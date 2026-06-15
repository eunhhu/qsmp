package dev.qsmp.frontier;

import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

final class FrontierItems {
    static final String TACTICAL_WHISTLE = "tactical_whistle";
    static final String FIELD_COMPASS = "field_compass";
    static final String UPGRADE_TIER_2 = "outpost_upgrade_2";
    static final String UPGRADE_TIER_3 = "outpost_upgrade_3";

    private final QSMPFrontier plugin;
    private final FrontierKeys keys;

    FrontierItems(QSMPFrontier plugin, FrontierKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    ItemStack outpostCore(OutpostType type) {
        ItemStack item = customItem(
                Material.BARREL,
                type.itemType(),
                type.coloredName(),
                List.of(
                        ChatColor.GRAY + type.description(),
                        ChatColor.GRAY + "Place it to establish a persistent site.",
                        ChatColor.AQUA + "Gatherers and nearby warehouses improve operation."));
        return item;
    }

    ItemStack tacticalWhistle() {
        ItemStack item = customItem(
                Material.GOAT_HORN,
                TACTICAL_WHISTLE,
                ChatColor.GOLD + "Tactical Whistle",
                whistleLore(CompanionRole.VANGUARD));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
                keys.whistleRole, PersistentDataType.STRING, CompanionRole.VANGUARD.name());
        item.setItemMeta(meta);
        return item;
    }

    ItemStack fieldCompass() {
        return customItem(
                Material.COMPASS,
                FIELD_COMPASS,
                ChatColor.GOLD + "Frontier Compass",
                List.of(
                        ChatColor.GRAY + "Right-click to attune it to the warfront.",
                        ChatColor.GRAY + "The needle then points toward the battlefield.",
                        ChatColor.DARK_AQUA + "The raid begins when explorers enter the field."));
    }

    ItemStack upgradeKit(int targetTier) {
        String type = targetTier == 2 ? UPGRADE_TIER_2 : UPGRADE_TIER_3;
        Material material = targetTier == 2 ? Material.IRON_BLOCK : Material.DIAMOND_BLOCK;
        return customItem(
                material,
                type,
                ChatColor.GOLD + "Outpost Upgrade Kit " + roman(targetTier),
                List.of(
                        ChatColor.GRAY + "Right-click an owned outpost to install.",
                        ChatColor.AQUA + "Raises production and storage throughput.",
                        ChatColor.DARK_GRAY + "Target tier: " + targetTier));
    }

    String itemType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(
                keys.itemType, PersistentDataType.STRING);
    }

    boolean is(ItemStack item, String expected) {
        return expected.equals(itemType(item));
    }

    OutpostType outpostType(ItemStack item) {
        return OutpostType.fromItemType(itemType(item));
    }

    int upgradeTarget(ItemStack item) {
        return switch (String.valueOf(itemType(item))) {
            case UPGRADE_TIER_2 -> 2;
            case UPGRADE_TIER_3 -> 3;
            default -> 0;
        };
    }

    CompanionRole whistleRole(ItemStack item) {
        if (!is(item, TACTICAL_WHISTLE)) {
            return null;
        }
        String stored = item.getItemMeta().getPersistentDataContainer().get(
                keys.whistleRole, PersistentDataType.STRING);
        CompanionRole role = stored == null ? null : CompanionRole.parse(stored);
        return role == null ? CompanionRole.VANGUARD : role;
    }

    CompanionRole cycleWhistle(Player player, ItemStack whistle) {
        CompanionRole current = whistleRole(whistle);
        CompanionRole[] roles = CompanionRole.values();
        CompanionRole next = roles[(current.ordinal() + 1) % roles.length];
        ItemMeta meta = whistle.getItemMeta();
        meta.getPersistentDataContainer().set(
                keys.whistleRole, PersistentDataType.STRING, next.name());
        meta.setLore(whistleLore(next));
        whistle.setItemMeta(meta);
        player.sendActionBar(ChatColor.GOLD + "Whistle stance: " + ChatColor.AQUA
                + next.display());
        return next;
    }

    void giveFieldCompassOnce(Player player) {
        if (player.getPersistentDataContainer().has(
                keys.fieldGuideGiven, PersistentDataType.BYTE)) {
            return;
        }
        player.getPersistentDataContainer().set(
                keys.fieldGuideGiven, PersistentDataType.BYTE, (byte) 1);
        give(player, fieldCompass());
        player.sendMessage(ChatColor.GOLD + "A Frontier Compass was added to your inventory.");
        player.sendMessage(ChatColor.GRAY
                + "Right-click it to locate the battlefield. Entering the field starts its event.");
    }

    void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack extra : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    void consumeOne(Player player, ItemStack held) {
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            held.setAmount(held.getAmount() - 1);
        }
    }

    void registerRecipes() {
        addOutpostRecipe(OutpostType.WOOD, Material.OAK_LOG, Material.IRON_AXE);
        addOutpostRecipe(OutpostType.QUARRY, Material.STONE_BRICKS, Material.IRON_PICKAXE);
        addOutpostRecipe(OutpostType.MINE, Material.DEEPSLATE_TILES, Material.DIAMOND_PICKAXE);
        addOutpostRecipe(OutpostType.RANCH, Material.HAY_BLOCK, Material.GOLDEN_CARROT);
        addOutpostRecipe(OutpostType.GREENHOUSE, Material.MOSS_BLOCK, Material.GLOW_BERRIES);
        addOutpostRecipe(OutpostType.FISHERY, Material.PRISMARINE, Material.FISHING_ROD);
        addOutpostRecipe(OutpostType.WAREHOUSE, Material.CHEST, Material.HOPPER);

        ShapedRecipe whistle = new ShapedRecipe(
                new NamespacedKey(plugin, TACTICAL_WHISTLE), tacticalWhistle());
        whistle.shape(" C ", "GHG", " R ");
        whistle.setIngredient('C', Material.COPPER_INGOT);
        whistle.setIngredient('G', Material.GOLD_INGOT);
        whistle.setIngredient('H', Material.GOAT_HORN);
        whistle.setIngredient('R', Material.REDSTONE);
        Bukkit.addRecipe(whistle, true);

        ShapedRecipe compass = new ShapedRecipe(
                new NamespacedKey(plugin, FIELD_COMPASS), fieldCompass());
        compass.shape(" A ", "ACA", " A ");
        compass.setIngredient('A', Material.AMETHYST_SHARD);
        compass.setIngredient('C', Material.COMPASS);
        Bukkit.addRecipe(compass, true);

        ShapedRecipe tierTwo = new ShapedRecipe(
                new NamespacedKey(plugin, UPGRADE_TIER_2), upgradeKit(2));
        tierTwo.shape("IRI", "RLR", "IRI");
        tierTwo.setIngredient('I', Material.IRON_BLOCK);
        tierTwo.setIngredient('R', Material.REDSTONE_BLOCK);
        tierTwo.setIngredient('L', Material.LODESTONE);
        Bukkit.addRecipe(tierTwo, true);

        ShapelessRecipe tierThree = new ShapelessRecipe(
                new NamespacedKey(plugin, UPGRADE_TIER_3), upgradeKit(3));
        tierThree.addIngredient(new RecipeChoice.ExactChoice(upgradeKit(2)));
        tierThree.addIngredient(4, Material.DIAMOND);
        tierThree.addIngredient(2, Material.NETHERITE_SCRAP);
        Bukkit.addRecipe(tierThree, true);
    }

    private void addOutpostRecipe(OutpostType type, Material shell, Material catalyst) {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(plugin, type.itemType()), outpostCore(type));
        recipe.shape("SCS", "BLB", "SRS");
        recipe.setIngredient('S', shell);
        recipe.setIngredient('C', catalyst);
        recipe.setIngredient('B', Material.BARREL);
        recipe.setIngredient('L', Material.LODESTONE);
        recipe.setIngredient('R', new RecipeChoice.MaterialChoice(type.icon()));
        Bukkit.addRecipe(recipe, true);
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
                keys.itemType, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private List<String> whistleLore(CompanionRole role) {
        return List.of(
                ChatColor.GRAY + "Right-click air or a block to change stance.",
                ChatColor.GRAY + "Right-click your companion to assign it.",
                ChatColor.AQUA + "Selected: " + role.display(),
                ChatColor.DARK_GRAY + role.description());
    }

    private String roman(int tier) {
        return tier == 2 ? "II" : "III";
    }
}
