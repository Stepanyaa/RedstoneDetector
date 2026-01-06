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

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

public class RedstoneDetector extends JavaPlugin implements Listener, TabCompleter {
    public final Map<UUID, List<String>> playerChunkDetailsLines = new HashMap<>();
    public final Map<UUID, Integer> playerChunkDetailsPage = new HashMap<>();
    public final Map<UUID, String> playerChunkDetailsTitle = new HashMap<>();
    private long chunkDataRetentionHours = 24;

    public static class ChunkCoordinate {
        private final String world;
        private final int x;
        private final int z;

        public ChunkCoordinate(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }

        public String world() {
            return world;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        @Override
        public String toString() {
            return world + ";" + x + ";" + z;
        }

        public static ChunkCoordinate fromString(String s) {
            String[] parts = s.split(";");
            return new ChunkCoordinate(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }

        public String toDisplayString() {
            return "[" + x + ", " + z + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkCoordinate that = (ChunkCoordinate) o;
            return x == that.x && z == that.z && Objects.equals(world, that.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, z);
        }
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public static class ChunkData {
        public AtomicInteger redstoneCount = new AtomicInteger(0);
        public AtomicInteger entityCount = new AtomicInteger(0);
        public long firstDetected = System.currentTimeMillis();
        public long lastScanned = System.currentTimeMillis();
        public boolean clearedByAdmin = false;
        public long clearedTime = 0;
    }

    private final Map<ChunkCoordinate, ChunkData> chunkMap = new ConcurrentHashMap<>();
    private GuiManager guiManager;
    private boolean freezeRedstone = false;
    private long lastFreezeTime = 0;
    private boolean monitoringEnabled = true;
    private double criticalTPS = 15.0;
    private int maxRedstone = 100;
    private int maxEntities = 100;
    private final Map<ChunkCoordinate, Map<Location, Material>> redstoneBackups = new ConcurrentHashMap<>();
    private final Set<Material> redstoneMaterials = new HashSet<>();
    private int chunksPerTick = 3;
    private boolean firstCriticalState = true;
    private File chunkDataFile;
    private YamlConfiguration chunkDataConfig;
    private long lastTPSWarning = 0;
    private final long TPS_WARNING_COOLDOWN = 10000;
    private double lastReportedTPS = 20.0;
    private static final String CURRENT_VERSION = "1.0.3";
    private boolean isFirstEnable = true;
    private final String PLUGIN_NAME = "RedstoneDetector";
    private boolean updateAvailable = false;
    private String latestModrinthVersion = null;

    private FileConfiguration messagesConfig;
    private File messagesFile;
    private String language;
    private static final String[] SUPPORTED_LANGUAGES = {"en", "ru"};

    @Override
    public void onEnable() {
        getLogger().info(getMessage("plugin.startup", "======== RedstoneDetector STARTING ========"));
        saveDefaultConfig();
        reloadConfig();
        loadConfig();

        loadMessages();
        updateConfigFile();
        updateMessagesFiles();
        checkForUpdates();

        chunkDataFile = new File(getDataFolder(), "chunk-data.yml");
        loadChunkData();

        initializeRedstoneMaterials();

        this.guiManager = new GuiManager(this);
        guiManager.loadPlayerStates();

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        registerCommands();
        startOptimizedChunkScanTask();
        startAutoSaveTask();

        getLogger().info(getMessage("plugin.enabled", "Plugin successfully enabled!"));
        this.isFirstEnable = false;
        int pluginId = 27778;
        Metrics metrics = new Metrics(this, pluginId);
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOldChunkData();
            }
        }.runTaskTimer(this, 20L * 60 * 120, 20L * 60 * 120);
    }

    private void updateConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            getLogger().info(getMessage("warning.config-file-create", "Created config file: config.yml"));
            return;
        }

        YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
        String currentFileVersion = existingConfig.getString("config-version", "0.0.0");

        if (currentFileVersion.equals(CURRENT_VERSION)) {
            if (isFirstEnable) {
                getLogger().info(getMessage("warning.config-file-up-to-date", "Config file config.yml is up-to-date (version %version%).")
                        .replace("%version%", CURRENT_VERSION));
            }
            return;
        }

