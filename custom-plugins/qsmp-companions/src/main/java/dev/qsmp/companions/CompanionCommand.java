package dev.qsmp.companions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class CompanionCommand implements CommandExecutor, TabCompleter {
    private final QSMPCompanions plugin;
    private final CompanionService companions;
    private final CompanionItems items;

    CompanionCommand(
            QSMPCompanions plugin,
            CompanionService companions,
            CompanionItems items) {
        this.plugin = plugin;
        this.companions = companions;
        this.items = items;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA
                    + "Sneak-right-click your companion with an empty hand to inspect it.");
            sender.sendMessage(ChatColor.LIGHT_PURPLE
                    + "Craft a Ghast Bonding Charm to bond a Happy Ghast.");
            if (sender.hasPermission("qsmpcompanions.admin")) {
                sender.sendMessage(ChatColor.GOLD + "/companion claim"
                        + ChatColor.GRAY + " - operator repair fallback");
            }
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(sender);
            case "claim" -> claim(sender);
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            default -> false;
        };
    }

    private boolean info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Entity target = player.getTargetEntity(10);
        if (!(target instanceof LivingEntity living) || !companions.isSupported(target)) {
            player.sendMessage(ChatColor.RED + "Look directly at an owned companion.");
            return true;
        }

        companions.sendInfo(player, living);
        return true;
    }

    private boolean claim(CommandSender sender) {
        if (!sender.hasPermission("qsmpcompanions.admin")) {
            sender.sendMessage(ChatColor.RED
                    + "Craft a Ghast Bonding Charm to claim one in survival.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Entity target = player.getTargetEntity(10);
        if (!(target instanceof HappyGhast ghast)) {
            player.sendMessage(ChatColor.RED + "Look directly at a Happy Ghast.");
            return true;
        }
        if (companions.ownerId(ghast) != null) {
            player.sendMessage(ChatColor.RED + "That Happy Ghast already has an owner.");
            return true;
        }
        companions.claimHappyGhast(player, ghast);
        player.sendMessage(ChatColor.AQUA + "Happy Ghast claimed as your companion.");
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qsmpcompanions.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.YELLOW
                    + "/companion give <player> <cryopod|iron|diamond|netherite|charm>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        ItemStack item = items.create(args[2]);
        if (target == null || item == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player or item.");
            return true;
        }
        items.give(target, item);
        sender.sendMessage(ChatColor.GREEN + "Item given to " + target.getName() + ".");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("qsmpcompanions.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        plugin.reloadSettings();
        sender.sendMessage(ChatColor.GREEN + "QSMPCompanions settings reloaded.");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("info"));
            if (sender.hasPermission("qsmpcompanions.admin")) {
                choices.add("claim");
                choices.add("give");
                choices.add("reload");
            }
            return matching(choices, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return matching(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return matching(
                    List.of("cryopod", "iron", "diamond", "netherite", "charm"), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> matching(List<String> choices, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

}
