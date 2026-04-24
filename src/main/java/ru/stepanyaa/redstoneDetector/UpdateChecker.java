/**
 * MIT License
 *
 * RedstoneDetector
 * Copyright (c) 2026 Stepanyaa
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
    private static final String CURRENT_VERSION = "1.0.7";
    private static final String PLUGIN_NAME = "RedstoneDetector";
    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/redstonedetector/version";
    
    private final RedstoneDetector plugin;
    private boolean updateAvailable = false;
    private String latestVersion = null;

    public UpdateChecker(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void checkForUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL(MODRINTH_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "RedstoneDetector/" + CURRENT_VERSION);

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

                    if (versionNumber != null && !versionNumber.equals(CURRENT_VERSION)) {
                        updateAvailable = true;
                        latestVersion = versionNumber;
                        
                        String updateMessage = plugin.getMessage("update.available", 
                            "&cAvailable update &f{plugin}&c Current: &f{current} &c→ New: &f{new} &c| &e{url}")
                            .replace("{plugin}", PLUGIN_NAME)
                            .replace("{current}", CURRENT_VERSION)
                            .replace("{new}", versionNumber)
                            .replace("{url}", "https://modrinth.com/plugin/redstonedetector/versions");
                        
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            plugin.getLogger().info(updateMessage));
                    } else {
                        plugin.getLogger().info(plugin.getMessage("update.latest", 
                            "RedstoneDetector: you have the latest version ({version})")
                            .replace("{version}", CURRENT_VERSION));
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
            String updateMessage = plugin.getMessage("update.available", 
                "&aAvailable update &e{plugin}&a! Current: &f{current} &a→ New: &f{new} &a| &b{url}")
                .replace("{plugin}", PLUGIN_NAME)
                .replace("{current}", CURRENT_VERSION)
                .replace("{new}", latestVersion)
                .replace("{url}", "https://modrinth.com/plugin/redstonedetector/versions");
            player.sendMessage(updateMessage);
        }
    }
}
