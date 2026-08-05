package com.sectersion.chatstral;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class Blacklist {

    private final Chatstral plugin;
    private final Set<Pattern> blockedPatterns = new HashSet<>();
    private final Set<String> blockedWords = new HashSet<>();

    public Blacklist(Chatstral plugin) {
        this.plugin = plugin;
        loadBlacklist();
    }

    public void loadBlacklist() {
        File blacklistFile = new File(plugin.getDataFolder(), "blacklist.txt");

        if (!blacklistFile.exists()) {
            plugin.saveResource("blacklist.txt", false);
        }

        blockedPatterns.clear();
        blockedWords.clear();

        try {
            if (blacklistFile.exists()) {
                java.nio.file.Files.readAllLines(blacklistFile.toPath(), StandardCharsets.UTF_8)
                        .stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(line -> {
                            if (line.startsWith("regex:")) {
                                String regex = line.substring(6).trim();
                                try {
                                    blockedPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Invalid regex in blacklist: " + regex);
                                }
                            } else {
                                blockedWords.add(line.toLowerCase());
                            }
                        });
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load blacklist: " + e.getMessage());
        }

        plugin.getLogger().info("Loaded " + blockedWords.size() + " blocked words and "
                + blockedPatterns.size() + " blocked patterns.");
    }

    public boolean isBlocked(String message) {
        return checkBlock(message).blocked();
    }

    public String getBlockReason(String message) {
        return checkBlock(message).reason();
    }

    private BlockResult checkBlock(String message) {
        String lowerMessage = message.toLowerCase();

        for (String word : blockedWords) {
            if (lowerMessage.contains(word)) {
                return new BlockResult(true, "blacklist_word");
            }
        }

        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(message).find()) {
                return new BlockResult(true, "blacklist_pattern");
            }
        }

        return new BlockResult(false, null);
    }

    private record BlockResult(boolean blocked, String reason) {}

    public void reload() {
        loadBlacklist();
    }
}
