package dev.qsmp.frontier;

import java.util.List;
import java.util.Queue;
import org.bukkit.World;

record LandmarkBuild(
        String key,
        String name,
        World world,
        int centerChunkX,
        int centerChunkZ,
        int ticketRadius,
        Queue<BlockPlan> blocks,
        List<LandmarkAction> actions) {
}
