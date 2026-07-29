package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URL;

public class UpdateChecker {

    private final String currentVersion;
    private static final String PLUGIN_NAME = "RedstoneDetector";
    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/redstonedetector/version";

    private final RedstoneDetector plugin;
    private boolean updateAvailable = false;
    private String latestVersion = null;

    public UpdateChecker(RedstoneDetector plugin) {
        this.plugin = plugin;
        String version = null;
        try {
            version = plugin.getDescription().getVersion();
        } catch (Throwable ignored) {
        }
        this.currentVersion = (version == null || version.isEmpty()) ? "unknown" : version;
    }

    public void checkForUpdates() {
        ru.stepanyaa.redstoneDetector.platform.Platforms.scheduler().async(() -> {
            try {
                URL url = new URL(MODRINTH_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "RedstoneDetector/" + currentVersion);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String jsonResponse = response.toString();
                    String versionNumber = extractLatestVersion(jsonResponse);

                    if (versionNumber != null && !versionNumber.equals(currentVersion)) {
                        updateAvailable = true;
                        latestVersion = versionNumber;

                        String updateMessage = plugin.getMessage("update.available",
                            "&cAvailable update &f{plugin}&c Current: &f{current} &c→ New: &f{new} &c| &e{url}")
                            .replace("{plugin}", PLUGIN_NAME)
                            .replace("{current}", currentVersion)
                            .replace("{new}", versionNumber)
                            .replace("{url}", "https://modrinth.com/plugin/redstonedetector/versions");

                        ru.stepanyaa.redstoneDetector.platform.Platforms.scheduler().run(() ->
                            plugin.getLogger().info(updateMessage));
                    } else {
                        plugin.getLogger().info(plugin.getMessage("update.latest",
                            "RedstoneDetector: you have the latest version ({version})")
                            .replace("{version}", currentVersion));
                    }
                } else {
                    plugin.getLogger().warning(plugin.getMessage("update.check_failed",
                        "Failed to check for updates (code: {code})")
                        .replace("{code}", String.valueOf(responseCode)));
                }
            } catch (Exception e) {
                plugin.getLogger().warning(plugin.getMessage("update.error",
                    "Error checking for updates: {message}")
                    .replace("{message}", e.getMessage()));
            }
        });
    }
    private String extractLatestVersion(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonArray()) return null;

            JsonArray versions = element.getAsJsonArray();
            if (versions.size() == 0) return null;

            JsonObject latest = versions.get(0).getAsJsonObject();
            return latest.get("version_number").getAsString();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse Modrinth JSON with Gson: " + e.getMessage());
            return null;
        }
    }

    public void notifyPlayer(Player player) {
        if (updateAvailable && (player.hasPermission("redstonedetector.admin") || player.isOp())) {
            String updateMessage = plugin.getMessage(player, "update.available",
                "&aAvailable update &e{plugin}&a! Current: &f{current} &a→ New: &f{new} &a| &b{url}")
                .replace("{plugin}", PLUGIN_NAME)
                .replace("{current}", currentVersion)
                .replace("{new}", latestVersion)
                .replace("{url}", "https://modrinth.com/plugin/redstonedetector/versions");
            player.sendMessage(updateMessage);
        }
    }
}
