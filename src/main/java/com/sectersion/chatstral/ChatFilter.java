package com.sectersion.chatstral;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatFilter implements Listener {

    private final Chatstral plugin;
    private final ShieldstralClient shieldstralClient;
    private final Blacklist blacklist;
    private final Map<Player, Long> cooldowns = new ConcurrentHashMap<>();

    public ChatFilter(Chatstral plugin, ShieldstralClient shieldstralClient, Blacklist blacklist) {
        this.plugin = plugin;
        this.shieldstralClient = shieldstralClient;
        this.blacklist = blacklist;
    }

    private long getCooldownMs() {
        return plugin.getConfig().getLong("cooldown-ms", 1000);
    }

    private boolean logOnly() {
        return "log".equalsIgnoreCase(plugin.getConfig().getString("filter-mode", "block"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (player.hasPermission("chatstral.bypass")) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastSend = cooldowns.get(player);
        if (lastSend != null && now - lastSend < getCooldownMs()) {
            return;
        }

        boolean logOnly = logOnly();

        if (blacklist.isBlocked(message)) {
            plugin.getLogger().info((logOnly ? "[LOG] Flagged" : "Blocked") + " message from "
                    + player.getName() + " (blacklist): " + message);
            if (!logOnly) {
                event.setCancelled(true);
                sendBlockedMessage(player, "Blacklisted content detected.");
                return;
            }
        }

        if (!plugin.getConfig().getBoolean("ai-filter-enabled", true)) {
            return;
        }

        if (modelNotReady(player)) {
            return;
        }

        // cancel now, redeliver manually once the async score resolves
        event.setCancelled(true);

        shieldstralClient.checkMessage(player.getName(), message)
                .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(result -> processResult(player, message, result))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Filter error: " + ex.getMessage());
                    passMessage(player, message);
                    return null;
                });
    }

    private boolean modelNotReady(Player player) {
        if (plugin.getModelManager().isDownloading()) {
            player.sendMessage(Component.text("Chat filter is downloading... Please try again in a moment.")
                    .color(NamedTextColor.YELLOW));
            return true;
        }

        if (!plugin.getModelManager().isModelReady()) {
            player.sendMessage(Component.text("Chat filter is loading... Please try again in a moment.")
                    .color(NamedTextColor.YELLOW));
            return true;
        }

        return false;
    }

    private void processResult(Player player, String originalMessage, ShieldstralClient.FilterResult result) {
        cooldowns.put(player, System.currentTimeMillis());
        boolean logOnly = logOnly();

        if (result.blocked()) {
            plugin.getLogger().info((logOnly ? "[LOG] Flagged" : "Blocked") + " message from " + player.getName()
                    + " (score=" + String.format("%.3f", result.score()) + "): " + originalMessage);
        }

        if (result.blocked() && !logOnly) {
            sendBlockedMessage(player, "Your message was flagged as inappropriate.");
        } else {
            passMessage(player, originalMessage);
        }
    }

    private void sendBlockedMessage(Player player, String reason) {
        Component msg = Component.text("Chat Filter: " + reason)
                .color(NamedTextColor.RED);
        player.sendMessage(msg);

        Component suggestion = Component.text("Your message was not sent. Please review your message and try again.")
                .color(NamedTextColor.GRAY);
        player.sendMessage(suggestion);
    }

    private void passMessage(Player player, String message) {
        Component displayName = player.displayName();
        Component formatted = Component.text("<")
                .append(displayName)
                .append(Component.text("> "))
                .append(Component.text(message))
                .color(NamedTextColor.WHITE);

        for (Player recipient : plugin.getServer().getOnlinePlayers()) {
            recipient.sendMessage(formatted);
        }

        plugin.getServer().getConsoleSender().sendMessage(formatted);
    }

    public void clearCooldown(Player player) {
        cooldowns.remove(player);
    }
}
