package dev.qsmp.frontier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class OutpostService {
    private static final UUID PUBLIC_OWNER = new UUID(0L, 0L);

    private final QSMPFrontier plugin;
    private final FrontierKeys keys;
    private final FrontierItems items;
    private final CompanionRoleService roles;
    private final ProgressionService progression;
    private final CinematicService cinematics;
    private final File storageFile;
    private final Map<String, OutpostRecord> outposts = new HashMap<>();

    OutpostService(
            QSMPFrontier plugin,
            FrontierKeys keys,
            FrontierItems items,
            CompanionRoleService roles,
            ProgressionService progression,
            CinematicService cinematics) {
        this.plugin = plugin;
        this.keys = keys;
        this.items = items;
        this.roles = roles;
        this.progression = progression;
        this.cinematics = cinematics;
        storageFile = new File(plugin.getDataFolder(), "outposts.yml");
    }

    void load() {
        outposts.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = data.getConfigurationSection("outposts");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "outposts." + id + ".";
            String world = data.getString(path + "world");
            String owner = data.getString(path + "owner");
            OutpostType type = OutpostType.fromItemType(
                    "outpost_" + data.getString(path + "type", ""));
            try {
                if (world != null && owner != null && type != null) {
                    OutpostRecord record = new OutpostRecord(
                            id,
                            UUID.fromString(owner),
                            world,
                            data.getInt(path + "x"),
                            data.getInt(path + "y"),
                            data.getInt(path + "z"),
                            type,
                            Math.max(1, Math.min(3, data.getInt(path + "tier", 1))),
                            Math.max(0L, data.getLong(path + "cycles", 0L)));
                    outposts.put(locationKey(
                            record.world, record.x, record.y, record.z), record);
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignored invalid outpost record " + id);
            }
        }
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (OutpostRecord record : outposts.values()) {
            String path = "outposts." + record.id + ".";
            data.set(path + "owner", record.owner.toString());
            data.set(path + "world", record.world);
            data.set(path + "x", record.x);
            data.set(path + "y", record.y);
            data.set(path + "z", record.z);
            data.set(path + "type", record.type.name().toLowerCase());
            data.set(path + "tier", record.tier);
            data.set(path + "cycles", record.cycles);
        }
        try {
            data.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save outposts: " + exception.getMessage());
        }
    }

    boolean create(Player player, Block block, OutpostType type) {
        return createRecord(player, player.getUniqueId(), block, type);
    }

    void createPublic(CommandSender sender, OutpostType type) {
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() - 36 + type.ordinal() * 12;
        int y = world.getHighestBlockYAt(x, spawn.getBlockZ() + 8) + 1;
        int z = spawn.getBlockZ() + 8;
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.BARREL, false);
        if (createRecord(sender, PUBLIC_OWNER, block, type)) {
            sender.sendMessage("Public " + type.display() + " created at "
                    + x + " " + y + " " + z + ".");
        }
    }

    boolean interact(Player player, Block block, ItemStack held) {
        OutpostRecord record = outposts.get(locationKey(block.getLocation()));
        if (record == null) {
            return false;
        }
        int targetTier = items.upgradeTarget(held);
        if (targetTier > 0) {
            if (!canManage(player, record)) {
                player.sendMessage(ChatColor.RED + "Only the owner can upgrade this outpost.");
                return true;
            }
            if (targetTier != record.tier + 1) {
                player.sendMessage(ChatColor.YELLOW + "This site is tier " + record.tier
                        + "; install the next sequential upgrade.");
                return true;
            }
            record.tier = targetTier;
            updateBarrel(record);
            items.consumeOne(player, held);
            save();
            block.getWorld().spawnParticle(
                    Particle.FIREWORK, block.getLocation().add(0.5, 1.0, 0.5),
                    24, 0.5, 0.6, 0.5, 0.08);
            block.getWorld().playSound(
                    block.getLocation(), Sound.BLOCK_ANVIL_USE, 0.9f, 1.35f);
            cinematics.onOutpostUpgrade(block);
            player.sendMessage(ChatColor.GOLD + record.type.display()
                    + ChatColor.GREEN + " upgraded to tier " + record.tier + ".");
            progression.grantXp(
                    player,
                    plugin.getConfig().getInt("progression.rewards.outpost-upgrade-survivor-xp", 90),
                    "outpost upgrade");
            return true;
        }

        double efficiency = siteMultiplier(record);
        String status = record.type.produces()
                ? "Tier " + record.tier + " | cycles " + record.cycles
                        + " | site " + Math.round(efficiency * 100.0) + "%"
                : "Tier " + record.tier + " | receives overflow within "
                        + (int) warehouseRadius() + " blocks";
        player.sendActionBar(ChatColor.GOLD + record.type.display()
                + ChatColor.GRAY + " - " + status);
        return false;
    }

    boolean breakOutpost(Player player, Block block) {
        OutpostRecord record = outposts.get(locationKey(block.getLocation()));
        if (record == null) {
            return false;
        }
        if (!canManage(player, record)) {
            player.sendMessage(ChatColor.RED + "Only the owner can dismantle this outpost.");
            return true;
        }
        if (block.getState() instanceof Barrel barrel) {
            for (ItemStack stored : barrel.getInventory().getContents()) {
                if (stored != null && !stored.getType().isAir()) {
                    items.give(player, stored.clone());
                }
            }
            barrel.getInventory().clear();
        }
        outposts.remove(locationKey(block.getLocation()));
        save();
        items.give(player, items.outpostCore(record.type));
        player.sendMessage(ChatColor.YELLOW + record.type.display()
                + " dismantled; stored resources were returned.");
        return true;
    }

    boolean isOutpost(Block block) {
        return outposts.containsKey(locationKey(block.getLocation()));
    }

    int count() {
        return outposts.size();
    }

    void produce() {
        boolean changed = false;
        for (OutpostRecord record : outposts.values().toArray(OutpostRecord[]::new)) {
            if (!record.type.produces()) {
                continue;
            }
            World world = Bukkit.getWorld(record.world);
            if (world == null) {
                continue;
            }
            LoadedBarrel loaded = loadBarrel(record);
            if (loaded == null) {
                outposts.remove(locationKey(record.world, record.x, record.y, record.z));
                changed = true;
                continue;
            }

            Location location = loaded.barrel.getLocation().add(0.5, 0.8, 0.5);
            double companionMultiplier = roles.gatheringMultiplier(location, record.owner);
            double multiplier = companionMultiplier
                    * siteMultiplier(record)
                    * progression.productionMultiplier(record.owner);
            List<ItemStack> overflow = new ArrayList<>();
            for (ItemStack base : record.type.production(record.tier)) {
                base.setAmount(Math.max(1, (int) Math.floor(base.getAmount() * multiplier)));
                overflow.addAll(loaded.barrel.getInventory().addItem(base).values());
            }
            close(loaded);

            List<ItemStack> remaining = routeToWarehouses(record, overflow);
            Particle particle = remaining.isEmpty()
                    ? Particle.HAPPY_VILLAGER
                    : Particle.SMOKE;
            world.spawnParticle(
                    particle, location, companionMultiplier > 1.0 ? 8 : 4,
                    0.3, 0.3, 0.3, 0.02);
            cinematics.onOutpostProduction(
                    location,
                    companionMultiplier > 1.0 || multiplier > 1.25,
                    !remaining.isEmpty());
            record.cycles++;
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private boolean createRecord(
            CommandSender sender, UUID owner, Block block, OutpostType type) {
        if (!(block.getState() instanceof Barrel barrel)) {
            return false;
        }
        String id = UUID.randomUUID().toString();
        OutpostRecord record = new OutpostRecord(
                id,
                owner,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                type,
                1,
                0L);
        outposts.put(locationKey(block.getLocation()), record);
        updateBarrel(record);
        save();
        cinematics.onOutpostEstablished(block);
        sender.sendMessage(ChatColor.GOLD + type.display()
                + ChatColor.GREEN + " established at tier 1.");
        if (sender instanceof Player player && siteMultiplier(record) < 1.0) {
            player.sendMessage(ChatColor.YELLOW
                    + "This location has reduced efficiency; terrain and depth now matter.");
        }
        return true;
    }

    private void updateBarrel(OutpostRecord record) {
        World world = Bukkit.getWorld(record.world);
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(record.x, record.y, record.z);
        if (!(block.getState() instanceof Barrel barrel)) {
            return;
        }
        barrel.getPersistentDataContainer().set(
                keys.outpostType, PersistentDataType.STRING, record.type.name());
        barrel.getPersistentDataContainer().set(
                keys.outpostOwner, PersistentDataType.STRING, record.owner.toString());
        barrel.getPersistentDataContainer().set(
                keys.outpostTier, PersistentDataType.INTEGER, record.tier);
        barrel.setCustomName(record.type.display() + " - Tier " + record.tier);
        barrel.update(true);
    }

    private List<ItemStack> routeToWarehouses(
            OutpostRecord source, List<ItemStack> initialOverflow) {
        List<ItemStack> overflow = new ArrayList<>(initialOverflow);
        if (overflow.isEmpty()) {
            return overflow;
        }
        double radiusSquared = warehouseRadius() * warehouseRadius();
        List<OutpostRecord> warehouses = outposts.values().stream()
                .filter(candidate -> candidate.type == OutpostType.WAREHOUSE)
                .filter(candidate -> candidate.world.equals(source.world))
                .filter(candidate -> candidate.owner.equals(source.owner)
                        || candidate.owner.equals(PUBLIC_OWNER))
                .filter(candidate -> distanceSquared(source, candidate) <= radiusSquared)
                .sorted(Comparator.comparingDouble(candidate -> distanceSquared(source, candidate)))
                .toList();
        for (OutpostRecord warehouse : warehouses) {
            LoadedBarrel loaded = loadBarrel(warehouse);
            if (loaded == null) {
                continue;
            }
            overflow = insertAll(loaded.barrel.getInventory(), overflow);
            close(loaded);
            if (overflow.isEmpty()) {
                break;
            }
        }
        return overflow;
    }

    private List<ItemStack> insertAll(Inventory inventory, List<ItemStack> source) {
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack item : source) {
            overflow.addAll(inventory.addItem(item).values());
        }
        return overflow;
    }

    private LoadedBarrel loadBarrel(OutpostRecord record) {
        World world = Bukkit.getWorld(record.world);
        if (world == null) {
            return null;
        }
        Chunk chunk = world.getChunkAt(record.x >> 4, record.z >> 4);
        boolean loadedHere = !chunk.isLoaded();
        if (loadedHere && !chunk.load()) {
            return null;
        }
        Block block = world.getBlockAt(record.x, record.y, record.z);
        if (!(block.getState() instanceof Barrel barrel)) {
            if (loadedHere) {
                chunk.unload();
            }
            return null;
        }
        return new LoadedBarrel(barrel, chunk, loadedHere);
    }

    private void close(LoadedBarrel loaded) {
        if (loaded.loadedHere) {
            loaded.chunk.unload();
        }
    }

    private boolean canManage(Player player, OutpostRecord record) {
        return player.hasPermission("qsmpfrontier.admin")
                || player.getUniqueId().equals(record.owner);
    }

    private double siteMultiplier(OutpostRecord record) {
        World world = Bukkit.getWorld(record.world);
        if (world == null) {
            return 1.0;
        }
        Block block = world.getBlockAt(record.x, record.y, record.z);
        return switch (record.type) {
            case MINE -> record.y <= 32 ? 1.25 : 0.85;
            case GREENHOUSE -> block.getRelative(0, 1, 0).getLightFromSky() >= 12 ? 1.15 : 0.8;
            case FISHERY -> hasNearbyWater(block) ? 1.2 : 0.7;
            case WOOD -> hasNearby(block, Material.OAK_LOG, Material.SPRUCE_LOG,
                    Material.BIRCH_LOG, Material.JUNGLE_LOG) ? 1.15 : 1.0;
            default -> 1.0;
        };
    }

    private boolean hasNearbyWater(Block center) {
        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 1; y++) {
                for (int z = -4; z <= 4; z++) {
                    if (center.getRelative(x, y, z).getType() == Material.WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasNearby(Block center, Material... materials) {
        for (int x = -5; x <= 5; x++) {
            for (int y = -3; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    Material current = center.getRelative(x, y, z).getType();
                    for (Material material : materials) {
                        if (current == material) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private double warehouseRadius() {
        return Math.max(16.0,
                plugin.getConfig().getDouble("outposts.warehouse-radius", 64.0));
    }

    private double distanceSquared(OutpostRecord first, OutpostRecord second) {
        double x = first.x - second.x;
        double y = first.y - second.y;
        double z = first.z - second.z;
        return x * x + y * y + z * z;
    }

    private String locationKey(Location location) {
        return locationKey(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    private String locationKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    private static final class OutpostRecord {
        private final String id;
        private final UUID owner;
        private final String world;
        private final int x;
        private final int y;
        private final int z;
        private final OutpostType type;
        private int tier;
        private long cycles;

        private OutpostRecord(
                String id,
                UUID owner,
                String world,
                int x,
                int y,
                int z,
                OutpostType type,
                int tier,
                long cycles) {
            this.id = id;
            this.owner = owner;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.tier = tier;
            this.cycles = cycles;
        }
    }

    private record LoadedBarrel(Barrel barrel, Chunk chunk, boolean loadedHere) {
    }
}
