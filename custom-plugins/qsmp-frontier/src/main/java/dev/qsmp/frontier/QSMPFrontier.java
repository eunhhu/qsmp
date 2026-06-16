package dev.qsmp.frontier;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class QSMPFrontier extends JavaPlugin {
    private OutpostService outposts;
    private WarfrontService warfront;
    private ExpeditionService expeditions;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FrontierKeys keys = new FrontierKeys(this);
        CombatService combat = new CombatService(this);
        CompanionRoleService roles = new CompanionRoleService(this, keys);
        FrontierItems items = new FrontierItems(this, keys);
        outposts = new OutpostService(this, keys, items, roles);
        WarfrontBuilder builder = new WarfrontBuilder(this);
        warfront = new WarfrontService(this, keys, items, builder);
        expeditions = new ExpeditionService(this, keys, items);
        FrontierListener listener = new FrontierListener(
                combat, roles, items, outposts, warfront, expeditions);
        FrontierCommand commandHandler = new FrontierCommand(
                this, roles, items, outposts, warfront, expeditions);

        Bukkit.getPluginManager().registerEvents(listener, this);
        PluginCommand command = Objects.requireNonNull(
                getCommand("frontier"), "frontier command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        items.registerRecipes();
        outposts.load();
        warfront.load();
        expeditions.load();
        Bukkit.getScheduler().runTaskLater(this, warfront::ensureEncounter, 40L);
        Bukkit.getScheduler().runTaskTimer(this, roles::tick, 20L, 10L);
        long productionInterval = Math.max(
                100L, getConfig().getLong("outposts.production-interval-ticks", 1200L));
        Bukkit.getScheduler().runTaskTimer(
                this, outposts::produce, productionInterval, productionInterval);
        Bukkit.getScheduler().runTaskTimer(this, warfront::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, expeditions::tick, 30L, 20L);
        getLogger().info(
                "Dynamic combat, tactical companions, outposts, warfront raids, "
                        + "and ruin expeditions are enabled.");
    }

    @Override
    public void onDisable() {
        if (outposts != null) {
            outposts.save();
        }
        if (warfront != null) {
            warfront.stop(false);
        }
        if (expeditions != null) {
            expeditions.save();
            expeditions.cleanupSentinels();
        }
    }
}
