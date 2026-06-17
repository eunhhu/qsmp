package dev.qsmp.frontier;

import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class CinematicService {
    private final QSMPFrontier plugin;
    private final FrontierItems items;
    private long lastTrailAt;

    CinematicService(QSMPFrontier plugin, FrontierItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    void tick() {
        if (!enabled() || !plugin.getConfig().getBoolean("cinematics.compass-trails", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTrailAt < 1200L) {
            return;
        }
        lastTrailAt = now;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (items.is(player.getInventory().getItemInMainHand(), FrontierItems.FIELD_COMPASS)
                    || items.is(player.getInventory().getItemInOffHand(), FrontierItems.FIELD_COMPASS)) {
                paintCompassTrail(player);
            }
            if (items.is(player.getInventory().getItemInMainHand(), FrontierItems.FRONTIER_CODEX)) {
                player.getWorld().spawnParticle(
                        Particle.PORTAL,
                        player.getLocation().add(0.0, 1.0, 0.0),
                        5,
                        0.25,
                        0.35,
                        0.25,
                        0.01);
            }
        }
    }

    void onJoin(Player player) {
        if (!enabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.sendTitle(
                    ChatColor.DARK_PURPLE + "QSMP FRONTIER",
                    ChatColor.GRAY + "Hold the compass. Follow the world.",
                    20,
                    70,
                    20);
            player.playSound(player.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 0.8f, 0.75f);
            ring(player.getLocation().add(0.0, 0.2, 0.0), Particle.END_ROD, 2.2, 18, 0.03);
        }, 30L);
    }

    void onParryReady(Player player) {
        if (!enabled()) {
            return;
        }
        Location location = player.getLocation().add(0.0, 1.0, 0.0);
        player.getWorld().spawnParticle(Particle.CRIT, location, 8, 0.35, 0.2, 0.35, 0.04);
    }

    void onPerfectParry(Player player, Entity attacker) {
        if (!enabled()) {
            return;
        }
        Location center = player.getLocation().add(0.0, 1.0, 0.0);
        ring(center, Particle.CRIT, 1.7, 24, 0.12);
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 3, 0.5, 0.25, 0.5, 0.0);
        if (attacker != null) {
            beam(center, attacker.getLocation().add(0.0, 1.0, 0.0), Particle.END_ROD, 10);
        }
    }

    void onRoll(Player player) {
        if (!enabled()) {
            return;
        }
        Location location = player.getLocation().add(0.0, 0.25, 0.0);
        player.getWorld().spawnParticle(Particle.CLOUD, location, 18, 0.55, 0.15, 0.55, 0.04);
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, location, 2, 0.25, 0.1, 0.25, 0.0);
    }

    void onDodgeAvoid(Player player) {
        if (!enabled()) {
            return;
        }
        player.getWorld().spawnParticle(
                Particle.END_ROD,
                player.getLocation().add(0.0, 1.0, 0.0),
                10,
                0.4,
                0.5,
                0.4,
                0.03);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.55f, 1.85f);
    }

    void onCompanionAssigned(Player player, Entity companion, CompanionRole role) {
        if (!enabled()) {
            return;
        }
        Location location = companion.getLocation().add(0.0, 1.0, 0.0);
        Particle particle = switch (role) {
            case VANGUARD -> Particle.CRIT;
            case RANGER -> Particle.END_ROD;
            case MEDIC -> Particle.HAPPY_VILLAGER;
            case GATHERER -> Particle.SOUL_FIRE_FLAME;
        };
        ring(location, particle, 1.3, 18, 0.03);
        beam(player.getEyeLocation(), location, particle, 8);
        companion.getWorld().playSound(companion.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.7f, 1.5f);
    }

    void onRangerShot(LivingEntity companion, LivingEntity target) {
        if (!enabled()) {
            return;
        }
        beam(companion.getEyeLocation(), target.getEyeLocation(), Particle.END_ROD, 8);
    }

    void onMedicPulse(LivingEntity companion) {
        if (!enabled()) {
            return;
        }
        ring(companion.getLocation().add(0.0, 0.4, 0.0), Particle.HAPPY_VILLAGER, 3.0, 28, 0.02);
    }

    void onOutpostEstablished(Block block) {
        if (!enabled()) {
            return;
        }
        Location center = block.getLocation().add(0.5, 0.8, 0.5);
        ring(center, Particle.HAPPY_VILLAGER, 2.6, 30, 0.02);
        block.getWorld().playSound(center, Sound.BLOCK_BEACON_AMBIENT, 0.8f, 1.25f);
    }

    void onOutpostUpgrade(Block block) {
        if (!enabled()) {
            return;
        }
        Location center = block.getLocation().add(0.5, 1.0, 0.5);
        ring(center, Particle.FIREWORK, 3.0, 36, 0.05);
        block.getWorld().playSound(center, Sound.BLOCK_ANVIL_USE, 0.9f, 1.45f);
    }

    void onOutpostProduction(Location location, boolean boosted, boolean overflow) {
        if (!enabled()) {
            return;
        }
        Particle particle = overflow ? Particle.SMOKE : boosted ? Particle.FIREWORK : Particle.HAPPY_VILLAGER;
        location.getWorld().spawnParticle(particle, location, boosted ? 10 : 5, 0.35, 0.35, 0.35, 0.02);
    }

    void onRuinSignal(Player player, Location target) {
        if (!enabled()) {
            return;
        }
        beam(player.getLocation().add(0.0, 1.1, 0.0), toward(player, target, 7.0), Particle.SOUL_FIRE_FLAME, 9);
    }

    void onRuinSurfaced(Location center) {
        if (!enabled()) {
            return;
        }
        broadcastNear(center, ChatColor.DARK_AQUA + "The ruin exhales. Find the ward anchors.");
        center.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.4f, 0.55f);
        expandingRings(center.clone().add(0.5, 0.3, 0.5), Particle.SOUL_FIRE_FLAME);
    }

    void onWardBroken(Location location, int remaining) {
        if (!enabled()) {
            return;
        }
        location.getWorld().spawnParticle(Particle.SONIC_BOOM, location.clone().add(0.5, 1.1, 0.5), 1);
        ring(location.clone().add(0.5, 0.5, 0.5), Particle.SOUL_FIRE_FLAME, 2.4, 28, 0.05);
        location.getWorld().playSound(location, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.3f, 0.6f + remaining * 0.08f);
    }

    void onVaultOpened(Location vault) {
        if (!enabled() || vault == null) {
            return;
        }
        vault.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING,
                vault.clone().add(0.5, 1.3, 0.5),
                48,
                0.8,
                0.9,
                0.8,
                0.06);
        vault.getWorld().playSound(vault, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.8f, 0.65f);
    }

    void onVaultClaimed(Location vault, List<Player> players) {
        if (!enabled() || vault == null) {
            return;
        }
        expandingRings(vault.clone().add(0.5, 0.3, 0.5), Particle.END_ROD);
        for (Player player : players) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 0.9f);
        }
    }

    void onWarfrontPrelude(List<Player> players, int remaining) {
        if (!enabled()) {
            return;
        }
        for (Player player : players) {
            if (remaining <= 5) {
                player.getWorld().spawnParticle(
                        Particle.SMOKE,
                        player.getLocation().add(0.0, 1.8, 0.0),
                        6,
                        0.8,
                        0.25,
                        0.8,
                        0.02);
            }
        }
    }

    void onWarfrontStart(Location center, List<Player> players) {
        if (!enabled()) {
            return;
        }
        center.getWorld().playSound(center, Sound.EVENT_RAID_HORN, 3.0f, 0.55f);
        expandingRings(center.clone().add(0.5, 0.3, 0.5), Particle.SMOKE);
        for (Player player : players) {
            player.sendTitle(ChatColor.DARK_RED + "WARFRONT", ChatColor.GRAY + "Hold the lanes", 10, 55, 15);
        }
    }

    void onWarfrontWave(Location center, int wave) {
        if (!enabled()) {
            return;
        }
        ring(center.clone().add(0.5, 0.4, 35.5), Particle.CRIT, 4.0 + wave, 36, 0.05);
        center.getWorld().playSound(center, Sound.EVENT_RAID_HORN, 1.8f, 0.72f + wave * 0.05f);
    }

    void onWarfrontBreach(Location center) {
        if (!enabled()) {
            return;
        }
        Location breach = center.clone().add(0.5, 0.6, 37.5);
        breach.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, breach, 3, 3.0, 0.6, 1.2, 0.0);
        breach.getWorld().playSound(breach, Sound.ENTITY_RAVAGER_ROAR, 2.2f, 0.55f);
    }

    void onIronTyrantSpawn(Location location) {
        if (!enabled()) {
            return;
        }
        expandingRings(location.clone().add(0.0, 0.3, 0.0), Particle.CRIT);
        location.getWorld().playSound(location, Sound.ENTITY_WITHER_SPAWN, 1.7f, 0.62f);
    }

    void onIronTyrantPhase(Location location, int phase) {
        if (!enabled()) {
            return;
        }
        ring(location.clone().add(0.0, 0.5, 0.0), phase >= 2 ? Particle.FIREWORK : Particle.SMOKE, 5.0, 44, 0.06);
        location.getWorld().playSound(location, Sound.ENTITY_RAVAGER_ROAR, 2.0f, phase >= 2 ? 0.42f : 0.65f);
    }

    void onWarfrontVictory(Location center, List<Player> players) {
        if (!enabled()) {
            return;
        }
        expandingRings(center.clone().add(0.5, 0.4, 0.5), Particle.FIREWORK);
        for (Player player : players) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.8f);
        }
    }

    void onDragonAwaken(EnderDragon dragon, int players) {
        if (!enabled()) {
            return;
        }
        Location center = dragon.getLocation();
        center.getWorld().playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 3.0f, 0.45f);
        expandingRings(center.clone().add(0.0, 1.0, 0.0), Particle.PORTAL);
        broadcastNear(center, ChatColor.DARK_PURPLE
                + "The dragon is awake. Scaled for " + players + " raider(s).");
    }

    void onDragonPhase(EnderDragon dragon, int phase, Set<Location> stones) {
        if (!enabled()) {
            return;
        }
        Location center = dragon.getLocation();
        center.getWorld().playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 2.0f, 0.45f);
        for (Location stone : stones) {
            beam(center.clone().add(0.0, 2.5, 0.0), stone.clone().add(0.5, 1.5, 0.5), Particle.PORTAL, 18);
            ring(stone.clone().add(0.5, 0.6, 0.5), Particle.END_ROD, 2.0 + phase, 28, 0.04);
        }
        broadcastNear(center, ChatColor.LIGHT_PURPLE
                + "Resonance stones are shielding the dragon. Split and shatter them.");
    }

    void onDragonStoneBroken(Player player, Location stone, int remaining) {
        if (!enabled()) {
            return;
        }
        stone.getWorld().spawnParticle(Particle.SONIC_BOOM, stone.clone().add(0.5, 1.2, 0.5), 1);
        ring(stone.clone().add(0.5, 0.5, 0.5), Particle.PORTAL, 3.0, 36, 0.08);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.9f);
        if (remaining > 0) {
            player.sendActionBar(ChatColor.LIGHT_PURPLE + "Resonance shattered. " + remaining + " remain.");
        }
    }

    void onDragonShieldCollapsed(Location center) {
        if (!enabled()) {
            return;
        }
        center.getWorld().playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.4f, 0.95f);
        expandingRings(center.clone().add(0.0, 0.8, 0.0), Particle.TOTEM_OF_UNDYING);
    }

    void onDragonCleared(Location center, List<Player> players) {
        if (!enabled()) {
            return;
        }
        center.getWorld().playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.6f, 0.65f);
        expandingRings(center.clone().add(0.0, 1.0, 0.0), Particle.FIREWORK);
        for (Player player : players) {
            player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.2f);
        }
    }

    private void paintCompassTrail(Player player) {
        Location target = player.getCompassTarget();
        if (target == null || !target.getWorld().equals(player.getWorld())) {
            return;
        }
        Location origin = player.getLocation().add(0.0, 0.85, 0.0);
        Vector direction = target.toVector().subtract(origin.toVector()).setY(0.0);
        if (direction.lengthSquared() < 16.0) {
            return;
        }
        direction.normalize();
        for (int i = 2; i <= 9; i++) {
            Location point = origin.clone().add(direction.clone().multiply(i));
            point.setY(player.getWorld().getHighestBlockYAt(point) + 0.2);
            player.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.02, 0.02, 0.0);
        }
        player.sendActionBar(ChatColor.AQUA + "Frontier trail "
                + ChatColor.GRAY + Math.round(player.getLocation().distance(target)) + "m");
    }

    private Location toward(Player player, Location target, double distance) {
        Vector direction = target.toVector()
                .subtract(player.getLocation().toVector())
                .setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            direction = player.getLocation().getDirection().setY(0.0);
        }
        return player.getLocation().add(direction.normalize().multiply(distance)).add(0.0, 1.1, 0.0);
    }

    private void expandingRings(Location center, Particle particle) {
        for (int step = 0; step < 4; step++) {
            int delay = step * 5;
            double radius = 1.5 + step * 1.35;
            Bukkit.getScheduler().runTaskLater(plugin, () -> ring(center, particle, radius, 36, 0.04), delay);
        }
    }

    private void ring(Location center, Particle particle, double radius, int points, double speed) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (int i = 0; i < points; i++) {
            double radians = Math.PI * 2.0 * i / points;
            Location point = center.clone().add(
                    Math.cos(radians) * radius,
                    0.0,
                    Math.sin(radians) * radius);
            world.spawnParticle(particle, point, 1, 0.03, 0.03, 0.03, speed);
        }
    }

    private void beam(Location from, Location to, Particle particle, int points) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return;
        }
        Vector start = from.toVector();
        Vector delta = to.toVector().subtract(start);
        for (int i = 0; i <= points; i++) {
            Location point = start.clone()
                    .add(delta.clone().multiply((double) i / points))
                    .toLocation(world);
            world.spawnParticle(particle, point, 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    private void broadcastNear(Location center, String message) {
        double radius = 96.0;
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= radius * radius) {
                player.sendMessage(message);
            }
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("cinematics.enabled", true);
    }
}
