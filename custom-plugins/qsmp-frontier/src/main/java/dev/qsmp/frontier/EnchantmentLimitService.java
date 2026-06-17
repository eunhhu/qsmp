package dev.qsmp.frontier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

final class EnchantmentLimitService implements Listener {
    private static final int RESULT_SLOT = 2;
    private static final int DEFAULT_MAXIMUM_REPAIR_COST = 999_999;
    private static final Set<String> DEFAULT_SINGLE_LEVEL_ENCHANTS = Set.of(
            "minecraft:mending",
            "minecraft:infinity",
            "minecraft:flame",
            "minecraft:silk_touch",
            "minecraft:aqua_affinity",
            "minecraft:channeling",
            "minecraft:multishot",
            "minecraft:binding_curse",
            "minecraft:vanishing_curse");

    private final QSMPFrontier plugin;
    private final Map<UUID, Long> cleanupNoticeAt = new LinkedHashMap<>();

    EnchantmentLimitService(QSMPFrontier plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!enabled()) {
            return;
        }
        unlock(event.getView());
        ItemStack left = event.getInventory().getFirstItem();
        if (empty(left)) {
            return;
        }
        ItemStack right = event.getInventory().getSecondItem();
        ItemStack base = event.getResult();
        if (empty(base)) {
            base = left.clone();
        } else {
            base = base.clone();
        }
        Map<Enchantment, Integer> merged = mergedEnchantments(left, right, base);
        boolean changed = applyEnchantments(base, merged);
        changed |= sanitize(base);
        if (changed || !merged.isEmpty()) {
            event.setResult(base);
            int cost = Math.max(event.getView().getRepairCost(), estimatedCost(merged));
            if (cost > 0) {
                event.getView().setRepairCost(Math.min(cost, maximumRepairCost()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!enabled() || event.getView().getType() != InventoryType.ANVIL) {
            return;
        }
        if (event.getView().getTopInventory() instanceof AnvilInventory
                && event.getView() instanceof AnvilView view) {
            unlock(view);
        }
        if (event.getRawSlot() == RESULT_SLOT) {
            sanitize(event.getCurrentItem());
        }
        if (event.getWhoClicked() instanceof Player player) {
            scheduleInventorySanitize(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!enabled()) {
            return;
        }
        Map<Enchantment, Integer> enchants = event.getEnchantsToAdd();
        for (Map.Entry<Enchantment, Integer> entry : new ArrayList<>(enchants.entrySet())) {
            entry.setValue(clampLevel(entry.getKey(), entry.getValue()));
        }
        sanitize(event.getItem());
        scheduleInventorySanitize(event.getEnchanter());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleInventorySanitize(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (sanitize(event.getItem().getItemStack())) {
                notifyCleanup(player);
            }
            scheduleInventorySanitize(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleInventorySanitize(player);
        }
    }

    boolean sanitizeInventory(Player player) {
        if (!enabled()) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        boolean storageChanged = false;
        for (ItemStack item : storage) {
            storageChanged |= sanitize(item);
        }
        if (storageChanged) {
            inventory.setStorageContents(storage);
        }
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (ItemStack item : armor) {
            armorChanged |= sanitize(item);
        }
        if (armorChanged) {
            inventory.setArmorContents(armor);
        }
        ItemStack offHand = inventory.getItemInOffHand();
        boolean offHandChanged = false;
        if (sanitize(offHand)) {
            inventory.setItemInOffHand(offHand);
            offHandChanged = true;
        }
        return storageChanged || armorChanged || offHandChanged;
    }

    void tickOnlinePlayers() {
        if (!enabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (sanitizeInventory(player)) {
                notifyCleanup(player);
            }
        }
        if (cleanupNoticeAt.size() > 128) {
            long cutoff = System.currentTimeMillis() - 5L * 60L * 1000L;
            cleanupNoticeAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    private void scheduleInventorySanitize(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && sanitizeInventory(player)) {
                notifyCleanup(player);
            }
        });
    }

    private void notifyCleanup(Player player) {
        long now = System.currentTimeMillis();
        long last = cleanupNoticeAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 3000L) {
            return;
        }
        cleanupNoticeAt.put(player.getUniqueId(), now);
        player.sendActionBar(ChatColor.AQUA
                + "Enchantments synced to QSMP cap."
                + ChatColor.GRAY + " Lv.10 scalable, single-rank kept Lv.1");
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.22f, 1.45f);
    }

    private void unlock(AnvilView view) {
        int maximum = maximumRepairCost();
        view.bypassEnchantmentLevelRestriction(true);
        view.setDoUnsafeEnchants(true);
        view.setMaximumRepairCost(maximum);
    }

    private Map<Enchantment, Integer> mergedEnchantments(
            ItemStack left,
            ItemStack right,
            ItemStack target) {
        Map<Enchantment, Integer> leftEnchants = enchantments(left);
        Map<Enchantment, Integer> rightEnchants = enchantments(right);
        Map<Enchantment, Integer> merged = new LinkedHashMap<>();
        List<Enchantment> all = new ArrayList<>(leftEnchants.keySet());
        for (Enchantment enchantment : rightEnchants.keySet()) {
            if (!all.contains(enchantment)) {
                all.add(enchantment);
            }
        }
        for (Enchantment enchantment : all) {
            if (!mayApply(enchantment, target, leftEnchants)) {
                continue;
            }
            int maximum = maximumLevel(enchantment);
            int level = FrontierMath.mergedEnchantLevel(
                    leftEnchants.getOrDefault(enchantment, 0),
                    rightEnchants.getOrDefault(enchantment, 0),
                    maximum);
            if (level <= 0 || conflicts(enchantment, merged.keySet())) {
                continue;
            }
            merged.put(enchantment, level);
        }
        return merged;
    }

    private boolean applyEnchantments(ItemStack item, Map<Enchantment, Integer> enchants) {
        if (empty(item) || enchants.isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        boolean changed = false;
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
                int current = storage.getStoredEnchantLevel(entry.getKey());
                if (current != entry.getValue()) {
                    storage.addStoredEnchant(entry.getKey(), entry.getValue(), true);
                    changed = true;
                }
            }
        } else {
            for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
                int current = meta.getEnchantLevel(entry.getKey());
                if (current != entry.getValue()) {
                    meta.addEnchant(entry.getKey(), entry.getValue(), true);
                    changed = true;
                }
            }
        }
        if (changed) {
            item.setItemMeta(meta);
        }
        return changed;
    }

    private boolean sanitize(ItemStack item) {
        if (empty(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        boolean changed = normalizeDirectEnchants(meta);
        if (meta instanceof EnchantmentStorageMeta storage) {
            changed |= normalizeStoredEnchants(storage);
        }
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore != null) {
                List<String> cleaned = lore.stream()
                        .filter(line -> !bugEnchantLine(line))
                        .toList();
                if (cleaned.size() != lore.size()) {
                    meta.setLore(cleaned.isEmpty() ? null : cleaned);
                    changed = true;
                }
            }
        }
        if (changed) {
            item.setItemMeta(meta);
        }
        return changed;
    }

    private boolean normalizeDirectEnchants(ItemMeta meta) {
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> entry : new ArrayList<>(meta.getEnchants().entrySet())) {
            int normalized = clampLevel(entry.getKey(), entry.getValue());
            if (normalized != entry.getValue()) {
                meta.removeEnchant(entry.getKey());
                meta.addEnchant(entry.getKey(), normalized, true);
                changed = true;
            }
        }
        return changed;
    }

    private boolean normalizeStoredEnchants(EnchantmentStorageMeta meta) {
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> entry : new ArrayList<>(meta.getStoredEnchants().entrySet())) {
            int normalized = clampLevel(entry.getKey(), entry.getValue());
            if (normalized != entry.getValue()) {
                meta.removeStoredEnchant(entry.getKey());
                meta.addStoredEnchant(entry.getKey(), normalized, true);
                changed = true;
            }
        }
        return changed;
    }

    private Map<Enchantment, Integer> enchantments(ItemStack item) {
        if (empty(item)) {
            return Map.of();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchants();
        }
        return item.getEnchantments();
    }

    private boolean mayApply(
            Enchantment enchantment,
            ItemStack target,
            Map<Enchantment, Integer> leftEnchants) {
        if (target.getType() == Material.ENCHANTED_BOOK || leftEnchants.containsKey(enchantment)) {
            return true;
        }
        try {
            return enchantment.canEnchantItem(target);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean conflicts(Enchantment enchantment, Set<Enchantment> existing) {
        for (Enchantment other : existing) {
            if (!other.equals(enchantment)
                    && (other.conflictsWith(enchantment)
                            || enchantment.conflictsWith(other))) {
                return true;
            }
        }
        return false;
    }

    private int estimatedCost(Map<Enchantment, Integer> enchants) {
        int cost = 0;
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            cost += Math.max(1, entry.getKey().getAnvilCost())
                    * Math.max(1, entry.getValue());
        }
        return Math.max(1, cost);
    }

    private int clampLevel(Enchantment enchantment, int level) {
        return Math.max(1, Math.min(level, maximumLevel(enchantment)));
    }

    private int maximumLevel(Enchantment enchantment) {
        if (singleLevelEnchants().contains(key(enchantment))) {
            return 1;
        }
        return Math.max(1, plugin.getConfig().getInt("enchanting.max-level", 10));
    }

    private int maximumRepairCost() {
        return Math.max(40, plugin.getConfig()
                .getInt("enchanting.anvil.maximum-repair-cost", DEFAULT_MAXIMUM_REPAIR_COST));
    }

    private Set<String> singleLevelEnchants() {
        List<String> configured = plugin.getConfig().getStringList("enchanting.single-level");
        if (configured.isEmpty()) {
            return DEFAULT_SINGLE_LEVEL_ENCHANTS;
        }
        Set<String> keys = new HashSet<>();
        for (String value : configured) {
            keys.add(value.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private String key(Enchantment enchantment) {
        NamespacedKey key = enchantment.getKey();
        return key.namespace() + ":" + key.value();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("enchanting.enabled", true);
    }

    private boolean bugEnchantLine(String line) {
        String stripped = ChatColor.stripColor(line);
        if (stripped == null) {
            return false;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        return lower.contains("bug enchant")
                || lower.contains("bugged enchant")
                || lower.contains("illegal enchant")
                || lower.contains("invalid enchant")
                || ((stripped.contains("버그") || stripped.contains("오류"))
                        && (stripped.contains("인첸") || stripped.contains("인챈")));
    }

    private boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
