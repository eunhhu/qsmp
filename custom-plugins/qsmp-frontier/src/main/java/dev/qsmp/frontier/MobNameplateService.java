package dev.qsmp.frontier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

final class MobNameplateService {
    private final QSMPFrontier plugin;
    private final NamespacedKey baseNameKey;

    MobNameplateService(QSMPFrontier plugin) {
        this.plugin = plugin;
        this.baseNameKey = new NamespacedKey(plugin, "nameplate_base_name");
    }

    void refreshLoaded() {
        if (!enabled()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class)) {
                update(entity);
            }
        }
    }

    void updateLater(LivingEntity entity) {
        if (!enabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (entity.isValid() && !entity.isDead()) {
                update(entity);
            }
        });
    }

    void update(LivingEntity entity) {
        if (!enabled() || !eligible(entity)) {
            return;
        }
        AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        double maximum = health == null ? entity.getHealth() : health.getValue();
        if (maximum <= 0.0) {
            return;
        }
        String baseName = baseName(entity);
        int level = Math.max(1, (int) Math.round(maximum / levelHealthStep()));
        entity.setCustomName(ChatColor.DARK_RED + baseName
                + ChatColor.GRAY + " Lv." + level
                + ChatColor.RED + " " + hearts(entity.getHealth())
                + ChatColor.DARK_GRAY + "/" + hearts(maximum));
        entity.setCustomNameVisible(true);
    }

    private boolean eligible(LivingEntity entity) {
        return !(entity instanceof Player)
                && !(entity instanceof EnderDragon)
                && entity instanceof Monster;
    }

    private String baseName(LivingEntity entity) {
        String stored = entity.getPersistentDataContainer().get(
                baseNameKey, PersistentDataType.STRING);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String current = ChatColor.stripColor(entity.getCustomName());
        String base = current == null || current.isBlank()
                ? titleCase(entity.getType().name())
                : current;
        int marker = base.indexOf(" Lv.");
        if (marker >= 0) {
            base = base.substring(0, marker).trim();
        }
        entity.getPersistentDataContainer().set(baseNameKey, PersistentDataType.STRING, base);
        return base;
    }

    private String titleCase(String value) {
        String[] parts = value.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private int hearts(double health) {
        return Math.max(0, (int) Math.ceil(health / 2.0));
    }

    private double levelHealthStep() {
        return Math.max(1.0, plugin.getConfig().getDouble("mob-nameplates.level-health-step", 4.0));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("mob-nameplates.enabled", true);
    }
}
