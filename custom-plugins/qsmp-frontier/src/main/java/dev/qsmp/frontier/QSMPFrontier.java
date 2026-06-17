package dev.qsmp.frontier;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class QSMPFrontier extends JavaPlugin {
    private OutpostService outposts;
    private WarfrontService warfront;
    private ExpeditionService expeditions;
    private ProgressionService progression;
    private DragonService dragons;
    private ResourcePackService resourcePacks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FrontierKeys keys = new FrontierKeys(this);
        progression = new ProgressionService(this);
        FrontierItems items = new FrontierItems(this, keys);
        resourcePacks = new ResourcePackService(this);
        resourcePacks.start();
        EnchantmentLimitService enchantments = new EnchantmentLimitService(this);
        CinematicService cinematics = new CinematicService(this, items);
        CombatService combat = new CombatService(this, progression, cinematics);
        CompanionRoleService roles = new CompanionRoleService(this, keys, cinematics);
        LegacyService legacies = new LegacyService(this, keys, progression, items);
        dragons = new DragonService(this, keys, items, cinematics);
        outposts = new OutpostService(this, keys, items, roles, progression, cinematics);
        WarfrontBuilder builder = new WarfrontBuilder(this);
        warfront = new WarfrontService(this, keys, items, builder, progression, legacies, cinematics);
        expeditions = new ExpeditionService(this, keys, items, progression, legacies, cinematics);
        FrontierGuideMenu guideMenu =
                new FrontierGuideMenu(items, warfront, expeditions, progression, legacies);
        FrontierListener listener = new FrontierListener(
                this, combat, roles, items, outposts, warfront, expeditions,
                progression, legacies, dragons, guideMenu, cinematics);
        FrontierCommand commandHandler = new FrontierCommand(
                this, roles, items, outposts, warfront, expeditions, progression, legacies);

        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(enchantments, this);
        Bukkit.getPluginManager().registerEvents(resourcePacks, this);
        PluginCommand command = Objects.requireNonNull(
                getCommand("frontier"), "frontier command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        PluginCommand survivorCommand = Objects.requireNonNull(
                getCommand("survivor"), "survivor command is missing from plugin.yml");
        survivorCommand.setExecutor(commandHandler);
        survivorCommand.setTabCompleter(commandHandler);
        PluginCommand legacyCommand = Objects.requireNonNull(
                getCommand("legacy"), "legacy command is missing from plugin.yml");
        legacyCommand.setExecutor(commandHandler);
        legacyCommand.setTabCompleter(commandHandler);

        items.registerRecipes();
        progression.load();
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
        Bukkit.getScheduler().runTaskTimer(this, dragons::tick, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, cinematics::tick, 50L, 10L);
        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> Bukkit.getOnlinePlayers().forEach(legacies::tickPlayer),
                40L,
                40L);
        Bukkit.getScheduler().runTaskTimer(this, enchantments::tickOnlinePlayers, 80L, 200L);
        getLogger().info(
                "Dynamic combat, tactical companions, outposts, warfront raids, "
                        + "ruin expeditions, survivor levels, legacy gear, and dragon raids are enabled.");
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
        if (progression != null) {
            progression.save();
        }
        if (dragons != null) {
            dragons.cleanup();
        }
        if (resourcePacks != null) {
            resourcePacks.stop();
        }
    }
}
