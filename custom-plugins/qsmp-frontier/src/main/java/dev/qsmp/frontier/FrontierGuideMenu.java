package dev.qsmp.frontier;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class FrontierGuideMenu {
    private static final String TITLE = ChatColor.DARK_PURPLE + "QSMP Codex";

    private final FrontierItems items;
    private final WarfrontService warfront;
    private final ExpeditionService expeditions;
    private final ProgressionService progression;
    private final LegacyService legacies;

    FrontierGuideMenu(
            FrontierItems items,
            WarfrontService warfront,
            ExpeditionService expeditions,
            ProgressionService progression,
            LegacyService legacies) {
        this.items = items;
        this.warfront = warfront;
        this.expeditions = expeditions;
        this.progression = progression;
        this.legacies = legacies;
    }

    void open(Player player) {
        FrontierMenuHolder holder =
                new FrontierMenuHolder(FrontierMenuHolder.Menu.CODEX, 54, TITLE);
        Inventory inventory = holder.getInventory();
        inventory.setItem(4, icon(
                Material.NETHER_STAR,
                ChatColor.GOLD + "Battle Readiness",
                readinessLore(player)));
        inventory.setItem(10, icon(
                Material.IRON_AXE,
                ChatColor.DARK_RED + "Warfront",
                List.of(
                        ChatColor.GRAY + "Attune compass to the battlefield.",
                        ChatColor.DARK_GRAY + "Move there to trigger the raid naturally.")));
        inventory.setItem(12, icon(
                Material.CRYING_OBSIDIAN,
                ChatColor.DARK_AQUA + "Sealed Ruin",
                List.of(
                        ChatColor.GRAY + "Attune compass to a ruin signal.",
                        ChatColor.DARK_GRAY + "Break wards, clear sentinels, claim vault.")));
        inventory.setItem(14, icon(
                Material.EXPERIENCE_BOTTLE,
                ChatColor.GOLD + "Survivor Level",
                progression.summaryLore(player)));
        inventory.setItem(16, icon(
                Material.ANVIL,
                ChatColor.LIGHT_PURPLE + "Legacy Gear",
                legacies.statusLore(player)));
        inventory.setItem(28, icon(
                Material.ENCHANTED_BOOK,
                ChatColor.AQUA + "Enchanting & Anvil",
                List.of(
                        ChatColor.GRAY + "Main enchantments merge to Lv.10.",
                        ChatColor.GRAY + "Utility sub-options cap at Lv.5.",
                        ChatColor.GRAY + "Vanilla single-rank enchants stay Lv.1.",
                        ChatColor.DARK_GRAY + "Anvil work only charges on real changes.",
                        ChatColor.DARK_GRAY + "Required level is capped at 38.")));
        inventory.setItem(30, icon(
                Material.BARREL,
                ChatColor.YELLOW + "Outposts",
                List.of(
                        ChatColor.GRAY + "Recipes are unlocked in your recipe book.",
                        ChatColor.DARK_GRAY + "Place cores in good terrain for better yield.")));
        inventory.setItem(32, icon(
                Material.DRAGON_BREATH,
                ChatColor.DARK_PURPLE + "End Raid",
                List.of(
                        ChatColor.GRAY + "True Ending + QSMP resonance phases.",
                        ChatColor.GRAY + "Stop DPS and shatter stones during shields.",
                        ChatColor.DARK_GRAY + "Dragon Heart and Void Scale feed Legacy growth.")));
        inventory.setItem(34, icon(
                Material.AMETHYST_CLUSTER,
                ChatColor.LIGHT_PURPLE + "Required Resource Pack",
                List.of(
                        ChatColor.GRAY + "Custom UI, sprites, and cinematic cues.",
                        ChatColor.DARK_GRAY + "Keep server resource pack enabled.")));
        inventory.setItem(49, icon(
                Material.BARRIER,
                ChatColor.RED + "Close",
                List.of(ChatColor.GRAY + "Return to the world.")));
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.9f);
    }

    void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof FrontierMenuHolder holder)
                || holder.menu() != FrontierMenuHolder.Menu.CODEX) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        switch (event.getRawSlot()) {
            case 10 -> {
                player.closeInventory();
                warfront.guide(player);
            }
            case 12 -> {
                player.closeInventory();
                expeditions.guide(player);
            }
            case 14 -> {
                player.closeInventory();
                progression.open(player);
            }
            case 16 -> {
                player.closeInventory();
                legacies.open(player);
            }
            case 28 -> {
                player.closeInventory();
                player.sendTitle(
                        ChatColor.AQUA + "ENCHANTING",
                        ChatColor.GRAY + "Main Lv.10, utility Lv.5, single Lv.1",
                        6, 52, 12);
                player.sendMessage(ChatColor.AQUA
                        + "Enchanting caps: Protection family, Sharpness, Smite, Bane, Efficiency, Unbreaking, and Power can merge to Lv.10.");
                player.sendMessage(ChatColor.GRAY
                        + "Utility caps: Fortune, Looting, Fire Aspect, Knockback, Feather Falling, Thorns, Trident/Crossbow/fishing/mobility enchants cap at Lv.5.");
                player.sendMessage(ChatColor.GRAY
                        + "Single caps: Mending, Infinity, Flame, Silk Touch, Aqua Affinity, Channeling, Multishot, and curses stay Lv.1.");
                player.sendMessage(ChatColor.DARK_GRAY
                        + "Notes: Density = mace fall-smash damage, Breach = mace armor bypass, Wind Burst = upward burst after smash, Lunge = spear forward dash.");
                player.sendMessage(ChatColor.DARK_GRAY
                        + "Anvil cost is charged only for real changes and is capped at 38 levels.");
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
            }
            case 30 -> {
                player.closeInventory();
                items.unlockRecipes(player);
                player.sendTitle(
                        ChatColor.YELLOW + "OUTPOSTS",
                        ChatColor.GRAY + "Open your recipe book and choose a terrain role",
                        8, 50, 12);
                player.sendMessage(ChatColor.GOLD
                        + "Outpost recipes are unlocked. Good terrain matters: deep mines low, fisheries near water, greenhouses under sky.");
            }
            case 32 -> {
                player.closeInventory();
                player.sendTitle(
                        ChatColor.DARK_PURPLE + "END RAID",
                        ChatColor.GRAY + "When resonance stones appear, stop DPS and shatter them",
                        8, 70, 16);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.7f);
            }
            case 34 -> {
                player.closeInventory();
                player.sendTitle(
                        ChatColor.LIGHT_PURPLE + "RESOURCE PACK",
                        ChatColor.GRAY + "Custom UI, items, and combat cues are server-required",
                        6, 48, 12);
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.35f);
            }
            case 49 -> player.closeInventory();
            default -> {
            }
        }
    }

    private List<String> readinessLore(Player player) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Survivor");
        lore.addAll(progression.summaryLore(player));
        lore.add(ChatColor.DARK_GRAY + " ");
        lore.add(ChatColor.DARK_GRAY + "Main-hand Legacy");
        lore.addAll(legacies.statusLore(player).stream().limit(5).toList());
        return lore;
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
