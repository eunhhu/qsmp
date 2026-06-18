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
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class FrontierListener implements Listener {
    private final QSMPFrontier plugin;
    private final CombatService combat;
    private final CompanionRoleService roles;
    private final FrontierItems items;
    private final OutpostService outposts;
    private final WarfrontService warfront;
    private final ExpeditionService expeditions;
    private final ProgressionService progression;
    private final LegacyService legacies;
    private final DragonService dragons;
    private final FrontierGuideMenu guideMenu;
    private final CinematicService cinematics;
    private final MobNameplateService nameplates;

    FrontierListener(
            QSMPFrontier plugin,
            CombatService combat,
            CompanionRoleService roles,
            FrontierItems items,
            OutpostService outposts,
            WarfrontService warfront,
            ExpeditionService expeditions,
            ProgressionService progression,
            LegacyService legacies,
            DragonService dragons,
            FrontierGuideMenu guideMenu,
            CinematicService cinematics,
            MobNameplateService nameplates) {
        this.plugin = plugin;
        this.combat = combat;
        this.roles = roles;
        this.items = items;
        this.outposts = outposts;
        this.warfront = warfront;
        this.expeditions = expeditions;
        this.progression = progression;
        this.legacies = legacies;
        this.dragons = dragons;
        this.guideMenu = guideMenu;
        this.cinematics = cinematics;
        this.nameplates = nameplates;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (combat.startParry(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacySwap(PlayerSwapHandItemsEvent event) {
        scheduleLegacySync(event.getPlayer());
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
        if (items.is(event.getItem(), FrontierItems.FRONTIER_CODEX)
                && event.getPlayer().isSneaking()
                && (event.getAction() == Action.RIGHT_CLICK_AIR
                        || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            guideMenu.open(event.getPlayer());
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
        items.giveStarterKit(event.getPlayer());
        progression.onJoin(event.getPlayer());
        legacies.ensureInventory(event.getPlayer());
        cinematics.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof LivingEntity entity) {
            nameplates.updateLater(entity);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        guideMenu.onClick(event);
        progression.onClick(event);
        legacies.onClick(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleLegacySync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleLegacySync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyHeld(PlayerItemHeldEvent event) {
        scheduleLegacySync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyItemDamage(PlayerItemDamageEvent event) {
        legacies.onItemDamage(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyBlockDamage(BlockDamageEvent event) {
        legacies.onBlockDamage(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        progression.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (combat.avoidDamage(event) || event.isCancelled()) {
            return;
        }
        progression.onIncomingDamage(event);
        legacies.onIncomingDamage(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageWindow(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        applyDamageWindow(entity);
        nameplates.updateLater(entity);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (entity.isValid()) {
                applyDamageWindow(entity);
                nameplates.update(entity);
            }
        });
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
        dragons.onCombat(event, attacker);
        if (attacker instanceof Player player
                && event.getEntity() instanceof LivingEntity target) {
            progression.onOutgoingDamage(event, player);
            legacies.onCombat(event, player, target);
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
        legacies.onKill(event);
        dragons.onDeath(event);
        progression.onOrdinaryMobDeath(event);
        progression.onVanillaBossDeath(event, legacies);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOutpostPlace(BlockPlaceEvent event) {
        progression.onBlockPlace(event);
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
        dragons.onBlockBreak(event);
        if (event.isCancelled()) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLegacyBlockBreak(BlockBreakEvent event) {
        progression.onNaturalBlockBreak(event);
        legacies.onBlockBreak(event);
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

    private void applyDamageWindow(LivingEntity entity) {
        int ticks = entity instanceof Player
                ? plugin.getConfig().getInt("combat.player-no-damage-ticks", 2)
                : plugin.getConfig().getInt("combat.mob-no-damage-ticks", 0);
        entity.setNoDamageTicks(Math.max(0, ticks));
    }

    private void scheduleLegacySync(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                legacies.tickPlayer(player);
            }
        });
    }
}
