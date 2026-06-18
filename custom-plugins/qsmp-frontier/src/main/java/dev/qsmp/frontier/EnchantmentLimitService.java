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
import org.bukkit.inventory.meta.Repairable;

final class EnchantmentLimitService implements Listener {
    private static final int RESULT_SLOT = 2;
    private static final int DEFAULT_MAXIMUM_REPAIR_COST = 38;
    private static final int DEFAULT_SIDE_MAX_LEVEL = 5;
    private static final Set<String> DEFAULT_HIGH_CAP_ENCHANTS = Set.of(
            "minecraft:protection",
            "minecraft:fire_protection",
            "minecraft:blast_protection",
            "minecraft:projectile_protection",
            "minecraft:sharpness",
            "minecraft:smite",
            "minecraft:bane_of_arthropods",
            "minecraft:efficiency",
            "minecraft:unbreaking",
            "minecraft:power");
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

    private record SanitizeResult(boolean itemChanged, boolean capChanged) {
    }

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
        ItemStack eventResult = event.getResult();
        if (empty(eventResult) && empty(right)) {
            return;
        }
        ItemStack base;
        if (empty(eventResult)) {
            base = left.clone();
        } else {
            base = eventResult.clone();
        }
        Map<Enchantment, Integer> merged = mergedEnchantments(left, right, base);
        boolean changed = applyEnchantments(base, merged);
        changed |= sanitize(base).capChanged();
        changed |= resetRepairPenalty(base);
        boolean vanillaOperation = !empty(eventResult) && event.getView().getRepairCost() > 0;
        boolean hasOperation = FrontierMath.hasAnvilOperation(changed, vanillaOperation);
        if (hasOperation) {
            event.setResult(base);
            int cost = FrontierMath.boundedAnvilCost(
                    event.getView().getRepairCost(),
                    changed ? estimatedCost(merged) : 0,
                    maximumRepairCost(),
                    true);
            event.getView().setRepairCost(cost);
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
            if (sanitize(event.getItem().getItemStack()).capChanged()) {
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
        boolean capChanged = false;
        for (ItemStack item : storage) {
            SanitizeResult sanitized = sanitize(item);
            storageChanged |= sanitized.itemChanged();
            capChanged |= sanitized.capChanged();
        }
        if (storageChanged) {
            inventory.setStorageContents(storage);
        }
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (ItemStack item : armor) {
            SanitizeResult sanitized = sanitize(item);
            armorChanged |= sanitized.itemChanged();
            capChanged |= sanitized.capChanged();
        }
        if (armorChanged) {
            inventory.setArmorContents(armor);
        }
        ItemStack offHand = inventory.getItemInOffHand();
        SanitizeResult offHandSanitized = sanitize(offHand);
        if (offHandSanitized.itemChanged()) {
            inventory.setItemInOffHand(offHand);
        }
        capChanged |= offHandSanitized.capChanged();
        return capChanged;
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
        player.sendActionBar(ChatColor.AQUA + "Enchant caps normalized."
                + ChatColor.GRAY + " Main Lv.10, utility Lv.5, single Lv.1");
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

    private SanitizeResult sanitize(ItemStack item) {
        if (empty(item)) {
            return new SanitizeResult(false, false);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new SanitizeResult(false, false);
        }
        boolean capChanged = normalizeDirectEnchants(meta);
        if (meta instanceof EnchantmentStorageMeta storage) {
            capChanged |= normalizeStoredEnchants(storage);
        }
        boolean loreChanged = cleanupGeneratedLore(meta);
        if (capChanged || loreChanged) {
            item.setItemMeta(meta);
        }
        return new SanitizeResult(capChanged || loreChanged, capChanged);
    }

    private boolean resetRepairPenalty(ItemStack item) {
        if (empty(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Repairable repairable) || !repairable.hasRepairCost()) {
            return false;
        }
        if (repairable.getRepairCost() == FrontierMath.anvilOutputRepairPenalty()) {
            return false;
        }
        repairable.setRepairCost(FrontierMath.anvilOutputRepairPenalty());
        item.setItemMeta(repairable);
        return true;
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
        String enchantKey = key(enchantment);
        return FrontierMath.enchantCap(
                enchantment.getMaxLevel(),
                singleLevelEnchants().contains(enchantKey),
                highCapEnchants().contains(enchantKey),
                plugin.getConfig().getInt("enchanting.max-level", 10),
                sideMaximumLevel());
    }

    private int maximumRepairCost() {
        return FrontierMath.anvilMaximumRepairCost(plugin.getConfig()
                .getInt("enchanting.anvil.maximum-repair-cost", DEFAULT_MAXIMUM_REPAIR_COST));
    }

    private int sideMaximumLevel() {
        return Math.max(1, plugin.getConfig().getInt("enchanting.side-max-level", DEFAULT_SIDE_MAX_LEVEL));
    }

    private Set<String> highCapEnchants() {
        List<String> configured = plugin.getConfig().getStringList("enchanting.high-cap");
        if (configured.isEmpty()) {
            return DEFAULT_HIGH_CAP_ENCHANTS;
        }
        return configuredKeys(configured);
    }

    private Set<String> singleLevelEnchants() {
        List<String> configured = plugin.getConfig().getStringList("enchanting.single-level");
        if (configured.isEmpty()) {
            return DEFAULT_SINGLE_LEVEL_ENCHANTS;
        }
        return configuredKeys(configured);
    }

    private Set<String> configuredKeys(List<String> configured) {
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

    private boolean cleanupGeneratedLore(ItemMeta meta) {
        if (!meta.hasLore()) {
            return false;
        }
        List<String> lore = meta.getLore();
        if (lore == null) {
            return false;
        }
        List<String> cleaned = lore.stream()
                .filter(line -> !bugEnchantLine(line) && !qsmpEnchantLine(line))
                .toList();
        if (cleaned.size() == lore.size()) {
            return false;
        }
        meta.setLore(cleaned.isEmpty() ? null : cleaned);
        return true;
    }

    private boolean qsmpEnchantLine(String line) {
        String stripped = ChatColor.stripColor(line);
        return stripped != null
                && (stripped.startsWith("QSMP enchant:")
                        || stripped.startsWith("QSMP detail:"));
    }

    private boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
