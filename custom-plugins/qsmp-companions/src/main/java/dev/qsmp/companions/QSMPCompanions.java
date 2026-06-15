package dev.qsmp.companions;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class QSMPCompanions extends JavaPlugin {
    private CompanionService companions;
    private CompanionItems items;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        companions = new CompanionService(this);
        items = new CompanionItems(this, companions);
        CompanionCommand commandHandler = new CompanionCommand(this, companions, items);
        CompanionListener listener = new CompanionListener(this, companions, items);

        Bukkit.getPluginManager().registerEvents(listener, this);
        PluginCommand command = Objects.requireNonNull(
                getCommand("companion"), "companion command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        items.registerRecipes();
        companions.refreshLoaded();

        long interval = Math.max(20L, getConfig().getLong("regeneration.interval-ticks", 100L));
        Bukkit.getScheduler().runTaskTimer(this, companions::regenerateLoaded, interval, interval);
        long combatInterval = Math.max(5L, getConfig().getLong("combat.interval-ticks", 10L));
        Bukkit.getScheduler().runTaskTimer(
                this, companions::tickCombat, combatInterval, combatInterval);
        getLogger().info(
                "Companion combat, levels, armor, cryopods, and regeneration are enabled.");
    }

    void reloadSettings() {
        reloadConfig();
        companions.refreshLoaded();
    }
}
