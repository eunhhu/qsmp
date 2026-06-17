package dev.qsmp.frontier;

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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

final class DragonService {
    private final QSMPFrontier plugin;
    private final FrontierKeys keys;
    private final FrontierItems items;
    private final CinematicService cinematics;
    private final Set<Location> resonanceBlocks = new HashSet<>();
    private UUID dragonId;
    private int phase;
    private long lastMinionSpawn;

    DragonService(
            QSMPFrontier plugin,
            FrontierKeys keys,
            FrontierItems items,
            CinematicService cinematics) {
        this.plugin = plugin;
        this.keys = keys;
        this.items = items;
        this.cinematics = cinematics;
    }

    void tick() {
        if (!enabled()) {
            return;
        }
        EnderDragon dragon = activeDragon();
        if (dragon == null) {
            dragonId = null;
            phase = 0;
            cleanupResonance(true);
            return;
        }
        configureDragon(dragon);
        pruneBrokenResonance();
        tickResonanceParticles();
        maybeStartPhase(dragon);
        tickMinions(dragon);
    }

    void onCombat(EntityDamageByEntityEvent event, Entity attacker) {
        if (!enabled()) {
            return;
        }
        if (event.getEntity() instanceof EnderDragon dragon) {
            configureDragon(dragon);
            if (!resonanceBlocks.isEmpty()) {
                double multiplier = plugin.getConfig()
                        .getDouble("dragon.resonance-shield-damage-multiplier", 0.25);
                event.setDamage(Math.max(0.0, event.getDamage() * multiplier));
                if (attacker instanceof Player player) {
                    player.sendActionBar(ChatColor.DARK_PURPLE
                            + "Dragon shielded: break the resonance stones.");
                }
            }
            return;
        }
        if (event.getEntity() instanceof Player && isDragonSource(attacker)) {
            int players = Math.max(1, activePlayers(attacker.getWorld(), attacker.getLocation()).size());
            double multiplier = 1.0
                    + Math.max(0, players - 1) * plugin.getConfig()
                            .getDouble("dragon.damage-per-extra-player", 0.08)
                    + phase * plugin.getConfig().getDouble("dragon.damage-per-phase", 0.08);
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    void onBlockBreak(BlockBreakEvent event) {
        if (resonanceBlocks.isEmpty()) {
            return;
        }
        Location main = resonanceMainBlock(event.getBlock());
        if (main == null || !resonanceBlocks.remove(main)) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        event.getPlayer().getWorld().playSound(
                main, Sound.BLOCK_BEACON_DEACTIVATE, 1.4f, 0.65f);
        cinematics.onDragonStoneBroken(event.getPlayer(), main, resonanceBlocks.size());
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE
                + event.getPlayer().getName() + " shattered a Dragon Resonance Stone. "
                + ChatColor.GRAY + resonanceBlocks.size() + " remain.");
        if (resonanceBlocks.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GOLD
                    + "The Ender Dragon's resonance shield collapses.");
            event.getPlayer().getWorld().playSound(
                    event.getPlayer().getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.8f, 0.9f);
            cinematics.onDragonShieldCollapsed(main);
        }
    }

