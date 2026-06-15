package dev.qsmp.frontier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

enum OutpostType {
    WOOD("Lumber Camp", "Produces mixed timber.", Material.OAK_LOG),
    QUARRY("Stone Quarry", "Produces stone, coal, and building minerals.", Material.STONECUTTER),
    MINE("Deep Mine", "Produces metal ores and rare deep resources.", Material.RAW_IRON),
    RANCH("Ranch", "Produces food, leather, and animal supplies.", Material.WHEAT),
    GREENHOUSE("Greenhouse", "Produces renewable crops and brewing plants.", Material.GLOW_BERRIES),
    FISHERY("Fishery", "Produces fish and ocean materials.", Material.COD),
    WAREHOUSE("Warehouse", "Accepts overflow from nearby production sites.", Material.CHEST);

    private final String display;
    private final String description;
    private final Material icon;

    OutpostType(String display, String description, Material icon) {
        this.display = display;
        this.description = description;
        this.icon = icon;
    }

    String display() {
        return display;
    }

    String description() {
        return description;
    }

    Material icon() {
        return icon;
    }

    boolean produces() {
        return this != WAREHOUSE;
    }

    List<ItemStack> production(int tier) {
        int safeTier = Math.max(1, Math.min(3, tier));
        List<ItemStack> result = new ArrayList<>();
        switch (this) {
            case WOOD -> {
                result.add(stack(Material.OAK_LOG, 4 + safeTier * 2));
                result.add(stack(Material.SPRUCE_LOG, 2 + safeTier));
                if (safeTier >= 3) {
                    result.add(stack(Material.CHERRY_LOG, 2));
                }
            }
            case QUARRY -> {
                result.add(stack(Material.COBBLESTONE, 8 + safeTier * 4));
                result.add(stack(Material.COAL, 1 + safeTier));
                if (safeTier >= 2) {
                    result.add(stack(Material.TUFF, safeTier * 2));
                }
            }
            case MINE -> {
                result.add(stack(Material.RAW_IRON, 2 + safeTier));
                result.add(stack(Material.RAW_COPPER, 3 + safeTier * 2));
                if (safeTier >= 2) {
                    result.add(stack(Material.RAW_GOLD, safeTier - 1));
                }
                if (safeTier >= 3) {
                    result.add(stack(Material.REDSTONE, 4));
                }
            }
            case RANCH -> {
                result.add(stack(Material.WHEAT, 4 + safeTier * 2));
                result.add(stack(Material.COOKED_BEEF, safeTier));
                result.add(stack(Material.LEATHER, safeTier));
                if (safeTier >= 3) {
                    result.add(stack(Material.FEATHER, 4));
                }
            }
            case GREENHOUSE -> {
                result.add(stack(Material.CARROT, 3 + safeTier * 2));
                result.add(stack(Material.POTATO, 3 + safeTier * 2));
                result.add(stack(Material.SUGAR_CANE, 2 + safeTier));
                if (safeTier >= 2) {
                    result.add(stack(Material.GLOW_BERRIES, safeTier * 2));
                }
                if (safeTier >= 3) {
                    result.add(stack(Material.COCOA_BEANS, 3));
                }
            }
            case FISHERY -> {
                result.add(stack(Material.COD, 3 + safeTier * 2));
                result.add(stack(Material.SALMON, 1 + safeTier));
                if (safeTier >= 2) {
                    result.add(stack(Material.INK_SAC, safeTier * 2));
                }
                if (safeTier >= 3) {
                    result.add(stack(Material.PRISMARINE_SHARD, 2));
                }
            }
            case WAREHOUSE -> {
            }
        }
        return result;
    }

    String itemType() {
        return "outpost_" + name().toLowerCase(Locale.ROOT);
    }

    static OutpostType fromItemType(String value) {
        if (value == null || !value.startsWith("outpost_")) {
            return null;
        }
        try {
            return valueOf(value.substring("outpost_".length()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    String coloredName() {
        return ChatColor.GOLD + display + ChatColor.AQUA + " Core";
    }

    private ItemStack stack(Material material, int amount) {
        return new ItemStack(material, amount);
    }
}
