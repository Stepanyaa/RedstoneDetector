package ru.stepanyaa.redstoneDetector;

import dev.faststats.ErrorTracker;
import dev.faststats.data.Metric;
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
import ru.stepanyaa.redstoneDetector.platform.Platforms;
import org.bstats.bukkit.Metrics;
import dev.faststats.bukkit.BukkitContext;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RedstoneDetector extends JavaPlugin implements Listener, TabCompleter {
    public static final ErrorTracker ERROR_TRACKER = ErrorTracker.contextAware();
    private final AtomicInteger gameCount = new AtomicInteger();
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
    private final BukkitContext context = new BukkitContext.Factory(this, "b518aa9851ae0e15397ea3c258d785f2")
            .errorTrackerService(ERROR_TRACKER)
            .metrics(factory -> factory
                    .addMetric(Metric.number("game_count", gameCount::get))
                    .addMetric(Metric.string("server_version", () -> "1.0.0"))

                    .onFlush(() -> gameCount.set(0))

                    .create())
            .create();

    private ConfigManager configManager;
    private MessageManager messageManager;
    private TPSMonitor tpsMonitor;
    private UpdateChecker updateChecker;
    private GuiManager guiManager;
    private FileManager fileManager;
    private ScanManager scanManager;
    private CoreProtectBridge coreProtectBridge;

    private static boolean isPurpur = false;
    private int lowTpsCounter = 0;

    private final Map<ChunkCoordinate, ChunkData> chunkMap = new ConcurrentHashMap<>();
    private final Map<ChunkCoordinate, Map<Location, org.bukkit.block.data.BlockData>> redstoneBackups = new ConcurrentHashMap<>();
    private final Set<ChunkCoordinate> frozenChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkCoordinate> autoFrozenChunks = ConcurrentHashMap.newKeySet();
    private final Set<Material> redstoneMaterials = new HashSet<>();

    private boolean freezeRedstone = false;
    private boolean manualFreezeOverride = false;
    private long freezeStartTime = 0;
    private int serverLagStreak = 0;
    private boolean autoGlobalFreeze = false;
    private long lastAutoFreezeTime = 0L;
    private boolean loadedChunksWarned = false;

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

        Platforms.install(this);
        MessageManager.migrateLegacyLangFiles(this);
        updateAllConfigFiles();
        reloadConfig();

        this.fileManager = new FileManager(this);
        fileManager.loadAll();

        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        this.messageManager = new MessageManager(this);
        messageManager.loadMessages();
        messageManager.updateMessagesFiles();

        getLogger().info(getMessage("plugin.startup", "======== RedstoneDetector STARTING ========"));

        this.tpsMonitor = new TPSMonitor(this);
        this.updateChecker = new UpdateChecker(this);
        this.guiManager = new GuiManager(this);
        this.scanManager = new ScanManager(this);
        this.coreProtectBridge = new CoreProtectBridge(this);

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
        startActivityTask();
        try {
            Class.forName("org.purpurmc.purpur.PurpurConfig");
            isPurpur = true;
        } catch (ClassNotFoundException e) {
            isPurpur = false;
        }

        int pluginId = 27778;
        context.ready();
        new Metrics(this, pluginId);

        getLogger().info(getMessage("plugin.enabled", "Plugin successfully enabled!"));
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.savePlayerStates();
        }
        context.shutdown();
        saveChunkData();
        Platforms.shutdown();
        getLogger().info(getMessage("plugin.shutdown", "GUI states and chunk data saved"));
    }

    private void updateAllConfigFiles() {
        java.io.File dataFolder = getDataFolder();
        ConfigUpdater.update(this, "config.yml", new java.io.File(dataFolder, "config.yml"));
        ConfigUpdater.update(this, "performance.yml", new java.io.File(dataFolder, "performance.yml"));
        ConfigUpdater.update(this, "blocks.yml", new java.io.File(dataFolder, "blocks.yml"));
        ConfigUpdater.update(this, "gui.yml", new java.io.File(dataFolder, "gui.yml"));
        java.io.File langDir = new java.io.File(dataFolder, "lang");
        for (String locale : MessageManager.BUNDLED_LOCALES) {
            ConfigUpdater.update(this, "lang/" + locale + ".yml",
                    new java.io.File(langDir, locale + ".yml"));
        }
    }

    private void initializeRedstoneMaterials() {
        redstoneMaterials.clear();

        java.util.List<String> names = new java.util.ArrayList<>();
        if (fileManager != null && fileManager.getBlocks() != null) {
            names.addAll(fileManager.getBlocks().getStringList("redstone-blocks"));
            names.addAll(fileManager.getBlocks().getStringList("optional-blocks"));
        }

        if (names.isEmpty()) {
            String[] defaults = {
                    "REDSTONE_WIRE", "REPEATER", "COMPARATOR", "PISTON", "STICKY_PISTON",
                    "OBSERVER", "DISPENSER", "DROPPER", "HOPPER", "REDSTONE_TORCH",
                    "REDSTONE_BLOCK", "LEVER", "STONE_BUTTON", "OAK_BUTTON",
                    "TRIPWIRE_HOOK", "TARGET", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR"
            };
            Collections.addAll(names, defaults);
        }

        int loaded = 0;
        for (String name : names) {
            if (name == null) continue;
            Material mat = Material.getMaterial(name.trim().toUpperCase(java.util.Locale.ROOT));
            if (mat != null && redstoneMaterials.add(mat)) {
                loaded++;
            }
        }
        getLogger().info("Loaded " + loaded + " redstone/lag block types from blocks.yml.");
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

    private boolean isMechanism(Material mat) {
        if (mat == null) return false;
        if (redstoneMaterials.contains(mat)) return true;
        String n = mat.name();
        return n.endsWith("_TRAPDOOR") || n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE")
                || n.endsWith("_PRESSURE_PLATE") || n.endsWith("_BUTTON") || n.endsWith("_RAIL")
                || n.equals("NOTE_BLOCK") || n.equals("BELL") || n.equals("TRIPWIRE")
                || n.equals("PISTON_HEAD") || n.equals("MOVING_PISTON") || n.equals("DAYLIGHT_DETECTOR")
                || n.equals("LECTERN") || n.equals("REDSTONE_LAMP") || n.equals("IRON_DOOR")
                || n.equals("IRON_TRAPDOOR") || n.equals("SCULK_SENSOR") || n.equals("CALIBRATED_SCULK_SENSOR");
    }

    private Chunk getLoadedChunkAt(ChunkCoordinate coord) {
        World w = Bukkit.getWorld(coord.world());
        if (w == null) return null;

        if (!Platforms.ownsChunk(w, coord.x(), coord.z())) return null;
        return chunkAtOrNull(w, coord.x(), coord.z());
    }

    private Chunk chunkAtOrNull(World world, int x, int z) {
        try {
            if (!world.isChunkLoaded(x, z)) return null;
            return world.getChunkAt(x, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void refreshChunkOnOwningRegion(ChunkCoordinate coord) {
        World world = Bukkit.getWorld(coord.world());
        if (world == null) return;
        Platforms.scheduler().runAtChunk(world, coord.x(), coord.z(), () -> {

            Chunk live = chunkAtOrNull(world, coord.x(), coord.z());
            if (live != null) scanChunk(live);
        });
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
                sender.sendMessage(ChatColor.RED + getMessage(sender, "command.player_only",
                        "This command is for players only!"));
            }
            return true;
        }
        if (command.equals("redstonedetector") || command.equals("rd")) {
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + getMessage(sender, "command.player_only",
                            "This command is for players only!"));
                    return true;
                }
                Player player = (Player) sender;
                if (!hasPerm(player, "redstonedetector.gui")) {
                    player.sendMessage(ChatColor.RED + getMessage(sender, "command.no_permission_gui",
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
            case "lang":
            case "language":
                return handleLang(sender, args);
            case "gui":
                return handleGui(sender);
            case "status":
                return handleStatus(sender);
            case "info":
                return handleInfo(sender);
            case "freeze":
                return handleFreeze(sender, true);
            case "unfreeze":
                return handleFreeze(sender, false);
            case "redstone":
                return handleRedstone(sender, args);
            case "stopredstone":
                return handleStopRedstone(sender);
            case "scan":
                return handleScan(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleLang(CommandSender sender, String[] args) {
        if (!hasPerm(sender, "redstonedetector.reload")) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.no_permission_lang",
                    "You do not have permission to change the language!"));
            return true;
        }

        java.util.List<String> available = messageManager.getAvailableLanguages();

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(getMessage(sender, "command.lang_current", "&6Current language: ")
                    + ChatColor.YELLOW + messageManager.getLanguage());
            sender.sendMessage(getMessage(sender, "command.lang_available", "&6Available languages: ")
                    + ChatColor.YELLOW + String.join(", ", available));
            return true;
        }

        String code = args[1].toLowerCase();
        if (!messageManager.setLanguage(code)) {
            sender.sendMessage(getMessage(sender, "command.lang_unknown", "&cUnknown language: ")
                    + ChatColor.YELLOW + code);
            sender.sendMessage(getMessage(sender, "command.lang_available", "&6Available languages: ")
                    + ChatColor.YELLOW + String.join(", ", available));
            return true;
        }

        sender.sendMessage(getMessage(sender, "command.lang_changed", "&aLanguage changed to: ")
                + ChatColor.YELLOW + messageManager.getLanguage());

        String changedBy = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        getLogger().info("Language changed to '" + messageManager.getLanguage() + "' by " + changedBy);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasPerm(sender, "redstonedetector.reload")) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.no_permission_reload",
                    "You do not have permission to reload the plugin!"));
            return true;
        }

        reloadConfig();
        fileManager.loadAll();
        configManager.loadConfig();
        messageManager.loadMessages();
        messageManager.updateMessagesFiles();
        initializeRedstoneMaterials();

        sender.sendMessage(ChatColor.GREEN + getMessage(sender, "command.reload_success",
                "Configuration reloaded!"));

        String byWho = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        getLogger().info("Configuration reloaded by " + byWho);

        return true;
    }

    private boolean handleGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.player_only",
                    "This command is for players only!"));
            return true;
        }

        Player player = (Player) sender;
        if (!hasPerm(player, "redstonedetector.gui")) {
            player.sendMessage(ChatColor.RED + getMessage(sender, "command.no_permission_gui",
                    "You do not have permission to use the GUI!"));
            return true;
        }

        return openGuiCommand(player);
    }

    private boolean handleRedstone(CommandSender sender, String[] args) {
        if (!sender.hasPermission("redstonedetector.admin")) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.no_permission_redstone",
                    "You do not have permission to manage redstone!"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.redstone_usage",
                    "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
            return true;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "freeze":
                freezeRedstone = true;
                manualFreezeOverride = true;
                sender.sendMessage(ChatColor.GREEN + getMessage(sender, "command.redstone_frozen",
                        "Redstone frozen!"));
                getLogger().info(getMessage(sender, "redstone.frozen_log", "Redstone frozen!"));
                break;

            case "unfreeze":
                freezeRedstone = false;
                manualFreezeOverride = false;
                sender.sendMessage(ChatColor.GREEN + getMessage(sender, "command.redstone_unfrozen",
                        "Redstone unfrozen!"));
                getLogger().info(getMessage(sender, "redstone.unfrozen_log", "Redstone unfrozen!"));
                break;

            case "status":
                String status = freezeRedstone ?
                        getMessage(sender, "command.redstone_status_frozen", "FROZEN") :
                        getMessage(sender, "command.redstone_status_active", "ACTIVE");
                sender.sendMessage(ChatColor.YELLOW + getMessage(sender, "command.redstone_status",
                        "Redstone status: {status}").replace("{status}", status));
                break;

            default:
                sender.sendMessage(ChatColor.RED + getMessage(sender, "command.redstone_usage",
                        "Usage: /redstonedetector redstone [freeze|unfreeze|status]"));
        }
        return true;
    }

    private boolean handleStopRedstone(CommandSender sender) {
        if (!sender.hasPermission("redstonedetector.admin")) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.no_permission_redstone",
                    "You do not have permission to manage redstone!"));
            return true;
        }

        freezeRedstone = true;
        manualFreezeOverride = true;

        sender.sendMessage(ChatColor.GOLD + getMessage(sender, "command.redstone_frozen",
                "Redstone signals have been frozen (Blocks are safe)!"));
        return true;
    }

    private boolean handleScan(CommandSender sender, String[] args) {
        if (!hasPerm(sender, "redstonedetector.scan")) {
            sender.sendMessage(color(getMessage(sender, "cmd.no_permission",
                    "&cYou don't have permission for this.")));
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            if (scanManager.requestCancel()) {
                sender.sendMessage(color(getMessage(sender, "cmd.scan.cancelled",
                        "&eScan cancellation requested.")));
            } else {
                sender.sendMessage(color("&cNo scan is currently running."));
            }
            return true;
        }

        if (scanManager.isRunning()) {
            sender.sendMessage(color(getMessage(sender, "cmd.scan.busy",
                    "&cA scan is already running: &e{percent}%")
                    .replace("{percent}", String.valueOf(scanManager.getProgressPercent()))));
            return true;
        }

        if (scanManager.startScan(sender)) {
            sender.sendMessage(color(getMessage(sender, "cmd.scan.started",
                    "&aScan started. Use &e/rd scan cancel &ato stop it.")));
        } else {
            sender.sendMessage(color(getMessage(sender, "cmd.scan.busy",
                    "&cA scan is already running: &e{percent}%")
                    .replace("{percent}", String.valueOf(scanManager.getProgressPercent()))));
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!hasPerm(sender, "redstonedetector.gui")) {
            sender.sendMessage(color(getMessage(sender, "cmd.no_permission",
                    "&cYou don't have permission for this.")));
            return true;
        }
        double tps = getCurrentTps();
        String tpsColor = tps >= configManager.getCriticalTPS() ? "&a"
                : (tps >= configManager.getCriticalTPS() - 3 ? "&e" : "&c");
        sender.sendMessage(color(getMessage(sender, "cmd.status.header",
                "&c&lRedstoneDetector &7- Status")));
        sender.sendMessage(color(getMessage(sender, "cmd.status.tps", "&7TPS: {color}{tps}")
                .replace("{color}", tpsColor)
                .replace("{tps}", String.format(java.util.Locale.US, "%.2f", tps))));
        sender.sendMessage(color(getMessage(sender, "cmd.status.problems", "&7Suspicious chunks: &e{count}")
                .replace("{count}", String.valueOf(getProblemChunkCount()))));
        sender.sendMessage(color(getMessage(sender, "cmd.status.frozen", "&7Frozen chunks: &b{count}")
                .replace("{count}", String.valueOf(getFrozenChunkCount()))));
        String state = freezeRedstone ? getMessage(sender, "cmd.state.on", "&cON")
                : getMessage(sender, "cmd.state.off", "&aOFF");
        sender.sendMessage(color(getMessage(sender, "cmd.status.global_freeze",
                "&7Global redstone freeze: {state}").replace("{state}", state)));
        sender.sendMessage(color(getMessage(sender, "cmd.status.last_scan", "&7Last scan: &f{time}")
                .replace("{time}", formatLastScan())));
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.player_only",
                    "This command is for players only!"));
            return true;
        }
        if (!hasPerm(sender, "redstonedetector.gui")) {
            sender.sendMessage(color(getMessage(sender, "cmd.no_permission",
                    "&cYou don't have permission for this.")));
            return true;
        }
        Player player = (Player) sender;
        Chunk chunk = player.getLocation().getChunk();
        ChunkCoordinate coord = new ChunkCoordinate(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (chunkMap.get(coord) == null) {
            scanSingleChunk(chunk);
        }
        if (chunkMap.get(coord) == null) {
            player.sendMessage(color(getMessage(sender, "cmd.info.no_data",
                    "&eNo detection data for your current chunk.")));
            return true;
        }
        guiManager.openChunkInfoGui(player, coord);
        return true;
    }

    private boolean handleFreeze(CommandSender sender, boolean freeze) {
        if (!hasPerm(sender, "redstonedetector.freeze")) {
            sender.sendMessage(color(getMessage(sender, "cmd.no_permission",
                    "&cYou don't have permission for this.")));
            return true;
        }
        setFreeze(freeze, true);
        if (freeze) {
            sender.sendMessage(color(getMessage(sender, "cmd.freeze.on",
                    "&aGlobal redstone freeze &cENABLED&a.")));
            getLogger().info("Global redstone freeze ENABLED by " + senderName(sender));
        } else {
            sender.sendMessage(color(getMessage(sender, "cmd.freeze.off",
                    "&aGlobal redstone freeze &2DISABLED&a.")));
            getLogger().info("Global redstone freeze DISABLED by " + senderName(sender));
        }
        return true;
    }

    private boolean openGuiCommand(Player player) {
        guiManager.openDashboard(player);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + getMessage(sender, "command.help_header",
                "=== RedstoneDetector Help ==="));

        if (hasPerm(sender, "redstonedetector.gui")) {
            sender.sendMessage(helpLine("/rd gui", getMessage(sender, "command.help_gui", " - Open the interface")));
            sender.sendMessage(helpLine("/rd status", getMessage(sender, "command.help_status", " - Show server status")));
            sender.sendMessage(helpLine("/rd info", getMessage(sender, "command.help_info", " - Info about your current chunk")));
        }
        if (hasPerm(sender, "redstonedetector.scan")) {
            sender.sendMessage(helpLine("/rd scan", getMessage(sender, "command.help_scan", " - Force chunk scan")));
            sender.sendMessage(helpLine("/rd scan cancel", getMessage(sender, "command.help_scan_cancel", " - Cancel a running scan")));
        }
        if (hasPerm(sender, "redstonedetector.freeze")) {
            sender.sendMessage(helpLine("/rd freeze", getMessage(sender, "command.help_freeze", " - Enable global redstone freeze")));
            sender.sendMessage(helpLine("/rd unfreeze", getMessage(sender, "command.help_unfreeze", " - Disable global redstone freeze")));
        }
        if (hasPerm(sender, "redstonedetector.redstone")) {
            sender.sendMessage(helpLine("/rd redstone freeze", getMessage(sender, "command.help_redstone_freeze", " - Freeze redstone")));
            sender.sendMessage(helpLine("/rd redstone unfreeze", getMessage(sender, "command.help_redstone_unfreeze", " - Unfreeze redstone")));
            sender.sendMessage(helpLine("/rd redstone status", getMessage(sender, "command.help_redstone_status", " - Redstone status")));
            sender.sendMessage(helpLine("/rd stopredstone", getMessage(sender, "command.help_stopredstone", " - Emergency stop")));
        }
        if (hasPerm(sender, "redstonedetector.reload")) {
            sender.sendMessage(helpLine("/rd reload", getMessage(sender, "command.help_reload", " - Reload the configuration")));
            sender.sendMessage(helpLine("/rd lang [code]", getMessage(sender, "command.help_lang", " - Show or change the language")));
        }

        sender.sendMessage(ChatColor.GOLD + getMessage(sender, "command.help_aliases", "Aliases: ") +
                ChatColor.YELLOW + "/rd, /redstonedetector");
    }

    private String helpLine(String cmd, String desc) {
        return ChatColor.YELLOW + cmd + ChatColor.WHITE + desc;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("redstonedetector") || cmd.getName().equalsIgnoreCase("rd")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                if (hasPerm(sender, "redstonedetector.gui")) {
                    completions.add("gui");
                    completions.add("status");
                    completions.add("info");
                }
                if (hasPerm(sender, "redstonedetector.scan")) completions.add("scan");
                if (hasPerm(sender, "redstonedetector.freeze")) {
                    completions.add("freeze");
                    completions.add("unfreeze");
                }
                if (hasPerm(sender, "redstonedetector.reload")) {
                    completions.add("reload");
                    completions.add("lang");
                }
                if (hasPerm(sender, "redstonedetector.gui")) completions.add("help");
                if (hasPerm(sender, "redstonedetector.freeze")) {
                    completions.add("redstone");
                    completions.add("stopredstone");
                }
                return completions;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("redstone")) {
                return Arrays.asList("freeze", "unfreeze", "status");
            }
            if (args.length == 2
                    && (args[0].equalsIgnoreCase("lang") || args[0].equalsIgnoreCase("language"))) {
                List<String> langs = new ArrayList<>(messageManager.getAvailableLanguages());
                langs.add("list");
                return langs;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
                return Collections.singletonList("cancel");
            }
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + getMessage(event.getPlayer(), "redstone.break_blocked",
                    "Redstone is frozen! You cannot break blocks."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (freezeRedstone && isRedstoneComponent(event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + getMessage(event.getPlayer(), "redstone.place_blocked",
                    "Redstone is frozen! You cannot place blocks."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstoneEvent(BlockRedstoneEvent event) {
        long t0 = System.nanoTime();
        ChunkData ad = activityData(event.getBlock());
        if (ad != null) {
            ad.redstoneWindow.incrementAndGet();
            ad.lastActivity = System.currentTimeMillis();
        }
        if (freezeRedstone || isInFrozenChunk(event.getBlock())) {
            event.setNewCurrent(0);
        }
        if (ad != null) ad.nanosWindow.addAndGet(System.nanoTime() - t0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        long t0 = System.nanoTime();
        ChunkData ad = activityData(event.getBlock());
        if (ad != null) {
            ad.pistonWindow.incrementAndGet();
            ad.lastActivity = System.currentTimeMillis();
        }
        if (freezeRedstone || isInFrozenChunk(event.getBlock())) {
            event.setCancelled(true);
        }
        if (ad != null) ad.nanosWindow.addAndGet(System.nanoTime() - t0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        long t0 = System.nanoTime();
        ChunkData ad = activityData(event.getBlock());
        if (ad != null) {
            ad.pistonWindow.incrementAndGet();
            ad.lastActivity = System.currentTimeMillis();
        }
        if (freezeRedstone || isInFrozenChunk(event.getBlock())) {
            event.setCancelled(true);
        }
        if (ad != null) ad.nanosWindow.addAndGet(System.nanoTime() - t0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Material mat = block.getType();
        if (!isMechanism(mat)) return;
        long t0 = System.nanoTime();
        ChunkData ad = activityData(block);
        if (ad != null) {
            ad.physicsWindow.incrementAndGet();
            if (mat == Material.OBSERVER) ad.observerWindow.incrementAndGet();
            else if (mat == Material.COMPARATOR) ad.comparatorWindow.incrementAndGet();
            else if (mat == Material.REPEATER) ad.repeaterWindow.incrementAndGet();
            else ad.neighborWindow.incrementAndGet();
            ad.lastActivity = System.currentTimeMillis();
        }
        if (freezeRedstone || isInFrozenChunk(block)) {
            event.setCancelled(true);
        }
        if (ad != null) ad.nanosWindow.addAndGet(System.nanoTime() - t0);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        updateChecker.notifyPlayer(event.getPlayer());
    }

    private ChunkData activityData(Block block) {
        if (block == null) return null;
        Chunk c = block.getChunk();
        ChunkCoordinate coord = new ChunkCoordinate(block.getWorld().getName(), c.getX(), c.getZ());
        return chunkMap.computeIfAbsent(coord, k -> new ChunkData());
    }

    public int smartFreezeNow(Player player) {
        double chunkMspt = configManager != null ? configManager.getChunkMsptThreshold() : 8.0;
        int minMech = configManager != null ? configManager.getCulpritMinMechanisms() : 30;
        int frozen = 0;
        for (Map.Entry<ChunkCoordinate, ChunkData> e : chunkMap.entrySet()) {
            ChunkCoordinate coord = e.getKey();
            ChunkData d = e.getValue();
            if (frozenChunks.contains(coord)) continue;

            boolean costly = d.msptContribution >= chunkMspt;

            if (!d.scanned) {
                refreshChunkOnOwningRegion(coord);
                continue;
            }
            boolean mechanical = d.redstoneCount.get() >= minMech;
            if (!costly || !mechanical) continue;
            autoFreezeChunk(coord, d, "smart freeze by "
                    + (player != null ? player.getName() : "console")
                    + ", " + String.format("%.1f", d.msptContribution) + " ms/tick");
            frozen++;
        }
        if (player != null) {
            if (frozen > 0) {
                player.sendMessage(color(getMessage(player, "cmd.smart_freeze_done",
                        "&bFroze {count} lagging chunk(s). The rest of the server keeps working.")
                        .replace("{count}", String.valueOf(frozen))));
            } else {
                player.sendMessage(color(getMessage(player, "cmd.smart_freeze_none",
                        "&aNo lagging chunks right now - nothing frozen. Shift + right-click to freeze ALL redstone.")));
            }
        }
        return frozen;
    }

    private void autoFreezeChunk(ChunkCoordinate coord, ChunkData data, String reason) {
        if (coord == null) return;
        autoFrozenChunks.add(coord);
        frozenChunks.add(coord);
        lastAutoFreezeTime = System.currentTimeMillis();
        if (data != null) {
            data.autoFrozen = true;
            data.autoFrozenSince = System.currentTimeMillis();
        }
        getLogger().warning("Intelligent freeze: chunk " + coord.toDisplayString()
                + " frozen (" + reason + ", " + (data != null ? String.format("%.1f", data.msptContribution) : "0")
                + " ms/tick, " + (data != null ? data.updatesPerSec : 0) + " updates/sec).");
    }

    private void startActivityTask() {
        Platforms.scheduler().timer(20L, 20L, new Runnable() {
            @Override
            public void run() {
                if (configManager == null) return;
                long now = System.currentTimeMillis();
                int maxR = getMaxRedstone();
                int maxE = getMaxEntities();
                int updThreshold = configManager.getUpdateScoreThreshold();
                boolean intelligent = configManager.isIntelligentFreezeEnabled();
                int cooldown = configManager.getAutoUnfreezeSeconds();

                double tps = tpsMonitor != null ? tpsMonitor.getTPS() : 20.0;
                boolean realMspt = tpsMonitor != null && tpsMonitor.hasRealMspt();
                double serverMspt = tpsMonitor != null ? tpsMonitor.getMSPT() : 50.0;
                double laggingMspt = configManager.getServerMsptThreshold();
                double baselineMspt = configManager.getBaselineMspt();
                int sustainedNeeded = configManager.getSustainedLagSeconds();
                int minUpd = configManager.getCulpritMinUpdatesPerSec();
                int minMech = configManager.getCulpritMinMechanisms();
                double chunkMsptLimit = configManager.getChunkMsptThreshold();
                int globalAfter = configManager.getGlobalFreezeAfterSeconds();
                double critTps = configManager.getCriticalTPS();

                boolean serverLagging = tps < critTps || (realMspt && serverMspt >= laggingMspt);
                if (serverLagging) serverLagStreak++; else serverLagStreak = 0;

                double effMspt = realMspt ? serverMspt : 1000.0 / Math.max(tps, 0.5);
                double excessMspt = Math.max(0.0, effMspt - baselineMspt);

                double totalWeight = 0.0;
                for (Map.Entry<ChunkCoordinate, ChunkData> e : chunkMap.entrySet()) {
                    ChunkData d = e.getValue();
                    int ph = d.physicsWindow.getAndSet(0);
                    int rs = d.redstoneWindow.getAndSet(0);
                    int pi = d.pistonWindow.getAndSet(0);
                    int co = d.comparatorWindow.getAndSet(0);
                    int ob = d.observerWindow.getAndSet(0);
                    int re = d.repeaterWindow.getAndSet(0);
                    int ne = d.neighborWindow.getAndSet(0);

                    d.physicsPerSec = ph;
                    d.redstonePerSec = rs;
                    d.pistonPerSec = pi;
                    d.comparatorPerSec = co;
                    d.observerPerSec = ob;
                    d.repeaterPerSec = re;
                    d.neighborPerSec = ne;
                    d.updatesPerSec = ph + rs + pi;
                    totalWeight += d.updatesPerSec * (1.0 + d.redstoneCount.get() / 64.0);
                }

                for (Map.Entry<ChunkCoordinate, ChunkData> e : chunkMap.entrySet()) {
                    ChunkData d = e.getValue();
                    long nanos = d.nanosWindow.getAndSet(0L);
                    double handlerMspt = nanos / 20.0 / 1_000_000.0;
                    double weight = d.updatesPerSec * (1.0 + d.redstoneCount.get() / 64.0);
                    double attributedMspt = (serverLagging && totalWeight > 0)
                            ? excessMspt * (weight / totalWeight) : 0.0;
                    d.msptContribution = Math.max(handlerMspt, attributedMspt);
                    ActivityAnalyzer.computeScores(d, maxR, maxE, updThreshold);
                }

                if (intelligent && serverLagging && serverLagStreak >= sustainedNeeded) {
                    boolean mergeAdjacent = configManager.isMergeAdjacentChunks();
                    int activeFloor = Math.max(3, minUpd / 10);

                    java.util.List<ChunkCoordinate> growable = new java.util.ArrayList<>();
                    java.util.List<ChunkCoordinate> seeds = new java.util.ArrayList<>();
                    for (Map.Entry<ChunkCoordinate, ChunkData> e : chunkMap.entrySet()) {
                        if (autoFrozenChunks.contains(e.getKey())) continue;
                        int u = e.getValue().updatesPerSec;
                        if (u >= 1) growable.add(e.getKey());
                        if (u >= activeFloor) seeds.add(e.getKey());

                        if (u >= 1 && !e.getValue().scanned) {
                            refreshChunkOnOwningRegion(e.getKey());
                        }
                    }

                    java.util.Set<ChunkCoordinate> visited = new java.util.HashSet<>();
                    for (ChunkCoordinate seed : seeds) {
                        if (visited.contains(seed)) continue;

                        java.util.List<ChunkCoordinate> cluster = new java.util.ArrayList<>();
                        java.util.Deque<ChunkCoordinate> stack = new java.util.ArrayDeque<>();
                        stack.push(seed);
                        visited.add(seed);
                        while (!stack.isEmpty()) {
                            ChunkCoordinate c = stack.pop();
                            cluster.add(c);
                            if (!mergeAdjacent) continue;
                            for (ChunkCoordinate other : growable) {
                                if (visited.contains(other)) continue;
                                if (!other.world().equals(c.world())) continue;
                                if (Math.abs(other.x() - c.x()) <= 1
                                        && Math.abs(other.z() - c.z()) <= 1) {
                                    visited.add(other);
                                    stack.push(other);
                                }
                            }
                        }

                        long clusterUpd = 0L;
                        long clusterMech = 0L;
                        double clusterMspt = 0.0;
                        boolean clusterScanned = false;
                        for (ChunkCoordinate c : cluster) {
                            ChunkData d = chunkMap.get(c);
                            if (d == null) continue;
                            refreshChunkOnOwningRegion(c);
                            clusterUpd += d.updatesPerSec;
                            clusterMech += d.redstoneCount.get();
                            clusterMspt += d.msptContribution;
                            if (d.scanned) clusterScanned = true;
                        }

                        boolean mechanical = clusterScanned && clusterMech >= minMech;
                        boolean busy = clusterUpd >= minUpd;
                        boolean costly = clusterMspt >= chunkMsptLimit;
                        if (busy && costly && mechanical) {
                            for (ChunkCoordinate c : cluster) {
                                if (autoFrozenChunks.contains(c)) continue;
                                ChunkData d = chunkMap.get(c);
                                if (d == null) continue;
                                String reason = cluster.size() > 1
                                        ? ("machine across " + cluster.size() + " chunks, "
                                                + clusterUpd + " updates/s, " + clusterMech
                                                + " mechanisms, " + String.format("%.1f", clusterMspt) + " ms/tick")
                                        : (d.updatesPerSec + " updates/s, "
                                                + d.redstoneCount.get() + " mechanisms");
                                autoFreezeChunk(c, d, reason);
                            }
                        }
                    }

                    boolean awaitingScan = false;
                    for (Map.Entry<ChunkCoordinate, ChunkData> e : chunkMap.entrySet()) {
                        ChunkData d = e.getValue();
                        if (d.updatesPerSec >= minUpd && d.redstoneCount.get() <= 0) {
                            awaitingScan = true;
                            break;
                        }
                    }
                    boolean graceOver = lastAutoFreezeTime == 0L
                            || (now - lastAutoFreezeTime) / 1000 >= globalAfter;
                    if (configManager.isGlobalFreezeEnabled() && !awaitingScan && graceOver
                            && tps < critTps && serverLagStreak >= globalAfter && !freezeRedstone) {
                        freezeRedstone = true;
                        manualFreezeOverride = false;
                        autoGlobalFreeze = true;
                        freezeStartTime = now;
                        getLogger().warning("Global freeze: server still lagging (TPS "
                                + String.format("%.1f", tps) + ", MSPT " + String.format("%.1f", effMspt)
                                + ") after per-chunk freezes. All redstone frozen.");
                    }
                }

                if (autoGlobalFreeze && !serverLagging && freezeRedstone && !manualFreezeOverride) {
                    freezeRedstone = false;
                    autoGlobalFreeze = false;
                    getLogger().info("Global freeze released: server recovered (TPS "
                            + String.format("%.1f", tps) + ").");
                }

                if (cooldown > 0) {
                    for (ChunkCoordinate c : new ArrayList<>(autoFrozenChunks)) {
                        ChunkData d = chunkMap.get(c);
                        long since = d != null ? d.autoFrozenSince : 0L;
                        if ((now - since) / 1000 >= cooldown) {
                            autoFrozenChunks.remove(c);
                            frozenChunks.remove(c);
                            if (d != null) d.autoFrozen = false;
                            getLogger().info("Intelligent freeze: released chunk "
                                    + c.toDisplayString() + " for re-evaluation.");
                        }
                    }
                }
            }
        });
    }

    private void startOptimizedChunkScanTask() {
        Platforms.scheduler().timer(20L, 20L, new Runnable() {
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

                    if (configManager.isScanOnLowTPS() && !configManager.isIntelligentFreezeEnabled()) {
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

                Chunk[] chunks = loadedChunksSafe(world);
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

                    scanChunkOnOwningRegion(chunk);
                }
            }
        });
    }

    public void scanChunkOnOwningRegion(Chunk chunk) {
        Platforms.scheduler().runAtChunk(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                () -> scanChunk(chunk));
    }

    public void scanChunkOnOwningRegion(Chunk chunk, java.util.function.Consumer<Boolean> callback) {
        Platforms.scheduler().runAtChunk(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                () -> callback.accept(scanSingleChunk(chunk)));
    }

    private Chunk[] loadedChunksSafe(World world) {
        try {
            return world.getLoadedChunks();
        } catch (Throwable failure) {
            if (!loadedChunksWarned) {
                loadedChunksWarned = true;
                getLogger().warning("Could not list loaded chunks of world "
                        + world.getName() + " from this thread: " + failure);
            }
            return new Chunk[0];
        }
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

                    if (isMechanism(mat)) {
                        redstoneFound++;
                        data.redstoneTypes
                                .computeIfAbsent(mat.name(), m -> new AtomicInteger(0))
                                .incrementAndGet();
                    }
                }
            }
        }
        for (Entity entity : chunk.getEntities()) {
            EntityType type = entity.getType();
            entitiesFound++;
            data.entityTypes
                    .computeIfAbsent(type.name(), t -> new AtomicInteger(0))
                    .incrementAndGet();
        }

        data.redstoneCount.set(redstoneFound);
        data.entityCount.set(entitiesFound);
        data.scanned = true;
    }

    private void scanAllChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : loadedChunksSafe(world)) {
                scanChunkOnOwningRegion(chunk);
            }
        }
        Platforms.scheduler().run(this::saveChunkData);
    }

    private void runHybridScan() {
        if (Platforms.isRegionised()) {

            scanAllChunks();
            return;
        }
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

        Platforms.scheduler().async(() -> {
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
                    if (isMechanism(type)) {
                        redstoneCount++;
                        chunkData.redstoneTypes.computeIfAbsent(type.name(), k ->
                                new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
                    }
                }
            }
        }
        if (data.entities != null && !data.entities.isEmpty()) {
            chunkData.entityCount.set(data.entities.size());
            for (EntityType type : data.entities) {
                chunkData.entityTypes.computeIfAbsent(type.name(), k ->
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
                            Platforms.scheduler().delay(delay / 50, () -> chunkMap.remove(coord));
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
        Platforms.scheduler().timer(20L * 60 * 5, 20L * 60 * 5, new Runnable() {
            @Override
            public void run() {
                saveChunkData();
            }
        });
    }

    private void startCleanupTask() {
        Platforms.scheduler().timer(20L * 60 * 120, 20L * 60 * 120, new Runnable() {
            @Override
            public void run() {
                cleanupOldChunkData();
            }
        });
    }

    public void openChunkDetails(Player player, ChunkCoordinate coord) {
        ChunkData data = chunkMap.get(coord);
        if (data == null) {
            player.sendMessage(ChatColor.RED + getMessage(player, "chunk.details.not_found", "Chunk data not found!"));
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add(getMessage(player, "chunk.details.detailed_header", "&6═════ &eChunk Details {coord} &6═════")
                .replace("{coord}", coord.toDisplayString()));
        lines.add("");

        lines.add(ChatColor.GOLD + "Redstone Components (" + data.redstoneCount.get() + "):");
        if (data.redstoneCount.get() == 0) {
            lines.add(ChatColor.GRAY + "  none");
        } else {
            data.redstoneTypes.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, AtomicInteger>>comparingInt(
                            entry -> entry.getValue().get()
                    ).reversed())
                    .forEach(entry -> {
                        String name = entry.getKey().toLowerCase().replace("_", " ");
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
                    .sorted(Comparator.<Map.Entry<String, AtomicInteger>>comparingInt(
                            entry -> entry.getValue().get()
                    ).reversed())
                    .forEach(entry -> {
                        String name = entry.getKey().toLowerCase().replace("_", " ");
                        name = name.substring(0, 1).toUpperCase() + name.substring(1);
                        lines.add(ChatColor.YELLOW + "  • " + name + ": " + ChatColor.WHITE + entry.getValue().get());
                    });
        }

        lines.add("");
        if (bungeeApiAvailable) {
            lines.add(getMessage(player, "chunk.details.navigation_hint", "&8Click on the buttons above to navigate • &7or &c/rdcancel &7to exit"));
        } else {
            lines.add(getMessage(player, "chunk.details.page_navigation", "&eType &bnext &e/ &bback &e(or &bдалее &e/ &bназад&e) or &c/rdcancel"));
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
            player.sendMessage(getMessage(player, "chunk.details.page_footer", "&7< &bback &7| &bnext &7>"));
        }

        if (page >= totalPages - 1) {
            player.sendMessage(getMessage(player, "chunk.details.end_of_list",
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

            Platforms.scheduler().runAtChunk(world, coord.x(), coord.z(), () -> {
                Location loc = new Location(
                        world,
                        coord.x() * 16 + 8,
                        world.getHighestBlockYAt(coord.x() * 16 + 8, coord.z() * 16 + 8) + 1,
                        coord.z() * 16 + 8
                );
                Platforms.scheduler().teleport(player, loc);
                player.sendMessage(ChatColor.GREEN + getMessage(player, "chunk.teleport_success",
                        "Teleported to chunk {coord}").replace("{coord}", coord.toDisplayString()));
            });
        } else {
            player.sendMessage(ChatColor.RED + getMessage(player, "chunk.world_not_found",
                    "World '{world}' not found!").replace("{world}", coord.world()));
        }
    }

    public void disableRedstoneInChunk(Player player, ChunkCoordinate coord) {
        disableRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage(player, "chunk.redstone_removed",
                "Redstone removed in chunk {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void disableRedstoneInChunk(ChunkCoordinate coord, String initiator) {
        World world = getServer().getWorld(coord.world());
        if (world == null) return;

        Platforms.scheduler().runAtChunk(world, coord.x(), coord.z(),
                () -> disableRedstoneNow(world, coord, initiator));
    }

    private void disableRedstoneNow(World world, ChunkCoordinate coord, String initiator) {
        Chunk chunk = world.getChunkAt(coord.x(), coord.z());
        if (!chunk.isLoaded()) return;

        Map<Location, org.bukkit.block.data.BlockData> backup = new HashMap<>();
        int removed = 0;

        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (isRedstoneComponent(block.getType())) {
                        backup.put(block.getLocation(), block.getBlockData().clone());
                        block.setType(Material.AIR, false);
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

                Platforms.scheduler().delay(20L * 60 * 10, () -> chunkMap.remove(coord));
            }

            getLogger().info(getMessage("chunk.redstone_removed_log",
                    "Removed {count} redstone blocks in chunk: {coord}")
                    .replace("{count}", String.valueOf(removed))
                    .replace("{coord}", coord.toDisplayString()));
        }
    }

    public void restoreRedstoneInChunk(Player player, ChunkCoordinate coord) {
        restoreRedstoneInChunk(coord, player.getName());
        player.sendMessage(ChatColor.GREEN + getMessage(player, "chunk.redstone_restored",
                "Redstone restored in chunk {coord}").replace("{coord}", coord.toDisplayString()));
    }

    public void restoreRedstoneInChunk(ChunkCoordinate coord, String initiator) {
        Map<Location, org.bukkit.block.data.BlockData> backup = redstoneBackups.get(coord);
        if (backup == null || backup.isEmpty()) return;

        World world = getServer().getWorld(coord.world());
        if (world == null) return;
        Platforms.scheduler().runAtChunk(world, coord.x(), coord.z(),
                () -> restoreRedstoneNow(coord, backup, initiator));
    }

    private void restoreRedstoneNow(ChunkCoordinate coord,
                                    Map<Location, org.bukkit.block.data.BlockData> backup,
                                    String initiator) {
        int restored = 0;
        for (Map.Entry<Location, org.bukkit.block.data.BlockData> entry : backup.entrySet()) {
            Block block = entry.getKey().getBlock();
            if (block.isEmpty()) {
                block.setBlockData(entry.getValue(), false);
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
        Platforms.scheduler().runAtChunk(world, coord.x(), coord.z(),
                () -> removeEntitiesNow(player, world, coord));
    }

    private void removeEntitiesNow(Player player, World world, ChunkCoordinate coord) {
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
            player.sendMessage(ChatColor.GREEN + getMessage(player, "chunk.entities_removed",
                    "Entities removed in chunk {coord}").replace("{coord}", coord.toDisplayString()));
            getLogger().info(getMessage(player, "chunk.entities_removed_log",
                    "Removed {count} entities in chunk: {coord}")
                    .replace("{count}", String.valueOf(removed))
                    .replace("{coord}", coord.toDisplayString()));
        } else {
            player.sendMessage(ChatColor.YELLOW + getMessage(player, "chunk.no_entities",
                    "No entities to remove in chunk {coord}").replace("{coord}", coord.toDisplayString()));
        }
    }

    public void sendSearchPrompt(Player player) {
        if (bungeeApiAvailable) {
            BungeeHandler.sendSearchPrompt(player, this);
        } else {
            player.sendMessage(ChatColor.YELLOW + getMessage(player, "chat.search.enter_coords",
                    "Enter chunk coordinates (X Z): ") + ChatColor.GRAY + " (e.g. 5 -3)");
            player.sendMessage(ChatColor.RED + getMessage(player, "plugin.cancel", "Type /rdcancel to cancel"));
        }
    }

    private static class BungeeHandler {
        public static void sendPagination(Player player, int page, int total, String prevCmd,
                                          String nextCmd, RedstoneDetector plugin) {
            net.md_5.bungee.api.chat.TextComponent message = new net.md_5.bungee.api.chat.TextComponent();

            if (page > 0) {
                net.md_5.bungee.api.chat.TextComponent prev = new net.md_5.bungee.api.chat.TextComponent(
                        plugin.getMessage(player, "chunk.details.button_back", "< Back "));
                prev.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, prevCmd));
                prev.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.hover.content.Text(
                                plugin.getMessage(player, "chunk.details.hover_back", "Click to go back"))));
                message.addExtra(prev);
            }

            message.addExtra(new net.md_5.bungee.api.chat.TextComponent(
                    plugin.getMessage(player, "chunk.details.button_separator", " | ")));

            if (page < total - 1) {
                net.md_5.bungee.api.chat.TextComponent next = new net.md_5.bungee.api.chat.TextComponent(
                        plugin.getMessage(player, "chunk.details.button_next", " Next >"));
                next.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, nextCmd));
                next.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.hover.content.Text(
                                plugin.getMessage(player, "chunk.details.hover_next", "Click to go next"))));
                message.addExtra(next);
            }

            player.spigot().sendMessage(message);
        }

        public static void sendSearchPrompt(Player player, RedstoneDetector plugin) {
            net.md_5.bungee.api.chat.TextComponent main = new net.md_5.bungee.api.chat.TextComponent(
                    ChatColor.YELLOW + plugin.getMessage(player, "chat.search.enter_coords",
                            "Enter chunk coordinates (X Z): "));

            net.md_5.bungee.api.chat.TextComponent example = new net.md_5.bungee.api.chat.TextComponent(
                    ChatColor.GRAY + "5 -3 ");
            example.setItalic(true);

            net.md_5.bungee.api.chat.TextComponent cancel = new net.md_5.bungee.api.chat.TextComponent(
                    plugin.getMessage(player, "plugin.cancel", " [Cancel]"));
            cancel.setColor(net.md_5.bungee.api.ChatColor.RED);
            cancel.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/rdcancel"));
            cancel.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(
                            plugin.getMessage(player, "chat.search.cancel_hover", "Click to cancel"))));

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

    public String getMessage(CommandSender sender, String key, String defaultValue) {
        return messageManager.getMessage(sender, key, defaultValue);
    }

    public int getMaxRedstone() {
        return configManager.getMaxRedstone();
    }

    public int getMaxEntities() {
        return configManager.getMaxEntities();
    }

    private volatile long statsCacheTime = 0L;
    private volatile int cachedProblemCount = 0;

    private boolean hasPerm(CommandSender sender, String perm) {
        return sender.hasPermission(perm) || sender.hasPermission("redstonedetector.admin");
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private String senderName(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
    }

    private String formatLastScan() {
        long t = getLastScanTime();
        if (t <= 0) return "never";
        long ago = (System.currentTimeMillis() - t) / 1000;
        if (ago < 60) return ago + "s ago";
        if (ago < 3600) return (ago / 60) + "m ago";
        return (ago / 3600) + "h ago";
    }

    public FileManager getFileManager() {
        return fileManager;
    }

    public ScanManager getScanManager() {
        return scanManager;
    }

    public CoreProtectBridge getCoreProtectBridge() {
        return coreProtectBridge;
    }

    public String formatMessage(String key, String def) {
        return fileManager != null ? getMessage(key, def) : def;
    }

    public boolean scanSingleChunk(Chunk chunk) {
        scanChunk(chunk);
        ChunkCoordinate coord = new ChunkCoordinate(
                chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        ChunkData data = chunkMap.get(coord);
        if (data == null) return false;
        ActivityAnalyzer.computeScores(data, getMaxRedstone(), getMaxEntities(),
                configManager != null ? configManager.getUpdateScoreThreshold() : 200);
        return ActivityAnalyzer.isSuspicious(data, getMaxRedstone(), getMaxEntities());
    }

    public int getProblemChunkCount() {
        long now = System.currentTimeMillis();
        long ttl = configManager != null ? configManager.getStatsCacheMs() : 1000L;
        if (now - statsCacheTime < ttl) return cachedProblemCount;
        int count = 0;
        for (ChunkData data : chunkMap.values()) {
            if (ActivityAnalyzer.isSuspicious(data, getMaxRedstone(), getMaxEntities())) count++;
        }
        cachedProblemCount = count;
        statsCacheTime = now;
        return count;
    }

    public int getFrozenChunkCount() {
        return frozenChunks.size();
    }

    public java.util.Set<ChunkCoordinate> getFrozenChunks() {
        return new java.util.HashSet<>(frozenChunks);
    }

    public boolean isAutoFrozen(ChunkCoordinate coord) {
        return autoFrozenChunks.contains(coord);
    }

    public double getServerMspt() {
        return tpsMonitor != null ? tpsMonitor.getMSPT() : 50.0;
    }

    public double getServerTps() {
        return tpsMonitor != null ? tpsMonitor.getTPS() : 20.0;
    }

    public boolean isChunkFrozen(ChunkCoordinate coord) {
        return redstoneBackups.containsKey(coord);
    }

    public boolean isChunkRedstoneFrozen(ChunkCoordinate coord) {
        return frozenChunks.contains(coord);
    }

    public boolean isChunkRedstoneRemoved(ChunkCoordinate coord) {
        return redstoneBackups.containsKey(coord);
    }

    private boolean isInFrozenChunk(Block block) {
        if (frozenChunks.isEmpty() || block == null) return false;
        Chunk c = block.getChunk();
        return frozenChunks.contains(new ChunkCoordinate(block.getWorld().getName(), c.getX(), c.getZ()));
    }

    public void freezeChunkRedstone(Player player, ChunkCoordinate coord) {
        frozenChunks.add(coord);
        player.sendMessage(color(getMessage(player, "chunk.redstone_frozen",
                "&bRedstone frozen (stopped) in chunk {coord}").replace("{coord}", coord.toDisplayString())));
        getLogger().info("Redstone frozen (stopped) in chunk " + coord.toDisplayString() + " by " + player.getName());
    }

    public void unfreezeChunkRedstone(Player player, ChunkCoordinate coord) {
        frozenChunks.remove(coord);
        player.sendMessage(color(getMessage(player, "chunk.redstone_unfrozen",
                "&aRedstone resumed in chunk {coord}").replace("{coord}", coord.toDisplayString())));
        getLogger().info("Redstone resumed in chunk " + coord.toDisplayString() + " by " + player.getName());
    }

    public boolean isRedstoneFrozen() {
        return freezeRedstone;
    }

    public void setFreeze(boolean freeze, boolean manual) {
        this.freezeRedstone = freeze;
        this.manualFreezeOverride = manual;
        if (freeze) this.freezeStartTime = System.currentTimeMillis();
    }

    public long getLastScanTime() {
        return scanManager != null ? scanManager.getLastScanTime() : 0L;
    }

    public double getCurrentTps() {
        return tpsMonitor != null ? tpsMonitor.getTPS() : 20.0;
    }

    public ChunkData getChunkData(ChunkCoordinate coord) {
        return chunkMap.get(coord);
    }

    public Set<Material> getRedstoneMaterials() {
        return redstoneMaterials;
    }
}
