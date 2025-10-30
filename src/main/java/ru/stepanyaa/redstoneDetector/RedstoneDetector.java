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

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bstats.bukkit.Metrics;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RedstoneDetector extends JavaPlugin implements Listener, TabCompleter {

    public record ChunkCoordinate(String world, int x, int z) {
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
    private static final String CURRENT_VERSION = "1.0.0";
    private boolean isFirstEnable = true;

    private FileConfiguration messagesConfig;
    private File messagesFile;
    private String language;
    private static final String[] SUPPORTED_LANGUAGES = {"en", "ru"};

    @Override
    public void onEnable() {
        // Сначала загружаем сообщения
        loadMessages();

        getLogger().info(getMessage("plugin.startup", "======== RedstoneDetector STARTING ========"));
        saveDefaultConfig();
        reloadConfig();
        loadConfig();

        updateConfigFile();
        updateMessagesFiles();

        chunkDataFile = new File(getDataFolder(), "chunk-data.yml");
        loadChunkData();

        initializeRedstoneMaterials();

        // Теперь инициализируем guiManager после загрузки сообщений
        this.guiManager = new GuiManager(this);
        guiManager.loadPlayerStates();


        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        registerCommands();
        startOptimizedChunkScanTask();
        startAutoSaveTask();

        getLogger().info(getMessage("plugin.enabled", "Plugin successfully enabled!"));
        this.isFirstEnable = false;
        int pluginId = 27778;
        Metrics metrics = new Metrics(this, pluginId);
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
                // Сохраняем новый файл
                saveResource("config.yml", true);
                getLogger().info(getMessage("warning.config-file-updated", "Updated config.yml to version %version%.")
                        .replace("%version%", CURRENT_VERSION));

                // Устанавливаем версию в новом файле
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
                    // Сохраняем новый файл
                    saveResource(fileName, true);
                    getLogger().info(getMessage("warning.messages-file-updated", "Updated messages file %file% to version %version%.")
                            .replace("%file%", fileName)
                            .replace("%version%", CURRENT_VERSION));

                    // Устанавливаем версию в новом файле
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

        // Инициализируем messagesConfig пустой конфигурацией по умолчанию
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
        // Добавляем дополнительную проверку на null
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
        // Добавляем проверки на null для всех компонентов
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
        if (command.equals("redstonedetector") || command.equals("rd")) {
            if (args.length == 0) {
                return openGuiCommand(sender);
            }
            return handleSubCommand(sender, args);
        }
        return false;
    }

    private boolean handleSubCommand(CommandSender sender, String[] args) {
        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "reload" -> reloadCommand(sender);
            case "gui" -> openGuiCommand(sender);
            case "redstone" -> redstoneCommand(sender, args);
            case "stopredstone" -> stopRedstoneCommand(sender);
            case "scan" -> scanCommand(sender);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean openGuiCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + getMessage("command.player_only", "This command is for players only!"));
            return true;
        }
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
        switch (args[1].toLowerCase()) {
            case "freeze" -> {
                setFreezeRedstone(true, sender.getName());
                sender.sendMessage(ChatColor.GREEN + getMessage("command.redstone_frozen", "Redstone frozen!"));
            }
            case "unfreeze" -> {
                setFreezeRedstone(false, sender.getName());
                monitoringEnabled = true;
                sender.sendMessage(ChatColor.GREEN + getMessage("command.redstone_unfrozen", "Redstone unfrozen!"));
            }
            case "status" -> sender.sendMessage(ChatColor.YELLOW + getMessage("command.redstone_status", "Redstone status: {status}").replace("{status}", (freezeRedstone ? ChatColor.RED + getMessage("command.redstone_status_frozen", "FROZEN") : ChatColor.GREEN + getMessage("command.redstone_status_active", "ACTIVE"))));
            default -> sender.sendMessage(ChatColor.RED + getMessage("command.redstone_usage", "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
        }
        return true;
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
                        Bukkit.getScheduler().runTaskLater(this, () -> chunkMap.remove(coord),
                                (600000 - (currentTime - data.clearedTime)) / 50);
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
                getLogger().info(getMessage("data.autosave", "Data automatically saved"));
            }
        }.runTaskTimer(this, 20 * 60 * 5, 20 * 60 * 5);
    }

    private void startOptimizedChunkScanTask() {
        new BukkitRunnable() {
            private final Queue<Chunk> chunkQueue = new LinkedList<>();
            private boolean wasLowTPS = false;
            private long lastTPSCheck = 0;
            private final long TPS_CHECK_INTERVAL = 1000;

            @Override
            public void run() {
                if (!monitoringEnabled) return;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastTPSCheck < TPS_CHECK_INTERVAL) {
                    return;
                }

                lastTPSCheck = currentTime;
                double currentTPS = 20.0;
                try {
                    double[] recentTps = Bukkit.getTPS();
                    if (recentTps != null && recentTps.length > 0) {
                        currentTPS = recentTps[0];
                    }
                } catch (Exception e) {
                    getLogger().warning(getMessage("tps.error", "Error retrieving TPS: ") + e.getMessage());
                }

                boolean criticalState = currentTPS < criticalTPS;

                if (criticalState) {
                    if (firstCriticalState) {
                        firstCriticalState = false;
                        forceFullRedstoneScan();

                        if (System.currentTimeMillis() - lastTPSWarning > TPS_WARNING_COOLDOWN) {
                            lastTPSWarning = System.currentTimeMillis();
                        }
                    }

                    wasLowTPS = true;

                    if (Math.abs(currentTPS - lastReportedTPS) > 1.0 &&
                            System.currentTimeMillis() - lastTPSWarning > TPS_WARNING_COOLDOWN) {
                        lastTPSWarning = System.currentTimeMillis();
                        lastReportedTPS = currentTPS;
                        getLogger().warning(getMessage("tps.critical", "Critical TPS: ") + currentTPS);
                    }

                    if (!freezeRedstone) {
                        setFreezeRedstone(true, "System");
                    }
                    lastFreezeTime = System.currentTimeMillis();

                    if (chunkQueue.isEmpty()) {
                        for (World world : getServer().getWorlds()) {
                            for (Chunk chunk : world.getLoadedChunks()) {
                                if (chunk.isLoaded()) {
                                    chunkQueue.add(chunk);
                                }
                            }
                        }
                    }

                    for (int i = 0; i < chunksPerTick && !chunkQueue.isEmpty(); i++) {
                        Chunk chunk = chunkQueue.poll();
                        if (chunk != null && chunk.isLoaded()) {
                            scanChunk(chunk);
                        }
                    }
                } else if (wasLowTPS) {
                    wasLowTPS = false;
                    firstCriticalState = true;
                    chunkQueue.clear();

                    long elapsed = currentTime - lastFreezeTime;
                    long freezeDuration = getConfig().getInt("freeze-duration", 60) * 1000L;

                    if (freezeRedstone && elapsed >= freezeDuration) {
                        setFreezeRedstone(false, "System");
                        getLogger().info(getMessage("tps.recovered", "Auto-unfreeze: TPS restored to ") + currentTPS);
                    }
                }
            }
        }.runTaskTimer(this, 100, 1);
    }

    private void forceFullRedstoneScan() {
        getLogger().info(getMessage("chunk.scan_forced", "Forced scanning of all chunks due to low TPS"));
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
        ChunkData data = chunkMap.computeIfAbsent(coord, k -> new ChunkData());

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

        entityCount = (int) Arrays.stream(chunk.getEntities())
                .filter(e -> !(e instanceof Player))
                .count();

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
        ChunkData data = chunkMap.get(coord);
        if (data != null) {
            player.sendMessage(ChatColor.GOLD + getMessage("chunk.details.header", "Chunk Details {coord}").replace("{coord}", coord.toDisplayString()));
            player.sendMessage(ChatColor.GRAY + getMessage("chunk.details.world", "World: {world}").replace("{world}", coord.world));
            player.sendMessage(ChatColor.RED + getMessage("chunk.details.redstone", "Redstone: {count}").replace("{count}", String.valueOf(data.redstoneCount.get())));
            player.sendMessage(ChatColor.GREEN + getMessage("chunk.details.entities", "Entities: {count}").replace("{count}", String.valueOf(data.entityCount.get())));
        } else {
            player.sendMessage(ChatColor.RED + getMessage("chunk.details.not_found", "Chunk data not found!"));
        }
    }

    public void teleportToChunk(Player player, ChunkCoordinate coord) {
        World world = getServer().getWorld(coord.world);
        if (world != null) {
            Location loc = new Location(
                    world,
                    coord.x * 16 + 8,
                    world.getHighestBlockYAt(coord.x * 16 + 8, coord.z * 16 + 8) + 1,
                    coord.z * 16 + 8
            );
            player.teleport(loc);
            player.sendMessage(ChatColor.GREEN + getMessage("chunk.teleport_success", "Teleported to chunk {coord}").replace("{coord}", coord.toDisplayString()));
        } else {
            player.sendMessage(ChatColor.RED + getMessage("chunk.world_not_found", "World '{world}' not found!").replace("{world}", coord.world));
        }
    }

    public void disableRedstoneInChunk(Player player, ChunkCoordinate coord) {
        disableRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage("chunk.redstone_removed", "Redstone removed in chunk {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void disableRedstoneInChunk(ChunkCoordinate coord, String initiator) {
        World world = getServer().getWorld(coord.world);
        if (world == null) return;

        Chunk chunk = world.getChunkAt(coord.x, coord.z);
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
                data.clearedTime = System.currentTimeMillis();
                Bukkit.getScheduler().runTaskLater(this, () -> chunkMap.remove(coord), 20 * 60 * 10);
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
    }
}