package dev.qsmp.companions;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

final class CompanionService {
    private final QSMPCompanions plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey xpKey;
    private final NamespacedKey armorTierKey;
    private final NamespacedKey lastDamageKey;
    private final NamespacedKey healthModifierKey;
    private final NamespacedKey attackModifierKey;
    private final NamespacedKey armorModifierKey;
    private final NamespacedKey toughnessModifierKey;
    private final NamespacedKey knockbackModifierKey;
    private final Map<UUID, UUID> combatTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAttacks = new ConcurrentHashMap<>();

    CompanionService(QSMPCompanions plugin) {
        this.plugin = plugin;
        ownerKey = new NamespacedKey(plugin, "owner");
        levelKey = new NamespacedKey(plugin, "level");
        xpKey = new NamespacedKey(plugin, "xp");
        armorTierKey = new NamespacedKey(plugin, "armor_tier");
        lastDamageKey = new NamespacedKey(plugin, "last_damage");
        healthModifierKey = new NamespacedKey(plugin, "level_health");
        attackModifierKey = new NamespacedKey(plugin, "level_attack");
        armorModifierKey = new NamespacedKey(plugin, "companion_armor");
        toughnessModifierKey = new NamespacedKey(plugin, "companion_toughness");
        knockbackModifierKey = new NamespacedKey(plugin, "companion_knockback");
    }