        if (getResource("config.yml") != null) {
            try {
                saveResource("config.yml", true);
                getLogger().info(getMessage("warning.config-file-updated", "Updated config.yml to version %version%.")
                        .replace("%version%", CURRENT_VERSION));
                YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);
                newConfig.set("config-version", CURRENT_VERSION);
                newConfig.save(configFile);
            } catch (Exception e) {
                getLogger().warning("Failed to update config.yml: " + e.getMessage());
            }
        } else {
            getLogger().warning(getMessage("warning.config-file-not-found", "Resource config.yml not found in plugin!"));
        }
    }

    private void updateMessagesFiles() {
        for (String lang : SUPPORTED_LANGUAGES) {
            String fileName = "messages_" + lang + ".yml";
            File messageFile = new File(getDataFolder(), fileName);

            if (!messageFile.exists()) {
                if (getResource(fileName) != null) {
                    saveResource(fileName, false);
                    getLogger().info(getMessage("warning.messages-file-create", "Created messages file: %file%")
                            .replace("%file%", fileName));
                } else {
                    getLogger().warning(getMessage("warning.messages-file-not-found", "Messages file %file% not found in plugin!")
                            .replace("%file%", fileName));
                    continue;
                }
            }

            YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(messageFile);
            String currentFileVersion = existingConfig.getString("version", "0.0.0");

            if (currentFileVersion.equals(CURRENT_VERSION)) {
                if (isFirstEnable) {
                    getLogger().info(getMessage("warning.messages-file-up-to-date", "Messages file %file% is up-to-date (version %version%).")
                            .replace("%file%", fileName)
                            .replace("%version%", CURRENT_VERSION));
                }
                continue;
            }

            if (getResource(fileName) != null) {
                try {
                    saveResource(fileName, true);
                    getLogger().info(getMessage("warning.messages-file-updated", "Updated messages file %file% to version %version%.")
                            .replace("%file%", fileName)
                            .replace("%version%", CURRENT_VERSION));

                    YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(messageFile);
                    newConfig.set("version", CURRENT_VERSION);
                    newConfig.save(messageFile);
                } catch (Exception e) {
                    getLogger().warning("Failed to update messages file " + fileName + ": " + e.getMessage());
                }
            } else {
                getLogger().warning(getMessage("warning.messages-file-not-found", "Messages file %file% not found in plugin!")
                        .replace("%file%", fileName));
            }
        }
    }

    private void loadMessages() {
        this.language = getConfig().getString("language", "en");
        if (!Arrays.asList(SUPPORTED_LANGUAGES).contains(this.language)) {
            getLogger().warning("Unsupported language '" + this.language + "' in config.yml, defaulting to 'en'");
            this.language = "en";
        }

        String messagesFileName = "messages_" + language + ".yml";
        messagesFile = new File(getDataFolder(), messagesFileName);

        messagesConfig = new YamlConfiguration();

        try {
            if (messagesFile.exists()) {
                messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            } else {
                getLogger().warning("Messages file " + messagesFileName + " does not exist!");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load messages file: " + e.getMessage());
        }
    }

    public String getMessage(String key, String defaultValue) {
        if (messagesConfig == null) {
            return ChatColor.translateAlternateColorCodes('&', defaultValue);
        }

        String message = messagesConfig.getString(key, defaultValue);
        if (message == null || message.isEmpty()) {
            return ChatColor.translateAlternateColorCodes('&', defaultValue);
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void initializeRedstoneMaterials() {
        Material[] materials = {
                Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR,
                Material.PISTON, Material.STICKY_PISTON, Material.OBSERVER,
                Material.DISPENSER, Material.DROPPER, Material.HOPPER,
                Material.REDSTONE_TORCH, Material.REDSTONE_BLOCK, Material.LEVER,
                Material.STONE_BUTTON, Material.OAK_BUTTON, Material.TRIPWIRE_HOOK,
                Material.TARGET
        };
        Collections.addAll(redstoneMaterials, materials);
    }

    private boolean isRedstoneComponent(Material material) {
        return redstoneMaterials.contains(material);
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.savePlayerStates();
        }

        saveChunkData();
        getLogger().info(getMessage("plugin.shutdown", "GUI states and chunk data saved"));
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("redstonedetector")).setExecutor(this);
        Objects.requireNonNull(getCommand("rd")).setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String command = cmd.getName().toLowerCase();
        Player player = (Player) sender;
        if (label.equalsIgnoreCase("rdpage") && args.length == 2) {
            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(args[0]);
            } catch (IllegalArgumentException e) {
                return true;
            }

            if (!targetUuid.equals(player.getUniqueId())) {
                return true;
            }

            if (args[1].equalsIgnoreCase("prev")) {
                prevChunkDetailsPage(player);
            } else if (args[1].equalsIgnoreCase("next")) {
                nextChunkDetailsPage(player);
            }
            return true;
        }
        if (command.equals("redstonedetector") || command.equals("rd")) {
            if (args.length == 0) {
                return openGuiCommand(sender);
            }
            return handleSubCommand(sender, args);
        }
        if (command.equalsIgnoreCase("rdcancel") && sender instanceof Player) {
            ChatListener.cancelSearch((Player) sender);
            return true;
        }
        return false;
    }

    private boolean handleSubCommand(CommandSender sender, String[] args) {
        String subCommand = args[0].toLowerCase();
        if (subCommand.equals("reload")) {
            return reloadCommand(sender);
        } else if (subCommand.equals("gui")) {
            return openGuiCommand(sender);
        } else if (subCommand.equals("redstone")) {
            return redstoneCommand(sender, args);
        } else if (subCommand.equals("stopredstone")) {
            return stopRedstoneCommand(sender);
        } else if (subCommand.equals("scan")) {
            return scanCommand(sender);
        } else {
            sendHelp(sender);
            return true;
        }
    }

    private boolean openGuiCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage("command.player_only", "This command is for players only!"));
            return true;
        }
        Player player = (Player) sender;
        if (!sender.hasPermission("redstonedetector.gui")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_gui", "You do not have permission to use the GUI!"));
            return true;
        }
        guiManager.restorePlayerState(player);
        return true;
    }

    private boolean reloadCommand(CommandSender sender) {
        if (!sender.hasPermission("redstonedetector.reload")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_reload", "You do not have permission to reload the plugin!"));
            return true;
        }
        reloadConfig();
        updateMessagesFiles();
        loadConfig();
        loadMessages();
        sender.sendMessage(ChatColor.GREEN + getMessage("command.reload_success", "Configuration reloaded!"));
        return true;
    }

    private boolean redstoneCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("redstonedetector.redstone")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_redstone", "You do not have permission to manage redstone!"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + getMessage("command.redstone_usage", "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
            return true;
        }
        String action = args[1].toLowerCase();
        if (action.equals("freeze")) {
            setFreezeRedstone(true, sender.getName());
            sender.sendMessage(ChatColor.GREEN + getMessage("command.redstone_frozen", "Redstone frozen!"));
            return true;
        } else if (action.equals("unfreeze")) {
            setFreezeRedstone(false, sender.getName());
            monitoringEnabled = true;
            sender.sendMessage(ChatColor.GREEN + getMessage("command.redstone_unfrozen", "Redstone unfrozen!"));
            return true;
        } else if (action.equals("status")) {
            String status = freezeRedstone ?
                    ChatColor.RED + getMessage("command.redstone_status_frozen", "FROZEN") :
                    ChatColor.GREEN + getMessage("command.redstone_status_active", "ACTIVE");
            sender.sendMessage(ChatColor.YELLOW + getMessage("command.redstone_status", "Redstone status: {status}").replace("{status}", status));
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + getMessage("command.redstone_usage", "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
            return true;
        }
    }

    private boolean stopRedstoneCommand(CommandSender sender) {
        if (!sender.hasPermission("redstonedetector.redstone")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_redstone", "You do not have permission to manage redstone!"));
            return true;
        }
        setFreezeRedstone(true, sender.getName());
        monitoringEnabled = false;
        sender.sendMessage(ChatColor.RED + getMessage("command.redstone_stopped", "Redstone activity forcibly stopped!"));
        return true;
    }

    private boolean scanCommand(CommandSender sender) {
        if (!sender.hasPermission("redstonedetector.scan")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_scan", "You do not have permission to force a scan!"));
            return true;
        }
        forceFullRedstoneScan();
        sender.sendMessage(ChatColor.GREEN + getMessage("command.scan_started", "Forced chunk scan started!"));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + getMessage("command.help_header", "=== RedstoneDetector Help ==="));
        if (sender.hasPermission("redstonedetector.gui")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector gui" + ChatColor.WHITE + getMessage("command.help_gui", " - Open the interface"));
        }
        if (sender.hasPermission("redstonedetector.reload")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector reload" + ChatColor.WHITE + getMessage("command.help_reload", " - Reload the configuration"));
        }
        if (sender.hasPermission("redstonedetector.redstone")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector redstone freeze" + ChatColor.WHITE + getMessage("command.help_redstone_freeze", " - Freeze redstone"));
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector redstone unfreeze" + ChatColor.WHITE + getMessage("command.help_redstone_unfreeze", " - Unfreeze redstone"));
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector redstone status" + ChatColor.WHITE + getMessage("command.help_redstone_status", " - Redstone status"));
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector stopredstone" + ChatColor.WHITE + getMessage("command.help_stopredstone", " - Emergency stop"));
        }
        if (sender.hasPermission("redstonedetector.scan")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector scan" + ChatColor.WHITE + getMessage("command.help_scan", " - Force chunk scan"));
        }
        sender.sendMessage(ChatColor.GOLD + getMessage("command.help_aliases", "Aliases: ") + ChatColor.YELLOW + "/rd");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("redstonedetector") || cmd.getName().equalsIgnoreCase("rd")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                if (sender.hasPermission("redstonedetector.gui")) completions.add("gui");
                if (sender.hasPermission("redstonedetector.reload")) completions.add("reload");
                if (sender.hasPermission("redstonedetector.redstone")) {
                    completions.add("redstone");
                    completions.add("stopredstone");
                }
                if (sender.hasPermission("redstonedetector.scan")) {
                    completions.add("scan");
                }
                return completions;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("redstone")) {
                return Arrays.asList("freeze", "unfreeze", "status");
            }
        }
        return Collections.emptyList();
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        criticalTPS = config.getDouble("critical-tps", 15.0);
        maxRedstone = config.getInt("max-redstone", 100);
        maxEntities = config.getInt("max-entities", 100);
        chunksPerTick = config.getInt("chunks-per-tick", 3);
        chunksPerTick = config.getInt("chunks-per-tick", 3);
        monitoringEnabled = config.getBoolean("scan-loaded-chunks", true);
        chunkDataRetentionHours = config.getLong("chunk-data-retention", 24);
    }
    private void cleanupOldChunkData() {
        if (chunkDataRetentionHours <= 0) {
            getLogger().info(getMessage("data.retention_disabled", "Chunk data retention disabled (chunk-data-retention: 0)"));
            return;
        }

        long cutoffTime = System.currentTimeMillis() - (chunkDataRetentionHours * 3600000L);
        int removedCount = 0;

        Iterator<Map.Entry<ChunkCoordinate, ChunkData>> iterator = chunkMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkCoordinate, ChunkData> entry = iterator.next();
            ChunkData data = entry.getValue();

            boolean shouldRemove = false;

            if (data.lastScanned < cutoffTime) {
                shouldRemove = true;
            }

            if (data.clearedByAdmin && data.clearedTime > 0 && data.clearedTime < cutoffTime) {
                shouldRemove = true;
            }

            if (shouldRemove) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            saveChunkData();
            getLogger().info(getMessage("data.cleanup_log", "Cleaned up {count} old chunk records (retention: {hours} hours)")
                    .replace("{count}", String.valueOf(removedCount))
                    .replace("{hours}", String.valueOf(chunkDataRetentionHours)));
        }
    }

    private void loadChunkData() {
        try {
            if (!chunkDataFile.exists() && !chunkDataFile.createNewFile()) {
                getLogger().severe(getMessage("data.error_chunk_file", "Failed to create chunk data file"));
            }
        } catch (IOException e) {
            getLogger().severe(getMessage("data.error_chunk_create", "Error creating chunk data file: ") + e.getMessage());
        }

        chunkDataConfig = YamlConfiguration.loadConfiguration(chunkDataFile);
        chunkMap.clear();
        long currentTime = System.currentTimeMillis();
        boolean changed = false;

        for (String key : chunkDataConfig.getKeys(false)) {
            ConfigurationSection section = chunkDataConfig.getConfigurationSection(key);
            if (section != null) {
                ChunkCoordinate coord = ChunkCoordinate.fromString(key);
                ChunkData data = new ChunkData();
                data.redstoneCount.set(section.getInt("redstone"));
                data.entityCount.set(section.getInt("entities"));
                data.firstDetected = section.getLong("firstDetected");
                data.lastScanned = section.getLong("lastScanned");
                data.clearedByAdmin = section.getBoolean("cleared", false);
                data.clearedTime = section.getLong("clearedTime", 0);

                if (data.clearedByAdmin) {
                    if (currentTime - data.clearedTime > 600000) {
                        chunkDataConfig.set(key, null);
                        changed = true;
                    } else {
                        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                            @Override
                            public void run() {
                                chunkMap.remove(coord);
                            }
                        }, (600000 - (currentTime - data.clearedTime)) / 50);
                    }
                } else if (currentTime - data.lastScanned > getConfig().getInt("chunk-data-retention", 24) * 3600000L) {
                    chunkDataConfig.set(key, null);
                    changed = true;
                } else {
                    chunkMap.put(coord, data);
                }
            }
        }
        if (changed) saveChunkData();
    }

    public void saveChunkData() {
        try {
            for (String key : chunkDataConfig.getKeys(false)) {
                chunkDataConfig.set(key, null);
            }

            long retentionPeriod = getConfig().getInt("chunk-data-retention", 24) * 3600000L;
            long currentTime = System.currentTimeMillis();

            for (Map.Entry<ChunkCoordinate, ChunkData> entry : chunkMap.entrySet()) {
                ChunkCoordinate coord = entry.getKey();
                ChunkData data = entry.getValue();

                if (currentTime - data.lastScanned <= retentionPeriod) {
                    ConfigurationSection section = chunkDataConfig.createSection(coord.toString());
                    section.set("redstone", data.redstoneCount.get());
                    section.set("entities", data.entityCount.get());
                    section.set("firstDetected", data.firstDetected);
                    section.set("lastScanned", data.lastScanned);
                    section.set("cleared", data.clearedByAdmin);
                    section.set("clearedTime", data.clearedTime);
                }
            }

            chunkDataConfig.save(chunkDataFile);
        } catch (IOException e) {
            getLogger().severe(getMessage("data.error_chunk_save", "Error saving chunk data: ") + e.getMessage());
        }
    }

    private void startAutoSaveTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                saveChunkData();
            }
        }.runTaskTimer(this, 20 * 60 * 5, 20 * 60 * 5);
    }

    private void startOptimizedChunkScanTask() {
        new BukkitRunnable() {
            private final List<World> worlds = new ArrayList<>();
            private int worldIndex = 0;
            private Iterator<Chunk> chunkIterator = null;

            @Override
            public void run() {
                if (!monitoringEnabled) return;

                worlds.clear();
                worlds.addAll(getServer().getWorlds());

                for (int i = 0; i < chunksPerTick; i++) {
                    if (worlds.isEmpty()) break;

                    if (chunkIterator == null || !chunkIterator.hasNext()) {
                        if (worldIndex >= worlds.size()) {
                            worldIndex = 0;
                        }
                        World world = worlds.get(worldIndex);
                        chunkIterator = Arrays.asList(world.getLoadedChunks()).iterator();
                        worldIndex++;
                    }

                    if (chunkIterator.hasNext()) {
                        Chunk chunk = chunkIterator.next();
                        scanChunk(chunk);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void forceFullRedstoneScan() {
        getLogger().warning(getMessage("chunk.scan_forced", "Forced scanning of all chunks due to low TPS"));
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (chunk.isLoaded()) {
                    scanChunk(chunk);
                }
            }
        }
    }

    private void scanChunk(Chunk chunk) {
        if (chunk == null || !chunk.isLoaded()) return;

        World world = chunk.getWorld();
        ChunkCoordinate coord = new ChunkCoordinate(world.getName(), chunk.getX(), chunk.getZ());
        ChunkData data = chunkMap.get(coord);
        if (data == null) {
            data = new ChunkData();
            chunkMap.put(coord, data);
        }

        if (data.clearedByAdmin) return;

        int redstoneCount = 0;
        int entityCount = 0;

        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (isRedstoneComponent(block.getType())) {
                        redstoneCount++;
                    }
                }
            }
        }

        entityCount = 0;
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Player)) {
                entityCount++;
            }
        }

        data.redstoneCount.set(redstoneCount);
        data.entityCount.set(entityCount);
        data.lastScanned = System.currentTimeMillis();
    }

    public void setFreezeRedstone(boolean freeze, String initiator) {
        boolean previousState = this.freezeRedstone;
        this.freezeRedstone = freeze;

        if (freeze && !previousState) {
            getLogger().warning(getMessage("redstone.frozen_log", "Redstone frozen!"));
            cancelActiveRedstone();
        } else if (!freeze && previousState) {
            getLogger().warning(getMessage("redstone.unfrozen_log", "Redstone unfrozen!"));
        }
    }

    private void cancelActiveRedstone() {
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (isRedstoneComponent(state.getType())) {
                        state.update(true, false);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (freezeRedstone) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (freezeRedstone) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        if (freezeRedstone) {
            event.setNewCurrent(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + getMessage("redstone.break_blocked", "Redstone is frozen! You cannot break blocks."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + getMessage("redstone.place_blocked", "Redstone is frozen! You cannot place blocks."));
        }
    }
    private boolean isNewerVersion(String current, String latest) {
        if (current.equals(latest)) return false;

        String[] currentParts = current.split("\\.");
        String[] latestParts = latest.split("\\.");

        int maxLength = Math.max(currentParts.length, latestParts.length);

        for (int i = 0; i < maxLength; i++) {
            int currentNum = (i < currentParts.length) ? Integer.parseInt(currentParts[i]) : 0;
            int latestNum = (i < latestParts.length) ? Integer.parseInt(latestParts[i]) : 0;

            if (currentNum > latestNum) return false;
            if (currentNum < latestNum) return true;
        }

        return false;
    }

    private void checkForUpdates() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("https://api.modrinth.com/v2/project/redstonedetector/version");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "RedstoneDetector Update Checker");

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        getLogger().warning(getMessage("update.check_failed", "Failed to check for updates (code: ") + responseCode + ")");
                        return;
                    }

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    String json = response.toString();
                    latestModrinthVersion = extractLatestVersion(json);

                    if (latestModrinthVersion == null) {
                        getLogger().warning(getMessage("update.parse_failed", "Failed to extract version from Modrinth response"));
                        return;
                    }

                    if (isNewerVersion(CURRENT_VERSION, latestModrinthVersion)) {
                        updateAvailable = true;
                        String updateMessage = getMessage("update.available", "&aAvailable update &e{plugin}&a! Current: &f{current} &a→ New: &f{new} &a| &b{url}")
                                .replace("{plugin}", PLUGIN_NAME)
                                .replace("{current}", CURRENT_VERSION)
                                .replace("{new}", latestModrinthVersion)
                                .replace("{url}", "https://modrinth.com/plugin/redstonedetector/versions");

                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (player.hasPermission("redstonedetector.admin") || player.isOp()) {
                                player.sendMessage(updateMessage);
                            }
                        }

                        getLogger().info(ChatColor.stripColor(updateMessage));
                    } else {
                        getLogger().info(getMessage("update.latest", "RedstoneDetector: you have the latest version ({version})")
                                .replace("{version}", CURRENT_VERSION));
                    }


                } catch (Exception e) {
                    getLogger().warning(getMessage("update.error", "Error checking for updates: ") + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }

    private String extractLatestVersion(String json) {
        try {
            int start = json.indexOf("\"version_number\":\"") + 18;
            if (start <= 18) return null;

            int end = json.indexOf("\"", start);
            if (end == -1) return null;

            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
    public void removeEntitiesInChunk(Player player, ChunkCoordinate coord) {
        World world = getServer().getWorld(coord.world());
        if (world == null) return;

        Chunk chunk = world.getChunkAt(coord.x(), coord.z());
        if (!chunk.isLoaded()) return;

        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
                removed++;
            }
        }

        if (removed > 0) {
            player.sendMessage(ChatColor.GREEN + getMessage("chunk.entities_removed", "Entities removed in chunk {coord}")
                    .replace("{coord}", coord.toDisplayString()));
            getLogger().info(getMessage("chunk.entities_removed_log", "Removed {count} entities in chunk: {coord}")
                    .replace("{count}", String.valueOf(removed))
                    .replace("{coord}", coord.toDisplayString()));
        } else {
            player.sendMessage(ChatColor.YELLOW + getMessage("chunk.no_entities", "No entities to remove in chunk {coord}")
                    .replace("{coord}", coord.toDisplayString()));
        }
    }
    public Map<ChunkCoordinate, ChunkData> getChunkMap() {
        return chunkMap;
    }

    public int getMaxRedstone() {
        return maxRedstone;
    }

    public int getMaxEntities() {
        return maxEntities;
    }

    public int getNotificationCooldown() {
        return getConfig().getInt("notification-cooldown", 300);
    }

    public int getItemsPerPage() {
        return 45;
    }

    public void openChunkDetails(Player player, ChunkCoordinate coord) {
        World world = getServer().getWorld(coord.world());
        if (world == null) {
            player.sendMessage(ChatColor.RED + getMessage("chunk.world_not_found", "World '{world}' not found!").replace("{world}", coord.world()));
            return;
        }

        Chunk chunk = world.getChunkAt(coord.x(), coord.z());
        if (!chunk.isLoaded()) {
            chunk.load();
        }

        Map<Material, Integer> redstoneComponents = new HashMap<>();
        Map<String, Integer> entityTypes = new HashMap<>();

        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (isRedstoneComponent(block.getType())) {
                        redstoneComponents.merge(block.getType(), 1, Integer::sum);
                    }
                }
            }
        }

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) continue;
            String name = entity.getType().name()
                    .toLowerCase()
                    .replace("_", " ");
            String capitalized = Arrays.stream(name.split(" "))
                    .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1))
                    .collect(Collectors.joining(" "));
            entityTypes.merge(capitalized, 1, Integer::sum);
        }

        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GOLD + "═════ " + ChatColor.YELLOW + "Chunk Details " + coord.toDisplayString() + ChatColor.GOLD + " ═════");


        int totalRedstone = redstoneComponents.values().stream().mapToInt(Integer::intValue).sum();
        if (totalRedstone > 0) {
            lines.add(ChatColor.RED + "Redstone Components: " + ChatColor.WHITE + totalRedstone);
            redstoneComponents.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .forEach(entry -> {
                        String name = entry.getKey().name().toLowerCase().replace("_", " ");
                        String pretty = Arrays.stream(name.split(" "))
                                .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1))
                                .collect(Collectors.joining(" "));
                        lines.add(ChatColor.GRAY + "  • " + ChatColor.WHITE + entry.getValue() + " × " + pretty);
                    });
        } else {
            lines.add(ChatColor.GREEN + "Redstone Components: " + ChatColor.GRAY + "none");
        }

        lines.add("");

        int totalEntities = entityTypes.values().stream().mapToInt(Integer::intValue).sum();
        if (totalEntities > 0) {
            lines.add(ChatColor.GREEN + "Entities: " + ChatColor.WHITE + totalEntities);
            entityTypes.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .forEach(entry -> {
                        lines.add(ChatColor.GRAY + "  • " + ChatColor.WHITE + entry.getValue() + " × " + entry.getKey());
                    });
        } else {
            lines.add(ChatColor.GREEN + "Entities: " + ChatColor.GRAY + "none");
        }

        playerChunkDetailsLines.put(player.getUniqueId(), lines);
        playerChunkDetailsPage.put(player.getUniqueId(), 0);
        playerChunkDetailsTitle.put(player.getUniqueId(), "Chunk Details " + coord.toDisplayString());

        ChatListener.waitingForChunkSearch.put(player.getUniqueId(), "PAGINATION");

        showChunkDetailsPage(player, 0);
    }
    private void showChunkDetailsPage(Player player, int page) {
        List<String> lines = playerChunkDetailsLines.get(player.getUniqueId());
        String title = playerChunkDetailsTitle.get(player.getUniqueId());
        if (lines == null || title == null) {
            player.sendMessage(ChatColor.RED + getMessage("chunk.details.error_display", "Error displaying chunk details."));
            return;
        }

        int linesPerPage = 15;
        int totalPages = (int) Math.ceil((double) lines.size() / linesPerPage);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        playerChunkDetailsPage.put(player.getUniqueId(), page);

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "═════ " + ChatColor.AQUA + title +
                ChatColor.GRAY + " (Page " + (page + 1) + "/" + totalPages + ")" + ChatColor.GOLD + " ═════");

        int start = page * linesPerPage;
        int end = Math.min(start + linesPerPage, lines.size());
        for (int i = start; i < end; i++) {
            player.sendMessage(lines.get(i));
        }

        if (totalPages > 1) {
            player.sendMessage("");

            String prevCommand = "/rdpage " + player.getUniqueId() + " prev";
            String nextCommand = "/rdpage " + player.getUniqueId() + " next";

            net.md_5.bungee.api.chat.TextComponent message = new net.md_5.bungee.api.chat.TextComponent();

            if (page > 0) {
                net.md_5.bungee.api.chat.TextComponent prev = new net.md_5.bungee.api.chat.TextComponent(
                        getMessage("chunk.details.button_back", "< Back "));
                prev.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, prevCommand));
                prev.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.TextComponent[]{
                                new net.md_5.bungee.api.chat.TextComponent(
                                        getMessage("chunk.details.hover_back", "Click to go back"))
                        }));
                message.addExtra(prev);
            }

            net.md_5.bungee.api.chat.TextComponent separator = new net.md_5.bungee.api.chat.TextComponent(
                    getMessage("chunk.details.button_separator", " | "));
            message.addExtra(separator);

            if (page < totalPages - 1) {
                net.md_5.bungee.api.chat.TextComponent next = new net.md_5.bungee.api.chat.TextComponent(
                        getMessage("chunk.details.button_next", "Next >"));
                next.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, nextCommand));
                next.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.TextComponent[]{
                                new net.md_5.bungee.api.chat.TextComponent(
                                        getMessage("chunk.details.hover_next", "Click to go next"))
                        }));
                message.addExtra(next);
            }

            player.spigot().sendMessage(message);

            player.sendMessage(getMessage("chunk.details.navigation_hint",
                    "Click on the buttons above to navigate • or /rdcancel to exit"));
        } else {
            player.sendMessage(getMessage("chunk.details.end_of_list",
                    "End of list • /rdcancel to exit"));
        }
    }

    public void nextChunkDetailsPage(Player player) {
        Integer current = playerChunkDetailsPage.get(player.getUniqueId());
        if (current == null) return;
        showChunkDetailsPage(player, current + 1);
    }

    public void prevChunkDetailsPage(Player player) {
        Integer current = playerChunkDetailsPage.get(player.getUniqueId());
        if (current == null) return;
        showChunkDetailsPage(player, current - 1);
    }

    public void teleportToChunk(Player player, ChunkCoordinate coord) {
        World world = getServer().getWorld(coord.world());
        if (world != null) {
            Location loc = new Location(
                    world,
                    coord.x() * 16 + 8,
                    world.getHighestBlockYAt(coord.x() * 16 + 8, coord.z() * 16 + 8) + 1,
                    coord.z() * 16 + 8
            );
            player.teleport(loc);
            player.sendMessage(ChatColor.GREEN + getMessage("chunk.teleport_success", "Teleported to chunk {coord}").replace("{coord}", coord.toDisplayString()));
        } else {
            player.sendMessage(ChatColor.RED + getMessage("chunk.world_not_found", "World '{world}' not found!").replace("{world}", coord.world()));
        }
    }

    public void disableRedstoneInChunk(Player player, ChunkCoordinate coord) {
        disableRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage("chunk.redstone_removed", "Redstone removed in chunk {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void disableRedstoneInChunk(ChunkCoordinate coord, String initiator) {
        World world = getServer().getWorld(coord.world());
        if (world == null) return;

        Chunk chunk = world.getChunkAt(coord.x(), coord.z());
        if (!chunk.isLoaded()) return;

        Map<Location, Material> backup = new HashMap<>();
        int removed = 0;

        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (isRedstoneComponent(block.getType())) {
                        backup.put(block.getLocation(), block.getType());
                        block.setType(Material.AIR);
                        removed++;
                    }
                }
            }
        }

        if (removed > 0) {
            redstoneBackups.put(coord, backup);

            ChunkData data = chunkMap.get(coord);
            if (data != null) {
                data.clearedByAdmin = true;
                saveChunkData();
                data.clearedTime = System.currentTimeMillis();
                Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                    @Override
                    public void run() {
                        chunkMap.remove(coord);
                    }
                }, 20 * 60 * 10);
            }
            getLogger().info(getMessage("chunk.redstone_removed_log", "Removed {count} redstone blocks in chunk: {coord}")
                    .replace("{count}", String.valueOf(removed))
                    .replace("{coord}", coord.toDisplayString()));
        }
    }

    public void restoreRedstoneInChunk(Player player, ChunkCoordinate coord) {
        restoreRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage("chunk.redstone_restored", "Redstone restored in chunk {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void restoreRedstoneInChunk(ChunkCoordinate coord, String initiator) {
        Map<Location, Material> backup = redstoneBackups.get(coord);
        if (backup == null || backup.isEmpty()) {
            return;
        }

        int restored = 0;
        for (Map.Entry<Location, Material> entry : backup.entrySet()) {
            Block block = entry.getKey().getBlock();
            if (block.isEmpty()) {
                block.setType(entry.getValue());
                restored++;
            }
        }

        redstoneBackups.remove(coord);
        getLogger().info(getMessage("chunk.redstone_restored_log", "Redstone restored in chunk: {coord}").replace("{coord}", coord.toDisplayString()));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (updateAvailable && (player.hasPermission("redstonedetector.admin") || player.isOp())) {
            String updateMessage = getMessage("update.available", "&aAvailable update &e{plugin}&a! Current: &f{current} &a→ New: &f{new} &a| &b{url}")
                    .replace("{plugin}", PLUGIN_NAME)
                    .replace("{current}", CURRENT_VERSION)
                    .replace("{new}", latestModrinthVersion)
                    .replace("{url}", "https://modrinth.com/plugin/redstonedetector/versions");
            player.sendMessage(updateMessage);
        }
    }
}