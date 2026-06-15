package dev.qsmp.companions;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class CompanionListener implements Listener {
    private final QSMPCompanions plugin;
    private final CompanionService companions;
    private final CompanionItems items;

    CompanionListener(
            QSMPCompanions plugin,
            CompanionService companions,
            CompanionItems items) {
        this.plugin = plugin;
        this.companions = companions;
        this.items = items;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> companions.initialize(event.getEntity()));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            companions.initialize(entity);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> companions.initialize(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof LivingEntity living)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        String type = items.itemType(held);
        if (CompanionItems.BONDING_CHARM.equals(type)) {
            event.setCancelled(true);
            items.bondHappyGhast(player, living, held);
            return;
        }
        if (CompanionItems.CRYOPOD_EMPTY.equals(type)) {
            event.setCancelled(true);
            items.capture(player, living, held);
            return;
        }
        int tier = items.armorTier(held);
        if (tier > 0) {
            event.setCancelled(true);
            items.applyArmor(player, living, held, tier);
            return;
        }
        if (player.isSneaking()
                && (held.getType() == Material.AIR || held.getType().isAir())
                && companions.isOwner(player, living)) {
            event.setCancelled(true);
            companions.sendInfo(player, living);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUseCryopod(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || !CompanionItems.CRYOPOD_FILLED.equals(items.itemType(event.getItem()))) {
            return;
        }
        event.setCancelled(true);
        items.release(
                event.getPlayer(),
                event.getItem(),
                event.getClickedBlock(),
                event.getBlockFace());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!companions.isSupported(event.getEntity())) {
            return;
        }
        UUID victimOwner = companions.ownerId(event.getEntity());
        UUID attackerOwner = attackingOwner(event.getDamager());
        if (victimOwner != null && victimOwner.equals(attackerOwner)) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Player player) {
                player.sendActionBar(ChatColor.YELLOW + "You cannot hurt your companion.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        companions.markDamaged(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatSignal(EntityDamageByEntityEvent event) {
        Entity attacker = directAttacker(event.getDamager());
        if (event.getEntity() instanceof LivingEntity victim && victim instanceof Enemy) {
            if (attacker instanceof Player player) {
                companions.engageNearby(player, victim);
            } else if (attacker instanceof LivingEntity living
                    && companions.isSupported(living)) {
                companions.engage(living, victim);
            }
        }

        if (attacker instanceof LivingEntity hostile && hostile instanceof Enemy) {
            if (event.getEntity() instanceof Player player) {
                companions.engageNearby(player, hostile);
            } else if (event.getEntity() instanceof LivingEntity companion
                    && companions.isSupported(companion)) {
                companions.engage(companion, hostile);
                Player owner = Bukkit.getPlayer(companions.ownerId(companion));
                if (owner != null) {
                    companions.engageNearby(owner, hostile);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)) {
            return;
        }
        Entity cause = event.getDamageSource().getCausingEntity();
        if (cause instanceof Projectile projectile && projectile.getShooter() instanceof Entity owner) {
            cause = owner;
        }
        if (cause instanceof Player player) {
            LivingEntity nearest = companions.nearestOwnedCompanion(player);
            if (nearest != null) {
                companions.addXp(nearest, companions.killReward());
            }
        } else if (cause instanceof LivingEntity living && companions.isSupported(living)) {
            companions.addXp(living, companions.killReward());
        }
    }

    private UUID attackingOwner(Entity damager) {
        Entity source = directAttacker(damager);
        if (source instanceof Player player) {
            return player.getUniqueId();
        }
        return companions.ownerId(source);
    }

    private Entity directAttacker(Entity damager) {
        Entity source = damager;
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        return source;
    }
}
