package dev.qsmp.frontier;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class QSMPFrontier extends JavaPlugin {
    private OutpostService outposts;
    private WarfrontService warfront;

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
        FrontierListener listener = new FrontierListener(
                combat, roles, items, outposts, warfront);
        FrontierCommand commandHandler = new FrontierCommand(
                this, roles, items, outposts, warfront);

        Bukkit.getPluginManager().registerEvents(listener, this);
        PluginCommand command = Objects.requireNonNull(
                getCommand("frontier"), "frontier command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        items.registerRecipes();
        outposts.load();
        warfront.load();
        Bukkit.getScheduler().runTaskLater(this, warfront::ensureEncounter, 40L);
        Bukkit.getScheduler().runTaskTimer(this, roles::tick, 20L, 10L);
        long productionInterval = Math.max(
                100L, getConfig().getLong("outposts.production-interval-ticks", 1200L));
        Bukkit.getScheduler().runTaskTimer(
                this, outposts::produce, productionInterval, productionInterval);
        Bukkit.getScheduler().runTaskTimer(this, warfront::tick, 20L, 20L);
        getLogger().info(
                "Dynamic combat, tactical companions, outposts, and warfront raids are enabled.");
    }

    @Override
    public void onDisable() {
        if (outposts != null) {
            outposts.save();
        }
        if (warfront != null && warfront.active()) {
            warfront.stop(false);
        }
    }
}
