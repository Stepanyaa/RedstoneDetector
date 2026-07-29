package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.core.EngineSettings;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class SpongeConfig {

    public static final String[] BUNDLED_LOCALES =
            {"en_us", "ru_ru", "de_de", "fr_fr", "pt_br", "pl_pl", "tr_tr", "uk_ua", "es_es", "zh_cn", "it_it", "ja_jp", "nl_nl"};

    private final File folder;
    private final Logger logger;
    private final String version;

    private SpongeYaml config = SpongeYaml.empty();
    private SpongeYaml performance = SpongeYaml.empty();
    private SpongeYaml blocks = SpongeYaml.empty();
    private SpongeYaml gui = SpongeYaml.empty();

    private final Set<String> mechanisms = new HashSet<String>();

    public SpongeConfig(File folder, Logger logger) {
        this(folder, logger, "1.2.0");
    }

    public SpongeConfig(File folder, Logger logger, String version) {
        this.folder = folder;
        this.logger = logger;
        this.version = version == null || version.isEmpty() ? "1.2.0" : version;
    }

    public void reload() {
        extract("config.yml");
        extract("performance.yml");
        extract("blocks.yml");
        extract("gui.yml");
        for (String locale : BUNDLED_LOCALES) {
            extract("lang/" + locale + ".yml");
        }

        SpongeConfigUpdater.update(folder, "config.yml", version, logger);
        SpongeConfigUpdater.update(folder, "performance.yml", version, logger);
        SpongeConfigUpdater.update(folder, "blocks.yml", version, logger);
        SpongeConfigUpdater.update(folder, "gui.yml", version, logger);
        for (String locale : BUNDLED_LOCALES) {
            SpongeConfigUpdater.update(folder, "lang/" + locale + ".yml", version, logger);
        }

        config = SpongeYaml.load(new File(folder, "config.yml"));
        performance = SpongeYaml.load(new File(folder, "performance.yml"));
        blocks = SpongeYaml.load(new File(folder, "blocks.yml"));
        gui = SpongeYaml.load(new File(folder, "gui.yml"));

        mechanisms.clear();
        List<String> listed = new ArrayList<String>();
        listed.addAll(blocks.getStringList("redstone-blocks"));
        listed.addAll(blocks.getStringList("blocks"));
        listed.addAll(blocks.getStringList("mechanisms"));
        for (String name : listed) {
            if (name != null && !name.trim().isEmpty()) {
                mechanisms.add(name.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (mechanisms.isEmpty()) {
            addFallbackMechanisms();
        }
    }

    private void addFallbackMechanisms() {
        String[] defaults = {
            "REDSTONE_WIRE", "REPEATER", "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON",
            "MOVING_PISTON", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "REDSTONE_BLOCK",
            "REDSTONE_LAMP", "DISPENSER", "DROPPER", "HOPPER", "LEVER", "TRIPWIRE",
            "TRIPWIRE_HOOK", "TARGET", "SLIME_BLOCK", "HONEY_BLOCK", "NOTE_BLOCK",
            "DAYLIGHT_DETECTOR", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "CRAFTER"
        };
        for (String name : defaults) {
            mechanisms.add(name);
        }
        logger.info("blocks.yml held no entries; using the built-in mechanism list.");
    }

    public boolean isMechanism(String typeName) {
        return typeName != null && mechanisms.contains(typeName.toUpperCase(Locale.ROOT));
    }

    public String version() {
        return version;
    }

    public SpongeYaml config() {
        return config;
    }

    public SpongeYaml performance() {
        return performance;
    }

    public SpongeYaml gui() {
        return gui;
    }

    public String language() {
        return config.getString("language", "en_us");
    }

    public boolean perPlayerLanguage() {
        return performance.getBoolean("per-player-language",
                config.getBoolean("per-player-language", true));
    }

    public boolean setLanguage(String locale) {
        if (locale == null || locale.trim().isEmpty()) {
            return false;
        }
        File file = new File(folder, "config.yml");
        try {
            String content = file.isFile()
                    ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    : "";
            String[] lines = content.split("\n", -1);
            boolean replaced = false;
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < lines.length; index++) {
                if (!replaced && lines[index].startsWith("language:")) {
                    builder.append("language: ").append(locale);
                    replaced = true;
                } else {
                    builder.append(lines[index]);
                }
                if (index < lines.length - 1) {
                    builder.append('\n');
                }
            }
            String updated = builder.toString();
            if (!replaced) {
                if (!updated.isEmpty() && !updated.endsWith("\n")) {
                    updated = updated + "\n";
                }
                updated = updated + "language: " + locale + "\n";
            }
            Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable failure) {
            logger.warning("Could not store the language in config.yml: " + failure);
            return false;
        }
    }

    public int chunksPerTick() {
        return Math.max(1, Math.min(32, performance.getInt("chunks-per-tick",
                config.getInt("chunks-per-tick", 3))));
    }

    public boolean scanOnLowTps() {
        return performance.getBoolean("scan-on-low-tps",
                config.getBoolean("scan-on-low-tps", true));
    }

    public boolean scanLoadedChunks() {
        return performance.getBoolean("scan-loaded-chunks",
                config.getBoolean("scan-loaded-chunks", true));
    }

    public long scanCooldownMs() {
        return Math.max(0L, performance.getInt("scan-cooldown-ms", 3000));
    }

    public long statsCacheMs() {
        return Math.max(0L, performance.getInt("stats-cache-ms", 1000));
    }

    public boolean mergeAdjacentChunks() {
        return performance.getBoolean("merge-adjacent-chunks", true);
    }

    public boolean updateCheckEnabled() {
        return config.getBoolean("update-check", true);
    }

    public long scanBudgetNanos() {
        int micros = performance.getInt("scan-budget-micros", 1000);
        if (micros < 100) {
            micros = 100;
        }
        if (micros > 20000) {
            micros = 20000;
        }
        return micros * 1000L;
    }

    public int minTrackedMechanisms() {
        return Math.max(1, performance.getInt("min-tracked-mechanisms", 8));
    }

    public int minTrackedEntities() {
        int configured = performance.getInt("min-tracked-entities", 0);
        if (configured > 0) {
            return configured;
        }
        int maxEntities = config.getInt("max-entities", 100);
        return Math.max(20, maxEntities / 2);
    }

    public int globalFreezeMinSeconds() {
        return Math.max(3, performance.getInt("global-freeze-min-seconds", 15));
    }

    public int freezeActionCooldownSeconds() {
        return Math.max(0, performance.getInt("freeze-action-cooldown-seconds", 20));
    }

    public int forcedScanCooldownSeconds() {
        return Math.max(60, performance.getInt("forced-scan-cooldown-seconds", 300));
    }

    public int hardStopAfterSeconds() {
        return Math.max(0, performance.getInt("hard-stop-after-seconds", 3));
    }

    public int forgetIdleAfterSeconds() {
        return Math.max(0, performance.getInt("forget-idle-chunks-after-seconds", 300));
    }

    public int costEstimateMinUpdatesPerSec() {
        return Math.max(1, performance.getInt("cost-estimate-min-updates-per-second", 40));
    }

    public EngineSettings settings() {
        EngineSettings settings = new EngineSettings();
        settings.maxRedstone = config.getInt("max-redstone", settings.maxRedstone);
        settings.maxEntities = config.getInt("max-entities", settings.maxEntities);
        settings.criticalTps = config.getDouble("critical-tps", settings.criticalTps);
        settings.chunkDataRetentionHours =
                config.getInt("chunk-data-retention", settings.chunkDataRetentionHours);

        settings.intelligentFreeze = performance.getBoolean("intelligent-freeze-enabled",
                performance.getBoolean("intelligent-freeze.enabled",
                        performance.getBoolean("intelligent-freeze", settings.intelligentFreeze)));
        settings.updateScoreThreshold = performance.getInt("update-score-threshold",
                settings.updateScoreThreshold);
        settings.autoFreezeUpdatesPerSec = performance.getInt("auto-freeze-updates-per-second",
                performance.getInt("auto-freeze-updates-per-sec", settings.autoFreezeUpdatesPerSec));
        settings.lowTpsFreezeUpdatesPerSec = performance.getInt("low-tps-freeze-updates-per-second",
                performance.getInt("low-tps-freeze-updates-per-sec", settings.lowTpsFreezeUpdatesPerSec));
        settings.autoUnfreezeSeconds = performance.getInt("auto-unfreeze-seconds",
                settings.autoUnfreezeSeconds);
        settings.serverMsptThreshold = performance.getDouble("server-mspt-threshold",
                settings.serverMsptThreshold);
        settings.chunkMsptThreshold = performance.getDouble("chunk-mspt-threshold",
                settings.chunkMsptThreshold);
        settings.sustainedLagSeconds = performance.getInt("sustained-lag-seconds",
                settings.sustainedLagSeconds);
        settings.baselineMspt = performance.getDouble("baseline-mspt", settings.baselineMspt);
        settings.culpritMinUpdatesPerSec = performance.getInt("culprit-min-updates-per-second",
                performance.getInt("culprit-min-updates-per-sec",
                        settings.culpritMinUpdatesPerSec));
        settings.culpritMinMechanisms = performance.getInt("culprit-min-mechanisms",
                settings.culpritMinMechanisms);
        settings.globalFreezeAfterSeconds = performance.getInt("global-freeze-after-seconds",
                settings.globalFreezeAfterSeconds);
        settings.globalFreezeEnabled = performance.getBoolean("global-freeze-enabled",
                settings.globalFreezeEnabled);

        settings.chunksPerTick = chunksPerTick();

        settings.detectorSensitivity = Math.max(0.1, Math.min(5.0,
                performance.getDouble("detector-sensitivity", settings.detectorSensitivity)));
        settings.sculkDetectorEnabled = performance.getBoolean("sculk-detector-enabled",
                settings.sculkDetectorEnabled);
        settings.trapdoorDetectorEnabled = performance.getBoolean("trapdoor-detector-enabled",
                settings.trapdoorDetectorEnabled);
        settings.autoUnfreezeEnabled = performance.getBoolean("auto-unfreeze",
                settings.autoUnfreezeEnabled);
        settings.debugLogging = performance.getBoolean("debug-logging",
                config.getBoolean("debug-logging", settings.debugLogging));
        settings.freezeRadius = Math.max(0, Math.min(8,
                performance.getInt("freeze-radius", settings.freezeRadius)));
        settings.maxFreezeSeconds = Math.max(0,
                performance.getInt("max-freeze-seconds", settings.maxFreezeSeconds));
        settings.guiRefreshTicks = Math.max(5,
                performance.getInt("gui-refresh-ticks", settings.guiRefreshTicks));
        settings.scanIntervalTicks = Math.max(5,
                performance.getInt("scan-interval-ticks", settings.scanIntervalTicks));

        settings.sculkActivationsPerSecond = Math.max(1, performance.getInt(
                "sculk-activations-per-second", settings.sculkActivationsPerSecond));
        settings.sculkGroupSize = Math.max(1,
                performance.getInt("sculk-group-size", settings.sculkGroupSize));
        settings.sculkPositionRate = Math.max(1,
                performance.getInt("sculk-position-rate", settings.sculkPositionRate));
        settings.sculkRepeatBurst = Math.max(1,
                performance.getInt("sculk-repeat-burst", settings.sculkRepeatBurst));
        settings.sculkLoopSeconds = Math.max(1,
                performance.getInt("sculk-loop-seconds", settings.sculkLoopSeconds));
        settings.sculkRepeatWindowMillis = Math.max(20L, performance.getInt(
                "sculk-repeat-window-millis", (int) settings.sculkRepeatWindowMillis));

        settings.trapdoorTogglesPerSecond = Math.max(1, performance.getInt(
                "trapdoor-toggles-per-second", settings.trapdoorTogglesPerSecond));
        settings.trapdoorClusterSize = Math.max(1,
                performance.getInt("trapdoor-cluster-size", settings.trapdoorClusterSize));
        settings.trapdoorPositionRate = Math.max(1,
                performance.getInt("trapdoor-position-rate", settings.trapdoorPositionRate));
        settings.trapdoorRepeatBurst = Math.max(1,
                performance.getInt("trapdoor-repeat-burst", settings.trapdoorRepeatBurst));
        settings.trapdoorLoopSeconds = Math.max(1,
                performance.getInt("trapdoor-loop-seconds", settings.trapdoorLoopSeconds));
        settings.trapdoorRepeatWindowMillis = Math.max(20L, performance.getInt(
                "trapdoor-repeat-window-millis", (int) settings.trapdoorRepeatWindowMillis));

        collect(settings.ignoredWorlds, performance.getStringList("ignored-worlds"), false);
        collect(settings.ignoredWorlds, config.getStringList("ignored-worlds"), false);
        collect(settings.ignoredChunks, performance.getStringList("ignored-chunks"), false);
        collect(settings.ignoredChunks, config.getStringList("ignored-chunks"), false);
        collect(settings.ignoredBlocks, performance.getStringList("ignored-blocks"), true);
        collect(settings.ignoredBlocks, config.getStringList("ignored-blocks"), true);

        return settings;
    }

    private static void collect(java.util.Set<String> target, java.util.List<String> values,
                                boolean upperCase) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            target.add(upperCase
                    ? ru.stepanyaa.redstoneDetector.core.BlockKinds.normalize(trimmed)
                    : trimmed);
        }
    }

    public File folder() {
        return folder;
    }

    private void extract(String name) {
        File target = new File(folder, name.replace('/', File.separatorChar));
        if (target.isFile()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            logger.warning("Could not create " + parent.getAbsolutePath());
            return;
        }
        InputStream source = getClass().getClassLoader().getResourceAsStream(name);
        if (source == null) {
            return;
        }
        try {
            OutputStream out = Files.newOutputStream(target.toPath());
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
            logger.info("Created " + name);
        } catch (Throwable failure) {
            logger.warning("Could not write " + name + ": " + failure);
        } finally {
            try {
                source.close();
            } catch (Throwable ignored) {

            }
        }
    }
}
