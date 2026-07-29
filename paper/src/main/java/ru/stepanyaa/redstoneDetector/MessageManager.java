package ru.stepanyaa.redstoneDetector;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
    private FileConfiguration fallbackConfig;

    private final java.util.Map<String, FileConfiguration> languageCache =
            new java.util.concurrent.ConcurrentHashMap<String, FileConfiguration>();

    private volatile java.util.Set<String> availableCache;
    private volatile long availableCacheAt;

    private java.lang.reflect.Method paperLocaleMethod;
    private java.lang.reflect.Method bukkitGetLocaleMethod;
    private volatile boolean localeMethodsResolved;

    public static final String[] BUNDLED_LOCALES =
            {"en_us", "ru_ru", "de_de", "fr_fr", "pt_br", "pl_pl", "tr_tr", "uk_ua", "es_es", "zh_cn", "it_it", "ja_jp", "nl_nl"};
    public static final String DEFAULT_LOCALE = "en_us";
    private static final String LANG_FOLDER = "lang";

    private static final String[][] LEGACY_CODES = {
            {"en", "en_us"}, {"ru", "ru_ru"}, {"de", "de_de"},
            {"fr", "fr_fr"}, {"pt", "pt_br"}, {"pl", "pl_pl"}, {"tr", "tr_tr"}};

    public MessageManager(RedstoneDetector plugin) {
        this.plugin = plugin;
        this.language = DEFAULT_LOCALE;
    }

    public static void migrateLegacyLangFiles(Plugin plugin) {
        File langDir = new File(plugin.getDataFolder(), LANG_FOLDER);
        File[] files = langDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.startsWith("messages_") || !name.endsWith(".yml")) {
                continue;
            }

            String code = name.substring("messages_".length(), name.length() - ".yml".length())
                    .toLowerCase();
            String locale = null;
            for (String[] pair : LEGACY_CODES) {
                if (pair[0].equals(code)) {
                    locale = pair[1];
                    break;
                }
            }
            if (locale == null) {

                locale = code.indexOf('_') > 0 ? code : code + "_" + code;
            }

            File target = new File(langDir, locale + ".yml");
            if (target.exists()) {

                if (file.delete()) {
                    plugin.getLogger().info("Removed obsolete lang/" + name);
                }
                continue;
            }
            if (file.renameTo(target)) {
                plugin.getLogger().info("Migrated lang/" + name + " to lang/" + locale + ".yml");
            } else {
                plugin.getLogger().warning("Could not rename lang/" + name
                        + " to lang/" + locale + ".yml");
            }
        }
    }

    public void loadMessages() {
        migrateLegacyLangFiles(plugin);

        File langDir = new File(plugin.getDataFolder(), LANG_FOLDER);
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        invalidate();

        String configured = normalizeLocale(plugin.getConfig().getString("language", DEFAULT_LOCALE));
        String resolved = match(configured);
        if (resolved == null) {
            plugin.getLogger().warning("No lang/" + configured + ".yml found (neither bundled nor custom), "
                    + "falling back to '" + DEFAULT_LOCALE + "'");
            resolved = DEFAULT_LOCALE;
        }
        this.language = resolved;

        messagesFile = new File(langDir, resolved + ".yml");
        if (!messagesFile.exists()) {
            extract(resolved, messagesFile);
        }

        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        fallbackConfig = loadBundled(DEFAULT_LOCALE);

        languageCache.put(resolved, messagesConfig);
    }

    private void invalidate() {
        languageCache.clear();
        availableCache = null;
        availableCacheAt = 0L;
    }

    private void extract(String locale, File target) {
        InputStream resource = plugin.getResource(LANG_FOLDER + "/" + locale + ".yml");
        if (resource == null) {
            return;
        }
        try {
            Files.copy(resource, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create lang/" + locale + ".yml: " + e.getMessage());
        }
    }

    private FileConfiguration loadBundled(String locale) {
        InputStream resource = plugin.getResource(LANG_FOLDER + "/" + locale + ".yml");
        if (resource == null) {
            return null;
        }
        try {
            java.io.InputStreamReader reader =
                    new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8);
            try {
                return YamlConfiguration.loadConfiguration(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private String normalizeLocale(String raw) {
        if (raw == null) {
            return DEFAULT_LOCALE;
        }
        return raw.trim().toLowerCase().replace('-', '_');
    }

    private String match(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }

        java.util.Set<String> available = availableSet();
        if (available.contains(code)) {
            return code;
        }

        for (String[] pair : LEGACY_CODES) {
            if (pair[0].equals(code) && available.contains(pair[1])) {
                return pair[1];
            }
        }

        String lang = code;
        int cut = lang.indexOf('_');
        if (cut > 0) {
            lang = lang.substring(0, cut);
        }
        if (available.contains(lang)) {
            return lang;
        }

        String prefix = lang + "_";
        for (String candidate : available) {
            if (candidate.startsWith(prefix)) {
                return candidate;
            }
        }
        return null;
    }

    public String getMessage(String key, String defaultMessage) {
        return color(lookup(null, key, defaultMessage));
    }

    public String getMessage(CommandSender sender, String key, String defaultMessage) {
        return color(lookup(sender, key, defaultMessage));
    }

    private String lookup(CommandSender sender, String key, String defaultMessage) {
        String message = null;

        if (sender != null) {
            String locale = resolveLanguage(sender);
            if (locale != null && !locale.equals(language)) {
                FileConfiguration config = configFor(locale);
                if (config != null) {
                    message = config.getString(key);
                }
            }
        }
        if (message == null && messagesConfig != null) {
            message = messagesConfig.getString(key);
        }
        if (message == null && fallbackConfig != null) {
            message = fallbackConfig.getString(key);
        }
        return message == null ? defaultMessage : message;
    }

    private String color(String message) {
        return message == null ? "" : ChatColor.translateAlternateColorCodes('&', message);
    }

    public String resolveLanguage(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return language;
        }
        if (plugin.getConfigManager() == null || !plugin.getConfigManager().isPerPlayerLanguage()) {
            return language;
        }

        String client = readClientLocale((Player) sender);
        if (client == null || client.isEmpty()) {
            return language;
        }

        String resolved = match(client);
        return resolved == null ? language : resolved;
    }

    private FileConfiguration configFor(String locale) {
        FileConfiguration cached = languageCache.get(locale);
        if (cached != null) {
            return cached;
        }

        FileConfiguration config;
        File file = new File(new File(plugin.getDataFolder(), LANG_FOLDER), locale + ".yml");
        if (file.isFile()) {
            config = YamlConfiguration.loadConfiguration(file);
        } else {
            config = loadBundled(locale);
        }

        if (config != null) {
            languageCache.put(locale, config);
        }
        return config;
    }

    private String readClientLocale(Player player) {
        resolveLocaleMethods(player);

        if (paperLocaleMethod != null) {
            try {
                Object result = paperLocaleMethod.invoke(player);
                if (result instanceof java.util.Locale) {
                    java.util.Locale locale = (java.util.Locale) result;
                    String tag = locale.getLanguage();
                    if (tag != null && !tag.isEmpty()) {
                        String country = locale.getCountry();
                        return normalizeLocale(country == null || country.isEmpty()
                                ? tag : tag + "_" + country);
                    }
                }
            } catch (Throwable ignored) {

            }
        }

        if (bukkitGetLocaleMethod != null) {
            try {
                Object result = bukkitGetLocaleMethod.invoke(player);
                if (result instanceof String) {
                    return normalizeLocale((String) result);
                }
            } catch (Throwable ignored) {

            }
        }

        try {
            Object spigot = player.spigot();
            Object result = spigot.getClass().getMethod("getLocale").invoke(spigot);
            if (result instanceof String) {
                return normalizeLocale((String) result);
            }
        } catch (Throwable ignored) {

        }
        return null;
    }

    private void resolveLocaleMethods(Player player) {
        if (localeMethodsResolved) {
            return;
        }
        localeMethodsResolved = true;

        try {
            java.lang.reflect.Method method = player.getClass().getMethod("locale");
            if (java.util.Locale.class.isAssignableFrom(method.getReturnType())) {
                paperLocaleMethod = method;
            }
        } catch (Throwable ignored) {
            paperLocaleMethod = null;
        }

        try {
            java.lang.reflect.Method method = player.getClass().getMethod("getLocale");
            if (String.class.isAssignableFrom(method.getReturnType())) {
                bukkitGetLocaleMethod = method;
            }
        } catch (Throwable ignored) {
            bukkitGetLocaleMethod = null;
        }
    }

    public java.util.List<String> getAvailableLanguages() {
        return new java.util.ArrayList<String>(availableSet());
    }

    private java.util.Set<String> availableSet() {
        long now = System.currentTimeMillis();
        java.util.Set<String> cached = availableCache;
        if (cached != null && now - availableCacheAt < 60000L) {
            return cached;
        }

        java.util.Set<String> codes = new java.util.TreeSet<String>();
        for (String bundled : BUNDLED_LOCALES) {
            codes.add(bundled);
        }

        File langDir = new File(plugin.getDataFolder(), LANG_FOLDER);
        File[] files = langDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (!file.isFile() || !name.endsWith(".yml")) {
                    continue;
                }
                String code = name.substring(0, name.length() - ".yml".length()).toLowerCase();
                if (!code.isEmpty() && !code.startsWith("messages_")) {
                    codes.add(code);
                }
            }
        }

        availableCache = codes;
        availableCacheAt = now;
        return codes;
    }

    public void updateMessagesFiles() {
        File langDir = new File(plugin.getDataFolder(), LANG_FOLDER);
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        for (String locale : BUNDLED_LOCALES) {
            File langFile = new File(langDir, locale + ".yml");
            if (!langFile.exists()) {
                extract(locale, langFile);
            }
        }
        invalidate();
    }

    public String getLanguage() {
        return language;
    }

    public boolean setLanguage(String code) {
        String resolved = match(normalizeLocale(code));
        if (resolved == null) {
            return false;
        }

        if (!writeLanguageToConfig(resolved)) {
            return false;
        }

        plugin.reloadConfig();
        loadMessages();
        return true;
    }

    private boolean writeLanguageToConfig(String locale) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        try {
            java.util.List<String> lines = Files.readAllLines(configFile.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (!trimmed.startsWith("#") && trimmed.startsWith("language:")) {
                    lines.set(i, "language: " + locale);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add("language: " + locale);
            }
            Files.write(configFile.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save language to config.yml: " + e.getMessage());
            return false;
        }
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }
}
