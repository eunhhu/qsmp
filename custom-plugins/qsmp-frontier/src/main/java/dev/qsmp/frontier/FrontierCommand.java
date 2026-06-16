package dev.qsmp.frontier;

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
import org.bukkit.entity.Player;

final class FrontierCommand implements CommandExecutor, TabCompleter {
    private final QSMPFrontier plugin;
    private final CompanionRoleService roles;
    private final FrontierItems items;
    private final OutpostService outposts;
    private final WarfrontService warfront;
    private final ExpeditionService expeditions;

    FrontierCommand(
            QSMPFrontier plugin,
            CompanionRoleService roles,
            FrontierItems items,
            OutpostService outposts,
            WarfrontService warfront,
            ExpeditionService expeditions) {
        this.plugin = plugin;
        this.roles = roles;
        this.items = items;
        this.outposts = outposts;
        this.warfront = warfront;
        this.expeditions = expeditions;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "role" -> role(sender, args);
            case "give" -> give(sender, args);
            case "outpost" -> outpost(sender, args);
            case "warfront" -> warfront(sender, args);
            case "expedition" -> expedition(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> {
                help(sender);
                yield true;
            }
        };
    }

    private boolean role(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED
                    + "Craft a Tactical Whistle to assign roles in survival.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(ChatColor.YELLOW
                    + "/frontier role <vanguard|ranger|medic|gatherer>");
            return true;
        }
        CompanionRole role = CompanionRole.parse(args[1]);
        if (role == null) {
            player.sendMessage(ChatColor.RED + "Unknown companion role.");
            return true;
        }
        Entity target = player.getTargetEntity(12);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Look directly at your companion.");
            return true;
        }
        roles.assign(player, target, role);
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.YELLOW
                    + "/frontier give <player> <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>");
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        OutpostType type = OutpostType.fromItemType("outpost_" + args[2].toLowerCase(Locale.ROOT));
        if (player == null || type == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player or outpost type.");
            return true;
        }
        items.give(player, items.outpostCore(type));
        sender.sendMessage(ChatColor.GREEN + type.display() + " core given to " + player.getName());
        return true;
    }

    private boolean warfront(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW
                    + "/frontier warfront <build|buildspawn|start|stop|status>");
            return true;
        }
        if (args[1].equalsIgnoreCase("buildspawn")) {
            warfront.buildSpawn(sender);
            return true;
        }
        if (args[1].equalsIgnoreCase("start")) {
            warfront.start(sender);
            return true;
        }
        if (args[1].equalsIgnoreCase("stop")) {
            if (sender.hasPermission("qsmpfrontier.admin")) {
                warfront.stop(true);
            } else {
                sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            }
            return true;
        }
        if (args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(
                    ChatColor.GOLD + "Warfront: " + ChatColor.GRAY + warfront.status());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use /frontier warfront buildspawn from the console.");
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "build" -> warfront.build(player);
            default -> player.sendMessage(ChatColor.RED + "Unknown warfront action.");
        }
        return true;
    }

    private boolean outpost(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("pulse")) {
            outposts.produce();
            sender.sendMessage(ChatColor.GREEN + "Outpost production pulse completed.");
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("buildspawn")) {
            OutpostType type = OutpostType.fromItemType(
                    "outpost_" + args[2].toLowerCase(Locale.ROOT));
            if (type == null) {
                sender.sendMessage(ChatColor.RED + "Unknown outpost type.");
            } else {
                outposts.createPublic(sender, type);
            }
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW
                + "/frontier outpost <buildspawn type|pulse>");
        return true;
    }

    private boolean expedition(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(
                    ChatColor.GOLD + "Expedition: " + ChatColor.GRAY + expeditions.status());
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("find")) {
            if (sender instanceof Player player) {
                expeditions.guide(player);
            } else {
                sender.sendMessage("Players only.");
            }
            return true;
        }
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("buildspawn")) {
            expeditions.buildSpawn(sender);
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
            expeditions.reset(true);
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW
                + "/frontier expedition <find|status|buildspawn|reset>");
        return true;
    }

    private boolean status(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "QSMP Frontier"
                + ChatColor.GRAY + " | Warfront: " + warfront.status()
                + " | Expedition: " + expeditions.status()
                + " | Outposts: " + outposts.count());
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return true;
        }
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "QSMPFrontier settings reloaded.");
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "QSMP Frontier");
        sender.sendMessage(ChatColor.AQUA
                + "Use the Frontier Compass, Tactical Whistle, and crafted outpost items.");
        sender.sendMessage(ChatColor.YELLOW + "/frontier status"
                + ChatColor.GRAY + " - show raid and outpost state");
        if (sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/frontier role <role>"
                    + ChatColor.GRAY + " - operator repair fallback");
            sender.sendMessage(ChatColor.YELLOW + "/frontier warfront <build|start|stop>");
            sender.sendMessage(ChatColor.YELLOW
                    + "/frontier give <player> <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>");
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("status"));
            if (sender.hasPermission("qsmpfrontier.admin")) {
                choices.add("role");
                choices.add("give");
                choices.add("outpost");
                choices.add("warfront");
                choices.add("expedition");
                choices.add("reload");
            } else if (sender instanceof Player) {
                choices.add("expedition");
            }
            return matching(choices, args[0]);
        }
        if (args.length == 2
                && args[0].equalsIgnoreCase("role")
                && sender.hasPermission("qsmpfrontier.admin")) {
            return matching(List.of("vanguard", "ranger", "medic", "gatherer"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("warfront")) {
            return matching(List.of("build", "buildspawn", "start", "stop", "status"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("outpost")) {
            return matching(List.of("buildspawn", "pulse"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("expedition")) {
            List<String> choices = new ArrayList<>(List.of("find", "status"));
            if (sender.hasPermission("qsmpfrontier.admin")) {
                choices.add("buildspawn");
                choices.add("reset");
            }
            return matching(choices, args[1]);
        }
        if (args.length == 3
                && args[0].equalsIgnoreCase("outpost")
                && args[1].equalsIgnoreCase("buildspawn")) {
            return matching(
                    List.of("wood", "quarry", "mine", "ranch",
                            "greenhouse", "fishery", "warehouse"),
                    args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return matching(
                    List.of("wood", "quarry", "mine", "ranch",
                            "greenhouse", "fishery", "warehouse"),
                    args[2]);
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
