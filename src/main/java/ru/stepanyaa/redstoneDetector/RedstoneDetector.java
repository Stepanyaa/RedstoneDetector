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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.util.Comparator;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bstats.bukkit.Metrics;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RedstoneDetector extends JavaPlugin implements Listener, TabCompleter {
    private static class SnapshotContainer {
        String worldName;
        int x, z, minY, maxY;
        ChunkSnapshot snapshot;
        List<EntityType> entities;

        SnapshotContainer(String w, int x, int z, ChunkSnapshot s, int min, int max, List<EntityType> entities) {
            this.worldName = w; this.x = x; this.z = z; this.snapshot = s;
            this.minY = min; this.maxY = max; this.entities = entities;
        }
    }

    private ConfigManager configManager;
    private MessageManager messageManager;
    private TPSMonitor tpsMonitor;
    private UpdateChecker updateChecker;
    private GuiManager guiManager;

    private static boolean isPurpur = false;
    private int lowTpsCounter = 0;

    private final Map<ChunkCoordinate, ChunkData> chunkMap = new ConcurrentHashMap<>();
    private final Map<ChunkCoordinate, Map<Location, Material>> redstoneBackups = new ConcurrentHashMap<>();
    private final Set<Material> redstoneMaterials = new HashSet<>();

    private boolean freezeRedstone = false;
    private boolean manualFreezeOverride = false;
    private long freezeStartTime = 0;

    private boolean monitoringEnabled = true;

    public final Map<UUID, List<String>> playerChunkDetailsLines = new HashMap<>();
    public final Map<UUID, Integer> playerChunkDetailsPage = new HashMap<>();
    public final Map<UUID, String> playerChunkDetailsTitle = new HashMap<>();

    private File chunkDataFile;
    private YamlConfiguration chunkDataConfig;

    private boolean bungeeApiAvailable = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        this.messageManager = new MessageManager(this);
        messageManager.loadMessages();
        messageManager.updateMessagesFiles();

        getLogger().info(getMessage("plugin.startup", "======== RedstoneDetector STARTING ========"));

        this.tpsMonitor = new TPSMonitor(this);
        this.updateChecker = new UpdateChecker(this);
        this.guiManager = new GuiManager(this);

        chunkDataFile = new File(getDataFolder(), "chunk-data.yml");
        loadChunkData();

        initializeRedstoneMaterials();

        guiManager.loadPlayerStates();

        checkBungeeApi();

        updateChecker.checkForUpdates();

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        registerCommands();

        startOptimizedChunkScanTask();
        startAutoSaveTask();
        startCleanupTask();
        try {
            Class.forName("org.purpurmc.purpur.PurpurConfig");
            isPurpur = true;
        } catch (ClassNotFoundException e) {
            isPurpur = false;
        }

        int pluginId = 27778;
        new Metrics(this, pluginId);

        getLogger().info(getMessage("plugin.enabled", "Plugin successfully enabled!"));
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.savePlayerStates();
        }
        saveChunkData();
        getLogger().info(getMessage("plugin.shutdown", "GUI states and chunk data saved"));
    }

    private void initializeRedstoneMaterials() {
        redstoneMaterials.clear();

        Material[] materials = {
                Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR,
                Material.PISTON, Material.STICKY_PISTON, Material.OBSERVER,
                Material.DISPENSER, Material.DROPPER, Material.HOPPER,
                Material.REDSTONE_TORCH, Material.REDSTONE_BLOCK, Material.LEVER,
                Material.STONE_BUTTON, Material.OAK_BUTTON, Material.TRIPWIRE_HOOK,
                Material.TARGET
        };

        Collections.addAll(redstoneMaterials, materials);
        addMaterialIfExists("SCULK_SENSOR");
        addMaterialIfExists("CALIBRATED_SCULK_SENSOR");
    }

    private void addMaterialIfExists(String materialName) {
        Material mat = Material.getMaterial(materialName);
        if (mat != null) {
            redstoneMaterials.add(mat);
        }
    }

    private boolean isRedstoneComponent(Material material) {
        return redstoneMaterials.contains(material);
    }

    private void checkBungeeApi() {
        try {
            Class.forName("net.md_5.bungee.api.chat.TextComponent");
            bungeeApiAvailable = true;
        } catch (ClassNotFoundException e) {
            bungeeApiAvailable = false;
            getLogger().info("BungeeCord Chat API not found. Interactive buttons disabled.");
        }
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("redstonedetector")).setExecutor(this);
        Objects.requireNonNull(getCommand("redstonedetector")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("rd")).setExecutor(this);
        Objects.requireNonNull(getCommand("rd")).setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String command = cmd.getName().toLowerCase();
        if (label.equalsIgnoreCase("rdpage") && args.length == 2) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            if (!player.hasPermission("redstonedetector.admin")) return true;

            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(args[0]);
            } catch (IllegalArgumentException e) {
                return true;
            }

            if (!targetUuid.equals(player.getUniqueId())) return true;

            String dir = args[1].toLowerCase();
            if (dir.equals("prev") || dir.equals("back") || dir.equals("назад")) {
                prevChunkDetailsPage(player);
            } else if (dir.equals("next") || dir.equals("далее")) {
                nextChunkDetailsPage(player);
            }
            return true;
        }
        if (command.equalsIgnoreCase("rdcancel")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (player.hasPermission("redstonedetector.admin")) {
                    ChatListener.cancelSearch(player);
                }
            } else {
                sender.sendMessage(ChatColor.RED + getMessage("command.player_only",
                        "This command is for players only!"));
            }
            return true;
        }
        if (command.equals("redstonedetector") || command.equals("rd")) {
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + getMessage("command.player_only",
                            "This command is for players only!"));
                    return true;
                }
                Player player = (Player) sender;
                if (!player.hasPermission("redstonedetector.admin")) {
                    player.sendMessage(ChatColor.RED + getMessage("command.no_permission_gui",
                            "You do not have permission to use the GUI!"));
                    return true;
                }
                return openGuiCommand(player);
            }
            return handleSubCommand(sender, args);
        }

        return false;
    }

    private boolean handleSubCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload":
                return handleReload(sender);
            case "gui":
                return handleGui(sender);
            case "redstone":
                return handleRedstone(sender, args);
            case "stopredstone":
                return handleStopRedstone(sender);
            case "scan":
                return handleScan(sender);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("redstonedetector.admin")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_reload",
                    "You do not have permission to reload the plugin!"));
            return true;
        }

        reloadConfig();
        configManager.loadConfig();
        messageManager.loadMessages();
        messageManager.updateMessagesFiles();

        sender.sendMessage(ChatColor.GREEN + getMessage("command.reload_success",
                "Configuration reloaded!"));

        String byWho = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        getLogger().info("Configuration reloaded by " + byWho);

        return true;
    }

    private boolean handleGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage("command.player_only",
                    "This command is for players only!"));
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("redstonedetector.admin")) {
            player.sendMessage(ChatColor.RED + getMessage("command.no_permission_gui",
                    "You do not have permission to use the GUI!"));
            return true;
        }

        return openGuiCommand(player);
    }

    private boolean handleRedstone(CommandSender sender, String[] args) {
        if (!sender.hasPermission("redstonedetector.admin")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_redstone",
                    "You do not have permission to manage redstone!"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + getMessage("command.redstone_usage",
                    "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
            return true;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "freeze":
                freezeRedstone = true;
                manualFreezeOverride = true;
                sender.sendMessage(ChatColor.GREEN + getMessage("command.redstone_frozen",
                        "Redstone frozen!"));
                getLogger().info(getMessage("redstone.frozen_log", "Redstone frozen!"));
                break;

            case "unfreeze":
                freezeRedstone = false;
                manualFreezeOverride = false;
                sender.sendMessage(ChatColor.GREEN + getMessage("command.redstone_unfrozen",
                        "Redstone unfrozen!"));
                getLogger().info(getMessage("redstone.unfrozen_log", "Redstone unfrozen!"));
                break;

            case "status":
                String status = freezeRedstone ?
                        getMessage("command.redstone_status_frozen", "FROZEN") :
                        getMessage("command.redstone_status_active", "ACTIVE");
                sender.sendMessage(ChatColor.YELLOW + getMessage("command.redstone_status",
                        "Redstone status: {status}").replace("{status}", status));
                break;

            default:
                sender.sendMessage(ChatColor.RED + getMessage("command.redstone_usage",
                        "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
        }
        return true;
    }

    private boolean handleStopRedstone(CommandSender sender) {
        if (!sender.hasPermission("redstonedetector.admin")) {
            sender.sendMessage(ChatColor.RED + getMessage("command.no_permission_redstone",
                    "You do not have permission to manage redstone!"));
            return true;
        }

        freezeRedstone = true;
        manualFreezeOverride = true;

        sender.sendMessage(ChatColor.GOLD + getMessage("command.redstone_frozen",
                "Redstone signals have been frozen (Blocks are safe)!"));
        return true;
    }

    private boolean handleScan(CommandSender sender) {
        if (isHybridServer()) {
            runHybridScan();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, this::scanAllChunks);
        }
        sender.sendMessage(ChatColor.GREEN + getMessage( "command.scan_started","Scanning started..."));
        return true;
    }

    private boolean openGuiCommand(Player player) {
        guiManager.openWorldSelectionGUI(player);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + getMessage("command.help_header",
                "=== RedstoneDetector Help ==="));

        if (sender.hasPermission("redstonedetector.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector gui" + ChatColor.WHITE +
                    getMessage("command.help_gui", " - Open the interface"));
        }
        if (sender.hasPermission("redstonedetector.reload")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector reload" + ChatColor.WHITE +
                    getMessage("command.help_reload", " - Reload the configuration"));
        }
        if (sender.hasPermission("redstonedetector.redstone")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector redstone freeze" + ChatColor.WHITE +
                    getMessage("command.help_redstone_freeze", " - Freeze redstone"));
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector redstone unfreeze" + ChatColor.WHITE +
                    getMessage("command.help_redstone_unfreeze", " - Unfreeze redstone"));
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector redstone status" + ChatColor.WHITE +
                    getMessage("command.help_redstone_status", " - Redstone status"));
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector stopredstone" + ChatColor.WHITE +
                    getMessage("command.help_stopredstone", " - Emergency stop"));
        }
        if (sender.hasPermission("redstonedetector.scan")) {
            sender.sendMessage(ChatColor.YELLOW + "/redstonedetector scan" + ChatColor.WHITE +
                    getMessage("command.help_scan", " - Force chunk scan"));
        }

        sender.sendMessage(ChatColor.GOLD + getMessage("command.help_aliases", "Aliases: ") +
                ChatColor.YELLOW + "/rd");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("redstonedetector") || cmd.getName().equalsIgnoreCase("rd")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                if (sender.hasPermission("redstonedetector.admin")) completions.add("gui");
                if (sender.hasPermission("redstonedetector.reload")) completions.add("reload");
                if (sender.hasPermission("redstonedetector.admin")) completions.add("help");
                if (sender.hasPermission("redstonedetector.redstone")) {
                    completions.add("redstone");
                    completions.add("stopredstone");
                }
                if (sender.hasPermission("redstonedetector.scan")) completions.add("scan");
                return completions;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("redstone")) {
                return Arrays.asList("freeze", "unfreeze", "status");
            }
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + getMessage("redstone.break_blocked",
                    "Redstone is frozen! You cannot break blocks."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + getMessage("redstone.place_blocked",
                    "Redstone is frozen! You cannot place blocks."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstoneEvent(BlockRedstoneEvent event) {
        if (freezeRedstone) {
            event.setNewCurrent(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (freezeRedstone) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (freezeRedstone) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        updateChecker.notifyPlayer(event.getPlayer());
    }

    private void startOptimizedChunkScanTask() {
        new BukkitRunnable() {
            private int worldIndex = 0;
            private int chunkIndex = 0;
            private long lastTpsCheck = 0;

            @Override
            public void run() {
                if (isPurpur && Bukkit.getOnlinePlayers().size() == 0) {
                    return;
                }
                long now = System.currentTimeMillis();

                if (now - lastTpsCheck > 2000) {
                    lastTpsCheck = now;
                    double currentTps = tpsMonitor.getTPS();

                    if (configManager.isScanOnLowTPS()) {
                        if (currentTps < configManager.getCriticalTPS()) {
                            if (isPurpur) {
                                lowTpsCounter++;
                                if (lowTpsCounter >= 5 && !freezeRedstone) {
                                    freezeRedstone = true;
                                    manualFreezeOverride = false;
                                    freezeStartTime = now;
                                    getLogger().warning("LOW TPS (Purpur 10s confirmed): " + String.format("%.2f", currentTps) +
                                            ". Redstone frozen!");
                                }
                            } else {
                                if (!freezeRedstone) {
                                    freezeRedstone = true;
                                    manualFreezeOverride = false;
                                    freezeStartTime = now;
                                    getLogger().warning("LOW TPS: " + String.format("%.2f", currentTps) +
                                            ". Redstone frozen!");
                                }
                            }
                        } else {
                            lowTpsCounter = 0;
                        }
                    }

                    if (freezeRedstone && !manualFreezeOverride) {
                        int duration = configManager.getFreezeDuration();
                        if (duration > 0 && (now - freezeStartTime) / 1000 >= duration) {
                            if (currentTps >= configManager.getCriticalTPS()) {
                                freezeRedstone = false;
                                lowTpsCounter = 0;
                                getLogger().info(getMessage("tps.recovered",
                                        "Auto-unfreeze: TPS restored to ") + String.format("%.2f", currentTps));
                            }
                        }
                    }
                }

                if (!monitoringEnabled || tpsMonitor.getTPS() >= configManager.getCriticalTPS()) {
                    return;
                }

                List<World> worlds = Bukkit.getWorlds();
                if (worlds.isEmpty()) return;

                if (worldIndex >= worlds.size()) {
                    worldIndex = 0;
                }
                World world = worlds.get(worldIndex);

                Chunk[] chunks = world.getLoadedChunks();
                if (chunks.length == 0) {
                    worldIndex++;
                    return;
                }

                int chunksToScan = Math.min(configManager.getChunksPerTick(), chunks.length - chunkIndex);
                for (int i = 0; i < chunksToScan; i++) {
                    if (chunkIndex >= chunks.length) {
                        chunkIndex = 0;
                        worldIndex++;
                        if (worldIndex >= worlds.size()) {
                            worldIndex = 0;
                        }
                        break;
                    }

                    Chunk chunk = chunks[chunkIndex++];
                    ChunkCoordinate coord = new ChunkCoordinate(world.getName(), chunk.getX(), chunk.getZ());

                    ChunkData existingData = chunkMap.get(coord);
                    if (existingData != null && now - existingData.lastScanned < 30000) {
                        continue;
                    }

                    scanChunk(chunk);
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }
    private void scanChunk(Chunk chunk) {
        ChunkCoordinate coord = new ChunkCoordinate(
                chunk.getWorld().getName(),
                chunk.getX(),
                chunk.getZ()
        );

        ChunkData data = chunkMap.computeIfAbsent(coord, k -> new ChunkData());
        data.lastScanned = System.currentTimeMillis();

        int redstoneFound = 0;
        int entitiesFound = 0;
        data.redstoneTypes.clear();
        data.entityTypes.clear();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = chunk.getWorld().getMinHeight(); y < chunk.getWorld().getMaxHeight(); y++) {
                    Block block = chunk.getBlock(x, y, z);
                    Material mat = block.getType();

                    if (redstoneMaterials.contains(mat)) {
                        redstoneFound++;
                        data.redstoneTypes
                                .computeIfAbsent(mat, m -> new AtomicInteger(0))
                                .incrementAndGet();
                    }
                }
            }
        }
        for (Entity entity : chunk.getEntities()) {
            EntityType type = entity.getType();
            entitiesFound++;
            data.entityTypes
                    .computeIfAbsent(type, t -> new AtomicInteger(0))
                    .incrementAndGet();
        }

        data.redstoneCount.set(redstoneFound);
        data.entityCount.set(entitiesFound);
    }

    private void scanAllChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk);
            }
        }
        Bukkit.getScheduler().runTask(this, this::saveChunkData);
    }

    private void runHybridScan() {
        List<SnapshotContainer> snapshots = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                List<EntityType> entityTypes = new ArrayList<>();
                for (Entity entity : chunk.getEntities()) {
                    entityTypes.add(entity.getType());
                }

                snapshots.add(new SnapshotContainer(
                        world.getName(),
                        chunk.getX(),
                        chunk.getZ(),
                        chunk.getChunkSnapshot(),
                        world.getMinHeight(),
                        world.getMaxHeight(),
                        entityTypes
                ));
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (SnapshotContainer container : snapshots) {
                processSnapshot(container);
            }
            saveChunkData();
        });
    }

    private void processSnapshot(SnapshotContainer data) {
        ChunkCoordinate coord = new ChunkCoordinate(data.worldName, data.x, data.z);
        ChunkData chunkData = new ChunkData();
        int redstoneCount = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = data.minY; y < data.maxY; y++) {
                    Material type = data.snapshot.getBlockType(x, y, z);
                    if (redstoneMaterials.contains(type)) {
                        redstoneCount++;
                        chunkData.redstoneTypes.computeIfAbsent(type, k ->
                                new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
                    }
                }
            }
        }
        if (data.entities != null && !data.entities.isEmpty()) {
            chunkData.entityCount.set(data.entities.size());
            for (EntityType type : data.entities) {
                chunkData.entityTypes.computeIfAbsent(type, k ->
                        new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
            }
        }
        if (redstoneCount > 0 || !data.entities.isEmpty()) {
            chunkData.redstoneCount.set(redstoneCount);
            chunkMap.put(coord, chunkData);
        }
    }


    private void loadChunkData() {
        try {
            if (!chunkDataFile.exists() && !chunkDataFile.createNewFile()) {
                getLogger().severe(getMessage("data.error_chunk_file",
                        "Failed to create chunk data file"));
            }
        } catch (IOException e) {
            getLogger().severe(getMessage("data.error_chunk_create",
                    "Error creating chunk data file: ") + e.getMessage());
        }

        chunkDataConfig = YamlConfiguration.loadConfiguration(chunkDataFile);
        chunkMap.clear();

        long currentTime = System.currentTimeMillis();
        long retentionTime = configManager.getChunkDataRetentionHours() * 3600000L;
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

                if (currentTime - data.lastScanned > retentionTime) {
                    chunkDataConfig.set(key, null);
                    changed = true;
                } else {
                    chunkMap.put(coord, data);

                    if (data.clearedByAdmin) {
                        long delay = 600000 - (currentTime - data.clearedTime);
                        if (delay > 0) {
                            Bukkit.getScheduler().runTaskLater(this, () -> chunkMap.remove(coord),
                                    delay / 50);
                        }
                    }
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

            long retentionPeriod = configManager.getChunkDataRetentionHours() * 3600000L;
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
            getLogger().severe(getMessage("data.error_chunk_save",
                    "Error saving chunk data: ") + e.getMessage());
        }
    }

    private void cleanupOldChunkData() {
        if (configManager.getChunkDataRetentionHours() <= 0) {
            getLogger().info(getMessage("data.retention_disabled",
                    "Chunk data retention disabled (chunk-data-retention: 0)"));
            return;
        }

        long cutoffTime = System.currentTimeMillis() -
                (configManager.getChunkDataRetentionHours() * 3600000L);
        int removedCount = 0;

        Iterator<Map.Entry<ChunkCoordinate, ChunkData>> iterator = chunkMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkCoordinate, ChunkData> entry = iterator.next();
            ChunkData data = entry.getValue();

            if (data.lastScanned < cutoffTime ||
                    (data.clearedByAdmin && data.clearedTime > 0 && data.clearedTime < cutoffTime)) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            saveChunkData();
            getLogger().info(getMessage("data.cleanup_log",
                    "Cleaned up {count} old chunk records (retention: {hours} hours)")
                    .replace("{count}", String.valueOf(removedCount))
                    .replace("{hours}", String.valueOf(configManager.getChunkDataRetentionHours())));
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

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOldChunkData();
            }
        }.runTaskTimer(this, 20L * 60 * 120, 20L * 60 * 120);
    }

    public void openChunkDetails(Player player, ChunkCoordinate coord) {
        ChunkData data = chunkMap.get(coord);
        if (data == null) {
            player.sendMessage(ChatColor.RED + getMessage("chunk.details.not_found", "Chunk data not found!"));
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add(getMessage("chunk.details.detailed_header", "&6═════ &eChunk Details {coord} &6═════")
                .replace("{coord}", coord.toDisplayString()));
        lines.add("");

        lines.add(ChatColor.GOLD + "Redstone Components (" + data.redstoneCount.get() + "):");
        if (data.redstoneCount.get() == 0) {
            lines.add(ChatColor.GRAY + "  none");
        } else {
            data.redstoneTypes.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<Material, AtomicInteger>>comparingInt(
                            entry -> entry.getValue().get()
                    ).reversed())
                    .forEach(entry -> {
                        String name = entry.getKey().name().toLowerCase().replace("_", " ");
                        name = name.substring(0, 1).toUpperCase() + name.substring(1);
                        lines.add(ChatColor.YELLOW + "  • " + name + ": " + ChatColor.WHITE + entry.getValue().get());
                    });
        }
        lines.add("");

        lines.add(ChatColor.GREEN + "Entities (" + data.entityCount.get() + "):");
        if (data.entityCount.get() == 0) {
            lines.add(ChatColor.GRAY + "  none");
        } else {
            data.entityTypes.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<EntityType, AtomicInteger>>comparingInt(
                            entry -> entry.getValue().get()
                    ).reversed())
                    .forEach(entry -> {
                        String name = entry.getKey().name().toLowerCase().replace("_", " ");
                        name = name.substring(0, 1).toUpperCase() + name.substring(1);
                        lines.add(ChatColor.YELLOW + "  • " + name + ": " + ChatColor.WHITE + entry.getValue().get());
                    });
        }

        lines.add("");
        if (bungeeApiAvailable) {
            lines.add(getMessage("chunk.details.navigation_hint", "&8Click on the buttons above to navigate • &7or &c/rdcancel &7to exit"));
        } else {
            lines.add(getMessage("chunk.details.page_navigation", "&eType &bnext &e/ &bback &e(or &bдалее &e/ &bназад&e) or &c/rdcancel"));
        }

        playerChunkDetailsLines.put(player.getUniqueId(), lines);
        playerChunkDetailsPage.put(player.getUniqueId(), 0);
        playerChunkDetailsTitle.put(player.getUniqueId(), coord.toString());

        showChunkDetailsPage(player, 0);
    }

    private void showChunkDetailsPage(Player player, int page) {
        List<String> lines = playerChunkDetailsLines.get(player.getUniqueId());
        if (lines == null || lines.isEmpty()) return;

        int linesPerPage = 10;
        int totalPages = (int) Math.ceil((double) lines.size() / linesPerPage);
        page = Math.max(0, Math.min(page, totalPages - 1));

        int start = page * linesPerPage;
        int end = Math.min(start + linesPerPage, lines.size());

        for (int i = start; i < end; i++) {
            player.sendMessage(lines.get(i));
        }

        if (bungeeApiAvailable && totalPages > 1) {
            String prevCmd = "/rdpage " + player.getUniqueId() + " prev";
            String nextCmd = "/rdpage " + player.getUniqueId() + " next";
            BungeeHandler.sendPagination(player, page, totalPages, prevCmd, nextCmd, this);
        } else if (totalPages > 1) {
            player.sendMessage(getMessage("chunk.details.page_footer", "&7< &bback &7| &bnext &7>"));
        }

        if (page >= totalPages - 1) {
            player.sendMessage(getMessage("chunk.details.end_of_list",
                    "&7End of list • &8/rdcancel &7to exit"));
        }
    }

    private void nextChunkDetailsPage(Player player) {
        Integer currentPage = playerChunkDetailsPage.get(player.getUniqueId());
        if (currentPage == null) return;

        List<String> lines = playerChunkDetailsLines.get(player.getUniqueId());
        if (lines == null) return;

        int linesPerPage = 10;
        int totalPages = (int) Math.ceil((double) lines.size() / linesPerPage);

        if (currentPage < totalPages - 1) {
            playerChunkDetailsPage.put(player.getUniqueId(), currentPage + 1);
            showChunkDetailsPage(player, currentPage + 1);
        }
    }

    private void prevChunkDetailsPage(Player player) {
        Integer currentPage = playerChunkDetailsPage.get(player.getUniqueId());
        if (currentPage == null || currentPage <= 0) return;

        playerChunkDetailsPage.put(player.getUniqueId(), currentPage - 1);
        showChunkDetailsPage(player, currentPage - 1);
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
            player.sendMessage(ChatColor.GREEN + getMessage("chunk.teleport_success",
                    "Teleported to chunk {coord}").replace("{coord}", coord.toDisplayString()));
        } else {
            player.sendMessage(ChatColor.RED + getMessage("chunk.world_not_found",
                    "World '{world}' not found!").replace("{world}", coord.world()));
        }
    }

    public void disableRedstoneInChunk(Player player, ChunkCoordinate coord) {
        disableRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage("chunk.redstone_removed",
                "Redstone removed in chunk {coord}").replace("{coord}", coord.toDisplayString()));
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
                data.clearedTime = System.currentTimeMillis();
                saveChunkData();

                Bukkit.getScheduler().runTaskLater(this, () -> chunkMap.remove(coord),
                        20 * 60 * 10);
            }

            getLogger().info(getMessage("chunk.redstone_removed_log",
                    "Removed {count} redstone blocks in chunk: {coord}")
                    .replace("{count}", String.valueOf(removed))
                    .replace("{coord}", coord.toDisplayString()));
        }
    }

    public void restoreRedstoneInChunk(Player player, ChunkCoordinate coord) {
        restoreRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage("chunk.redstone_restored",
                "Redstone restored in chunk {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void restoreRedstoneInChunk(ChunkCoordinate coord, String initiator) {
        Map<Location, Material> backup = redstoneBackups.get(coord);
        if (backup == null || backup.isEmpty()) return;

        int restored = 0;
        for (Map.Entry<Location, Material> entry : backup.entrySet()) {
            Block block = entry.getKey().getBlock();
            if (block.isEmpty()) {
                block.setType(entry.getValue());
                restored++;
            }
        }

        redstoneBackups.remove(coord);
        getLogger().info(getMessage("chunk.redstone_restored_log",
                "Redstone restored in chunk: {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void removeEntitiesInChunk(Player player, ChunkCoordinate coord) {
        World world = getServer().getWorld(coord.world());
        if (world == null) return;

        Chunk chunk = world.getChunkAt(coord.x(), coord.z());
        if (!chunk.isLoaded()) return;

        Entity[] entities = chunk.getEntities();
        int removed = 0;

        for (Entity entity : entities) {
            if (!(entity instanceof Player)) {
                entity.remove();
                removed++;
            }
        }

        if (removed > 0) {
            player.sendMessage(ChatColor.GREEN + getMessage("chunk.entities_removed",
                    "Entities removed in chunk {coord}").replace("{coord}", coord.toDisplayString()));
            getLogger().info(getMessage("chunk.entities_removed_log",
                    "Removed {count} entities in chunk: {coord}")
                    .replace("{count}", String.valueOf(removed))
                    .replace("{coord}", coord.toDisplayString()));
        } else {
            player.sendMessage(ChatColor.YELLOW + getMessage("chunk.no_entities",
                    "No entities to remove in chunk {coord}").replace("{coord}", coord.toDisplayString()));
        }
    }

    public void sendSearchPrompt(Player player) {
        if (bungeeApiAvailable) {
            BungeeHandler.sendSearchPrompt(player, this);
        } else {
            player.sendMessage(ChatColor.YELLOW + getMessage("chat.search.enter_coords",
                    "Enter chunk coordinates (X Z): ") + ChatColor.GRAY + " (e.g. 5 -3)");
            player.sendMessage(ChatColor.RED + getMessage("plugin.cancel", "Type /rdcancel to cancel"));
        }
    }

    private static class BungeeHandler {
        public static void sendPagination(Player player, int page, int total, String prevCmd,
                                          String nextCmd, RedstoneDetector plugin) {
            net.md_5.bungee.api.chat.TextComponent message = new net.md_5.bungee.api.chat.TextComponent();

            if (page > 0) {
                net.md_5.bungee.api.chat.TextComponent prev = new net.md_5.bungee.api.chat.TextComponent(
                        plugin.getMessage("chunk.details.button_back", "< Back "));
                prev.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, prevCmd));
                prev.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.hover.content.Text(
                                plugin.getMessage("chunk.details.hover_back", "Click to go back"))));
                message.addExtra(prev);
            }

            message.addExtra(new net.md_5.bungee.api.chat.TextComponent(
                    plugin.getMessage("chunk.details.button_separator", " | ")));

            if (page < total - 1) {
                net.md_5.bungee.api.chat.TextComponent next = new net.md_5.bungee.api.chat.TextComponent(
                        plugin.getMessage("chunk.details.button_next", " Next >"));
                next.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, nextCmd));
                next.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.hover.content.Text(
                                plugin.getMessage("chunk.details.hover_next", "Click to go next"))));
                message.addExtra(next);
            }

            player.spigot().sendMessage(message);
        }

        public static void sendSearchPrompt(Player player, RedstoneDetector plugin) {
            net.md_5.bungee.api.chat.TextComponent main = new net.md_5.bungee.api.chat.TextComponent(
                    ChatColor.YELLOW + plugin.getMessage("chat.search.enter_coords",
                            "Enter chunk coordinates (X Z): "));

            net.md_5.bungee.api.chat.TextComponent example = new net.md_5.bungee.api.chat.TextComponent(
                    ChatColor.GRAY + "5 -3 ");
            example.setItalic(true);

            net.md_5.bungee.api.chat.TextComponent cancel = new net.md_5.bungee.api.chat.TextComponent(
                    plugin.getMessage("plugin.cancel", " [Cancel]"));
            cancel.setColor(net.md_5.bungee.api.ChatColor.RED);
            cancel.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/rdcancel"));
            cancel.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(
                            plugin.getMessage("chat.search.cancel_hover", "Click to cancel"))));

            main.addExtra(example);
            main.addExtra(cancel);
            player.spigot().sendMessage(main);
        }
    }
    private boolean isHybridServer() {
        String name = Bukkit.getName().toLowerCase();
        String version = Bukkit.getVersion().toLowerCase();
        if (name.contains("mohist") || name.contains("arclight") ||
                name.contains("magma") || name.contains("catserver") ||
                name.contains("ketting") || name.contains("cardboard")) {
            return true;
        }
        if (version.contains("mohist") || version.contains("arclight")) {
            return true;
        }
        try {
            Class.forName("com.mohistmc.MohistConfig");
            return true;
        } catch (ClassNotFoundException ignored) {}
        return false;
    }

    public Map<ChunkCoordinate, ChunkData> getChunkMap() {
        return chunkMap;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public TPSMonitor getTPSMonitor() {
        return tpsMonitor;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public String getMessage(String key, String defaultValue) {
        return messageManager.getMessage(key, defaultValue);
    }

    public int getMaxRedstone() {
        return configManager.getMaxRedstone();
    }

    public int getMaxEntities() {
        return configManager.getMaxEntities();
    }
}