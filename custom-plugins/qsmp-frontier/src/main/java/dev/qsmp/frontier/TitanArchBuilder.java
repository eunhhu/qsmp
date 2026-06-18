package dev.qsmp.frontier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.inventory.ItemStack;

final class TitanArchBuilder {
    private TitanArchBuilder() {
    }

    static LandmarkBuild create(World world, String key, int centerX, int centerZ) {
        int baseY = surface(world, centerX, centerZ);
        Queue<BlockPlan> blocks = new ArrayDeque<>();
        List<LandmarkAction> actions = new ArrayList<>();
        terrace(blocks, world, centerX, baseY, centerZ);
        arch(blocks, centerX, baseY, centerZ);
        shrine(blocks, centerX, baseY, centerZ);
        addLoot(actions, world, centerX, baseY, centerZ);
        addGuardian(actions, world, centerX, baseY, centerZ);
        return new LandmarkBuild(
                key, "Titan Arch", world, centerX >> 4, centerZ >> 4,
                4, blocks, actions);
    }

    private static void terrace(Queue<BlockPlan> blocks, World world, int cx, int y, int cz) {
        for (int x = -36; x <= 36; x++) {
            for (int z = -28; z <= 28; z++) {
                double shape = (x * x) / 1296.0 + (z * z) / 784.0;
                if (shape > 1.0) {
                    continue;
                }
                int ground = surface(world, cx + x, cz + z);
                int target = y + (int) Math.round((1.0 - shape) * 4.0);
                for (int fill = ground; fill <= target; fill++) {
                    set(blocks, cx + x, fill, cz + z,
                            fill == target ? Material.MOSSY_STONE_BRICKS : Material.STONE);
                }
                for (int clear = target + 1; clear <= target + 5; clear++) {
                    set(blocks, cx + x, clear, cz + z, Material.AIR);
                }
            }
        }
    }

    private static void arch(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        for (int side : new int[] {-1, 1}) {
            pillar(blocks, cx + side * 24, y + 1, cz, 6, 38);
        }
        for (int x = -24; x <= 24; x++) {
            double t = Math.abs(x) / 24.0;
            int archY = y + 38 + (int) Math.round((1.0 - t * t) * 16.0);
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    set(blocks, cx + x, archY + dy, cz + dz,
                            Math.abs(dz) == 4 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS);
                }
            }
        }
        for (int x = -18; x <= 18; x += 6) {
            set(blocks, cx + x, y + 57, cz, Material.IRON_CHAIN);
            set(blocks, cx + x, y + 56, cz, Material.LANTERN);
        }
    }

    private static void pillar(Queue<BlockPlan> blocks, int cx, int y, int cz, int radius, int height) {
        for (int dy = 0; dy <= height; dy++) {
            int r = dy > height - 7 ? Math.max(3, radius - 2) : radius;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    boolean edge = Math.abs(x) == r || Math.abs(z) == r;
                    if (edge || dy % 9 == 0) {
                        set(blocks, cx + x, y + dy, cz + z,
                                dy % 9 == 0 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS);
                    }
                }
            }
        }
    }

    private static void shrine(Queue<BlockPlan> blocks, int cx, int y, int cz) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                if (Math.abs(x) == 8 || Math.abs(z) == 8 || Math.abs(x) <= 2 || Math.abs(z) <= 2) {
                    set(blocks, cx + x, y + 5, cz + z, Material.POLISHED_ANDESITE);
                }
            }
        }
        set(blocks, cx, y + 6, cz, Material.LODESTONE);
        set(blocks, cx, y + 7, cz, Material.LIGHTNING_ROD);
    }

    private static void addLoot(List<LandmarkAction> actions, World world, int cx, int y, int cz) {
        actions.add(() -> {
            Block block = world.getBlockAt(cx, y + 6, cz + 5);
            block.setType(Material.BARREL, false);
            if (block.getState() instanceof Barrel barrel) {
                barrel.getInventory().addItem(
                        new ItemStack(Material.DIAMOND, 6),
                        new ItemStack(Material.AMETHYST_SHARD, 16),
                        new ItemStack(Material.EXPERIENCE_BOTTLE, 20),
                        new ItemStack(Material.LODESTONE, 1));
                barrel.update(true, false);
            }
        });
    }

    private static void addGuardian(List<LandmarkAction> actions, World world, int cx, int y, int cz) {
        actions.add(() -> {
            IronGolem golem = (IronGolem) world.spawnEntity(
                    new Location(world, cx + 0.5, y + 7, cz + 0.5), EntityType.IRON_GOLEM);
            golem.setCustomName(ChatColor.GRAY + "Titan Arch Sentinel");
            golem.setPlayerCreated(false);
        });
    }

    private static int surface(World world, int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private static void set(Queue<BlockPlan> blocks, int x, int y, int z, Material material) {
        blocks.add(new BlockPlan(x, y, z, material));
    }
}
