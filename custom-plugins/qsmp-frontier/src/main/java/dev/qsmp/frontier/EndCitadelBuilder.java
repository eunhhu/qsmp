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
import org.bukkit.entity.Enderman;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.ItemStack;

final class EndCitadelBuilder {
    private EndCitadelBuilder() {
    }

    static LandmarkBuild create(QSMPFrontier plugin, World world, String key, int centerX, int centerZ) {
        int baseY = Math.max(72, Math.min(128,
                plugin.getConfig().getInt("landmarks.end-citadels.base-y", 88)));
        Queue<BlockPlan> blocks = new ArrayDeque<>();
        List<LandmarkAction> actions = new ArrayList<>();
        buildIsland(blocks, centerX, baseY, centerZ);
        buildCitadel(blocks, centerX, baseY + 1, centerZ);
        addLoot(actions, world, centerX, baseY, centerZ);
        addDefenders(actions, world, centerX, baseY, centerZ);
        return new LandmarkBuild(
                key, "Void Citadel", world, centerX >> 4, centerZ >> 4,
                5, blocks, actions);
    }

    private static void buildIsland(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        int radiusX = 58;
        int radiusZ = 44;
        for (int x = -radiusX; x <= radiusX; x++) {
            for (int z = -radiusZ; z <= radiusZ; z++) {
                double shape = (x * x) / (double) (radiusX * radiusX)
                        + (z * z) / (double) (radiusZ * radiusZ);
                if (shape > 1.0) {
                    continue;
                }
                int depth = 3 + (int) Math.round((1.0 - shape) * 13.0);
                for (int down = 0; down <= depth; down++) {
                    Material material = down == 0
                            ? Material.END_STONE_BRICKS
                            : (down > depth - 3 ? Material.OBSIDIAN : Material.END_STONE);
                    set(blocks, cx + x, y - down, cz + z, material);
                }
            }
        }
        for (int x = -46; x <= 46; x += 6) {
            set(blocks, cx + x, y + 1, cz - 31, Material.PURPUR_PILLAR);
            set(blocks, cx + x, y + 1, cz + 31, Material.PURPUR_PILLAR);
        }
    }

    private static void buildCitadel(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        buildTower(blocks, cx, y, cz, 7, 54, true);
        for (int[] offset : new int[][] {{32, 0}, {-32, 0}, {0, 32}, {0, -32}}) {
            buildTower(blocks, cx + offset[0], y, cz + offset[1], 5, 34, false);
            bridge(blocks, cx, y + 16, cz, cx + offset[0], cz + offset[1]);
        }
        ring(blocks, cx, y + 8, cz, 41);
        ring(blocks, cx, y + 31, cz, 18);
        for (int h = 55; h <= 74; h++) {
            int r = Math.max(1, 8 - (h - 55) / 3);
            square(blocks, cx, y + h, cz, r, Material.PURPUR_PILLAR);
        }
        set(blocks, cx, y + 75, cz, Material.END_ROD);
    }

    private static void buildTower(
            Queue<BlockPlan> blocks, int cx, int y, int cz, int radius, int height, boolean central) {
        for (int level = 0; level <= height; level++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    boolean edge = Math.abs(x) == radius || Math.abs(z) == radius;
                    boolean floor = level == 0 || level % 12 == 0;
                    if (!edge && !floor) {
                        continue;
                    }
                    Material material = central && level % 10 == 0
                            ? Material.END_STONE_BRICKS
                            : Material.PURPUR_BLOCK;
                    set(blocks, cx + x, y + level, cz + z, material);
                }
            }
            if (level % 12 == 6) {
                set(blocks, cx + radius, y + level, cz, Material.END_ROD);
                set(blocks, cx - radius, y + level, cz, Material.END_ROD);
                set(blocks, cx, y + level, cz + radius, Material.END_ROD);
                set(blocks, cx, y + level, cz - radius, Material.END_ROD);
            }
        }
        square(blocks, cx, y + height + 1, cz, radius + 1, Material.PURPUR_PILLAR);
    }

    private static void bridge(Queue<BlockPlan> blocks, int x1, int y, int z1, int x2, int z2) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int z = z1 + (z2 - z1) * i / steps;
            for (int w = -2; w <= 2; w++) {
                if (Math.abs(x2 - x1) > Math.abs(z2 - z1)) {
                    set(blocks, x, y, z + w, Material.END_STONE_BRICKS);
                    if (Math.abs(w) == 2) {
                        set(blocks, x, y + 1, z + w, Material.PURPUR_PILLAR);
                    }
                } else {
                    set(blocks, x + w, y, z, Material.END_STONE_BRICKS);
                    if (Math.abs(w) == 2) {
                        set(blocks, x + w, y + 1, z, Material.PURPUR_PILLAR);
                    }
                }
            }
        }
    }

    private static void ring(Queue<BlockPlan> blocks, int cx, int y, int cz, int radius) {
        for (int angle = 0; angle < 360; angle += 4) {
            double radians = Math.toRadians(angle);
            int x = cx + (int) Math.round(Math.cos(radians) * radius);
            int z = cz + (int) Math.round(Math.sin(radians) * radius);
            set(blocks, x, y, z, Material.PURPUR_PILLAR);
            set(blocks, x, y + 1, z, angle % 24 == 0 ? Material.END_ROD : Material.PURPUR_BLOCK);
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
        addBarrel(actions, world, cx, y + 18, cz, List.of(
                new ItemStack(Material.ELYTRA, 1),
                new ItemStack(Material.SHULKER_SHELL, 4),
                new ItemStack(Material.DIAMOND, 10),
                new ItemStack(Material.ENDER_PEARL, 16)));
        for (int[] offset : new int[][] {{32, 0}, {-32, 0}, {0, 32}, {0, -32}}) {
            addBarrel(actions, world, cx + offset[0], y + 13, cz + offset[1], List.of(
                    new ItemStack(Material.CHORUS_FRUIT, 16),
                    new ItemStack(Material.EXPERIENCE_BOTTLE, 24),
                    new ItemStack(Material.END_CRYSTAL, 1)));
        }
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
            for (int[] offset : new int[][] {{32, 0}, {-32, 0}, {0, 32}, {0, -32}, {0, 0}}) {
                Shulker shulker = (Shulker) world.spawnEntity(
                        new Location(world, cx + offset[0] + 0.5, y + 15, cz + offset[1] + 0.5),
                        EntityType.SHULKER);
                shulker.setCustomName(ChatColor.DARK_PURPLE + "Void Citadel Sentry");
                shulker.setCustomNameVisible(true);
            }
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0 * i / 8.0;
                Enderman enderman = (Enderman) world.spawnEntity(
                        new Location(world, cx + Math.cos(angle) * 22.0, y + 2, cz + Math.sin(angle) * 22.0),
                        EntityType.ENDERMAN);
                enderman.setCustomName(ChatColor.LIGHT_PURPLE + "Citadel Watcher");
            }
        });
    }

    private static void set(Queue<BlockPlan> blocks, int x, int y, int z, Material material) {
        blocks.add(new BlockPlan(x, y, z, material));
    }
}
