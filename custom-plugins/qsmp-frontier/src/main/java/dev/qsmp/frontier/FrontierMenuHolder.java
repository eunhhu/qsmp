package dev.qsmp.frontier;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class FrontierMenuHolder implements InventoryHolder {
    enum Menu {
        CODEX,
        SURVIVOR,
        LEGACY
    }

    private final Menu menu;
    private final Inventory inventory;

    FrontierMenuHolder(Menu menu, int size, String title) {
        this.menu = menu;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    Menu menu() {
        return menu;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
