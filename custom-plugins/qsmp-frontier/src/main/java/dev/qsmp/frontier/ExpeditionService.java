package dev.qsmp.frontier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class ExpeditionService {
    private static final int[][] WARD_OFFSETS = {
        {-12, -8},
        {12, -8},
        {0, 13}
    };

    private final QSMPFrontier plugin;
    private final FrontierKeys keys;
    private final FrontierItems items;
    private final File storageFile;
    private final Set<String> wards = new HashSet<>();
    private final Set<UUID> sentinels = new HashSet<>();

    private Location center;
    private Location vault;
    private boolean built;
    private boolean unlocked;
    private boolean claimed;
    private long cooldownUntil;
    private long lastAmbientAt;
    private long lastVaultPunishAt;

    ExpeditionService(QSMPFrontier plugin, FrontierKeys keys, FrontierItems items) {
        this.plugin = plugin;
        this.keys = keys;
        this.items = items;
        storageFile = new File(plugin.getDataFolder(), "expedition.yml");
    }

    void load() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(storageFile);
        if (!data.getBoolean("ready", false)) {
            return;
        }
        World world = Bukkit.getWorld(data.getString("world", ""));
        if (world == null) {
            return;
        }
        center = new Location(
                world, data.getInt("x"), data.getInt("y"), data.getInt("z"));
        vault = locationFromKey(data.getString("vault", ""));
        built = data.getBoolean("built", false);
        unlocked = data.getBoolean("unlocked", false);
        claimed = data.getBoolean("claimed", false);
        cooldownUntil = Math.max(0L, data.getLong("cooldown-until", 0L));
        wards.clear();
        wards.addAll(data.getStringList("wards"));
    }

    void save() {
        if (center == null) {
            if (storageFile.exists() && !storageFile.delete()) {
                plugin.getLogger().warning("Could not delete expedition data.");
            }
            return;
        }
        YamlConfiguration data = new YamlConfiguration();
        data.set("ready", true);
        data.set("world", center.getWorld().getName());
        data.set("x", center.getBlockX());
        data.set("y", center.getBlockY());
        data.set("z", center.getBlockZ());
        data.set("vault", vault == null ? null : locationKey(vault));
        data.set("built", built);
        data.set("unlocked", unlocked);
        data.set("claimed", claimed);
        data.set("cooldown-until", cooldownUntil);
        data.set("wards", new ArrayList<>(wards));
        try {
            data.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save expedition: " + exception.getMessage());
        }
    }

    void guide(Player player) {
        if (claimed && cooldownUntil <= System.currentTimeMillis()) {
            reset(false);
        }
        if (center == null) {
            chart(player.getWorld());
            player.sendMessage(ChatColor.YELLOW
                    + "The compass catches a ruin signal. Follow it and load the site.");
        }
        if (!player.getWorld().equals(center.getWorld())) {
            player.sendMessage(ChatColor.YELLOW
                    + "The sealed ruin lies in " + center.getWorld().getName() + ".");
            return;
        }
        player.setCompassTarget(center);
        player.playSound(player.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 0.8f, 1.55f);
        String state = built ? stateText() : "uncharted ground";
        player.sendActionBar(ChatColor.AQUA + "Ruin signal: "
                + Math.round(player.getLocation().distance(center)) + " blocks, " + state);
    }

    void buildSpawn(CommandSender sender) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return;
        }
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation().clone().add(36.0, 0.0, 36.0);
        int y = world.getHighestBlockYAt(
                spawn.getBlockX(), spawn.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        buildAt(new Location(world, spawn.getBlockX(), y, spawn.getBlockZ()));
        sender.sendMessage(ChatColor.GREEN + "Ruin expedition built at "
                + center.getBlockX() + ", " + center.getBlockZ() + ".");
    }

    void reset(boolean announce) {
        cleanupSentinels();
        center = null;
        vault = null;
        built = false;
        unlocked = false;
        claimed = false;
        cooldownUntil = 0L;
        wards.clear();
        save();
        if (announce) {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "The current ruin expedition was reset.");
        }
    }

    void tick() {
        if (center == null || claimed) {
            return;
        }
        if (!built) {
            tryBuildDiscoveredSite();
            return;
        }
        pruneSentinels();
        rebuildMissingWardState();
        if (!unlocked && wards.isEmpty() && sentinels.isEmpty()) {
            unlockVault();
        }
        tickAmbient();
        retargetSentinels();
    }

    String status() {
        if (center == null) {
            return "not charted";
        }
        if (claimed) {
            long remaining = Math.max(0L, cooldownUntil - System.currentTimeMillis());
            long minutes = Math.max(1L, (remaining + 59999L) / 60000L);
            return "claimed, next signal in " + minutes + " minute(s)";
        }
        if (!built) {
            return "charted ruin at " + center.getBlockX() + ", " + center.getBlockZ();
        }
        return stateText() + " at " + center.getBlockX() + ", " + center.getBlockZ()
                + ", wards " + wards.size() + ", sentinels " + sentinels.size();
    }

    boolean onInteract(PlayerInteractEvent event) {
        if (vault == null || event.getClickedBlock() == null
                || !sameBlock(event.getClickedBlock().getLocation(), vault)) {
            return false;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!unlocked) {
            player.sendMessage(ChatColor.RED + "The vault is sealed by "
                    + wards.size() + " ward(s). Break the marked anchors first.");
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.8f);
            long now = System.currentTimeMillis();
            if (now - lastVaultPunishAt > 4000L) {
                lastVaultPunishAt = now;
                spawnAmbush(player.getLocation(), Math.max(1, playersNear(40.0).size()), 1);
            }
            return true;
        }
        if (claimed) {
            player.sendMessage(ChatColor.YELLOW + "This ruin has already been claimed.");
            return true;
        }
        claim(player);
        return true;
    }

    boolean onBlockBreak(BlockBreakEvent event) {
        if (center == null || !built || event.getBlock() == null) {
            return false;
        }
        String key = locationKey(event.getBlock().getLocation());
        if (wards.contains(key)) {
            event.setCancelled(true);
            Block block = event.getBlock();
            block.getWorld().spawnParticle(
                    Particle.SONIC_BOOM, block.getLocation().add(0.5, 1.0, 0.5), 1);
            block.getWorld().playSound(
                    block.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.2f, 0.7f);
            block.setType(Material.AIR, false);
            wards.remove(key);
            save();
            int players = Math.max(1, playersNear(50.0).size());
            spawnAmbush(block.getLocation(), players, 2);
            Bukkit.broadcastMessage(ChatColor.DARK_AQUA
                    + "RUIN: A ward anchor broke. " + wards.size() + " remaining.");
            if (wards.isEmpty() && sentinels.isEmpty()) {
                unlockVault();
            } else if (wards.isEmpty()) {
                Bukkit.broadcastMessage(ChatColor.RED
                        + "RUIN: Clear the sentinels to open the vault.");
            }
            return true;
        }
        if (vault != null && sameBlock(event.getBlock().getLocation(), vault) && !claimed) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Claim the vault before dismantling it.");
            return true;
        }
        return false;
    }

    void onDeath(EntityDeathEvent event) {
        if (!isExpeditionMob(event.getEntity())) {
            return;
        }
        sentinels.remove(event.getEntity().getUniqueId());
        if (!unlocked && wards.isEmpty() && sentinels.isEmpty()) {
            unlockVault();
        }
    }

    boolean isExpeditionMob(Entity entity) {
        return entity.getPersistentDataContainer().has(
                keys.expeditionMob, PersistentDataType.BYTE);
    }

    void cleanupSentinels() {
        for (UUID id : new HashSet<>(sentinels)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        sentinels.clear();
    }

    private void chart(World world) {
        Location spawn = world.getSpawnLocation();
        int minimum = Math.max(200,
                plugin.getConfig().getInt("expeditions.minimum-distance-from-spawn", 450));
        int maximum = Math.max(minimum + 100,
                plugin.getConfig().getInt("expeditions.maximum-distance-from-spawn", 1600));
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        int distance = ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
        center = new Location(
                world,
                spawn.getBlockX() + Math.round(Math.cos(angle) * distance),
                world.getSeaLevel(),
                spawn.getBlockZ() + Math.round(Math.sin(angle) * distance));
        built = false;
        unlocked = false;
        claimed = false;
        cooldownUntil = 0L;
        wards.clear();
        save();
        Bukkit.getConsoleSender().sendMessage(
                "Ruin expedition charted at " + center.getBlockX() + ", "
                        + center.getBlockZ() + "; construction waits for exploration.");
    }

    private void tryBuildDiscoveredSite() {
        if (center == null || !regionLoaded(center) || playersNear(discoveryRadius()).isEmpty()) {
            return;
        }
        buildAt(buildableCenter(center));
        Bukkit.broadcastMessage(ChatColor.AQUA
                + "A sealed ruin has surfaced beyond the frontier.");
    }

    private void buildAt(Location requestedCenter) {
        World world = requestedCenter.getWorld();
        int cx = requestedCenter.getBlockX();
        int cz = requestedCenter.getBlockZ();
        int y = world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        center = new Location(world, cx, y, cz);
        built = true;
        unlocked = false;
        claimed = false;
        cooldownUntil = 0L;
        wards.clear();
        cleanupSentinels();

        flatten(world, cx, cz, 8);
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                if (Math.abs(x) == 7 || Math.abs(z) == 7 || Math.floorMod(x * 13 + z, 5) == 0) {
                    placeSurface(world, cx + x, cz + z, Material.COBBLED_DEEPSLATE);
                }
            }
        }
        ring(world, cx, cz, 10, Material.DEEPSLATE_TILE_WALL);
        placeSurface(world, cx - 4, cz - 4, Material.SOUL_CAMPFIRE);
        placeSurface(world, cx + 4, cz - 4, Material.SOUL_CAMPFIRE);
        placeSurface(world, cx, cz + 5, Material.SOUL_LANTERN);

        vault = surfaceLocation(world, cx, cz);
        Block vaultBlock = vault.getBlock();
        vaultBlock.setType(Material.BARREL, false);
        BlockState state = vaultBlock.getState();
        if (state instanceof Barrel barrel) {
            barrel.setCustomName(ChatColor.DARK_AQUA + "Sealed Ruin Vault");
            barrel.update(true, false);
        }

        for (int[] offset : WARD_OFFSETS) {
            Location ward = surfaceLocation(world, cx + offset[0], cz + offset[1]);
            ward.getBlock().setType(Material.CRYING_OBSIDIAN, false);
            wards.add(locationKey(ward));
            ward.getWorld().spawnParticle(
                    Particle.SOUL_FIRE_FLAME, ward.clone().add(0.5, 1.2, 0.5), 16, 0.4, 0.5, 0.4);
        }
        save();
    }

    private void flatten(World world, int cx, int cz, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                int y = world.getHighestBlockYAt(
                        cx + x, cz + z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                world.getBlockAt(cx + x, y, cz + z).setType(Material.COARSE_DIRT, false);
                for (int clear = 1; clear <= 4; clear++) {
                    world.getBlockAt(cx + x, y + clear, cz + z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void ring(World world, int cx, int cz, int radius, Material material) {
        for (int angle = 0; angle < 360; angle += 12) {
            double radians = Math.toRadians(angle);
            int x = cx + (int) Math.round(Math.cos(radians) * radius);
            int z = cz + (int) Math.round(Math.sin(radians) * radius);
            placeSurface(world, x, z, material);
        }
    }

    private void placeSurface(World world, int x, int z, Material material) {
        surfaceLocation(world, x, z).getBlock().setType(material, false);
    }

    private Location surfaceLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new Location(world, x, y, z);
    }

    private Location buildableCenter(Location approximate) {
        World world = approximate.getWorld();
        int startX = approximate.getBlockX();
        int startZ = approximate.getBlockZ();
        for (int radius = 0; radius <= 48; radius += 8) {
            for (int dx = -radius; dx <= radius; dx += 8) {
                for (int dz = -radius; dz <= radius; dz += 8) {
                    if (radius != 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = startX + dx;
                    int z = startZ + dz;
                    int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    Material ground = world.getBlockAt(x, y, z).getType();
                    if (isBuildableGround(ground)) {
                        return new Location(world, x, y, z);
                    }
                }
            }
        }
        int y = world.getHighestBlockYAt(startX, startZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(world, startX, y, startZ);
    }

    private void spawnAmbush(Location source, int players, int burst) {
        int base = Math.max(1, plugin.getConfig().getInt("expeditions.sentinel-base-count", 2));
        int perPlayer = Math.max(0,
                plugin.getConfig().getInt("expeditions.sentinel-count-per-player", 1));
        int count = base + players * perPlayer + Math.max(0, burst);
        for (int i = 0; i < count; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            double radius = ThreadLocalRandom.current().nextDouble(6.0, 11.0);
            int x = source.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = source.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            Location spawn = surfaceLocation(source.getWorld(), x, z);
            EntityType type = switch (i % 5) {
                case 0 -> EntityType.WITCH;
                case 1, 2 -> EntityType.PILLAGER;
                case 3 -> EntityType.SKELETON;
                default -> EntityType.VINDICATOR;
            };
            Entity entity = source.getWorld().spawnEntity(
                    spawn, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (entity instanceof LivingEntity living) {
                markSentinel(living, players);
                sentinels.add(living.getUniqueId());
            } else {
                entity.remove();
            }
        }
    }

    private void markSentinel(LivingEntity living, int players) {
        living.getPersistentDataContainer().set(
                keys.expeditionMob, PersistentDataType.BYTE, (byte) 1);
        living.addScoreboardTag("qsmp_frontier");
        living.addScoreboardTag("qsmp_expedition");
        living.setCustomName(ChatColor.DARK_AQUA + "Ruin Sentinel");
        living.setCustomNameVisible(true);
        living.setRemoveWhenFarAway(false);
        double health = FrontierMath.sentinelHealth(
                players,
                plugin.getConfig().getDouble("expeditions.sentinel-base-health", 24.0),
                plugin.getConfig().getDouble("expeditions.sentinel-health-per-player", 6.0));
        setBase(living, Attribute.MAX_HEALTH, Math.min(2048.0, health));
        AttributeInstance maximum = living.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null) {
            living.setHealth(maximum.getValue());
        }
        AttributeInstance attack = living.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attack.getBaseValue() + players * 1.2);
        }
    }

    private void unlockVault() {
        unlocked = true;
        save();
        if (vault != null) {
            vault.getWorld().playSound(vault, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.4f, 0.8f);
            vault.getWorld().spawnParticle(
                    Particle.TOTEM_OF_UNDYING, vault.clone().add(0.5, 1.2, 0.5),
                    32, 0.7, 0.8, 0.7);
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "RUIN: The vault seal is broken.");
    }

    private void claim(Player opener) {
        claimed = true;
        long cooldownMinutes = Math.max(1L,
                plugin.getConfig().getLong("expeditions.cooldown-minutes", 90L));
        cooldownUntil = System.currentTimeMillis() + cooldownMinutes * 60000L;
        cleanupSentinels();
        List<Player> rewarded = playersNear(rewardRadius());
        if (rewarded.isEmpty()) {
            rewarded = List.of(opener);
        }
        for (Player player : rewarded) {
            player.giveExp(plugin.getConfig().getInt("expeditions.reward-experience", 350));
            giveOrDrop(player, new ItemStack(Material.EMERALD, 8));
            giveOrDrop(player, new ItemStack(Material.EXPERIENCE_BOTTLE, 6));
            giveOrDrop(player, new ItemStack(Material.AMETHYST_SHARD, 6));
            if (ThreadLocalRandom.current().nextDouble() < 0.35) {
                giveOrDrop(player, new ItemStack(Material.DIAMOND, 1));
            }
            if (ThreadLocalRandom.current().nextDouble() < 0.18) {
                giveOrDrop(player, new ItemStack(Material.ECHO_SHARD, 1));
            }
            if (ThreadLocalRandom.current().nextDouble() < 0.12) {
                OutpostType type = OutpostType.values()[
                        ThreadLocalRandom.current().nextInt(OutpostType.values().length)];
                items.give(player, items.outpostCore(type));
            }
            player.sendTitle(
                    ChatColor.GOLD + "RUIN CLAIMED",
                    ChatColor.YELLOW + "Expedition spoils recovered",
                    10, 60, 20);
        }
        if (vault != null) {
            vault.getBlock().setType(Material.CHEST, false);
            vault.getWorld().playSound(vault, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.9f);
        }
        save();
    }

    private void rebuildMissingWardState() {
        boolean changed = false;
        for (String key : new HashSet<>(wards)) {
            Location location = locationFromKey(key);
            if (location == null || location.getBlock().getType() == Material.AIR) {
                wards.remove(key);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private void pruneSentinels() {
        sentinels.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
    }

    private void retargetSentinels() {
        for (UUID id : sentinels) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Mob mob && (mob.getTarget() == null || mob.getTarget().isDead())) {
                Player target = closestPlayer(mob.getLocation(), 48.0);
                if (target != null) {
                    mob.setTarget(target);
                }
            }
        }
    }

    private void tickAmbient() {
        long now = System.currentTimeMillis();
        if (now - lastAmbientAt < 3000L || center == null) {
            return;
        }
        lastAmbientAt = now;
        List<Player> nearby = playersNear(48.0);
        if (nearby.isEmpty()) {
            return;
        }
        World world = center.getWorld();
        for (String key : wards) {
            Location ward = locationFromKey(key);
            if (ward != null) {
                world.spawnParticle(
                        Particle.SOUL_FIRE_FLAME, ward.clone().add(0.5, 1.0, 0.5),
                        5, 0.25, 0.35, 0.25);
            }
        }
        if (unlocked && vault != null) {
            world.spawnParticle(
                    Particle.END_ROD, vault.clone().add(0.5, 1.0, 0.5),
                    8, 0.4, 0.5, 0.4);
        }
    }

    private boolean regionLoaded(Location location) {
        World world = location.getWorld();
        int radius = 2;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isChunkLoaded(chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<Player> playersNear(double radius) {
        if (center == null) {
            return List.of();
        }
        double distance = radius * radius;
        List<Player> players = new ArrayList<>();
        for (Player player : center.getWorld().getPlayers()) {
            if (!player.isDead()
                    && player.getLocation().distanceSquared(center) <= distance) {
                players.add(player);
            }
        }
        return players;
    }

    private Player closestPlayer(Location location, double radius) {
        Player closest = null;
        double nearest = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            double candidate = player.getLocation().distanceSquared(location);
            if (!player.isDead() && candidate < nearest) {
                closest = player;
                nearest = candidate;
            }
        }
        return closest;
    }

    private double discoveryRadius() {
        return Math.max(32.0,
                plugin.getConfig().getDouble("expeditions.discovery-radius", 96.0));
    }

    private double rewardRadius() {
        return Math.max(16.0,
                plugin.getConfig().getDouble("expeditions.reward-radius", 48.0));
    }

    private boolean isBuildableGround(Material material) {
        return material.isSolid()
                && !material.name().endsWith("_LEAVES")
                && material != Material.WATER
                && material != Material.LAVA
                && material != Material.POWDER_SNOW
                && material != Material.ICE
                && material != Material.PACKED_ICE
                && material != Material.BLUE_ICE;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private String stateText() {
        if (unlocked) {
            return "vault open";
        }
        if (wards.isEmpty()) {
            return "sentinel cleanup";
        }
        return "sealed";
    }

    private void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private String locationKey(Location location) {
        return location.getWorld().getName()
                + ":" + location.getBlockX()
                + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    private Location locationFromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(
                    world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
