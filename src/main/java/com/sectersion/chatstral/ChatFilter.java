package com.sectersion.chatstral;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ChatFilter implements Listener {

    private final Chatstral plugin;
    private final ShieldstralClient shieldstralClient;
    private final Blacklist blacklist;
    private final Map<Player, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<Player, PendingChat> pendingChats = new ConcurrentHashMap<>();

    public ChatFilter(Chatstral plugin, ShieldstralClient shieldstralClient, Blacklist blacklist) {
        this.plugin = plugin;
        this.shieldstralClient = shieldstralClient;
        this.blacklist = blacklist;
    }

    private long getCooldownMs() {
        return plugin.getConfig().getLong("cooldown-ms", 1000);
    }

    @EventHandler(priority = EventPriority.LOW)
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

        if (blacklist.isBlocked(message)) {
            event.setCancelled(true);
            sendBlockedMessage(player, "Blacklisted content detected.");
            return;
        }

        if (!plugin.getConfig().getBoolean("ai-filter-enabled", true)) {
            return;
        }

        if (modelNotReady(player)) {
            return;
        }

        Component originalFormat = Component.text(event.getFormat());
        pendingChats.put(player, new PendingChat(message, originalFormat, event));

        shieldstralClient.checkMessage(player.getName(), message)
                .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(result -> processResult(player, result))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Filter error: " + ex.getMessage());
                    pendingChats.remove(player);
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

    private void processResult(Player player, ShieldstralClient.FilterResult result) {
        PendingChat pending = pendingChats.remove(player);
        if (pending == null) {
            return;
        }

        cooldowns.put(player, System.currentTimeMillis());

        if (result.blocked()) {
            plugin.getLogger().info("Blocked message from " + player.getName()
                    + " (score=" + String.format("%.3f", result.score()) + "): " + pending.message());
            pending.event().setCancelled(true);
            sendBlockedMessage(player, "Your message was flagged as inappropriate.");
        } else {
            pending.event().setFormat(pending.originalFormat().toString());
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

    public void clearCooldown(Player player) {
        cooldowns.remove(player);
    }

    private record PendingChat(String message, Component originalFormat, AsyncPlayerChatEvent event) {}
}