    void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        cleanupResonance(true);
        dragonId = null;
        phase = 0;
        if (!enabled()) {
            return;
        }
        List<Player> rewarded = activePlayers(dragon.getWorld(), dragon.getLocation());
        cinematics.onDragonCleared(dragon.getLocation(), rewarded);
        for (Player player : rewarded) {
            items.give(player, items.dragonHeart());
            items.give(player, items.voidScale(plugin.getConfig().getInt("dragon.rewards.void-scale", 4)));
            items.give(player, items.memoryShard());
            double chance = plugin.getConfig().getDouble("dragon.rewards.ender-core-chance", 0.35);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                items.give(player, items.enderCore());
            }
            player.sendTitle(
                    ChatColor.LIGHT_PURPLE + "END RAID CLEARED",
                    ChatColor.GRAY + "Dragon Heart recovered",
                    10,
                    70,
                    20);
        }
    }

    void cleanup() {
        cleanupResonance(true);
        dragonId = null;
        phase = 0;
    }

    private void configureDragon(EnderDragon dragon) {
        dragonId = dragon.getUniqueId();
        if (dragon.getPersistentDataContainer().has(keys.dragonScaled, PersistentDataType.BYTE)) {
            return;
        }
        dragon.getPersistentDataContainer().set(
                keys.dragonScaled, PersistentDataType.BYTE, (byte) 1);
        int players = Math.max(1, activePlayers(dragon.getWorld(), dragon.getLocation()).size());
        double maximum = plugin.getConfig().getDouble("dragon.base-health", 450.0)
                + Math.max(0, players - 1)
                        * plugin.getConfig().getDouble("dragon.health-per-extra-player", 180.0);
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (health != null && health.getBaseValue() < maximum) {
            health.setBaseValue(maximum);
        }
        if (health != null) {
            dragon.setHealth(Math.min(maximum, health.getValue()));
        }
        dragon.setCustomName(ChatColor.DARK_PURPLE + "Awakened Ender Dragon");
        dragon.setCustomNameVisible(true);
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE
                + "The End arena wakes. Dragon strength scaled for " + players + " raider(s).");
        cinematics.onDragonAwaken(dragon, players);
    }

    private EnderDragon activeDragon() {
        if (dragonId != null) {
            Entity entity = Bukkit.getEntity(dragonId);
            if (entity instanceof EnderDragon dragon && dragon.isValid() && !dragon.isDead()) {
                return dragon;
            }
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) {
                continue;
            }
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                if (dragon.isValid() && !dragon.isDead()) {
                    return dragon;
                }
            }
        }
        return null;
    }

    private void maybeStartPhase(EnderDragon dragon) {
        if (!resonanceBlocks.isEmpty()) {
            return;
        }
        AttributeInstance health = dragon.getAttribute(Attribute.MAX_HEALTH);
        double maximum = health == null ? dragon.getHealth() : health.getValue();
        double ratio = maximum <= 0.0 ? 1.0 : dragon.getHealth() / maximum;
        double first = plugin.getConfig().getDouble("dragon.phase-one-health-ratio", 0.65);
        double second = plugin.getConfig().getDouble("dragon.phase-two-health-ratio", 0.35);
        if (phase < 1 && ratio <= first) {
            beginResonancePhase(dragon, 1);
        } else if (phase < 2 && ratio <= second) {
            beginResonancePhase(dragon, 2);
        }
    }

    private void beginResonancePhase(EnderDragon dragon, int nextPhase) {
        phase = nextPhase;
        cleanupResonance(false);
        int stones = plugin.getConfig().getInt("dragon.resonance-stones", 3) + nextPhase - 1;
        double radius = plugin.getConfig().getDouble("dragon.resonance-radius", 42.0)
                + nextPhase * 8.0;
        World world = dragon.getWorld();
        for (int i = 0; i < stones; i++) {
            double angle = (Math.PI * 2.0 * i / stones) + (nextPhase * 0.45);
            placeResonanceStone(world, angle, radius);
        }
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE
                + "Dragon phase " + nextPhase + ": resonance stones are shielding the arena.");
        world.playSound(new Location(world, 0.0, 80.0, 0.0),
                Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.6f);
        cinematics.onDragonPhase(dragon, nextPhase, resonanceBlocks);
    }

    private void placeResonanceStone(World world, double angle, double radius) {
        int x = (int) Math.round(Math.cos(angle) * radius);
        int z = (int) Math.round(Math.sin(angle) * radius);
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        if (y < 48 || y > 96) {
            y = 64;
        }
        Block base = world.getBlockAt(x, y - 1, z);
        if (base.getType().isAir()) {
            base.setType(Material.END_STONE_BRICKS, false);
        }
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.CRYING_OBSIDIAN, false);
        block.getRelative(BlockFace.UP).setType(Material.END_ROD, false);
        resonanceBlocks.add(block.getLocation());
    }

    private void tickResonanceParticles() {
        for (Location location : resonanceBlocks) {
            World world = location.getWorld();
            if (world == null) {
                continue;
            }
            world.spawnParticle(
                    Particle.PORTAL,
                    location.clone().add(0.5, 1.2, 0.5),
                    18,
                    0.35,
                    0.55,
                    0.35,
                    0.02);
            world.spawnParticle(
                    Particle.END_ROD,
                    location.clone().add(0.5, 1.9, 0.5),
                    4,
                    0.15,
                    0.2,
                    0.15,
                    0.01);
        }
    }

    private void tickMinions(EnderDragon dragon) {
        if (resonanceBlocks.isEmpty()) {
            return;
        }
        long cooldown = Math.max(3000L,
                plugin.getConfig().getLong("dragon.minion-cooldown-ticks", 240L) * 50L);
        long now = System.currentTimeMillis();
        if (now - lastMinionSpawn < cooldown) {
            return;
        }
        lastMinionSpawn = now;
        int count = plugin.getConfig().getInt("dragon.minions-per-phase", 2) + phase;
        List<Player> players = activePlayers(dragon.getWorld(), dragon.getLocation());
        if (players.isEmpty()) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
            EntityType type = i % 3 == 0 ? EntityType.SHULKER : EntityType.PHANTOM;
            Location spawn = target.getLocation().clone().add(
                    random(-10, 10), type == EntityType.PHANTOM ? 12.0 : 1.0, random(-10, 10));
            Entity spawned = dragon.getWorld().spawnEntity(
                    spawn, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (spawned instanceof LivingEntity living) {
                living.setRemoveWhenFarAway(true);
                living.addScoreboardTag("qsmp_dragon_minion");
                AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
                if (health != null) {
                    health.setBaseValue(Math.min(80.0, health.getBaseValue() + phase * 8.0));
                    living.setHealth(health.getValue());
                }
            }
        }
    }

    private void pruneBrokenResonance() {
        resonanceBlocks.removeIf(location -> {
            Block block = location.getBlock();
            return block.getType() != Material.CRYING_OBSIDIAN;
        });
    }

    private Location resonanceMainBlock(Block block) {
        if (block.getType() == Material.CRYING_OBSIDIAN) {
            return block.getLocation();
        }
        Block below = block.getRelative(BlockFace.DOWN);
        if (block.getType() == Material.END_ROD
                && below.getType() == Material.CRYING_OBSIDIAN) {
            return below.getLocation();
        }
        return null;
    }

    private List<Player> activePlayers(World world, Location center) {
        double radius = Math.max(48.0, plugin.getConfig().getDouble("dragon.reward-radius", 192.0));
        List<Player> players = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (!player.isDead() && player.getLocation().distanceSquared(center) <= radius * radius) {
                players.add(player);
            }
        }
        return players;
    }

    private boolean isDragonSource(Entity attacker) {
        return attacker instanceof EnderDragon || attacker.getType() == EntityType.DRAGON_FIREBALL;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("dragon.enabled", true);
    }

    private int random(int minimum, int maximum) {
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    private void cleanupResonance(boolean removeBlocks) {
        if (removeBlocks) {
            for (Location location : resonanceBlocks) {
                Block block = location.getBlock();
                if (block.getType() == Material.CRYING_OBSIDIAN) {
                    block.setType(Material.AIR, false);
                }
                Block above = block.getRelative(BlockFace.UP);
                if (above.getType() == Material.END_ROD) {
                    above.setType(Material.AIR, false);
                }
            }
        }
        resonanceBlocks.clear();
    }
}
