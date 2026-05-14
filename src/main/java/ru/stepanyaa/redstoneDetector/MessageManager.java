/**
 * MIT License
 *
 * RedstoneDetector
 * Copyright (c) 2025 Stepanyaa
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

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class MessageManager {
    private final RedstoneDetector plugin;
    private FileConfiguration messagesConfig;
    private File messagesFile;
    private String language;
    private static final String[] SUPPORTED_LANGUAGES = {"en", "ru", "de", "fr", "pt", "pl", "tr"};
    private static final String LANG_FOLDER = "lang";

    public MessageManager(RedstoneDetector plugin) {
        this.plugin = plugin;
        this.language = plugin.getConfig().getString("language", "en");
    }

    public void loadMessages() {
        String lang = plugin.getConfig().getString("language", "en");
        if (!isLanguageSupported(lang)) {
            plugin.getLogger().warning("Unsupported language '" + lang + "', falling back to 'en'");
            lang = "en";
        }
        this.language = lang;

        File langDir = new File(plugin.getDataFolder(), LANG_FOLDER);
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        messagesFile = new File(langDir, "messages_" + lang + ".yml");

        if (!messagesFile.exists()) {
            InputStream resource = plugin.getResource("messages_" + lang + ".yml");
            if (resource != null) {
                try {
                    Files.copy(resource, messagesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not create messages file: " + e.getMessage());
                }
            } else {
                plugin.getLogger().warning("Resource messages_" + lang + ".yml not found in jar!");
            }
        }

        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(String key, String defaultMessage) {
        String message = messagesConfig.getString(key, defaultMessage);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private boolean isLanguageSupported(String lang) {
        for (String supported : SUPPORTED_LANGUAGES) {
            if (supported.equals(lang)) {
                return true;
            }
        }
        return false;
    }

    public void updateMessagesFiles() {
        File langDir = new File(plugin.getDataFolder(), LANG_FOLDER);
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        for (String lang : SUPPORTED_LANGUAGES) {
            File langFile = new File(langDir, "messages_" + lang + ".yml");

            if (!langFile.exists()) {
                InputStream resource = plugin.getResource("messages_" + lang + ".yml");
                if (resource != null) {
                    try {
                        Files.copy(resource, langFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Failed to create lang/messages_" + lang + ".yml: " + e.getMessage());
                    }
                }
            }
        }
    }

    public String getLanguage() {
        return language;
    }
}
