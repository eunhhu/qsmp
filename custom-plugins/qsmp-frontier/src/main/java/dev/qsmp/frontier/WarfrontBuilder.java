package dev.qsmp.frontier;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

final class WarfrontBuilder {
    private static final int HALF_WIDTH = 40;
    private static final int HALF_DEPTH = 55;

    private final QSMPFrontier plugin;
    private BukkitTask buildTask;

    WarfrontBuilder(QSMPFrontier plugin) {
        this.plugin = plugin;
    }

    boolean isBuilding() {
        return buildTask != null;
    }

    Location selectSite(World world) {
        Location spawn = world.getSpawnLocation();
        int minimum = Math.max(400,
                plugin.getConfig().getInt("warfront.minimum-distance-from-spawn", 700));
        int maximum = Math.max(minimum + 100,
                plugin.getConfig().getInt("warfront.maximum-distance-from-spawn", 1100));
        int[] offset = FrontierMath.siteOffset(world.getSeed(), minimum, maximum);
        return new Location(
                world,
                spawn.getBlockX() + offset[0],
                world.getSeaLevel(),
                spawn.getBlockZ() + offset[1]);
    }

    boolean regionLoaded(Location center) {
        World world = center.getWorld();
        int minimumChunkX = (center.getBlockX() - HALF_WIDTH - 4) >> 4;
        int maximumChunkX = (center.getBlockX() + HALF_WIDTH + 4) >> 4;
        int minimumChunkZ = (center.getBlockZ() - HALF_DEPTH - 4) >> 4;
        int maximumChunkZ = (center.getBlockZ() + HALF_DEPTH + 4) >> 4;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    void build(CommandSender sender, Location requestedCenter, Consumer<Location> completed) {
        if (isBuilding()) {
            sender.sendMessage(ChatColor.RED + "A warfront is already being built.");
            return;
        }
        World world = requestedCenter.getWorld();
        int centerX = requestedCenter.getBlockX();
        int centerZ = requestedCenter.getBlockZ();
        Location center = new Location(
                world, centerX, surfaceY(world, centerX, centerZ), centerZ);
        Queue<BlockPlan> plans = createPlans(center);
        int total = plans.size();
        int perTick = Math.max(
                500, plugin.getConfig().getInt("warfront.build-blocks-per-tick", 3000));
        sender.sendMessage(ChatColor.GOLD + "Natural warfront construction started: "
                + total + " block operations.");

        buildTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int placed = 0;
            while (placed < perTick && !plans.isEmpty()) {
                BlockPlan plan = plans.remove();
                world.getBlockAt(plan.x(), plan.y(), plan.z()).setType(plan.material(), false);
                placed++;
            }
            if (!plans.isEmpty()) {
                if (plans.size() % 12000 < perTick) {
                    int percent = (int) Math.round(100.0 * (total - plans.size()) / total);
                    if (sender instanceof Player player) {
                        player.sendActionBar(
                                ChatColor.YELLOW + "Building warfront: " + percent + "%");
                    }
                }
                return;
            }
            buildTask.cancel();
            buildTask = null;
            Bukkit.broadcastMessage(ChatColor.GOLD
                    + "An old battlefield has emerged somewhere beyond the frontier.");
            completed.accept(center);
        }, 1L, 1L);
    }

    private Queue<BlockPlan> createPlans(Location center) {
        Queue<BlockPlan> plans = new ArrayDeque<>();
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -HALF_DEPTH; z <= HALF_DEPTH; z++) {
                boolean lane = nearLane(x);
                boolean crossRoad = Math.abs(z + 34) <= 2 || Math.abs(z - 35) <= 2;
                if (!lane && !crossRoad) {
                    continue;
                }
                int y = surfaceY(world, cx + x, cz + z);
                Material surface = Math.floorMod(x * 3 + z, 9) == 0
                        ? Material.MUD
                        : Material.COARSE_DIRT;
                add(plans, cx + x, y, cz + z, surface);
                clearHeadroom(plans, world, cx + x, y, cz + z, 3);
            }
        }

        buildFortress(plans, world, cx, cz);
        buildAlliedCamp(plans, world, cx, cz);
        buildBattlefieldCover(plans, world, cx, cz);
        buildSignalMonuments(plans, world, cx, cz);
        return plans;
    }

    private void buildFortress(Queue<BlockPlan> plans, World world, int cx, int cz) {
        int wallZ = cz + 45;
        for (int x = -36; x <= 36; x++) {
            int ground = surfaceY(world, cx + x, wallZ);
            clearHeadroom(plans, world, cx + x, ground, wallZ, 12);
            for (int height = 1; height <= 11; height++) {
                boolean gate = Math.abs(x) <= 4 && height <= 7;
                Material material = gate
                        ? Material.AIR
                        : (height == 11 && Math.floorMod(x, 4) < 2
                                ? Material.POLISHED_BLACKSTONE_BRICKS
                                : Material.DEEPSLATE_BRICKS);
                add(plans, cx + x, ground + height, wallZ, material);
                if (!gate && height <= 3) {
                    add(plans, cx + x, ground + height, wallZ + 1, Material.DEEPSLATE_TILES);
                }
            }
        }

        for (int towerX : new int[] {-34, -14, 14, 34}) {
            buildTower(plans, world, cx + towerX, wallZ);
        }
        int gateGround = surfaceY(world, cx, wallZ);
        add(plans, cx, gateGround + 8, wallZ, Material.SOUL_LANTERN);
    }

    private void buildTower(Queue<BlockPlan> plans, World world, int x, int z) {
        int ground = surfaceY(world, x, z);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                for (int height = 1; height <= 14; height++) {
                    if (edge || height == 1 || height == 12) {
                        add(plans, x + dx, ground + height, z + dz, Material.DEEPSLATE_BRICKS);
                    }
                }
                if (edge && Math.floorMod(dx + dz, 2) == 0) {
                    add(plans, x + dx, ground + 15, z + dz, Material.DEEPSLATE_TILE_WALL);
                }
            }
        }
        add(plans, x, ground + 13, z, Material.SOUL_CAMPFIRE);
    }

    private void buildAlliedCamp(Queue<BlockPlan> plans, World world, int cx, int cz) {
        int campZ = cz - 45;
        for (int x = -34; x <= 34; x++) {
            if (Math.abs(x) <= 5) {
                continue;
            }
            int ground = surfaceY(world, cx + x, campZ);
            for (int height = 1; height <= 4; height++) {
                add(plans, cx + x, ground + height, campZ, Material.STRIPPED_SPRUCE_LOG);
            }
            if (Math.floorMod(x, 3) == 0) {
                add(plans, cx + x, ground + 5, campZ, Material.SPRUCE_FENCE);
            }
        }
        buildTent(plans, world, cx - 22, campZ + 7, Material.RED_WOOL);
        buildTent(plans, world, cx + 22, campZ + 7, Material.BLUE_WOOL);
        buildTent(plans, world, cx, campZ + 3, Material.WHITE_WOOL);
        placeOnSurface(plans, world, cx - 7, campZ + 9, Material.BARREL);
        placeOnSurface(plans, world, cx + 7, campZ + 9, Material.BARREL);
        placeOnSurface(plans, world, cx, campZ + 12, Material.CAMPFIRE);
    }

    private void buildTent(
            Queue<BlockPlan> plans, World world, int cx, int cz, Material wool) {
        int ground = surfaceY(world, cx, cz);
        for (int z = -4; z <= 4; z++) {
            for (int x = -4; x <= 4; x++) {
                int height = 5 - Math.abs(x);
                if (height >= 1) {
                    add(plans, cx + x, ground + height, cz + z, wool);
                }
            }
        }
        add(plans, cx, ground + 1, cz - 4, Material.SPRUCE_FENCE);
        add(plans, cx, ground + 2, cz - 4, Material.LANTERN);
        add(plans, cx, ground + 1, cz + 4, Material.SPRUCE_FENCE);
        add(plans, cx, ground + 2, cz + 4, Material.LANTERN);
    }

    private void buildBattlefieldCover(
            Queue<BlockPlan> plans, World world, int cx, int cz) {
        for (int lane : new int[] {-25, 0, 25}) {
            for (int z : new int[] {-20, 2, 22}) {
                for (int x = -4; x <= 4; x++) {
                    int worldX = cx + lane + x;
                    int worldZ = cz + z;
                    int ground = surfaceY(world, worldX, worldZ);
                    add(plans, worldX, ground + 1, worldZ, Material.COBBLED_DEEPSLATE_WALL);
                    if (Math.abs(x) == 4) {
                        add(plans, worldX, ground + 2, worldZ, Material.IRON_BARS);
                    }
                }
            }
            placeOnSurface(plans, world, cx + lane, cz + 32, Material.SOUL_CAMPFIRE);
        }
    }

    private void buildSignalMonuments(Queue<BlockPlan> plans, World world, int cx, int cz) {
        for (int angle = 0; angle < 360; angle += 10) {
            double radians = Math.toRadians(angle);
            int x = cx + (int) Math.round(Math.cos(radians) * 9.0);
            int z = cz + (int) Math.round(Math.sin(radians) * 9.0);
            Material material = angle % 40 == 0
                    ? Material.CRYING_OBSIDIAN
                    : angle % 20 == 0 ? Material.CHISELED_DEEPSLATE : Material.POLISHED_BLACKSTONE;
            placeOnSurface(plans, world, x, z, material);
        }
        for (int[] marker : new int[][] {{0, 9}, {0, -9}, {9, 0}, {-9, 0}}) {
            int x = cx + marker[0];
            int z = cz + marker[1];
            int ground = surfaceY(world, x, z);
            add(plans, x, ground + 2, z, Material.SOUL_LANTERN);
        }
        for (int lane : new int[] {-25, 0, 25}) {
            buildLaneStandard(plans, world, cx + lane, cz - 32, Material.BLUE_BANNER);
            buildLaneStandard(plans, world, cx + lane, cz + 28, Material.RED_BANNER);
            for (int z = -30; z <= 30; z += 12) {
                int ground = surfaceY(world, cx + lane, cz + z);
                add(plans, cx + lane - 4, ground + 1, cz + z, Material.IRON_BARS);
                add(plans, cx + lane + 4, ground + 1, cz + z, Material.IRON_BARS);
            }
        }
        int gateZ = cz + 45;
        for (int x : new int[] {-7, 7}) {
            int ground = surfaceY(world, cx + x, gateZ - 2);
            for (int y = 1; y <= 5; y++) {
                add(plans, cx + x, ground + y, gateZ - 2,
                        y == 5 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE_BRICKS);
            }
            add(plans, cx + x, ground + 6, gateZ - 2, Material.SOUL_LANTERN);
        }
    }

    private void buildLaneStandard(
            Queue<BlockPlan> plans,
            World world,
            int x,
            int z,
            Material banner) {
        int ground = surfaceY(world, x, z);
        add(plans, x, ground + 1, z, Material.DEEPSLATE_TILE_WALL);
        add(plans, x, ground + 2, z, Material.DEEPSLATE_TILE_WALL);
        add(plans, x, ground + 3, z, banner);
    }

    private int surfaceY(World world, int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private void clearHeadroom(
            Queue<BlockPlan> plans, World world, int x, int ground, int z, int height) {
        int maximum = Math.min(world.getMaxHeight() - 1, ground + height);
        for (int y = ground + 1; y <= maximum; y++) {
            add(plans, x, y, z, Material.AIR);
        }
    }

    private void placeOnSurface(
            Queue<BlockPlan> plans, World world, int x, int z, Material material) {
        add(plans, x, surfaceY(world, x, z) + 1, z, material);
    }

    private boolean nearLane(int x) {
        return Math.abs(x) <= 3 || Math.abs(x - 25) <= 3 || Math.abs(x + 25) <= 3;
    }

    private void add(Queue<BlockPlan> plans, int x, int y, int z, Material material) {
        plans.add(new BlockPlan(x, y, z, material));
    }

    private record BlockPlan(int x, int y, int z, Material material) {
    }
}
