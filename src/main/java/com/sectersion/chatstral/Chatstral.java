package com.sectersion.chatstral;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class Chatstral extends JavaPlugin {

    private ModelManager modelManager;
    private ShieldstralClient shieldstralClient;
    private Blacklist blacklist;
    private ChatFilter chatFilter;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        double threshold = getConfig().getDouble("ai-threshold", 0.5);
        boolean aiEnabled = getConfig().getBoolean("ai-filter-enabled", true);

        blacklist = new Blacklist(this);
        modelManager = new ModelManager(this);
        shieldstralClient = new ShieldstralClient(modelManager, this, threshold);
        chatFilter = new ChatFilter(this, shieldstralClient, blacklist);

        getServer().getPluginManager().registerEvents(chatFilter, this);

        if (aiEnabled) {
            getLogger().info("Initializing ShieldStral chat filter...");
            getLogger().info("This will download llama-server and the model on first run.");
            modelManager.initialize();
        } else {
            getLogger().info("AI filter is disabled in config. Using blacklist only.");
        }

        getLogger().info("Chatstral has been enabled!");
    }

    @Override
    public void onDisable() {
        if (shieldstralClient != null) {
            shieldstralClient.shutdown();
        }
        if (modelManager != null) {
            modelManager.shutdown();
        }
        getLogger().info("Chatstral has been disabled!");
    }

    public ModelManager getModelManager() {
        return modelManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chatstral")) {
            if (args.length == 0) {
                sender.sendMessage(Component.text("Chatstral - AI-Powered Chat Filter")
                        .color(NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Version: " + getDescription().getVersion())
                        .color(NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Model Status: " + getModelStatus())
                        .color(NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Use /chatstral reload to reload blacklist."));
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("chatstral.admin")) {
                    sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
                    return true;
                }
                blacklist.reload();
                sender.sendMessage(Component.text("Blacklist reloaded!").color(NamedTextColor.GREEN));
                return true;
            }

            if (args[0].equalsIgnoreCase("status")) {
                sender.sendMessage(Component.text("=== Chatstral Status ===").color(NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Model Ready: " + modelManager.isModelReady())
                        .color(NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Model Downloading: " + modelManager.isDownloading())
                        .color(NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Server Running: " + modelManager.isServerRunning())
                        .color(NamedTextColor.GRAY));
                return true;
            }
        }
        return false;
    }

    private String getModelStatus() {
        if (modelManager.isDownloading()) return "Downloading...";
        if (modelManager.isModelReady()) return "Ready";
        return "Loading...";
    }
}
