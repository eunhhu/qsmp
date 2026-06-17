package dev.qsmp.frontier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class ProgressionService {
    private final QSMPFrontier plugin;
    private final File storageFile;
    private final NamespacedKey healthKey;
    private final NamespacedKey speedKey;
    private final Map<UUID, SurvivorRecord> records = new HashMap<>();
    private final Map<BlockKey, Long> playerPlacedBlocks = new HashMap<>();
    private final Map<UUID, Long> commonMiningXpAt = new HashMap<>();
    private final Map<UUID, Long> survivorXpCueAt = new HashMap<>();
    private final Map<UUID, Long> survivorImpactCueAt = new HashMap<>();
    private final Map<UUID, Long> survivorGuardCueAt = new HashMap<>();

    ProgressionService(QSMPFrontier plugin) {
        this.plugin = plugin;
        storageFile = new File(plugin.getDataFolder(), "progression.yml");
        healthKey = new NamespacedKey(plugin, "survivor_endurance_health");
        speedKey = new NamespacedKey(plugin, "survivor_agility_speed");
    }

    void load() {
        records.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = data.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String path = "players." + key + ".";
                SurvivorRecord record = new SurvivorRecord();
                record.name = data.getString(path + "name", "");
                record.level = clamp(data.getInt(path + "level", 1), 1, maxLevel());
                record.xp = Math.max(0, data.getInt(path + "xp", 0));
                record.points = Math.max(0, data.getInt(path + "points", 0));
                record.traitPoints = Math.max(0, data.getInt(path + "trait-points", 0));
                record.freeRespec = data.getBoolean(path + "free-respec", true);
                for (SurvivorStat stat : SurvivorStat.values()) {
                    record.stats.put(stat, Math.max(0,
                            data.getInt(path + "stats." + stat.key(), 0)));
                }
                records.put(id, record);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignored invalid survivor record " + key);
            }
        }
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, SurvivorRecord> entry : records.entrySet()) {
            String path = "players." + entry.getKey() + ".";
            SurvivorRecord record = entry.getValue();
            data.set(path + "name", record.name);
            data.set(path + "level", record.level);
            data.set(path + "xp", record.xp);
            data.set(path + "points", record.points);
            data.set(path + "trait-points", record.traitPoints);
            data.set(path + "free-respec", record.freeRespec);
            for (SurvivorStat stat : SurvivorStat.values()) {
                data.set(path + "stats." + stat.key(), record.stat(stat));
            }
        }
        try {
            data.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save survivor progression: "
                    + exception.getMessage());
        }
    }

    void onJoin(Player player) {
        SurvivorRecord record = record(player);
        record.name = player.getName();
        applyStats(player);
        if (record.points > 0) {
            player.sendActionBar(ChatColor.GOLD + "Survivor Lv. " + record.level
                    + ChatColor.GRAY + " | "
                    + ChatColor.AQUA + record.points
                    + ChatColor.GRAY + " stat point(s) ready in the Codex");
        }
    }

    void onQuit(Player player) {
        SurvivorRecord record = records.get(player.getUniqueId());
        if (record != null) {
            record.name = player.getName();
            save();
        }
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("spend")) {
            return spend(sender, args);
        }
        if (args[0].equalsIgnoreCase("respec")) {
            return respec(sender);
        }
        if (args[0].equalsIgnoreCase("grant")) {
            return grantCommand(sender, args);
        }
        sender.sendMessage(ChatColor.YELLOW
                + "/survivor opens the Survivor Core GUI. Commands: spend, respec.");
        return true;
    }

    List<String> tab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("stats", "spend", "respec"));
            if (sender.hasPermission("qsmpfrontier.admin")) {
                choices.add("grant");
            }
            return match(choices, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spend")) {
            return match(List.of("might", "endurance", "agility", "wisdom", "command"), args[1]);
        }
        if (args.length == 2
                && args[0].equalsIgnoreCase("grant")
                && sender.hasPermission("qsmpfrontier.admin")) {
            return match(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    void grantXp(Player player, int amount, String reason) {
        if (amount <= 0 || !plugin.getConfig().getBoolean("progression.enabled", true)) {
            return;
        }
        SurvivorRecord record = record(player);
        record.name = player.getName();
        int remaining = amount;
        int levels = 0;
        while (remaining > 0 && record.level < maxLevel()) {
            int required = nextXp(record.level);
            int space = required - record.xp;
            int added = Math.min(space, remaining);
            record.xp += added;
            remaining -= added;
            if (record.xp >= required) {
                record.xp = 0;
                record.level++;
                record.points++;
                levels++;
                if (record.level % 10 == 0) {
                    record.traitPoints++;
                }
            }
        }
        if (record.level >= maxLevel() && remaining > 0) {
            record.xp = 0;
        }
        if (levels > 0) {
            applyStats(player);
            player.getWorld().spawnParticle(
                    Particle.TOTEM_OF_UNDYING,
                    player.getLocation().add(0.0, 1.1, 0.0),
                    34,
                    0.55,
                    0.75,
                    0.55,
                    0.05);
            player.getWorld().playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            player.sendTitle(
                    ChatColor.GOLD + "SURVIVOR LEVEL " + record.level,
                    ChatColor.YELLOW + "+" + levels + " stat point(s)",
                    10, 60, 20);
        } else {
            player.sendActionBar(ChatColor.AQUA + "+" + amount + " Survivor XP"
                    + ChatColor.GRAY + " - " + reason);
            playXpCue(player, amount);
        }
        save();
    }

    void onIncomingDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isCancelled()) {
            return;
        }
        double multiplier = incomingDamageMultiplier(player, event.getCause());
        if (multiplier < 1.0) {
            double original = event.getDamage();
            double adjusted = Math.max(0.0, original * multiplier);
            event.setDamage(adjusted);
            playGuardCue(player, original - adjusted);
        }
    }

    void onOutgoingDamage(EntityDamageByEntityEvent event, Player attacker) {
        if (event.isCancelled()) {
            return;
        }
        double multiplier = outgoingDamageMultiplier(attacker);
        if (multiplier > 1.0) {
            event.setDamage(event.getDamage() * multiplier);
            if (event.getEntity() instanceof LivingEntity target) {
                playMightCue(attacker, target, multiplier);
            }
        }
    }

    void onVanillaBossDeath(EntityDeathEvent event, LegacyService legacies) {
        if (!(event.getEntity() instanceof EnderDragon || event.getEntity() instanceof Wither)) {
            return;
        }
        int survivorXp = event.getEntity() instanceof EnderDragon
                ? plugin.getConfig().getInt("progression.rewards.ender-dragon-survivor-xp", 1500)
                : plugin.getConfig().getInt("progression.rewards.wither-survivor-xp", 900);
        int legacyXp = event.getEntity() instanceof EnderDragon
                ? plugin.getConfig().getInt("legacy.rewards.ender-dragon-xp", 500)
                : plugin.getConfig().getInt("legacy.rewards.wither-xp", 300);
        String reason = event.getEntity() instanceof EnderDragon
                ? "Ender Dragon kill"
                : "Wither kill";
        Set<Player> rewarded = new HashSet<>();
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            rewarded.add(killer);
        }
        for (org.bukkit.entity.Entity entity : event.getEntity().getNearbyEntities(128.0, 96.0, 128.0)) {
            if (entity instanceof Player player && !player.isDead()) {
                rewarded.add(player);
            }
        }
        for (Player player : rewarded) {
            grantXp(player, survivorXp, reason);
            legacies.grantRaidXp(player, legacyXp, reason);
        }
    }

    void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("progression.rewards.gather.enabled", true)) {
            return;
        }
        Material material = event.getBlockPlaced().getType();
        if (!trackedPlacedMaterial(material)) {
            return;
        }
        playerPlacedBlocks.put(BlockKey.of(event.getBlockPlaced()), System.currentTimeMillis());
        prunePlacedBlocks();
    }

    void onNaturalBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()
                || !plugin.getConfig().getBoolean("progression.rewards.gather.enabled", true)) {
            return;
        }
        Block block = event.getBlock();
        int xp = gatherXp(block);
        if (xp <= 0) {
            return;
        }
        BlockKey key = BlockKey.of(block);
        if (playerPlacedBlocks.remove(key) != null) {
            return;
        }
        if (commonMiningXp(block.getType())) {
            long now = System.currentTimeMillis();
            long cooldown = Math.max(0L, plugin.getConfig()
                    .getLong("progression.rewards.gather.common-cooldown-ms", 1200L));
            long last = commonMiningXpAt.getOrDefault(event.getPlayer().getUniqueId(), 0L);
            if (now - last < cooldown) {
                return;
            }
            commonMiningXpAt.put(event.getPlayer().getUniqueId(), now);
        }
        grantXp(event.getPlayer(), xp, gatherReason(block.getType()));
    }

    void onOrdinaryMobDeath(EntityDeathEvent event) {
        if (!plugin.getConfig().getBoolean("progression.rewards.hunting.enabled", true)
                || event.getEntity() instanceof EnderDragon
                || event.getEntity() instanceof Wither
                || !(event.getEntity() instanceof Monster)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity().fromMobSpawner()) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getEntity().getEntitySpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || reason == CreatureSpawnEvent.SpawnReason.EGG
                || reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND
                || reason == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        int xp = event.getEntity().getScoreboardTags().contains("qsmp_frontier")
                ? plugin.getConfig().getInt("progression.rewards.hunting.frontier-mob-xp", 22)
                : plugin.getConfig().getInt("progression.rewards.hunting.hostile-mob-xp", 14);
        grantXp(killer, xp, "hunting");
    }

    void applyStats(Player player) {
        SurvivorRecord record = record(player);
        applyModifier(
                player,
                Attribute.MAX_HEALTH,
                healthKey,
                record.stat(SurvivorStat.ENDURANCE)
                        * plugin.getConfig().getDouble("progression.stats.endurance-health", 1.0),
                AttributeModifier.Operation.ADD_NUMBER);
        applyModifier(
                player,
                Attribute.MOVEMENT_SPEED,
                speedKey,
                record.stat(SurvivorStat.AGILITY)
                        * plugin.getConfig().getDouble("progression.stats.agility-speed-scalar", 0.004),
                AttributeModifier.Operation.ADD_SCALAR);
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null && player.getHealth() > health.getValue()) {
            player.setHealth(health.getValue());
        }
    }

    double rollCooldownMultiplier(Player player) {
        int agility = record(player).stat(SurvivorStat.AGILITY);
        double reduction = agility * plugin.getConfig()
                .getDouble("progression.stats.agility-roll-cooldown-reduction", 0.025);
        return Math.max(0.55, 1.0 - reduction);
    }

    double legacyXpMultiplier(Player player) {
        int wisdom = record(player).stat(SurvivorStat.WISDOM);
        return 1.0 + wisdom * plugin.getConfig()
                .getDouble("progression.stats.wisdom-legacy-xp", 0.025);
    }

    double productionMultiplier(UUID owner) {
        SurvivorRecord record = records.get(owner);
        if (record == null) {
            return 1.0;
        }
        return 1.0 + record.stat(SurvivorStat.COMMAND)
                * plugin.getConfig().getDouble("progression.stats.command-outpost-production", 0.025);
    }

    List<String> summaryLore(Player player) {
        SurvivorRecord record = record(player);
        int required = record.level >= maxLevel() ? 0 : nextXp(record.level);
        List<String> lore = new ArrayList<>(List.of(
                ChatColor.GOLD + "Survivor Lv. " + record.level + "/" + maxLevel(),
                ChatColor.GRAY + "XP " + record.xp + "/" + required + " "
                        + xpBar(record.xp, required),
                ChatColor.GRAY + "Unspent stat points: " + ChatColor.AQUA + record.points));
        SurvivorStat strongest = strongestStat(record);
        if (strongest != null) {
            lore.add(ChatColor.DARK_AQUA + "Current identity: " + strongest.display()
                    + " " + record.stat(strongest));
        } else {
            lore.add(ChatColor.DARK_GRAY + "Spend points in Survivor Core.");
        }
        return lore;
    }

    List<String> xpRouteLore() {
        return List.of(
                ChatColor.GRAY + "Hunt natural hostile mobs.",
                ChatColor.GRAY + "Mine ores, ancient debris, and stone.",
                ChatColor.GRAY + "Harvest mature crops and natural logs.",
                ChatColor.GRAY + "Clear raids, ruins, bosses, and outposts.",
                ChatColor.DARK_GRAY + "Spawner/custom mobs do not farm Survivor XP.");
    }

    List<String> battleLore(Player player) {
        SurvivorRecord record = record(player);
        return List.of(
                ChatColor.GRAY + "Might: +"
                        + percent(outgoingDamageMultiplier(player) - 1.0)
                        + " final damage",
                ChatColor.GRAY + "Endurance: -"
                        + percent(1.0 - incomingDamageMultiplier(player, EntityDamageEvent.DamageCause.ENTITY_ATTACK))
                        + " incoming damage",
                ChatColor.GRAY + "Agility: roll cooldown x"
                        + String.format(Locale.ROOT, "%.2f", rollCooldownMultiplier(player)),
                ChatColor.GRAY + "Wisdom: x"
                        + String.format(Locale.ROOT, "%.2f", legacyXpMultiplier(player))
                        + " Legacy XP",
                ChatColor.GRAY + "Command: x"
                        + String.format(Locale.ROOT, "%.2f", 1.0 + record.stat(SurvivorStat.COMMAND)
                                * plugin.getConfig().getDouble(
                                        "progression.stats.command-outpost-production", 0.025))
                        + " outpost yield");
    }

    void open(Player player) {
        SurvivorRecord record = record(player);
        int required = record.level >= maxLevel() ? 0 : nextXp(record.level);
        int cap = Math.max(1, plugin.getConfig().getInt("progression.stat-cap", 20));
        FrontierMenuHolder holder =
                new FrontierMenuHolder(FrontierMenuHolder.Menu.SURVIVOR, 54,
                        ChatColor.GOLD + "Survivor Core");
        Inventory inventory = holder.getInventory();
        inventory.setItem(4, icon(
                Material.EXPERIENCE_BOTTLE,
                ChatColor.GOLD + "Survivor Lv. " + record.level + "/" + maxLevel(),
                List.of(
                        ChatColor.GRAY + "XP " + record.xp + "/" + required + " "
                                + xpBar(record.xp, required),
                        ChatColor.GRAY + "Stat points: " + ChatColor.AQUA + record.points,
                        ChatColor.GRAY + "Trait points: " + ChatColor.LIGHT_PURPLE
                                + record.traitPoints,
                        ChatColor.DARK_AQUA + "XP routes: hunt, mine, harvest, raids.",
                        ChatColor.DARK_GRAY + "Placed blocks are ignored for gather XP.")));
        inventory.setItem(11, icon(
                Material.COMPASS,
                ChatColor.AQUA + "XP Routes",
                xpRouteLore()));
        inventory.setItem(15, icon(
                Material.SHIELD,
                ChatColor.BLUE + "Battle Readout",
                battleLore(player)));
        for (SurvivorStat stat : SurvivorStat.values()) {
            inventory.setItem(statSlot(stat), statIcon(record, stat, cap));
        }
        inventory.setItem(49, icon(
                Material.ECHO_SHARD,
                ChatColor.AQUA + "Respec",
                List.of(
                        ChatColor.GRAY + "Returns spent stat points.",
                        record.freeRespec
                                ? ChatColor.GREEN + "First reset is free."
                                : ChatColor.DARK_AQUA + "Cost: 1 Echo Shard.",
                        ChatColor.YELLOW + "Click to reset.")));
        inventory.setItem(53, icon(
                Material.BARRIER,
                ChatColor.RED + "Close",
                List.of(ChatColor.GRAY + "Return to the world.")));
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.05f);
    }

    void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof FrontierMenuHolder holder)
                || holder.menu() != FrontierMenuHolder.Menu.SURVIVOR) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SurvivorStat stat = statBySlot(event.getRawSlot());
        if (stat != null) {
            spendPoint(player, stat);
            refresh(player);
            return;
        }
        if (event.getRawSlot() == 49) {
            respecPlayer(player);
            refresh(player);
            return;
        }
        if (event.getRawSlot() == 53) {
            player.closeInventory();
        }
    }

    private boolean spend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "/survivor spend <might|endurance|agility|wisdom|command>");
            return true;
        }
        SurvivorStat stat = SurvivorStat.parse(args[1]);
        if (stat == null) {
            player.sendActionBar(ChatColor.RED + "Unknown survivor stat.");
            return true;
        }
        spendPoint(player, stat);
        open(player);
        return true;
    }

    private boolean respec(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        respecPlayer(player);
        open(player);
        return true;
    }

    private boolean spendPoint(Player player, SurvivorStat stat) {
        SurvivorRecord record = record(player);
        if (record.points <= 0) {
            player.sendActionBar(ChatColor.RED + "No unspent stat points.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return false;
        }
        int cap = Math.max(1, plugin.getConfig().getInt("progression.stat-cap", 20));
        if (record.stat(stat) >= cap) {
            player.sendActionBar(ChatColor.RED + stat.display() + " is capped.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return false;
        }
        record.stats.put(stat, record.stat(stat) + 1);
        record.points--;
        applyStats(player);
        save();
        player.sendActionBar(ChatColor.GREEN + stat.display() + " "
                + record.stat(stat) + "/" + cap);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        return true;
    }

    private boolean respecPlayer(Player player) {
        SurvivorRecord record = record(player);
        if (!record.freeRespec && !player.hasPermission("qsmpfrontier.admin")) {
            ItemStack cost = new ItemStack(Material.ECHO_SHARD, 1);
            if (!player.getInventory().containsAtLeast(cost, 1)) {
                player.sendActionBar(ChatColor.RED + "Respec requires one Echo Shard.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                return false;
            }
            player.getInventory().removeItem(cost);
        }
        int spent = record.stats.values().stream().mapToInt(Integer::intValue).sum();
        for (SurvivorStat stat : SurvivorStat.values()) {
            record.stats.put(stat, 0);
        }
        record.points += spent;
        record.freeRespec = false;
        applyStats(player);
        save();
        player.sendActionBar(ChatColor.GREEN + "Stats reset. Returned points: " + spent);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
        return true;
    }

    private ItemStack statIcon(SurvivorRecord record, SurvivorStat stat, int cap) {
        int value = record.stat(stat);
        List<String> lore = new ArrayList<>(List.of(
                ChatColor.GRAY + "Rank " + value + "/" + cap,
                ChatColor.DARK_AQUA + statEffect(stat)));
        if (record.points > 0 && value < cap) {
            lore.add(ChatColor.YELLOW + "Click to spend 1 point.");
        } else if (value >= cap) {
            lore.add(ChatColor.RED + "Capped.");
        } else {
            lore.add(ChatColor.DARK_GRAY + "No unspent points.");
        }
        return icon(statMaterial(stat), ChatColor.AQUA + stat.display(), lore);
    }

    private int statSlot(SurvivorStat stat) {
        return switch (stat) {
            case MIGHT -> 19;
            case ENDURANCE -> 21;
            case AGILITY -> 23;
            case WISDOM -> 25;
            case COMMAND -> 31;
        };
    }

    private SurvivorStat statBySlot(int slot) {
        for (SurvivorStat stat : SurvivorStat.values()) {
            if (statSlot(stat) == slot) {
                return stat;
            }
        }
        return null;
    }

    private Material statMaterial(SurvivorStat stat) {
        return switch (stat) {
            case MIGHT -> Material.IRON_SWORD;
            case ENDURANCE -> Material.SHIELD;
            case AGILITY -> Material.FEATHER;
            case WISDOM -> Material.ENCHANTING_TABLE;
            case COMMAND -> Material.BELL;
        };
    }

    private String statEffect(SurvivorStat stat) {
        return switch (stat) {
            case MIGHT -> "+" + percent(plugin.getConfig()
                    .getDouble("progression.stats.might-damage", 0.02)) + " weapon damage/rank";
            case ENDURANCE -> "+" + plugin.getConfig()
                    .getDouble("progression.stats.endurance-health", 1.0)
                    + " max health, -" + percent(plugin.getConfig()
                    .getDouble("progression.stats.endurance-damage-reduction", 0.01))
                    + " damage/rank";
            case AGILITY -> "+" + percent(plugin.getConfig()
                    .getDouble("progression.stats.agility-speed-scalar", 0.004))
                    + " speed, roll/fall mastery";
            case WISDOM -> "+" + percent(plugin.getConfig()
                    .getDouble("progression.stats.wisdom-legacy-xp", 0.025))
                    + " Legacy XP/rank";
            case COMMAND -> "+" + percent(plugin.getConfig()
                    .getDouble("progression.stats.command-outpost-production", 0.025))
                    + " outpost yield/rank";
        };
    }

    private String xpBar(int xp, int required) {
        if (required <= 0) {
            return "[MAX]";
        }
        int filled = clamp((int) Math.round((xp / (double) required) * 10.0), 0, 10);
        return "[" + "#".repeat(filled) + ".".repeat(10 - filled) + "]";
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private SurvivorStat strongestStat(SurvivorRecord record) {
        SurvivorStat strongest = null;
        int value = 0;
        for (SurvivorStat stat : SurvivorStat.values()) {
            int current = record.stat(stat);
            if (current > value) {
                value = current;
                strongest = stat;
            }
        }
        return strongest;
    }

    private void playXpCue(Player player, int amount) {
        if (!cooldown(survivorXpCueAt, player.getUniqueId(), 900L)) {
            return;
        }
        player.getWorld().spawnParticle(
                Particle.ENCHANT,
                player.getLocation().add(0.0, 0.9, 0.0),
                Math.min(18, 5 + Math.max(0, amount / 3)),
                0.28,
                0.35,
                0.28,
                0.02);
        player.playSound(
                player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.18f, 1.7f);
    }

    private void playMightCue(Player attacker, LivingEntity target, double multiplier) {
        if (!cooldown(survivorImpactCueAt, attacker.getUniqueId(), 450L)) {
            return;
        }
        target.getWorld().spawnParticle(
                Particle.CRIT,
                target.getLocation().add(0.0, target.getHeight() * 0.58, 0.0),
                Math.min(12, 3 + (int) Math.round((multiplier - 1.0) * 80.0)),
                0.28,
                0.24,
                0.28,
                0.08);
        attacker.playSound(
                attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.14f, 1.25f);
    }

    private void playGuardCue(Player player, double preventedDamage) {
        if (preventedDamage <= 0.0
                || !cooldown(survivorGuardCueAt, player.getUniqueId(), 650L)) {
            return;
        }
        player.getWorld().spawnParticle(
                Particle.ENCHANT,
                player.getLocation().add(0.0, 1.0, 0.0),
                Math.min(18, 6 + (int) Math.ceil(preventedDamage)),
                0.45,
                0.55,
                0.45,
                0.02);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.18f, 1.35f);
    }

    private boolean cooldown(Map<UUID, Long> timers, UUID playerId, long milliseconds) {
        long now = System.currentTimeMillis();
        long last = timers.getOrDefault(playerId, 0L);
        if (now - last < milliseconds) {
            return false;
        }
        timers.put(playerId, now);
        return true;
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

    private void refresh(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                open(player);
            }
        });
    }

    private boolean grantCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.YELLOW + "/survivor grant <player> <xp>");
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player.");
            return true;
        }
        try {
            grantXp(player, Math.max(1, Integer.parseInt(args[2])), "operator grant");
            sender.sendMessage(ChatColor.GREEN + "Granted Survivor XP to " + player.getName() + ".");
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "XP must be a number.");
        }
        return true;
    }

    private double outgoingDamageMultiplier(Player player) {
        int might = record(player).stat(SurvivorStat.MIGHT);
        return 1.0 + might * plugin.getConfig()
                .getDouble("progression.stats.might-damage", 0.02);
    }

    private double incomingDamageMultiplier(Player player, EntityDamageEvent.DamageCause cause) {
        SurvivorRecord record = record(player);
        double endurance = record.stat(SurvivorStat.ENDURANCE)
                * plugin.getConfig().getDouble("progression.stats.endurance-damage-reduction", 0.01);
        double agility = cause == EntityDamageEvent.DamageCause.FALL
                ? record.stat(SurvivorStat.AGILITY)
                        * plugin.getConfig().getDouble("progression.stats.agility-fall-reduction", 0.025)
                : 0.0;
        return Math.max(0.55, 1.0 - endurance - agility);
    }

    private SurvivorRecord record(Player player) {
        SurvivorRecord record = records.computeIfAbsent(player.getUniqueId(), ignored -> new SurvivorRecord());
        record.name = player.getName();
        return record;
    }

    private int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt("progression.max-level", 30));
    }

    private int nextXp(int level) {
        return FrontierMath.survivorNextXp(
                level,
                plugin.getConfig().getInt("progression.base-next-level-xp", 120),
                plugin.getConfig().getInt("progression.next-level-xp-growth", 40));
    }

    private int gatherXp(Block block) {
        Material material = block.getType();
        if (material == Material.ANCIENT_DEBRIS) {
            return plugin.getConfig().getInt("progression.rewards.gather.ancient-debris-xp", 24);
        }
        if (material.name().endsWith("_ORE")) {
            return plugin.getConfig().getInt("progression.rewards.gather.ore-xp", 8);
        }
        if (Tag.LOGS.isTagged(material)) {
            return plugin.getConfig().getInt("progression.rewards.gather.log-xp", 2);
        }
        if (block.getBlockData() instanceof Ageable crop
                && crop.getAge() == crop.getMaximumAge()) {
            return plugin.getConfig().getInt("progression.rewards.gather.crop-xp", 2);
        }
        if (commonMiningXp(material)) {
            return plugin.getConfig().getInt("progression.rewards.gather.common-xp", 1);
        }
        return 0;
    }

    private boolean trackedPlacedMaterial(Material material) {
        return material == Material.ANCIENT_DEBRIS
                || material.name().endsWith("_ORE")
                || Tag.LOGS.isTagged(material)
                || commonMiningXp(material);
    }

    private boolean commonMiningXp(Material material) {
        return switch (material) {
            case STONE,
                    DEEPSLATE,
                    TUFF,
                    GRANITE,
                    DIORITE,
                    ANDESITE,
                    CALCITE,
                    BASALT,
                    SMOOTH_BASALT,
                    BLACKSTONE,
                    NETHERRACK,
                    END_STONE -> true;
            default -> false;
        };
    }

    private String gatherReason(Material material) {
        if (material == Material.ANCIENT_DEBRIS || material.name().endsWith("_ORE")) {
            return "mining";
        }
        if (Tag.LOGS.isTagged(material)) {
            return "woodcutting";
        }
        return commonMiningXp(material) ? "excavation" : "harvest";
    }

    private void prunePlacedBlocks() {
        if (playerPlacedBlocks.size() < 4096) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L;
        playerPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private void applyModifier(
            Player player,
            Attribute attribute,
            NamespacedKey key,
            double amount,
            AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(key);
        if (amount != 0.0) {
            instance.addTransientModifier(new AttributeModifier(key, amount, operation));
        }
    }

    private List<String> match(List<String> choices, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class SurvivorRecord {
        private String name = "";
        private int level = 1;
        private int xp;
        private int points;
        private int traitPoints;
        private boolean freeRespec = true;
        private final EnumMap<SurvivorStat, Integer> stats =
                new EnumMap<>(SurvivorStat.class);

        private SurvivorRecord() {
            for (SurvivorStat stat : SurvivorStat.values()) {
                stats.put(stat, 0);
            }
        }

        private int stat(SurvivorStat stat) {
            return stats.getOrDefault(stat, 0);
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private static BlockKey of(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ());
        }
    }
}
