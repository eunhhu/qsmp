package dev.qsmp.frontier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.ItemStack;

final class NetherForgeBuilder {
    private NetherForgeBuilder() {
    }

    static LandmarkBuild create(QSMPFrontier plugin, World world, String key, int centerX, int centerZ) {
        int baseY = Math.max(36, Math.min(96,
                plugin.getConfig().getInt("landmarks.nether-forges.base-y", 64)));
        Queue<BlockPlan> blocks = new ArrayDeque<>();
        List<LandmarkAction> actions = new ArrayList<>();
        carveChamber(blocks, centerX, baseY, centerZ);
        buildForge(blocks, centerX, baseY, centerZ);
        addLoot(actions, world, centerX, baseY, centerZ);
        addDefenders(actions, world, centerX, baseY, centerZ);
        return new LandmarkBuild(
                key, "Infernal Forge", world, centerX >> 4, centerZ >> 4,
                4, blocks, actions);
    }

    private static void carveChamber(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        for (int x = -34; x <= 34; x++) {
            for (int z = -34; z <= 34; z++) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance > 34.0) {
                    continue;
                }
                int height = 18 + (int) Math.round((34.0 - distance) * 0.35);
                for (int dy = -4; dy <= height; dy++) {
                    if (dy == -4) {
                        set(blocks, cx + x, y + dy, cz + z,
                                distance > 28.0 ? Material.BLACKSTONE : Material.BASALT);
                    } else {
                        set(blocks, cx + x, y + dy, cz + z, Material.AIR);
                    }
                }
            }
        }
    }

    private static void buildForge(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        buildCore(blocks, cx, y, cz);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0 * i / 8.0;
            int x = cx + (int) Math.round(Math.cos(angle) * 24.0);
            int z = cz + (int) Math.round(Math.sin(angle) * 24.0);
            rib(blocks, x, y - 3, z, i % 2 == 0 ? 28 : 20);
            bridge(blocks, cx, y + 2, cz, x, z);
        }
        ring(blocks, cx, y + 4, cz, 18, Material.POLISHED_BLACKSTONE_BRICKS);
        ring(blocks, cx, y + 13, cz, 30, Material.BASALT);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 2.0 * i / 4.0 + Math.PI / 4.0;
            int x = cx + (int) Math.round(Math.cos(angle) * 14.0);
            int z = cz + (int) Math.round(Math.sin(angle) * 14.0);
            pillar(blocks, x, y - 3, z, 22, Material.NETHER_BRICKS);
        }
    }

    private static void buildCore(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        for (int dy = -3; dy <= 28; dy++) {
            int radius = dy < 0 ? 9 : Math.max(3, 9 - dy / 5);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) {
                        Material material = Math.abs(x) <= 1 && Math.abs(z) <= 1 && dy <= 18
                                ? Material.LAVA
                                : (dy % 5 == 0 ? Material.MAGMA_BLOCK : Material.BLACKSTONE);
                        set(blocks, cx + x, y + dy, cz + z, material);
                    }
                }
            }
        }
    }

    private static void rib(Queue<BlockPlan> blocks, int x, int y, int z, int height) {
        for (int dy = 0; dy <= height; dy++) {
            int radius = Math.max(1, 4 - dy / 8);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= radius + 1) {
                        set(blocks, x + dx, y + dy, z + dz,
                                dy % 6 == 0 ? Material.POLISHED_BASALT : Material.BASALT);
                    }
                }
            }
        }
        set(blocks, x, y + height + 1, z, Material.SOUL_LANTERN);
    }

    private static void pillar(Queue<BlockPlan> blocks, int x, int y, int z, int height, Material material) {
        for (int dy = 0; dy <= height; dy++) {
            square(blocks, x, y + dy, z, 2, dy % 7 == 0 ? Material.RED_NETHER_BRICKS : material);
        }
    }

    private static void bridge(Queue<BlockPlan> blocks, int x1, int y, int z1, int x2, int z2) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int z = z1 + (z2 - z1) * i / steps;
            for (int w = -2; w <= 2; w++) {
                if (Math.abs(x2 - x1) > Math.abs(z2 - z1)) {
                    set(blocks, x, y, z + w, Material.POLISHED_BLACKSTONE_BRICKS);
                    if (Math.abs(w) == 2) {
                        set(blocks, x, y + 1, z + w, Material.NETHER_BRICK_FENCE);
                    }
                } else {
                    set(blocks, x + w, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    if (Math.abs(w) == 2) {
                        set(blocks, x + w, y + 1, z, Material.NETHER_BRICK_FENCE);
                    }
                }
            }
        }
    }

    private static void ring(Queue<BlockPlan> blocks, int cx, int y, int cz, int radius, Material material) {
        for (int angle = 0; angle < 360; angle += 5) {
            double radians = Math.toRadians(angle);
            int x = cx + (int) Math.round(Math.cos(radians) * radius);
            int z = cz + (int) Math.round(Math.sin(radians) * radius);
            set(blocks, x, y, z, material);
            if (angle % 20 == 0) {
                set(blocks, x, y + 1, z, Material.IRON_CHAIN);
            }
        }
    }

    private static void square(Queue<BlockPlan> blocks, int cx, int y, int cz, int radius, Material material) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (Math.abs(x) == radius || Math.abs(z) == radius) {
                    set(blocks, cx + x, y, cz + z, material);
                }
            }
        }
    }

    private static void addLoot(List<LandmarkAction> actions, World world, int cx, int y, int cz) {
        addBarrel(actions, world, cx, y + 6, cz + 10, List.of(
                new ItemStack(Material.NETHERITE_SCRAP, 3),
                new ItemStack(Material.GILDED_BLACKSTONE, 12),
                new ItemStack(Material.BLAZE_ROD, 8),
                new ItemStack(Material.GOLD_BLOCK, 2)));
        addBarrel(actions, world, cx - 18, y + 3, cz - 18, List.of(
                new ItemStack(Material.ANCIENT_DEBRIS, 2),
                new ItemStack(Material.EXPERIENCE_BOTTLE, 24)));
    }

    private static void addBarrel(
            List<LandmarkAction> actions, World world, int x, int y, int z, List<ItemStack> loot) {
        actions.add(() -> {
            Block block = world.getBlockAt(x, y, z);
            block.setType(Material.BARREL, false);
            if (block.getState() instanceof Barrel barrel) {
                for (ItemStack item : loot) {
                    barrel.getInventory().addItem(item);
                }
                barrel.update(true, false);
            }
        });
    }

    private static void addDefenders(List<LandmarkAction> actions, World world, int cx, int y, int cz) {
        actions.add(() -> {
            for (int i = 0; i < 6; i++) {
                double angle = Math.PI * 2.0 * i / 6.0;
                WitherSkeleton skeleton = (WitherSkeleton) world.spawnEntity(
                        new Location(world, cx + Math.cos(angle) * 20.0, y + 3, cz + Math.sin(angle) * 20.0),
                        EntityType.WITHER_SKELETON);
                skeleton.setCustomName(ChatColor.DARK_RED + "Forge-Bound Warden");
            }
            for (int i = 0; i < 4; i++) {
                Blaze blaze = (Blaze) world.spawnEntity(
                        new Location(world, cx + 0.5, y + 11 + i, cz + 0.5),
                        EntityType.BLAZE);
                blaze.setCustomName(ChatColor.GOLD + "Infernal Forge Spark");
            }
        });
    }

    private static void set(Queue<BlockPlan> blocks, int x, int y, int z, Material material) {
        blocks.add(new BlockPlan(x, y, z, material));
    }
}