    UUID ownerId(Entity entity) {
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return tameable.getOwnerUniqueId();
        }
        String stored = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (stored == null) {
            return null;
        }
        try {
            return UUID.fromString(stored);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    boolean isSupported(Entity entity) {
        return entity instanceof LivingEntity
                && (entity instanceof Tameable || entity instanceof HappyGhast)
                && ownerId(entity) != null;
    }

    boolean isOwner(Player player, Entity entity) {
        return player.getUniqueId().equals(ownerId(entity));
    }

    void claimHappyGhast(Player owner, HappyGhast ghast) {
        ghast.getPersistentDataContainer().set(
                ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        initialize(ghast);
    }

    void initialize(Entity entity) {
        if (!isSupported(entity)) {
            return;
        }
        PersistentDataContainer data = entity.getPersistentDataContainer();
        if (!data.has(levelKey, PersistentDataType.INTEGER)) {
            data.set(levelKey, PersistentDataType.INTEGER, 1);
        }
        if (!data.has(xpKey, PersistentDataType.INTEGER)) {
            data.set(xpKey, PersistentDataType.INTEGER, 0);
        }
        if (!data.has(armorTierKey, PersistentDataType.INTEGER)) {
            data.set(armorTierKey, PersistentDataType.INTEGER, 0);
        }
        applyStats((LivingEntity) entity, true);
    }

    void refreshLoaded() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                initialize(entity);
            }
        }
    }

    int level(Entity entity) {
        return entity.getPersistentDataContainer().getOrDefault(
                levelKey, PersistentDataType.INTEGER, 1);
    }

    int xp(Entity entity) {
        return entity.getPersistentDataContainer().getOrDefault(
                xpKey, PersistentDataType.INTEGER, 0);
    }

    int xpToNext(int level) {
        int base = plugin.getConfig().getInt("leveling.base-xp", 50);
        int perLevel = plugin.getConfig().getInt("leveling.xp-per-level", 25);
        return Math.max(1, base + Math.max(0, level - 1) * perLevel);
    }

    int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt("leveling.max-level", 30));
    }

    int killReward() {
        return Math.max(1, plugin.getConfig().getInt("leveling.hostile-kill-xp", 15));
    }

    double shareRadius() {
        return Math.max(1.0, plugin.getConfig().getDouble("leveling.share-radius", 24.0));
    }

    void addXp(LivingEntity entity, int amount) {
        if (!isSupported(entity) || amount <= 0) {
            return;
        }
        int level = level(entity);
        if (level >= maxLevel()) {
            return;
        }

        int xp = xp(entity) + amount;
        int gained = 0;
        while (level < maxLevel() && xp >= xpToNext(level)) {
            xp -= xpToNext(level);
            level++;
            gained++;
        }
        if (level >= maxLevel()) {
            xp = 0;
        }

        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(levelKey, PersistentDataType.INTEGER, level);
        data.set(xpKey, PersistentDataType.INTEGER, xp);
        applyStats(entity, true);

        if (gained > 0) {
            Player owner = Bukkit.getPlayer(ownerId(entity));
            if (owner != null) {
                owner.sendMessage(ChatColor.GOLD + displayName(entity)
                        + " reached level " + level + "!");
            }
        }
    }

    LivingEntity nearestOwnedCompanion(Player player) {
        double radius = shareRadius();
        LivingEntity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity candidate : player.getNearbyEntities(radius, radius, radius)) {
            if (!(candidate instanceof LivingEntity living) || !isOwner(player, candidate)) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(player.getLocation());
            if (distance < closestDistance) {
                closest = living;
                closestDistance = distance;
            }
        }
        return closest;
    }

    void engageNearby(Player owner, LivingEntity target) {
        if (target.isDead()) {
            return;
        }
        double radius = Math.max(1.0,
                plugin.getConfig().getDouble("combat.assist-radius", 20.0));
        for (Entity candidate : owner.getNearbyEntities(radius, radius, radius)) {
            if (candidate instanceof LivingEntity living && isOwner(owner, candidate)) {
                engage(living, target);
            }
        }
    }

    void engage(LivingEntity companion, LivingEntity target) {
        if (!isSupported(companion)
                || companion.equals(target)
                || target.isDead()
                || !companion.getWorld().equals(target.getWorld())) {
            return;
        }
        combatTargets.put(companion.getUniqueId(), target.getUniqueId());
    }

    void tickCombat() {
        double pursuit = Math.max(4.0,
                plugin.getConfig().getDouble("combat.max-pursuit-distance", 32.0));
        double attackRange = Math.max(1.0,
                plugin.getConfig().getDouble("combat.attack-range", 2.8));
        double speed = Math.max(0.1,
                plugin.getConfig().getDouble("combat.path-speed", 1.15));
        long cooldown = Math.max(1L,
                plugin.getConfig().getLong("combat.attack-cooldown-ticks", 20L)) * 50L;
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, UUID> entry : combatTargets.entrySet()) {
            Entity companionEntity = Bukkit.getEntity(entry.getKey());
            Entity targetEntity = Bukkit.getEntity(entry.getValue());
            if (!(companionEntity instanceof Mob companion)
                    || !(targetEntity instanceof LivingEntity target)
                    || !isSupported(companion)
                    || companion.isDead()
                    || target.isDead()
                    || !companion.getWorld().equals(target.getWorld())
                    || companion.getLocation().distanceSquared(target.getLocation())
                            > pursuit * pursuit) {
                clearCombatTarget(entry.getKey(), companionEntity);
                continue;
            }
            if ((companion instanceof Sittable sittable && sittable.isSitting())
                    || !companion.getPassengers().isEmpty()) {
                companion.getPathfinder().stopPathfinding();
                continue;
            }

            companion.getPathfinder().moveTo(target, speed);
            if (companion.getLocation().distanceSquared(target.getLocation())
                    > attackRange * attackRange) {
                continue;
            }
            long lastAttack = lastAttacks.getOrDefault(companion.getUniqueId(), 0L);
            if (now - lastAttack < cooldown) {
                continue;
            }
            lastAttacks.put(companion.getUniqueId(), now);
            companion.lookAt(target);
            companion.swingMainHand();
            target.damage(combatDamage(companion), companion);
        }
    }

    int armorTier(Entity entity) {
        return entity.getPersistentDataContainer().getOrDefault(
                armorTierKey, PersistentDataType.INTEGER, 0);
    }

    void setArmorTier(LivingEntity entity, int tier) {
        entity.getPersistentDataContainer().set(
                armorTierKey, PersistentDataType.INTEGER, Math.max(0, Math.min(3, tier)));
        applyStats(entity, true);
    }

    String armorName(int tier) {
        return switch (tier) {
            case 1 -> "Iron";
            case 2 -> "Diamond";
            case 3 -> "Netherite";
            default -> "None";
        };
    }

    void markDamaged(Entity entity) {
        if (isSupported(entity)) {
            entity.getPersistentDataContainer().set(
                    lastDamageKey, PersistentDataType.LONG, System.currentTimeMillis());
        }
    }

    void regenerateLoaded() {
        long now = System.currentTimeMillis();
        long delay = Math.max(0L,
                plugin.getConfig().getLong("regeneration.out-of-combat-seconds", 10L) * 1000L);
        double fraction = Math.max(0.0,
                plugin.getConfig().getDouble("regeneration.max-health-fraction", 0.04));
        double minimum = Math.max(0.0,
                plugin.getConfig().getDouble("regeneration.minimum-heal", 1.0));

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!isSupported(entity) || entity.isDead()) {
                    continue;
                }
                initialize(entity);
                long lastDamage = entity.getPersistentDataContainer().getOrDefault(
                        lastDamageKey, PersistentDataType.LONG, 0L);
                if (now - lastDamage < delay) {
                    continue;
                }
                AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth == null || entity.getHealth() >= maxHealth.getValue()) {
                    continue;
                }
                double heal = Math.max(minimum, maxHealth.getValue() * fraction);
                entity.setHealth(Math.min(maxHealth.getValue(), entity.getHealth() + heal));
            }
        }
    }

    String displayName(Entity entity) {
        String customName = entity.getCustomName();
        if (customName != null && !customName.isBlank()) {
            return ChatColor.stripColor(customName);
        }
        String raw = entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(raw.length());
        boolean capitalize = true;
        for (char character : raw.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    void sendInfo(Player player, LivingEntity entity) {
        if (!isSupported(entity) || !isOwner(player, entity)) {
            player.sendMessage(ChatColor.RED + "You can only inspect your own companion.");
            return;
        }
        int currentLevel = level(entity);
        AttributeInstance maximumHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        double maximum = maximumHealth == null ? entity.getHealth() : maximumHealth.getValue();
        player.sendMessage(ChatColor.GOLD + displayName(entity)
                + ChatColor.GRAY + " | Level " + currentLevel + "/" + maxLevel());
        if (currentLevel < maxLevel()) {
            player.sendMessage(ChatColor.GRAY + "XP: " + xp(entity)
                    + "/" + xpToNext(currentLevel));
        }
        player.sendMessage(ChatColor.GRAY + "Health: " + format(entity.getHealth())
                + "/" + format(maximum) + " | Armor: " + armorName(armorTier(entity)));
    }

    private void applyStats(LivingEntity entity, boolean preserveHealthGain) {
        double oldMaximum = maximumHealth(entity);
        int level = level(entity);
        int tier = armorTier(entity);
        double healthPerLevel = plugin.getConfig().getDouble("stats.health-per-level", 0.04);
        double attackPerLevel = plugin.getConfig().getDouble("stats.attack-per-level", 0.03);

        updateModifier(entity.getAttribute(Attribute.MAX_HEALTH), healthModifierKey,
                Math.max(0, level - 1) * healthPerLevel, AttributeModifier.Operation.ADD_SCALAR);
        updateModifier(entity.getAttribute(Attribute.ATTACK_DAMAGE), attackModifierKey,
                Math.max(0, level - 1) * attackPerLevel, AttributeModifier.Operation.ADD_SCALAR);
        updateModifier(entity.getAttribute(Attribute.ARMOR), armorModifierKey,
                armorValue(tier, "armor"), AttributeModifier.Operation.ADD_NUMBER);
        updateModifier(entity.getAttribute(Attribute.ARMOR_TOUGHNESS), toughnessModifierKey,
                armorValue(tier, "toughness"), AttributeModifier.Operation.ADD_NUMBER);
        updateModifier(entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE), knockbackModifierKey,
                armorValue(tier, "knockback-resistance"), AttributeModifier.Operation.ADD_NUMBER);

        double newMaximum = maximumHealth(entity);
        if (preserveHealthGain && newMaximum > oldMaximum) {
            entity.setHealth(Math.min(newMaximum, entity.getHealth() + newMaximum - oldMaximum));
        } else if (entity.getHealth() > newMaximum) {
            entity.setHealth(newMaximum);
        }
    }

    private double armorValue(int tier, String statistic) {
        if (tier <= 0) {
            return 0.0;
        }
        String name = switch (tier) {
            case 1 -> "iron";
            case 2 -> "diamond";
            default -> "netherite";
        };
        return plugin.getConfig().getDouble("armor." + name + "." + statistic, 0.0);
    }

    private double maximumHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? entity.getHealth() : attribute.getValue();
    }

    private double combatDamage(LivingEntity entity) {
        AttributeInstance attack = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null && attack.getValue() > 0.0) {
            return attack.getValue();
        }
        double base = Math.max(0.1,
                plugin.getConfig().getDouble("combat.fallback-base-damage", 2.0));
        double perLevel = Math.max(0.0,
                plugin.getConfig().getDouble("combat.fallback-damage-per-level", 0.15));
        return base + Math.max(0, level(entity) - 1) * perLevel;
    }

    private void clearCombatTarget(UUID companionId, Entity entity) {
        combatTargets.remove(companionId);
        lastAttacks.remove(companionId);
        if (entity instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
        }
    }

    private void updateModifier(
            AttributeInstance attribute,
            NamespacedKey key,
            double amount,
            AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(key);
        if (Math.abs(amount) > 0.000001) {
            attribute.addModifier(new AttributeModifier(key, amount, operation));
        }
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
