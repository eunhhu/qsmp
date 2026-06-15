package dev.qsmp.frontier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

final class CompanionRoleService {
    private final QSMPFrontier plugin;
    private final FrontierKeys keys;
    private final Map<UUID, UUID> ownerTargets = new HashMap<>();
    private final Map<UUID, Long> lastRangedAttack = new HashMap<>();
    private final Map<UUID, Long> lastHeal = new HashMap<>();

    CompanionRoleService(QSMPFrontier plugin, FrontierKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    UUID ownerId(Entity entity) {
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return tameable.getOwnerUniqueId();
        }
        String stored = entity.getPersistentDataContainer().get(
                keys.companionOwner, PersistentDataType.STRING);
        if (stored == null) {
            return null;
        }
        try {
            return UUID.fromString(stored);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    boolean isCompanion(Entity entity) {
        return entity instanceof LivingEntity
                && (entity instanceof Tameable || entity instanceof HappyGhast)
                && ownerId(entity) != null;
    }

    boolean isOwner(Player player, Entity entity) {
        return player.getUniqueId().equals(ownerId(entity));
    }

    CompanionRole role(Entity entity) {
        String stored = entity.getPersistentDataContainer().get(
                keys.companionRole, PersistentDataType.STRING);
        return stored == null ? null : CompanionRole.parse(stored);
    }

    boolean assign(Player player, Entity entity, CompanionRole role) {
        if (!isCompanion(entity) || !isOwner(player, entity)) {
            player.sendMessage(ChatColor.RED + "Look directly at your owned companion.");
            return false;
        }
        entity.getPersistentDataContainer().set(
                keys.companionRole, PersistentDataType.STRING, role.name());
        player.sendMessage(ChatColor.GOLD + entity.getName() + ChatColor.GRAY
                + " is now " + ChatColor.AQUA + role.display()
                + ChatColor.GRAY + ": " + role.description() + ".");
        return true;
    }

    void signalTarget(Player owner, LivingEntity target) {
        if (!target.isDead()) {
            ownerTargets.put(owner.getUniqueId(), target.getUniqueId());
        }
    }

    void signalTarget(Entity companion, LivingEntity target) {
        UUID owner = ownerId(companion);
        if (owner != null && !target.isDead()) {
            ownerTargets.put(owner, target.getUniqueId());
        }
    }

    void tick() {
        long now = System.currentTimeMillis();
        double radius = plugin.getConfig().getDouble("companions.tactical-radius", 28.0);
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity companion : world.getLivingEntities()) {
                CompanionRole role = role(companion);
                UUID ownerId = ownerId(companion);
                Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
                if (role == null || owner == null || owner.isDead()
                        || !owner.getWorld().equals(world)
                        || owner.getLocation().distanceSquared(companion.getLocation())
                                > radius * radius) {
                    continue;
                }
                switch (role) {
                    case RANGER -> tickRanger(companion, owner, now);
                    case MEDIC -> tickMedic(companion, owner, now);
                    default -> {
                    }
                }
            }
        }
    }

    double gatheringMultiplier(Location location, UUID owner) {
        double radius = plugin.getConfig().getDouble("outposts.gatherer-radius", 10.0);
        for (Entity candidate : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (candidate instanceof LivingEntity
                    && owner.equals(ownerId(candidate))
                    && role(candidate) == CompanionRole.GATHERER) {
                int level = candidate.getPersistentDataContainer().getOrDefault(
                        keys.companionLevel, PersistentDataType.INTEGER, 1);
                return FrontierMath.gatherMultiplier(level);
            }
        }
        return 1.0;
    }

    double outgoingMultiplier(Entity attacker) {
        return role(attacker) == CompanionRole.VANGUARD
                ? plugin.getConfig().getDouble(
                        "companions.vanguard-damage-multiplier", 1.35)
                : 1.0;
    }

    double incomingMultiplier(Entity victim) {
        if (role(victim) != CompanionRole.VANGUARD) {
            return 1.0;
        }
        double reduction = plugin.getConfig().getDouble(
                "companions.vanguard-damage-reduction", 0.22);
        return Math.max(0.1, 1.0 - reduction);
    }

    private void tickRanger(LivingEntity companion, Player owner, long now) {
        long cooldown = plugin.getConfig().getLong(
                "companions.ranger-cooldown-ticks", 45L) * 50L;
        if (now - lastRangedAttack.getOrDefault(companion.getUniqueId(), 0L) < cooldown) {
            return;
        }
        Entity targetEntity = Bukkit.getEntity(ownerTargets.get(owner.getUniqueId()));
        if (!(targetEntity instanceof LivingEntity target)
                || target.isDead()
                || !target.getWorld().equals(companion.getWorld())
                || target.getLocation().distanceSquared(companion.getLocation()) > 24.0 * 24.0) {
            return;
        }
        Vector direction = target.getEyeLocation().toVector()
                .subtract(companion.getEyeLocation().toVector())
                .normalize()
                .multiply(1.8);
        Arrow arrow = companion.launchProjectile(Arrow.class, direction);
        arrow.setDamage(plugin.getConfig().getDouble("companions.ranger-damage", 6.0));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        lastRangedAttack.put(companion.getUniqueId(), now);
        companion.getWorld().playSound(
                companion.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.3f);
    }

    private void tickMedic(LivingEntity companion, Player owner, long now) {
        long cooldown = plugin.getConfig().getLong(
                "companions.medic-cooldown-ticks", 80L) * 50L;
        if (now - lastHeal.getOrDefault(companion.getUniqueId(), 0L) < cooldown) {
            return;
        }
        double radius = plugin.getConfig().getDouble("companions.medic-radius", 9.0);
        double heal = plugin.getConfig().getDouble("companions.medic-heal", 4.0);
        heal(owner, heal);
        for (Entity candidate : companion.getNearbyEntities(radius, radius, radius)) {
            if (candidate instanceof LivingEntity living
                    && owner.getUniqueId().equals(ownerId(living))) {
                heal(living, heal);
            }
        }
        companion.getWorld().spawnParticle(
                Particle.HEART, companion.getLocation().add(0, 1.2, 0), 6, 0.6, 0.4, 0.6);
        companion.getWorld().playSound(
                companion.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.6f);
        lastHeal.put(companion.getUniqueId(), now);
    }

    private void heal(LivingEntity entity, double amount) {
        double maximum = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? entity.getHealth()
                : entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        entity.setHealth(Math.min(maximum, entity.getHealth() + Math.max(0.0, amount)));
    }
}
