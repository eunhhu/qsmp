package dev.qsmp.frontier;

import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class FrontierListener implements Listener {
    private final CombatService combat;
    private final CompanionRoleService roles;
    private final FrontierItems items;
    private final OutpostService outposts;
    private final WarfrontService warfront;
    private final ExpeditionService expeditions;

    FrontierListener(
            CombatService combat,
            CompanionRoleService roles,
            FrontierItems items,
            OutpostService outposts,
            WarfrontService warfront,
            ExpeditionService expeditions) {
        this.combat = combat;
        this.roles = roles;
        this.items = items;
        this.outposts = outposts;
        this.warfront = warfront;
        this.expeditions = expeditions;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (combat.startParry(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        combat.onSneak(event.getPlayer(), event.isSneaking());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCompanionWhistle(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !items.is(event.getPlayer().getInventory().getItemInMainHand(),
                        FrontierItems.TACTICAL_WHISTLE)) {
            return;
        }
        CompanionRole role = items.whistleRole(
                event.getPlayer().getInventory().getItemInMainHand());
        event.setCancelled(true);
        roles.assign(event.getPlayer(), event.getRightClicked(), role);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUseFrontierItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && expeditions.onInteract(event)) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && outposts.isOutpost(event.getClickedBlock())) {
            if (outposts.interact(
                    event.getPlayer(), event.getClickedBlock(), event.getItem())) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack held = event.getItem();
        if (items.is(held, FrontierItems.TACTICAL_WHISTLE)) {
            event.setCancelled(true);
            items.cycleWhistle(event.getPlayer(), held);
        } else if (items.is(held, FrontierItems.FIELD_COMPASS)) {
            event.setCancelled(true);
            if (event.getPlayer().isSneaking()) {
                expeditions.guide(event.getPlayer());
            } else {
                warfront.guide(event.getPlayer());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        items.giveFieldCompassOnce(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        combat.avoidDamage(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (combat.parry(event)) {
            return;
        }
        Entity attacker = directAttacker(event.getDamager());
        if (roles.isCompanion(attacker)) {
            event.setDamage(event.getDamage() * roles.outgoingMultiplier(attacker));
            if (event.getEntity() instanceof LivingEntity target) {
                roles.signalTarget(attacker, target);
            }
        }
        if (roles.isCompanion(event.getEntity())) {
            event.setDamage(event.getDamage() * roles.incomingMultiplier(event.getEntity()));
        }
        if (attacker instanceof Player player
                && event.getEntity() instanceof LivingEntity target) {
            roles.signalTarget(player, target);
        }
        if (event.getEntity() instanceof Player player
                && attacker instanceof LivingEntity hostile) {
            roles.signalTarget(player, hostile);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        warfront.onDeath(event.getEntity());
        expeditions.onDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOutpostPlace(BlockPlaceEvent event) {
        OutpostType type = items.outpostType(event.getItemInHand());
        if (type != null) {
            outposts.create(event.getPlayer(), event.getBlockPlaced(), type);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOutpostBreak(BlockBreakEvent event) {
        if (expeditions.onBlockBreak(event)) {
            return;
        }
        if (!outposts.isOutpost(event.getBlock())) {
            return;
        }
        outposts.breakOutpost(event.getPlayer(), event.getBlock());
        if (outposts.isOutpost(event.getBlock())) {
            event.setCancelled(true);
        } else {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(outposts::isOutpost);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGather(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        Material blockType = event.getBlockState().getType();
        if (!isGatherable(blockType, event)) {
            return;
        }
        double multiplier = roles.gatheringMultiplier(
                event.getBlockState().getLocation(), player.getUniqueId());
        if (multiplier <= 1.0) {
            return;
        }
        for (Item dropped : event.getItems()) {
            ItemStack original = dropped.getItemStack();
            int bonusAmount = (int) Math.floor(original.getAmount() * (multiplier - 1.0));
            if (bonusAmount > 0) {
                ItemStack bonus = original.clone();
                bonus.setAmount(Math.min(bonus.getMaxStackSize(), bonusAmount));
                player.getWorld().dropItemNaturally(
                        dropped.getLocation(), bonus);
            }
        }
        player.sendActionBar(ChatColor.AQUA + "Gatherer bonus x"
                + String.format(java.util.Locale.ROOT, "%.2f", multiplier));
    }

    private boolean isGatherable(Material material, BlockDropItemEvent event) {
        if (Tag.LOGS.isTagged(material)
                || material.name().endsWith("_ORE")
                || material == Material.ANCIENT_DEBRIS
                || material == Material.MELON
                || material == Material.PUMPKIN
                || material == Material.SUGAR_CANE) {
            return true;
        }
        if (event.getBlockState().getBlockData() instanceof Ageable crop) {
            return crop.getAge() == crop.getMaximumAge();
        }
        return false;
    }

    private Entity directAttacker(Entity damager) {
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }
}
