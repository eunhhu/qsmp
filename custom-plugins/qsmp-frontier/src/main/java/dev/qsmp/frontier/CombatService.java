package dev.qsmp.frontier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

final class CombatService {
    private final QSMPFrontier plugin;
    private final Map<UUID, Long> rollUntil = new HashMap<>();
    private final Map<UUID, Long> rollCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> parryUntil = new HashMap<>();
    private final Map<UUID, Long> parryCooldownUntil = new HashMap<>();

    CombatService(QSMPFrontier plugin) {
        this.plugin = plugin;
    }

    boolean startParry(Player player) {
        if (!hasShield(player)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (parryCooldownUntil.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(ChatColor.DARK_GRAY + "Parry is recovering");
            return true;
        }
        long window = plugin.getConfig().getLong("combat.parry-window-ms", 425L);
        long cooldown = plugin.getConfig().getLong("combat.parry-cooldown-ms", 1800L);
        parryUntil.put(player.getUniqueId(), now + Math.max(100L, window));
        parryCooldownUntil.put(player.getUniqueId(), now + Math.max(window, cooldown));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1.8f);
        player.sendActionBar(ChatColor.AQUA + "PARRY");
        return true;
    }

    void onSneak(Player player, boolean sneaking) {
        if (!sneaking || !player.isSprinting() || !player.isOnGround() || player.isFlying()) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (rollCooldownUntil.getOrDefault(id, 0L) > now) {
            return;
        }

        Vector direction = player.getLocation().getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            return;
        }
        double speed = plugin.getConfig().getDouble("combat.roll-horizontal-speed", 1.15);
        direction.normalize().multiply(Math.max(0.4, speed)).setY(0.22);
        player.setVelocity(direction);
        long invulnerability = plugin.getConfig()
                .getLong("combat.roll-invulnerability-ms", 475L);
        long cooldown = plugin.getConfig().getLong("combat.roll-cooldown-ms", 2600L);
        rollUntil.put(id, now + Math.max(150L, invulnerability));
        rollCooldownUntil.put(id, now + Math.max(invulnerability, cooldown));
        player.getWorld().spawnParticle(
                Particle.CLOUD, player.getLocation().add(0, 0.2, 0), 12, 0.4, 0.1, 0.4, 0.03);
        player.getWorld().playSound(
                player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.35f);
        player.sendActionBar(ChatColor.WHITE + "DODGE");
    }

    boolean avoidDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return false;
        }
        if (rollUntil.getOrDefault(player.getUniqueId(), 0L) < System.currentTimeMillis()) {
            return false;
        }
        event.setCancelled(true);
        player.getWorld().spawnParticle(
                Particle.SWEEP_ATTACK, player.getLocation().add(0, 1.0, 0), 2);
        return true;
    }

    boolean parry(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || parryUntil.getOrDefault(player.getUniqueId(), 0L)
                        < System.currentTimeMillis()) {
            return false;
        }
        event.setCancelled(true);
        parryUntil.remove(player.getUniqueId());
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.7f);
        player.getWorld().spawnParticle(
                Particle.CRIT, player.getLocation().add(0, 1.0, 0), 25, 0.5, 0.6, 0.5, 0.15);
        player.sendActionBar(ChatColor.GOLD + "PERFECT PARRY");

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            Vector reflected = projectile.getVelocity().multiply(-1.25);
            projectile.setShooter(player);
            projectile.teleport(projectile.getLocation().add(reflected.clone().normalize()));
            projectile.setVelocity(reflected);
        } else if (damager instanceof LivingEntity attacker) {
            Vector knockback = attacker.getLocation().toVector()
                    .subtract(player.getLocation().toVector())
                    .setY(0.25);
            if (knockback.lengthSquared() > 0.01) {
                attacker.setVelocity(knockback.normalize().multiply(1.15).setY(0.35));
            }
            attacker.setFreezeTicks(Math.min(attacker.getMaxFreezeTicks(), 30));
        }
        return true;
    }

    private boolean hasShield(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.SHIELD
                || player.getInventory().getItemInOffHand().getType() == Material.SHIELD;
    }
}
