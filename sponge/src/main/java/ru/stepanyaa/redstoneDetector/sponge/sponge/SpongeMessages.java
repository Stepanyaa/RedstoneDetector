package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.core.EngineMessages;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class SpongeMessages {

    public static final String DEFAULT_LOCALE = "en_us";
    private static final String LANG_FOLDER = "lang";
    private static final long AVAILABLE_CACHE_MS = 60000L;

    private static final String[][] LEGACY_CODES = {
        {"en", "en_us"}, {"ru", "ru_ru"}, {"de", "de_de"}, {"fr", "fr_fr"},
        {"pt", "pt_br"}, {"pl", "pl_pl"}, {"tr", "tr_tr"}
    };

    private final SpongeConfig config;
    private final Logger logger;

    private final Map<String, SpongeYaml> loaded = new ConcurrentHashMap<String, SpongeYaml>();

    private volatile SpongeYaml english = SpongeYaml.empty();
    private volatile String serverLocale = DEFAULT_LOCALE;
    private volatile boolean perPlayer = true;

    private volatile Set<String> availableCache;
    private volatile long availableCachedAt;

    public SpongeMessages(SpongeConfig config) {
        this(config, Logger.getLogger("RedstoneDetector"));
    }

    public SpongeMessages(SpongeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void reload() {
        loaded.clear();
        availableCache = null;
        availableCachedAt = 0L;
        migrateLegacyFiles();
        perPlayer = config.perPlayerLanguage();

        String configured = normalize(config.language());
        String matched = match(configured);
        if (matched == null) {
            logger.warning("Unknown language '" + config.language() + "', using "
                    + DEFAULT_LOCALE + " instead.");
            matched = DEFAULT_LOCALE;
        }
        serverLocale = matched;
        english = configFor(DEFAULT_LOCALE);
        configFor(serverLocale);
    }

    public String get(String key, String fallback) {
        return lookup(serverLocale, key, fallback);
    }

    public String get(Object viewer, String key, String fallback) {
        return lookup(resolveLanguage(viewer), key, fallback);
    }

    public String format(Object viewer, String key, String fallback, String... replacements) {
        String text = get(viewer, key, fallback);
        if (replacements == null) {
            return text;
        }
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            text = text.replace(replacements[index], String.valueOf(replacements[index + 1]));
        }
        return text;
    }

    public String formatServer(String key, String fallback, String... replacements) {
        return format(null, key, fallback, replacements);
    }

    private String lookup(String locale, String key, String fallback) {
        String value = read(locale, key);
        if (value == null) {
            String alias = ALIASES.get(key);
            if (alias != null) {
                value = read(locale, alias);
            }
        }
        return value == null ? (fallback == null ? "" : fallback) : value;
    }

    private String read(String locale, String key) {
        String value = configFor(locale).getString(key, null);
        if ((value == null || value.isEmpty()) && !locale.equals(serverLocale)) {
            value = configFor(serverLocale).getString(key, null);
        }
        if (value == null || value.isEmpty()) {
            value = english.getString(key, null);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    private static final Map<String, String> ALIASES = new java.util.HashMap<String, String>();

    static {
        ALIASES.put("cmd.reload.success", "command.reload_success");
        ALIASES.put("cmd.entities.removed", "chunk.entities_removed");
        ALIASES.put("cmd.tp.done", "chunk.teleport_success");
        ALIASES.put("cmd.tp.failed", "chunk.world_not_found");
        ALIASES.put("cmd.freeze.cleared", "chunk.redstone_removed");
        ALIASES.put("cmd.freeze.restored", "chunk.redstone_restored");

        ALIASES.put("gui.players_only", "command.player_only");
        ALIASES.put("gui.back", "gui.world_back");
        ALIASES.put("gui.back_lore", "gui.world_back_lore");
        ALIASES.put("gui.back_list_lore", "gui.chunkinfo.back_list_lore");
        ALIASES.put("gui.list.title", "gui.chunk_list_title");
        ALIASES.put("gui.search_compass_name", "gui.search_compass_title");
        ALIASES.put("gui.search_compass_lore", "gui.search_compass_line1");
        ALIASES.put("gui.search_compass_prompt", "gui.search_compass_line2");
        ALIASES.put("gui.sort_mechanisms", "gui.sort_redstone");

        ALIASES.put("gui.dashboard.title", "gui.dashboard.status_title");
        ALIASES.put("gui.dashboard.status", "gui.dashboard.status_title");
        ALIASES.put("gui.dashboard.problems", "gui.dashboard.suspicious");
        ALIASES.put("gui.dashboard.tracked", "gui.dashboard.suspicious");
        ALIASES.put("gui.dashboard.list", "gui.dashboard.chunks");
        ALIASES.put("gui.dashboard.list_lore", "gui.dashboard.chunks_lore");
        ALIASES.put("gui.dashboard.frozen_lore", "gui.dashboard.frozen_chunks_lore");
        ALIASES.put("gui.dashboard.scan_lore", "gui.dashboard.scan_start_lore");
        ALIASES.put("gui.dashboard.stop_all", "gui.dashboard.freeze");
        ALIASES.put("gui.dashboard.stop_all_lore", "gui.dashboard.freeze_all_lore");
        ALIASES.put("gui.dashboard.smart_freeze", "gui.dashboard.freeze");
        ALIASES.put("gui.dashboard.smart_freeze_lore", "gui.dashboard.freeze_smart_lore");
        ALIASES.put("gui.dashboard.refresh", "gui.dashboard.reload");
        ALIASES.put("gui.dashboard.refresh_lore", "gui.dashboard.reload_lore");
        ALIASES.put("gui.dashboard.mspt", "gui.chunkinfo.server_mspt");

        ALIASES.put("gui.chunkinfo.mechanisms", "gui.chunkinfo.redstone");
        ALIASES.put("gui.chunkinfo.updates", "gui.chunkinfo.updates_sec");
        ALIASES.put("gui.chunkinfo.mspt", "gui.chunkinfo.chunk_mspt");
        ALIASES.put("gui.chunkinfo.no_data", "cmd.info.no_data");
        ALIASES.put("gui.chunkinfo.click", "gui.chunk_lclick");
        ALIASES.put("gui.chunkinfo.click_actions", "gui.chunk_lclick_info");
        ALIASES.put("gui.chunkinfo.teleport", "gui.chunk_teleport");
        ALIASES.put("gui.chunkinfo.stop", "gui.chunkinfo.freeze_stop");
        ALIASES.put("gui.chunkinfo.stop_lore", "gui.chunkinfo.freeze_stop_lore");
        ALIASES.put("gui.chunkinfo.resume", "gui.chunkinfo.unfreeze_stop");
    }

    public String resolveLanguage(Object viewer) {
        if (!perPlayer || viewer == null) {
            return serverLocale;
        }
        String reported = SpongeViewers.clientLocale(viewer);
        if (reported == null) {
            return serverLocale;
        }
        String matched = match(normalize(reported));
        return matched == null ? serverLocale : matched;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_LOCALE;
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public String match(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        Set<String> available = availableSet();
        if (available.contains(normalized)) {
            return normalized;
        }
        for (String[] pair : LEGACY_CODES) {
            if (pair[0].equals(normalized) && available.contains(pair[1])) {
                return pair[1];
            }
        }
        String language = normalized;
        int underscore = normalized.indexOf('_');
        if (underscore > 0) {
            language = normalized.substring(0, underscore);
            if (available.contains(language)) {
                return language;
            }
        }
        String prefix = language + "_";
        for (String candidate : available) {
            if (candidate.startsWith(prefix)) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isAvailable(String locale) {
        return match(normalize(locale)) != null;
    }

    public List<String> availableLanguages() {
        List<String> languages = new ArrayList<String>(availableSet());
        Collections.sort(languages);
        return languages;
    }

    public String serverLanguage() {
        return serverLocale;
    }

    public boolean perPlayerLanguage() {
        return perPlayer;
    }

    private Set<String> availableSet() {
        Set<String> cached = availableCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - availableCachedAt < AVAILABLE_CACHE_MS) {
            return cached;
        }
        Set<String> found = new LinkedHashSet<String>();
        for (String locale : SpongeConfig.BUNDLED_LOCALES) {
            found.add(locale);
        }
        File folder = new File(config.folder(), LANG_FOLDER);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".yml")) {
                    continue;
                }
                String locale = name.substring(0, name.length() - 4);
                if (locale.startsWith("messages_")) {
                    locale = locale.substring("messages_".length());
                }
                if (!locale.isEmpty()) {
                    found.add(normalize(locale));
                }
            }
        }
        availableCache = found;
        availableCachedAt = now;
        return found;
    }

    private SpongeYaml configFor(String locale) {
        SpongeYaml cached = loaded.get(locale);
        if (cached != null) {
            return cached;
        }
        File file = new File(new File(config.folder(), LANG_FOLDER), locale + ".yml");
        SpongeYaml document = SpongeYaml.load(file);
        if (document.raw().isEmpty()) {
            File legacy = new File(new File(config.folder(), LANG_FOLDER),
                    "messages_" + locale + ".yml");
            document = SpongeYaml.load(legacy);
        }
        if (document.raw().isEmpty()) {
            String bundled = SpongeConfigUpdater.read(LANG_FOLDER + "/" + locale + ".yml");
            if (bundled != null) {
                document = SpongeYaml.parse(bundled);
            }
        }
        loaded.put(locale, document);
        return document;
    }

    private void migrateLegacyFiles() {
        File folder = new File(config.folder(), LANG_FOLDER);
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.isFile() || !name.startsWith("messages_") || !name.endsWith(".yml")) {
                continue;
            }
            String locale = normalize(name.substring("messages_".length(),
                    name.length() - 4));
            File renamed = new File(folder, locale + ".yml");
            if (renamed.isFile()) {
                continue;
            }
            if (file.renameTo(renamed)) {
                logger.info("Renamed " + file.getName() + " to " + renamed.getName() + ".");
            }
        }
    }

    public EngineMessages forViewer(final Object viewer) {
        return new EngineMessages() {
            @Override
            public String get(String key, String fallback) {
                return SpongeMessages.this.get(viewer, key, fallback);
            }
        };
    }
}
