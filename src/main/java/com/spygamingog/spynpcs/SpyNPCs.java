package com.spygamingog.spynpcs;

import com.spygamingog.spynpcs.commands.NPCCommand;
import com.spygamingog.spynpcs.managers.NPCManager;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public class SpyNPCs extends JavaPlugin {
    @Getter
    private static SpyNPCs instance;
    @Getter
    private NPCManager npcManager;

    @Override
    public void onEnable() {
        instance = this;
        
        // Initialize managers
        this.npcManager = new NPCManager(this);
        
        // Load NPCs from persistence
        this.npcManager.loadNPCs();
        
        // Register commands
        NPCCommand npcCommand = new NPCCommand();
        getCommand("spynpc").setExecutor(npcCommand);
        getCommand("spynpc").setTabCompleter(npcCommand);
        
        getLogger().info("SpyNPCs has been enabled!");
    }

    @Override
    public void onDisable() {
        // Save NPCs to persistence
        if (this.npcManager != null) {
            this.npcManager.saveNPCs();
        }
        getLogger().info("SpyNPCs has been disabled!");
    }
}
