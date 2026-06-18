package dev.qsmp.frontier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;

final class LandmarkService implements Listener {
    private final QSMPFrontier plugin;
    private final File storageFile;
    private final Set<String> built = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    private final Set<String> preparing = new HashSet<>();
    private final Queue<LandmarkBuild> queue = new ArrayDeque<>();
    private BukkitTask buildTask;

    LandmarkService(QSMPFrontier plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "landmarks.yml");
    }

    void load() {
        built.clear();
        pending.clear();
        preparing.clear();
        if (!storageFile.isFile()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(storageFile);
        built.addAll(data.getStringList("built"));
        pending.addAll(data.getStringList("pending"));
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("built", built.stream().sorted().toList());
        data.set("pending", pending.stream().sorted().toList());
        try {
            data.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save landmark state: " + exception.getMessage());
        }
    }

    void shutdown() {
        if (buildTask != null) {
            buildTask.cancel();
            buildTask = null;
        }
        for (LandmarkBuild build : queue) {
            setTickets(build, false);
        }
        queue.clear();
        preparing.clear();
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled()) {
            return;
        }
        Chunk chunk = event.getChunk();
        LandmarkKind kind = kind(chunk.getWorld());
        if (kind == null || !isTargetChunk(kind, chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            return;
        }
        String key = key(kind, chunk.getWorld(), chunk.getX(), chunk.getZ());
        if (built.contains(key)) {
            return;
        }
        if (!event.isNewChunk() && !pending.contains(key)) {
            return;
        }
        if (!preparing.add(key)) {
            return;
        }
        prepareChunks(kind, chunk.getWorld(), chunk.getX(), chunk.getZ(), key);
    }

    private void prepareChunks(LandmarkKind kind, World world, int chunkX, int chunkZ, String key) {
        int radius = ticketRadius(kind);
        world.getChunksAtAsync(
                chunkX - radius, chunkZ - radius,
                chunkX + radius, chunkZ + radius,
                true,
                () -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin,
                            () -> enqueuePrepared(kind, world, chunkX, chunkZ, key));
                });
    }

    private void enqueuePrepared(LandmarkKind kind, World world, int chunkX, int chunkZ, String key) {
        if (!enabled() || built.contains(key)) {
            preparing.remove(key);
            return;
        }
        int radius = ticketRadius(kind);
        if (!chunksLoaded(world, chunkX, chunkZ, radius)) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> prepareChunks(kind, world, chunkX, chunkZ, key), 20L);
            return;
        }
        LandmarkBuild build = create(kind, world, chunkX, chunkZ, key);
        if (!setTickets(build, true)) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> prepareChunks(kind, world, chunkX, chunkZ, key), 20L);
            return;
        }
        pending.add(key);
        save();
        queue.add(build);
        if (buildTask == null) {
            buildTask = Bukkit.getScheduler().runTaskTimer(plugin, this::buildTick, 1L, 1L);
        }
    }

    private LandmarkBuild create(LandmarkKind kind, World world, int chunkX, int chunkZ, String key) {
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        return switch (kind) {
            case END_CITADEL -> EndCitadelBuilder.create(plugin, world, key, centerX, centerZ);
            case NETHER_FORGE -> NetherForgeBuilder.create(plugin, world, key, centerX, centerZ);
            case TITAN_ARCH -> TitanArchBuilder.create(world, key, centerX, centerZ);
        };
    }

    private void buildTick() {
        LandmarkBuild build = queue.peek();
        try {
            if (build == null) {
                buildTask.cancel();
                buildTask = null;
                return;
            }
            int perTick = Math.max(500, plugin.getConfig().getInt("landmarks.blocks-per-tick", 2500));
            for (int placed = 0; placed < perTick && !build.blocks().isEmpty(); placed++) {
                BlockPlan plan = build.blocks().remove();
                build.world().getBlockAt(plan.x(), plan.y(), plan.z()).setType(plan.material(), false);
            }
            if (!build.blocks().isEmpty()) {
                return;
            }
            for (LandmarkAction action : build.actions()) {
                action.run();
            }
            queue.remove();
            setTickets(build, false);
            preparing.remove(build.key());
            pending.remove(build.key());
            built.add(build.key());
            save();
            plugin.getLogger().info("Generated automatic landmark: "
                    + build.name() + " at " + build.world().getName()
                    + " chunk " + build.centerChunkX() + "," + build.centerChunkZ());
        } catch (RuntimeException exception) {
            if (build != null) {
                queue.remove();
                setTickets(build, false);
                preparing.remove(build.key());
                save();
            }
            plugin.getLogger().severe("Automatic landmark generation failed: "
                    + exception.getMessage());
        }
    }

    private boolean setTickets(LandmarkBuild build, boolean add) {
        if (add && !chunksLoaded(
                build.world(), build.centerChunkX(), build.centerChunkZ(), build.ticketRadius())) {
            return false;
        }
        for (int dx = -build.ticketRadius(); dx <= build.ticketRadius(); dx++) {
            for (int dz = -build.ticketRadius(); dz <= build.ticketRadius(); dz++) {
                int chunkX = build.centerChunkX() + dx;
                int chunkZ = build.centerChunkZ() + dz;
                if (add) {
                    build.world().addPluginChunkTicket(chunkX, chunkZ, plugin);
                } else {
                    build.world().removePluginChunkTicket(chunkX, chunkZ, plugin);
                }
            }
        }
        return true;
    }

    private boolean chunksLoaded(World world, int centerChunkX, int centerChunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isChunkLoaded(centerChunkX + dx, centerChunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int ticketRadius(LandmarkKind kind) {
        return switch (kind) {
            case END_CITADEL -> 5;
            case NETHER_FORGE, TITAN_ARCH -> 4;
        };
    }

    private boolean isTargetChunk(LandmarkKind kind, World world, int chunkX, int chunkZ) {
        int region = regionChunks(kind);
        int regionX = Math.floorDiv(chunkX, region);
        int regionZ = Math.floorDiv(chunkZ, region);
        long mixed = mix(world.getSeed(), kind.salt, regionX, regionZ);
        int targetX = regionX * region + (int) Math.floorMod(mixed, region);
        int targetZ = regionZ * region + (int) Math.floorMod(mixed >>> 24, region);
        if (chunkX != targetX || chunkZ != targetZ) {
            return false;
        }
        int minimum = minimumDistanceChunks(kind);
        return Math.max(Math.abs(chunkX), Math.abs(chunkZ)) >= minimum;
    }

    private long mix(long seed, long salt, int regionX, int regionZ) {
        long value = seed ^ salt;
        value ^= Integer.toUnsignedLong(regionX) * 0x9E3779B97F4A7C15L;
        value ^= Long.rotateLeft(Integer.toUnsignedLong(regionZ) * 0xC2B2AE3D27D4EB4FL, 21);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private int regionChunks(LandmarkKind kind) {
        return Math.max(12, plugin.getConfig().getInt(kind.path + ".region-chunks", kind.defaultRegion));
    }

    private int minimumDistanceChunks(LandmarkKind kind) {
        return Math.max(0, plugin.getConfig().getInt(kind.path + ".minimum-distance-chunks", kind.defaultMinimum));
    }

    private LandmarkKind kind(World world) {
        return switch (world.getEnvironment()) {
            case THE_END -> enabled("landmarks.end-citadels") ? LandmarkKind.END_CITADEL : null;
            case NETHER -> enabled("landmarks.nether-forges") ? LandmarkKind.NETHER_FORGE : null;
            case NORMAL -> enabled("landmarks.titan-arches") ? LandmarkKind.TITAN_ARCH : null;
            default -> null;
        };
    }

    private boolean enabled() {
        return plugin.isEnabled() && plugin.getConfig().getBoolean("landmarks.enabled", true);
    }

    private boolean enabled(String path) {
        return plugin.getConfig().getBoolean(path + ".enabled", true);
    }

    private String key(LandmarkKind kind, World world, int chunkX, int chunkZ) {
        return kind.name().toLowerCase(Locale.ROOT) + ":"
                + world.getUID() + ":" + chunkX + ":" + chunkZ;
    }

    private enum LandmarkKind {
        END_CITADEL("landmarks.end-citadels", 48, 64, 0x45f10bdc2a11L),
        NETHER_FORGE("landmarks.nether-forges", 32, 24, 0x7a02beef991dL),
        TITAN_ARCH("landmarks.titan-arches", 40, 32, 0x11cc7715abcdL);

        final String path;
        final int defaultRegion;
        final int defaultMinimum;
        final long salt;

        LandmarkKind(String path, int defaultRegion, int defaultMinimum, long salt) {
            this.path = path;
            this.defaultRegion = defaultRegion;
            this.defaultMinimum = defaultMinimum;
            this.salt = salt;
        }
    }
}
