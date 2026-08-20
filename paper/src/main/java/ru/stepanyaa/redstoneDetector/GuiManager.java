package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class GuiManager implements Listener, InventoryHolder {
    private final Set<UUID> transitioningPlayers = new HashSet<>();
    public enum SortMode {
        COORDINATE, REDSTONE, ENTITIES
    }

    public enum HistoryFilter {
        ALL, REMOVE, PLACE, INTERACT
    }
    @Override
    public Inventory getInventory() {
        return null;
    }

    public enum GuiState {
        WORLD_SELECTION, CHUNK_LIST, CHUNK_ACTIONS, DASHBOARD, CHUNK_INFO, FROZEN_LIST, CHUNK_HISTORY
    }

    public static class PlayerGuiState {
        public GuiState state;
        public String world;
        public int page;
        public ChunkCoordinate chunkCoord;
        public SortMode sortMode;
        public java.util.List<ChunkCoordinate> frozenView;
        public int historyPage;
        public HistoryFilter historyFilter;
        public java.util.List<CoreProtectBridge.HistoryEntry> historyEntries;

        public PlayerGuiState(GuiState state) {
            this.state = state;
            this.sortMode = SortMode.COORDINATE;
            this.historyFilter = HistoryFilter.ALL;
            this.historyEntries = Collections.emptyList();
        }
    }

    private final RedstoneDetector plugin;
    private final Map<UUID, PlayerGuiState> playerStates = new HashMap<>();

    public GuiManager(RedstoneDetector plugin) {
        this.plugin = plugin;
        startDashboardRefreshTask();
    }

    private void startDashboardRefreshTask() {
        ru.stepanyaa.redstoneDetector.platform.Platforms.scheduler().timer(20L, 20L, new Runnable() {
            @Override
            public void run() {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    PlayerGuiState st = playerStates.get(online.getUniqueId());
                    if (st == null || st.state != GuiState.DASHBOARD) continue;
                    Inventory top = online.getOpenInventory().getTopInventory();
                    if (top == null || !(top.getHolder() instanceof GuiManager)) continue;
                    if (top.getSize() < 27) continue;
                    refreshDashboardItems(online, top);
                    online.updateInventory();
                }
            }
        });
    }

    public void openDashboard(Player player) {
        markTransition(player);
        org.bukkit.configuration.file.FileConfiguration guiCfg = plugin.getFileManager().getGui();
        boolean useColors = guiCfg == null || guiCfg.getBoolean("use-color-indicators", true);
        String rawTitle = guiCfg != null
                ? guiCfg.getString("dashboard-title", "&cRedstoneDetector &8\u00bb &7Dashboard")
                : "RedstoneDetector Dashboard";
        Inventory gui = Bukkit.createInventory(this, 27, ChatColor.translateAlternateColorCodes('&', rawTitle));

        gui.setItem(4, buildStatusItem(player));

        gui.setItem(10, dashButton(player, Material.MAP, "gui.dashboard.chunks", "&aProblem Chunks",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.chunks_lore", "Open the list of suspicious chunks."))));

        gui.setItem(12, buildScanItem(player));

        gui.setItem(14, buildFreezeItem(player));

        gui.setItem(16, dashButton(player, Material.PACKED_ICE, "gui.dashboard.frozen_chunks", "&bFrozen Chunks",
                Arrays.asList(
                        ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.frozen_chunks_lore", "View and manage frozen chunks."),
                        ChatColor.DARK_GRAY + plugin.getMessage(player, "gui.dashboard.frozen", "Frozen chunks: ") + ChatColor.AQUA + plugin.getFrozenChunkCount())));

        gui.setItem(21, dashButton(player, Material.NETHER_STAR, "gui.dashboard.reload", "&dReload Plugin",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.reload_lore", "Reload all configuration files."))));

        gui.setItem(23, dashButton(player, Material.BARRIER, "gui.dashboard.close", "&cClose",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.close_lore", "Close this menu."))));

        PlayerGuiState state = playerStates.get(player.getUniqueId());
        if (state == null) state = new PlayerGuiState(GuiState.DASHBOARD);
        state.state = GuiState.DASHBOARD;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
    }

    public void openFrozenChunksGui(Player player) {
        openFrozenChunksGui(player, 0);
    }

    public void openFrozenChunksGui(Player player, int page) {
        markTransition(player);
        String rawTitle = plugin.getMessage(player, "gui.frozen.title", "&bFrozen Chunks");
        Inventory gui = Bukkit.createInventory(this, 54, ChatColor.translateAlternateColorCodes('&', rawTitle));

        List<ChunkCoordinate> frozen = new ArrayList<>(plugin.getFrozenChunks());
        frozen.sort((a, b) -> {
            int cmp = a.world().compareTo(b.world());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.x(), b.x());
            if (cmp != 0) return cmp;
            return Integer.compare(a.z(), b.z());
        });

        int perPage = 45;
        int totalPages = Math.max(1, (int) Math.ceil((double) frozen.size() / perPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        List<ChunkCoordinate> pageItems;
        if (frozen.isEmpty()) {
            pageItems = Collections.emptyList();
            gui.setItem(22, loreItem(Material.PAPER,
                    ChatColor.YELLOW + plugin.getMessage(player, "gui.frozen.empty", "No frozen chunks"),
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.frozen.empty_lore", "Chunks you freeze will appear here."))));
        } else {
            int start = page * perPage;
            int end = Math.min(start + perPage, frozen.size());
            pageItems = new ArrayList<>(frozen.subList(start, end));
            for (int i = 0; i < pageItems.size(); i++) {
                gui.setItem(i, buildFrozenItem(player, pageItems.get(i)));
            }
        }

        if (page > 0) {
            gui.setItem(45, dashButton(player, Material.ARROW, "gui.previous_page", "&ePrevious Page", Collections.emptyList()));
        }
        if (page < totalPages - 1) {
            gui.setItem(53, dashButton(player, Material.ARROW, "gui.next_page", "&eNext Page", Collections.emptyList()));
        }
        gui.setItem(49, dashButton(player, Material.BARRIER, "gui.frozen.back", "&7Back to Menu",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.frozen.back_lore", "Return to the main menu."))));

        PlayerGuiState state = new PlayerGuiState(GuiState.FROZEN_LIST);
        state.page = page;
        state.frozenView = pageItems;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
    }

    private ItemStack buildFrozenItem(Player player, ChunkCoordinate coord) {
        boolean auto = plugin.isAutoFrozen(coord);
        boolean removed = plugin.isChunkRedstoneRemoved(coord);
        ChunkData data = plugin.getChunkData(coord);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.world", "World: ") + ChatColor.WHITE + coord.world());
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.coordinates", "Coordinates: ") + ChatColor.WHITE + coord.toDisplayString());
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.frozen.type", "Type: ")
                + (auto ? ChatColor.RED + plugin.getMessage(player, "gui.frozen.type_auto", "Automatic")
                        : ChatColor.AQUA + plugin.getMessage(player, "gui.frozen.type_manual", "Manual")));
        if (data != null) {
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.updates_sec", "Updates/sec: ") + ChatColor.AQUA + data.updatesPerSec);
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.chunk_mspt", "Chunk MSPT: ") + ChatColor.AQUA + String.format("%.2f", data.msptContribution) + " ms");
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.danger", "Danger: ") + ChatColor.GOLD + data.dangerLevel);
        }
        if (removed) {
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.removed_status", "Redstone: ")
                    + ChatColor.RED + plugin.getMessage(player, "gui.chunkinfo.removed", "REMOVED"));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + plugin.getMessage(player, "gui.frozen.click_info", "Click to open chunk info"));
        Material mat = auto ? Material.ICE : Material.BLUE_ICE;
        return loreItem(mat, ChatColor.AQUA + plugin.getMessage(player, "gui.frozen.item_name", "Chunk {coord}")
                .replace("{coord}", coord.toDisplayString()), lore);
    }

    private void handleFrozenListClick(Player player, PlayerGuiState state, String displayName, int slot) {
        String s = ChatColor.stripColor(displayName);
        if (s.equals(dashName(player, "gui.frozen.back", "&7Back to Menu"))) {
            openDashboard(player);
            return;
        }
        if (s.equals(dashName(player, "gui.previous_page", "&ePrevious Page"))) {
            openFrozenChunksGui(player, Math.max(0, state.page - 1));
            return;
        }
        if (s.equals(dashName(player, "gui.next_page", "&eNext Page"))) {
            openFrozenChunksGui(player, state.page + 1);
            return;
        }
        if (state.frozenView != null && slot >= 0 && slot < state.frozenView.size()) {
            ChunkCoordinate coord = state.frozenView.get(slot);
            if (coord != null) {
                openChunkInfoGui(player, coord);
            }
        }
    }

    private ItemStack buildStatusItem(Player player) {
        org.bukkit.configuration.file.FileConfiguration guiCfg = plugin.getFileManager().getGui();
        boolean useColors = guiCfg == null || guiCfg.getBoolean("use-color-indicators", true);
        double tps = plugin.getCurrentTps();
        double critical = plugin.getConfigManager().getCriticalTPS();
        ChatColor tpsColor;
        Material statusMat;
        if (tps >= critical) {
            tpsColor = ChatColor.GREEN;
            statusMat = Material.LIME_CONCRETE;
        } else if (tps >= critical - 3) {
            tpsColor = ChatColor.YELLOW;
            statusMat = Material.YELLOW_CONCRETE;
        } else {
            tpsColor = ChatColor.RED;
            statusMat = Material.RED_CONCRETE;
        }
        if (!useColors) statusMat = Material.PAPER;
        String on = ChatColor.RED + plugin.getMessage(player, "gui.common.on", "ON");
        String off = ChatColor.GREEN + plugin.getMessage(player, "gui.common.off", "OFF");
        List<String> statusLore = new ArrayList<>();
        statusLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.tps", "TPS: ") + tpsColor + String.format(Locale.US, "%.2f", tps));
        statusLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.suspicious", "Suspicious chunks: ") + ChatColor.YELLOW + plugin.getProblemChunkCount());
        statusLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.frozen", "Frozen chunks: ") + ChatColor.AQUA + plugin.getFrozenChunkCount());
        statusLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.global_freeze", "Global freeze: ") + (plugin.isRedstoneFrozen() ? on : off));
        statusLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.last_scan", "Last scan: ") + ChatColor.WHITE + dashboardTimeAgo(player, plugin.getLastScanTime()));
        return loreItem(statusMat, ChatColor.translateAlternateColorCodes('&',
                plugin.getMessage(player, "gui.dashboard.status_title", "&c&lServer Status")), statusLore);
    }

    private ItemStack buildScanItem(Player player) {
        List<String> scanLore = new ArrayList<>();
        if (plugin.getScanManager() != null && plugin.getScanManager().isRunning()) {
            scanLore.add(ChatColor.YELLOW + plugin.getMessage(player, "gui.dashboard.scan_progress", "Scanning... {percent}%")
                    .replace("{percent}", String.valueOf(plugin.getScanManager().getProgressPercent())));
            scanLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.scan_cancel_lore", "Click to cancel."));
        } else {
            scanLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.scan_start_lore", "Click to start a full scan."));
        }
        return dashButton(player, Material.CLOCK, "gui.dashboard.scan", "&aRun Scan", scanLore);
    }

    private ItemStack buildFreezeItem(Player player) {
        org.bukkit.configuration.file.FileConfiguration guiCfg = plugin.getFileManager().getGui();
        boolean useColors = guiCfg == null || guiCfg.getBoolean("use-color-indicators", true);
        boolean frozen = plugin.isRedstoneFrozen();
        String on = ChatColor.RED + plugin.getMessage(player, "gui.common.on", "ON");
        String off = ChatColor.GREEN + plugin.getMessage(player, "gui.common.off", "OFF");
        List<String> freezeLore = new ArrayList<>();
        freezeLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.global_freeze", "Global freeze: ") + (frozen ? on : off));
        freezeLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.freeze_smart_lore", "Click: freeze only the lagging chunks."));
        freezeLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.dashboard.freeze_all_lore", "Shift + right-click: freeze ALL redstone."));
        Material freezeMat = !useColors ? Material.PACKED_ICE : (frozen ? Material.ICE : Material.BLUE_ICE);
        return dashButton(player, freezeMat, "gui.dashboard.freeze", "&bToggle Global Freeze", freezeLore);
    }

    private void refreshDashboardItems(Player player, Inventory inv) {
        inv.setItem(4, buildStatusItem(player));
        inv.setItem(12, buildScanItem(player));
        inv.setItem(14, buildFreezeItem(player));
    }

    public void openChunkInfoGui(Player player, ChunkCoordinate coord) {
        markTransition(player);
        org.bukkit.configuration.file.FileConfiguration guiCfg = plugin.getFileManager().getGui();
        String rawTitle = guiCfg != null ? guiCfg.getString("chunk-info-title", "&cChunk Information")
                : "Chunk Information";
        Inventory gui = Bukkit.createInventory(this, 36, ChatColor.translateAlternateColorCodes('&', rawTitle));

        ChunkData data = plugin.getChunkData(coord);
        int redstone = data != null ? data.redstoneCount.get() : 0;
        int entities = data != null ? data.entityCount.get() : 0;
        int lagging = (data != null && data.redstoneTypes != null) ? data.redstoneTypes.size() : 0;
        boolean stopped = plugin.isChunkRedstoneFrozen(coord);
        boolean removed = plugin.isChunkRedstoneRemoved(coord);

        int medium = guiCfg != null ? guiCfg.getInt("load-medium-threshold", 150) : 150;
        int high = guiCfg != null ? guiCfg.getInt("load-high-threshold", 400) : 400;
        int load = redstone + entities;
        String loadLabel;
        if (load >= high) loadLabel = ChatColor.RED + plugin.getMessage(player, "gui.chunkinfo.load_high", "HIGH");
        else if (load >= medium) loadLabel = ChatColor.YELLOW + plugin.getMessage(player, "gui.chunkinfo.load_medium", "MEDIUM");
        else loadLabel = ChatColor.GREEN + plugin.getMessage(player, "gui.chunkinfo.load_low", "LOW");

        String reason;
        if (redstone > plugin.getMaxRedstone() && entities > plugin.getMaxEntities())
            reason = plugin.getMessage(player, "gui.chunkinfo.reason_both", "Redstone & entities over limit");
        else if (redstone > plugin.getMaxRedstone()) reason = plugin.getMessage(player, "gui.chunkinfo.reason_redstone", "Too much redstone");
        else if (entities > plugin.getMaxEntities()) reason = plugin.getMessage(player, "gui.chunkinfo.reason_entities", "Too many entities");
        else reason = plugin.getMessage(player, "gui.chunkinfo.reason_manual", "Manual inspection");

        String detected = (data != null && data.firstDetected > 0)
                ? dashboardTimeAgo(player, data.firstDetected) : plugin.getMessage(player, "gui.chunkinfo.unknown", "unknown");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.world", "World: ") + ChatColor.WHITE + coord.world());
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.coordinates", "Coordinates: ") + ChatColor.WHITE + coord.toDisplayString()
                + ChatColor.DARK_GRAY + " (" + plugin.getMessage(player, "gui.chunkinfo.blocks", "blocks") + " " + (coord.x() * 16) + ", " + (coord.z() * 16) + ")");
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.redstone", "Redstone components: ") + ChatColor.RED + redstone);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.entities", "Entities: ") + ChatColor.GREEN + entities);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.lagging", "Lagging block types: ") + ChatColor.GOLD + lagging);
        int lagScore = data != null ? data.lagScore : 0;
        int updateScore = data != null ? data.updateScore : 0;
        int updatesSec = data != null ? data.updatesPerSec : 0;
        String danger = data != null ? data.dangerLevel : "SAFE";
        String dangerColor;
        if ("CRITICAL".equals(danger)) dangerColor = ChatColor.DARK_RED.toString();
        else if ("HIGH".equals(danger)) dangerColor = ChatColor.RED.toString();
        else if ("MEDIUM".equals(danger)) dangerColor = ChatColor.GOLD.toString();
        else if ("LOW".equals(danger)) dangerColor = ChatColor.YELLOW.toString();
        else dangerColor = ChatColor.GREEN.toString();
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.updates_sec", "Updates/sec: ") + ChatColor.AQUA + updatesSec);
        double chunkMspt = data != null ? data.msptContribution : 0.0;
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.chunk_mspt", "Chunk MSPT: ") + ChatColor.AQUA + String.format("%.2f", chunkMspt) + " ms");
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.server_mspt", "Server MSPT: ") + ChatColor.AQUA + String.format("%.1f", plugin.getServerMspt()) + " ms");
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.server_tps", "Server TPS: ") + ChatColor.AQUA + String.format("%.1f", plugin.getServerTps()));
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.update_score", "Update score: ") + ChatColor.AQUA + updateScore);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.lag_score", "Lag score: ") + ChatColor.GOLD + lagScore);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.danger", "Danger: ") + dangerColor + danger);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.machine", "Machine: ") + ChatColor.WHITE + ActivityAnalyzer.machineDisplay((key, def) -> plugin.getMessage(player, key, def), data != null ? data.machineType : "none"));
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.freeze_status", "Freeze status: ")
                + (stopped ? ChatColor.AQUA + plugin.getMessage(player, "gui.chunkinfo.frozen", "FROZEN") : ChatColor.GREEN + plugin.getMessage(player, "gui.chunkinfo.active", "ACTIVE")));
        if (removed) {
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.removed_status", "Redstone: ")
                    + ChatColor.RED + plugin.getMessage(player, "gui.chunkinfo.removed", "REMOVED"));
        }
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.reason", "Reason: ") + ChatColor.WHITE + reason);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.detected", "Detected: ") + ChatColor.WHITE + detected);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.load", "Approx. load: ") + loadLabel);

                CoreProtectBridge cp = plugin.getCoreProtectBridge();
        final boolean cpAvailable = cp != null && cp.isAvailable();
        if (cpAvailable) {
            lore.add("");
            lore.add(ChatColor.DARK_AQUA + plugin.getMessage(player, "gui.chunkinfo.owner_header", "— Ownership (CoreProtect) —"));
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.possible_owner", "Possible owner: ")
                    + ChatColor.YELLOW + plugin.getMessage(player, "gui.chunkinfo.loading", "Loading..."));
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.last_builder", "Last builder: ")
                    + ChatColor.YELLOW + plugin.getMessage(player, "gui.chunkinfo.loading", "Loading..."));
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.last_modified", "Last modified: ")
                    + ChatColor.YELLOW + plugin.getMessage(player, "gui.chunkinfo.loading", "Loading..."));
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.last_redstone", "Last redstone placement: ")
                    + ChatColor.YELLOW + plugin.getMessage(player, "gui.chunkinfo.loading", "Loading..."));
            lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.placed", "Placed: ")
                    + ChatColor.YELLOW + plugin.getMessage(player, "gui.chunkinfo.loading", "Loading..."));
        }

        gui.setItem(4, loreItem(Material.MAP, ChatColor.AQUA + plugin.getMessage(player, "gui.chunkinfo.title", "Chunk {coord}").replace("{coord}", coord.toDisplayString()), lore));

        gui.setItem(10, dashButton(player, Material.ENDER_PEARL, "gui.chunk_teleport", "&fTeleport to Chunk",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.teleport_lore", "Teleport to this chunk."))));

        if (stopped) {
            gui.setItem(12, dashButton(player, Material.BLUE_ICE, "gui.chunkinfo.unfreeze_stop", "&aUnfreeze Redstone",
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.unfreeze_stop_lore", "Resume redstone activity here."))));
        } else {
            gui.setItem(12, dashButton(player, Material.ICE, "gui.chunkinfo.freeze_stop", "&bFreeze Redstone (stop)",
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.freeze_stop_lore", "Stop redstone here without removing blocks."))));
        }

        if (removed) {
            gui.setItem(14, dashButton(player, Material.EMERALD, "gui.chunkinfo.restore", "&aRestore Redstone",
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.restore_lore", "Restore previously removed redstone."))));
        } else {
            gui.setItem(14, dashButton(player, Material.REDSTONE_BLOCK, "gui.chunkinfo.remove", "&cRemove Redstone",
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.remove_lore", "Delete redstone blocks (restorable)."))));
        }

        gui.setItem(16, dashButton(player, Material.BLAZE_ROD, "gui.chunkinfo.remove_entities", "&6Remove Entities",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.remove_entities_lore", "Remove all non-player entities here."))));

                if (cpAvailable) {
            gui.setItem(20, dashButton(player, Material.BOOK, "gui.chunkinfo.history", "&eHistory",
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.history_lore", "View recent changes in this chunk."))));
        }

        gui.setItem(22, dashButton(player, Material.ARROW, "gui.chunkinfo.back_list", "&7Back to Chunk List",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.chunkinfo.back_list_lore", "Return to the chunk list."))));

        PlayerGuiState prev = playerStates.get(player.getUniqueId());
        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_INFO);
        state.chunkCoord = coord;
        state.world = coord.world();
        state.page = prev != null ? prev.page : 0;
        state.sortMode = (prev != null && prev.sortMode != null) ? prev.sortMode : SortMode.COORDINATE;
        state.historyPage = 0;
        state.historyFilter = HistoryFilter.ALL;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);

                if (cpAvailable) {
            final UUID playerId = player.getUniqueId();
            final Inventory openGui = gui;
            cp.lookupChunkAsync(coord).thenAccept(info -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) return;
                    PlayerGuiState st = playerStates.get(playerId);
                    if (st == null || st.state != GuiState.CHUNK_INFO || st.chunkCoord == null
                            || !st.chunkCoord.equals(coord)) return;
                    Inventory top = p.getOpenInventory().getTopInventory();
                    if (top == null || top != openGui) return;

                                        ItemStack map = top.getItem(4);
                    if (map == null || !map.hasItemMeta()) return;
                    ItemMeta meta = map.getItemMeta();
                    List<String> currentLore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

                                        int headerIdx = -1;
                    String headerPrefix = ChatColor.DARK_AQUA.toString();
                    for (int i = 0; i < currentLore.size(); i++) {
                        if (currentLore.get(i) != null && currentLore.get(i).startsWith(headerPrefix)) {
                            headerIdx = i;
                            break;
                        }
                    }
                    if (headerIdx >= 0) {
                        while (currentLore.size() > headerIdx) {
                            currentLore.remove(currentLore.size() - 1);
                        }
                    }

                    String unknown = plugin.getMessage(p, "gui.chunkinfo.unknown", "unknown");
                    CoreProtectBridge bridge = plugin.getCoreProtectBridge();
                    currentLore.add("");
                    currentLore.add(ChatColor.DARK_AQUA + plugin.getMessage(p, "gui.chunkinfo.owner_header", "— Ownership (CoreProtect) —"));
                    currentLore.add(ChatColor.GRAY + plugin.getMessage(p, "gui.chunkinfo.possible_owner", "Possible owner: ")
                            + ChatColor.YELLOW + (info.possibleOwner != null ? info.possibleOwner : unknown));
                    currentLore.add(ChatColor.GRAY + plugin.getMessage(p, "gui.chunkinfo.last_builder", "Last builder: ")
                            + ChatColor.YELLOW + (info.lastBuilder != null ? info.lastBuilder : unknown));
                    currentLore.add(ChatColor.GRAY + plugin.getMessage(p, "gui.chunkinfo.last_modified", "Last modified: ")
                            + ChatColor.WHITE + (info.lastModified > 0 ? bridge.formatTimeAgo(p, info.lastModified) : unknown));
                    currentLore.add(ChatColor.GRAY + plugin.getMessage(p, "gui.chunkinfo.last_redstone", "Last redstone placement: ")
                            + ChatColor.YELLOW + (info.lastRedstonePlacer != null ? info.lastRedstonePlacer : unknown));
                    currentLore.add(ChatColor.GRAY + plugin.getMessage(p, "gui.chunkinfo.placed", "Placed: ")
                            + ChatColor.WHITE + (info.lastRedstonePlacement > 0 ? bridge.formatTimeAgo(p, info.lastRedstonePlacement) : unknown));

                    meta.setLore(currentLore);
                    map.setItemMeta(meta);
                    top.setItem(4, map);
                });
            });
        }
    }

    public void openChunkHistoryGui(Player player, ChunkCoordinate coord) {
        markTransition(player);
        String rawTitle = plugin.getMessage(player, "gui.history.title", "&eChunk History {coord}")
                .replace("{coord}", coord.toDisplayString());
        Inventory gui = Bukkit.createInventory(this, 54, ChatColor.translateAlternateColorCodes('&', rawTitle));

                gui.setItem(22, loreItem(Material.PAPER,
                ChatColor.YELLOW + plugin.getMessage(player, "gui.history.loading", "Loading history..."),
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.history.loading_lore", "Querying CoreProtect..."))));

        gui.setItem(49, dashButton(player, Material.ARROW, "gui.history.back", "&7Back to Chunk Info",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.history.back_lore", "Return to chunk details."))));

        PlayerGuiState prev = playerStates.get(player.getUniqueId());
        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_HISTORY);
        state.chunkCoord = coord;
        state.world = coord.world();
        state.page = prev != null ? prev.page : 0;
        state.sortMode = (prev != null && prev.sortMode != null) ? prev.sortMode : SortMode.COORDINATE;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);

        CoreProtectBridge cp = plugin.getCoreProtectBridge();
        if (cp == null || !cp.isAvailable()) {
            gui.setItem(22, loreItem(Material.BARRIER,
                    ChatColor.RED + plugin.getMessage(player, "gui.history.unavailable", "CoreProtect unavailable"),
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.history.unavailable_lore", "Install CoreProtect to see history."))));
            return;
        }

        final UUID playerId = player.getUniqueId();
        final Inventory openGui = gui;
        cp.lookupChunkAsync(coord).thenAccept(info -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                PlayerGuiState st = playerStates.get(playerId);
                if (st == null || st.state != GuiState.CHUNK_HISTORY || st.chunkCoord == null
                        || !st.chunkCoord.equals(coord)) return;
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top == null || top != openGui) return;

                st.historyEntries = info.history == null
                        ? Collections.emptyList()
                        : new ArrayList<>(info.history);
                renderChunkHistoryGui(p, top, st, cp);
            });
        });
    }

    private void renderChunkHistoryGui(Player player, Inventory gui, PlayerGuiState state,
                                       CoreProtectBridge cp) {
        final int perPage = 45;
        for (int slot = 0; slot < 54; slot++) gui.setItem(slot, null);

        List<CoreProtectBridge.HistoryEntry> filtered = new ArrayList<>();
        for (CoreProtectBridge.HistoryEntry entry : state.historyEntries) {
            if (matchesHistoryFilter(entry, state.historyFilter)) filtered.add(entry);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / perPage));
        state.historyPage = Math.max(0, Math.min(state.historyPage, totalPages - 1));

        if (filtered.isEmpty()) {
            gui.setItem(22, loreItem(Material.PAPER,
                    ChatColor.YELLOW + plugin.getMessage(player, "gui.history.empty", "No history found"),
                    Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.history.empty_lore", "No block changes logged in this chunk."))));
        } else {
            int start = state.historyPage * perPage;
            int end = Math.min(start + perPage, filtered.size());
            for (int i = start; i < end; i++) {
                gui.setItem(i - start, buildHistoryEntry(player, cp, filtered.get(i)));
            }
        }

        if (state.historyPage > 0) {
            gui.setItem(45, dashButton(player, Material.ARROW, "gui.previous_page", "&ePrevious Page", Collections.emptyList()));
        }
        gui.setItem(46, historyFilterButton(player, Material.IRON_PICKAXE, HistoryFilter.REMOVE,
                "gui.history.filter_remove", "&cBroke", state.historyFilter));
        gui.setItem(47, historyFilterButton(player, Material.GRASS_BLOCK, HistoryFilter.PLACE,
                "gui.history.filter_place", "&aPlaced", state.historyFilter));
        gui.setItem(48, historyFilterButton(player, Material.LEVER, HistoryFilter.INTERACT,
                "gui.history.filter_interact", "&bInteracted", state.historyFilter));
        gui.setItem(49, dashButton(player, Material.BARRIER, "gui.history.back", "&7Back to Chunk Info",
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.history.back_lore", "Return to chunk details."))));
        gui.setItem(50, historyFilterButton(player, Material.BOOK, HistoryFilter.ALL,
                "gui.history.filter_all", "&eAll actions", state.historyFilter));
        gui.setItem(51, loreItem(Material.PAPER,
                ChatColor.YELLOW + plugin.getMessage(player, "gui.history.page", "Page {page}/{pages}")
                        .replace("{page}", Integer.toString(state.historyPage + 1))
                        .replace("{pages}", Integer.toString(totalPages)),
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.history.results", "{count} results")
                        .replace("{count}", Integer.toString(filtered.size())))));
        if (state.historyPage < totalPages - 1) {
            gui.setItem(53, dashButton(player, Material.ARROW, "gui.next_page", "&eNext Page", Collections.emptyList()));
        }
    }

    private boolean matchesHistoryFilter(CoreProtectBridge.HistoryEntry entry, HistoryFilter filter) {
        if (filter == null || filter == HistoryFilter.ALL) return true;
        if (filter == HistoryFilter.REMOVE) return "remove".equals(entry.action);
        if (filter == HistoryFilter.PLACE) return "place".equals(entry.action);
        return "interact".equals(entry.action);
    }

    private ItemStack historyFilterButton(Player player, Material material, HistoryFilter buttonFilter,
                                          String key, String fallback, HistoryFilter selectedFilter) {
        boolean selected = buttonFilter == selectedFilter;
        String name = (selected ? ChatColor.GREEN + "✓ " : ChatColor.GRAY + "")
                + ChatColor.translateAlternateColorCodes('&', plugin.getMessage(player, key, fallback));
        String lore = selected
                ? ChatColor.GREEN + plugin.getMessage(player, "gui.history.filter_active", "Active filter")
                : ChatColor.GRAY + plugin.getMessage(player, "gui.history.filter_click", "Click to filter");
        return loreItem(material, name, Collections.singletonList(lore));
    }

    private ItemStack buildHistoryEntry(Player player, CoreProtectBridge cp,
                                        CoreProtectBridge.HistoryEntry entry) {
        Material icon = Material.STONE;
        try {
            Material material = Material.matchMaterial(entry.material);
            if (material != null && material.isItem()) icon = material;
            else if ("place".equals(entry.action)) icon = Material.GRASS_BLOCK;
            else if ("remove".equals(entry.action)) icon = Material.IRON_PICKAXE;
        } catch (Throwable ignored) {}

        String actionLabel;
        ChatColor actionColor;
        if ("place".equals(entry.action)) {
            actionLabel = plugin.getMessage(player, "gui.history.action_place", "placed");
            actionColor = ChatColor.GREEN;
        } else if ("remove".equals(entry.action)) {
            actionLabel = plugin.getMessage(player, "gui.history.action_remove", "removed");
            actionColor = ChatColor.RED;
        } else {
            actionLabel = plugin.getMessage(player, "gui.history.action_interact", "interacted");
            actionColor = ChatColor.AQUA;
        }

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.history.player", "Player: ") + ChatColor.YELLOW + entry.player);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.history.action", "Action: ") + actionColor + actionLabel);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.history.block", "Block: ") + ChatColor.WHITE + entry.material);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.history.coords", "At: ") + ChatColor.WHITE + entry.x + ", " + entry.y + ", " + entry.z);
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.history.when", "When: ") + ChatColor.WHITE + cp.formatTimeAgo(player, entry.timestamp));
        return loreItem(icon, actionColor + entry.player + " " + actionLabel + " " + ChatColor.WHITE + entry.material, lore);
    }

    private String dashboardTimeAgo(Player player, long t) {
        if (t <= 0) return plugin.getMessage(player, "gui.time_never", "never");
        long ago = (System.currentTimeMillis() - t) / 1000;
        if (ago < 60) return plugin.getMessage(player, "gui.time_just_now", "Just now");
        if (ago < 3600) return (ago / 60) + plugin.getMessage(player, "gui.time_minutes_ago", " minutes ago");
        return (ago / 3600) + plugin.getMessage(player, "gui.time_hours_ago", " hours ago");
    }

    private ItemStack loreItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack dashButton(Player player, Material mat, String key, String defLabel, List<String> lore) {
        String name = ChatColor.translateAlternateColorCodes('&',
                plugin.getMessage(player, key, defLabel));
        return loreItem(mat, name, lore);
    }

    private String dashName(Player player, String key, String defLabel) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                plugin.getMessage(player, key, defLabel)));
    }

    private void handleDashboardClick(Player player, String displayName,
                                      boolean isShiftClick, boolean isRightClick) {
        String s = ChatColor.stripColor(displayName);
        if (s.equals(dashName(player, "gui.dashboard.chunks", "&aProblem Chunks"))) {
            openWorldSelectionGUI(player);
        } else if (s.equals(dashName(player, "gui.dashboard.scan", "&aRun Scan"))) {
            boolean running = plugin.getScanManager() != null && plugin.getScanManager().isRunning();
            player.closeInventory();
            player.performCommand(running ? "rd scan cancel" : "rd scan");
        } else if (s.equals(dashName(player, "gui.dashboard.freeze", "&bToggle Global Freeze"))) {
            if (plugin.isRedstoneFrozen()) {

                player.performCommand("rd unfreeze");
            } else if (isShiftClick && isRightClick) {

                player.performCommand("rd freeze");
            } else {

                plugin.smartFreezeNow(player);
            }
            openDashboard(player);
        } else if (s.equals(dashName(player, "gui.dashboard.frozen_chunks", "&bFrozen Chunks"))) {
            openFrozenChunksGui(player);
        } else if (s.equals(dashName(player, "gui.dashboard.reload", "&dReload Plugin"))) {
            player.closeInventory();
            player.performCommand("rd reload");
        } else if (s.equals(dashName(player, "gui.dashboard.close", "&cClose"))) {
            player.closeInventory();
        }
    }

    private void handleChunkInfoClick(Player player, PlayerGuiState state, String displayName) {
        String s = ChatColor.stripColor(displayName);
        ChunkCoordinate coord = state.chunkCoord;
        if (coord == null) return;
        if (s.equals(dashName(player, "gui.chunkinfo.back_list", "&7Back to Chunk List"))) {
            openChunksGUI(player, state.world != null ? state.world : coord.world(), state.page);
        } else if (s.equals(dashName(player, "gui.chunk_teleport", "&fTeleport to Chunk"))) {
            plugin.teleportToChunk(player, coord);
            player.closeInventory();
        } else if (s.equals(dashName(player, "gui.chunkinfo.freeze_stop", "&bFreeze Redstone (stop)"))) {
            plugin.freezeChunkRedstone(player, coord);
            openChunkInfoGui(player, coord);
        } else if (s.equals(dashName(player, "gui.chunkinfo.unfreeze_stop", "&aUnfreeze Redstone"))) {
            plugin.unfreezeChunkRedstone(player, coord);
            openChunkInfoGui(player, coord);
        } else if (s.equals(dashName(player, "gui.chunkinfo.remove", "&cRemove Redstone"))) {
            plugin.disableRedstoneInChunk(player, coord);
            openChunkInfoGui(player, coord);
        } else if (s.equals(dashName(player, "gui.chunkinfo.restore", "&aRestore Redstone"))) {
            plugin.restoreRedstoneInChunk(player, coord);
            openChunkInfoGui(player, coord);
        } else if (s.equals(dashName(player, "gui.chunkinfo.remove_entities", "&6Remove Entities"))) {
            plugin.removeEntitiesInChunk(player, coord);
            openChunkInfoGui(player, coord);
        } else if (s.equals(dashName(player, "gui.chunkinfo.history", "&eHistory"))) {
            openChunkHistoryGui(player, coord);
        }
    }

    private void handleChunkHistoryClick(Player player, PlayerGuiState state, String displayName) {
        String s = ChatColor.stripColor(displayName);
        ChunkCoordinate coord = state.chunkCoord;
        if (coord == null) return;
        if (s.equals(dashName(player, "gui.history.back", "&7Back to Chunk Info"))) {
            openChunkInfoGui(player, coord);
        } else if (s.equals(dashName(player, "gui.previous_page", "&ePrevious Page"))) {
            state.historyPage--;
            refreshChunkHistory(player, state);
        } else if (s.equals(dashName(player, "gui.next_page", "&eNext Page"))) {
            state.historyPage++;
            refreshChunkHistory(player, state);
        } else if (s.equals(stripHistoryFilterMarker(dashName(player, "gui.history.filter_remove", "&cBroke")))) {
            state.historyFilter = HistoryFilter.REMOVE;
            state.historyPage = 0;
            refreshChunkHistory(player, state);
        } else if (s.equals(stripHistoryFilterMarker(dashName(player, "gui.history.filter_place", "&aPlaced")))) {
            state.historyFilter = HistoryFilter.PLACE;
            state.historyPage = 0;
            refreshChunkHistory(player, state);
        } else if (s.equals(stripHistoryFilterMarker(dashName(player, "gui.history.filter_interact", "&bInteracted")))) {
            state.historyFilter = HistoryFilter.INTERACT;
            state.historyPage = 0;
            refreshChunkHistory(player, state);
        } else if (s.equals(stripHistoryFilterMarker(dashName(player, "gui.history.filter_all", "&eAll actions")))) {
            state.historyFilter = HistoryFilter.ALL;
            state.historyPage = 0;
            refreshChunkHistory(player, state);
        }
    }

    private String stripHistoryFilterMarker(String value) {
        return value.startsWith("✓ ") ? value.substring(2) : value;
    }

    private void refreshChunkHistory(Player player, PlayerGuiState state) {
        CoreProtectBridge cp = plugin.getCoreProtectBridge();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (cp != null && top != null && top.getHolder() instanceof GuiManager) {
            renderChunkHistoryGui(player, top, state, cp);
            player.updateInventory();
        }
    }

    public void openWorldSelectionGUI(Player player) {
        markTransition(player);
        String title = plugin.getMessage(player, "gui.world_selection_title", "Select a World");
        Inventory gui = Bukkit.createInventory(this, 45, title);
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int[] centerSlots = {12, 13, 14, 21, 22, 23, 30, 31, 32};

        for (int i = 0; i < Math.min(worlds.size(), centerSlots.length); i++) {
            World world = worlds.get(i);
            Material icon = getWorldIcon(world);
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GREEN + world.getName());
                String viewChunksText = plugin.getMessage(player, "gui.world_view_chunks", "Click to view chunks");
                meta.setLore(Collections.singletonList(ChatColor.GRAY + viewChunksText));
                item.setItemMeta(meta);
            }
            gui.setItem(centerSlots[i], item);
        }

        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    plugin.getMessage(player, "gui.world_back", "&7Back to Menu")));
            backMeta.setLore(Collections.singletonList(ChatColor.GRAY +
                    plugin.getMessage(player, "gui.world_back_lore", "Return to the main menu.")));
            backBtn.setItemMeta(backMeta);
        }
        gui.setItem(40, backBtn);

        PlayerGuiState state = playerStates.get(player.getUniqueId());
        if (state == null) {
            state = new PlayerGuiState(GuiState.WORLD_SELECTION);
            playerStates.put(player.getUniqueId(), state);
        } else {
            state.state = GuiState.WORLD_SELECTION;
        }

        player.openInventory(gui);
    }

    private Material getWorldIcon(World world) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            return Material.NETHERRACK;
        } else if (world.getEnvironment() == World.Environment.THE_END) {
            return Material.END_STONE;
        } else {
            return Material.GRASS_BLOCK;
        }
    }

    public void openChunksGUI(Player player, String worldName, int page) {
        markTransition(player);
        PlayerGuiState state = playerStates.get(player.getUniqueId());
        if (state == null) {
            state = new PlayerGuiState(GuiState.CHUNK_LIST);
            state.world = worldName;
            state.page = page;
            playerStates.put(player.getUniqueId(), state);
        }
        SortMode currentSort = state.sortMode != null ? state.sortMode : SortMode.COORDINATE;

        List<Map.Entry<ChunkCoordinate, ChunkData>> filteredChunks = new ArrayList<>();

        long retentionTime = System.currentTimeMillis() - (plugin.getConfig().getLong("chunk-data-retention", 24) * 60 * 60 * 1000L);

        for (Map.Entry<ChunkCoordinate, ChunkData> entry : plugin.getChunkMap().entrySet()) {
            ChunkCoordinate coord = entry.getKey();
            ChunkData data = entry.getValue();

            if (coord.world().equals(worldName) &&
                    data.lastScanned >= retentionTime &&
                    ActivityAnalyzer.isSuspicious(data, plugin.getMaxRedstone(), plugin.getMaxEntities())) {
                filteredChunks.add(entry);
            }
        }

        filteredChunks.sort(getChunkComparator(currentSort));

        int chunksPerPage = 36;
        int totalPages = Math.max(1, (int) Math.ceil((double) filteredChunks.size() / chunksPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = plugin.getMessage(player, "gui.chunk_list_title", "Chunks in {world} (Page {page}/{total})")
                .replace("{world}", worldName)
                .replace("{page}", String.valueOf(page + 1))
                .replace("{total}", String.valueOf(totalPages));

        Inventory gui = Bukkit.createInventory(this, 54, title);

        ItemStack border = createItem(Material.AIR, " ");
        for (int i = 0; i < 9; i++) {
            gui.setItem(i, border);
        }

        ItemStack compass = createItem(Material.COMPASS,
                ChatColor.AQUA + plugin.getMessage(player, "gui.search_compass_title", "Search Chunk"));
        ItemMeta compassMeta = compass.getItemMeta();
        if (compassMeta != null) {
            compassMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + plugin.getMessage(player, "gui.search_compass_line1", "Click to search for a chunk"),
                    ChatColor.GRAY + plugin.getMessage(player, "gui.search_compass_line2", "Supports chunk coordinates (e.g. 5 -3)"),
                    ChatColor.GRAY + plugin.getMessage(player, "gui.search_compass_line3", "or block coordinates (e.g. 80 -48 → 5 -3)")
            ));
            compass.setItemMeta(compassMeta);
        }
        gui.setItem(4, compass);

        int start = page * chunksPerPage;
        int end = Math.min(start + chunksPerPage, filteredChunks.size());

        for (int i = start; i < end; i++) {
            Map.Entry<ChunkCoordinate, ChunkData> entry = filteredChunks.get(i);
            gui.setItem(9 + (i - start), createChunkItem(player, entry.getKey(), entry.getValue()));
        }

        addNavigationButtons(player, gui, page, totalPages, worldName);

        ItemStack sortButton = createItem(Material.HOPPER,
                ChatColor.YELLOW + plugin.getMessage(player, "gui.sort_title", "Sort Chunks"));
        ItemMeta sortMeta = sortButton.getItemMeta();
        if (sortMeta != null) {
            List<String> sortLore = new ArrayList<>();
            SortMode[] modes = SortMode.values();
            for (SortMode mode : modes) {
                String sortName = getSortDisplayName(player, mode);
                if (mode == currentSort) {
                    sortLore.add(ChatColor.GREEN + sortName + " " + plugin.getMessage(player, "gui.current_sort", "(Current)"));
                } else {
                    sortLore.add(ChatColor.GRAY + sortName);
                }
            }
            sortLore.add("");
            sortLore.add(ChatColor.YELLOW + plugin.getMessage(player, "gui.sort_lclick", "Left-click: Next sort"));
            sortLore.add(ChatColor.YELLOW + plugin.getMessage(player, "gui.sort_rclick", "Right-click: Previous sort"));
            sortMeta.setLore(sortLore);
            sortButton.setItemMeta(sortMeta);
        }
        gui.setItem(50, sortButton);

        state.state = GuiState.CHUNK_LIST;
        state.world = worldName;
        state.page = page;
        state.sortMode = currentSort;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
    }

    private Comparator<Map.Entry<ChunkCoordinate, ChunkData>> getChunkComparator(SortMode mode) {
        return (a, b) -> {
            ChunkData dataA = a.getValue();
            ChunkData dataB = b.getValue();
            ChunkCoordinate coordA = a.getKey();
            ChunkCoordinate coordB = b.getKey();

            int cmp;
            switch (mode) {
                case REDSTONE:
                    cmp = Integer.compare(dataB.redstoneCount.get(), dataA.redstoneCount.get());
                    if (cmp != 0) return cmp;
                    cmp = Integer.compare(dataB.entityCount.get(), dataA.entityCount.get());
                    if (cmp != 0) return cmp;
                    break;
                case ENTITIES:
                    cmp = Integer.compare(dataB.entityCount.get(), dataA.entityCount.get());
                    if (cmp != 0) return cmp;
                    cmp = Integer.compare(dataB.redstoneCount.get(), dataA.redstoneCount.get());
                    if (cmp != 0) return cmp;
                    break;
                case COORDINATE:
                default:
                    break;
            }
            cmp = Integer.compare(coordA.x(), coordB.x());
            if (cmp != 0) return cmp;
            return Integer.compare(coordA.z(), coordB.z());
        };
    }

    private String getSortDisplayName(Player player, SortMode mode) {
        switch (mode) {
            case COORDINATE:
                return plugin.getMessage(player, "gui.sort_coordinate", "By Coordinate");
            case REDSTONE:
                return plugin.getMessage(player, "gui.sort_redstone", "By Redstone Count");
            case ENTITIES:
                return plugin.getMessage(player, "gui.sort_entities", "By Entity Count");
            default:
                return "Unknown";
        }
    }

    private ItemStack createChunkItem(Player player, ChunkCoordinate coord, ChunkData data) {
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage(player, "gui.chunk_item_name", "Chunk {coord}")
                .replace("{coord}", coord.toDisplayString()));

        int bx1 = coord.x() * 16;
        int bz1 = coord.z() * 16;

        long minutesAgo = (System.currentTimeMillis() - data.lastScanned) / 60000;
        String timeAgo;
        if (minutesAgo == 0) {
            timeAgo = plugin.getMessage(player, "gui.time_just_now", "Just now");
        } else if (minutesAgo < 60) {
            timeAgo = minutesAgo + plugin.getMessage(player, "gui.time_minutes_ago", " min. ago");
        } else {
            timeAgo = (minutesAgo / 60) + plugin.getMessage(player, "gui.time_hours_ago", " h. ago");
        }

        List<String> lore = Arrays.asList(
                ChatColor.GRAY + "§l" + plugin.getMessage(player, "gui.chunk_coordinates", "Chunk Coordinates:"),
                ChatColor.WHITE + "  X: " + bx1 + " — " + (bx1 + 15),
                ChatColor.WHITE + "  Z: " + bz1 + " — " + (bz1 + 15),
                ChatColor.AQUA + "  ID: " + coord.x() + ", " + coord.z(),
                "",
                ChatColor.GRAY + plugin.getMessage(player, "gui.chunk_world", "World: {world}").replace("{world}", coord.world()),
                ChatColor.RED + plugin.getMessage(player, "gui.chunk_redstone", "Redstone: {count}").replace("{count}", String.valueOf(data.redstoneCount.get())),
                ChatColor.GREEN + plugin.getMessage(player, "gui.chunk_entities", "Entities: {count}").replace("{count}", String.valueOf(data.entityCount.get())),
                ChatColor.GRAY + plugin.getMessage(player, "gui.chunk_detected", "Detected: {time}").replace("{time}", timeAgo),
                "",
                ChatColor.YELLOW + plugin.getMessage(player, "gui.chunk_lclick_info", "Left click → Open info"),
                ChatColor.GOLD + plugin.getMessage(player, "gui.chunk_shift_rclick", "Shift + Right click → Remove redstone")
        );
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void addNavigationButtons(Player player, Inventory gui, int page, int totalPages, String worldName) {
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage(player, "gui.previous_page", "Previous Page"));
                prev.setItemMeta(meta);
            }
            gui.setItem(45, prev);
        }

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage(player, "gui.next_page", "Next Page"));
                next.setItemMeta(meta);
            }
            gui.setItem(53, next);
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta meta = back.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + plugin.getMessage(player, "gui.back_to_worlds", "Back to Worlds"));
            back.setItemMeta(meta);
        }
        gui.setItem(49, back);
    }

    public void openChunkActionsMenu(Player player, ChunkCoordinate coord) {
        markTransition(player);
        String title = plugin.getMessage(player, "gui.chunk_actions_title", "Chunk Actions");
        Inventory gui = Bukkit.createInventory(this, 27, title);

        ItemStack currentChunk = new ItemStack(Material.MAP);
        ItemMeta meta = currentChunk.getItemMeta();
        if (meta != null) {
            int x1 = coord.x() * 16;
            int z1 = coord.z() * 16;
            meta.setDisplayName(ChatColor.AQUA + "Current Chunk " + coord.toDisplayString());

            List<String> lore = Arrays.asList(
                    ChatColor.GRAY + "World: " + ChatColor.WHITE + coord.world(),
                    ChatColor.GRAY + "Blocks X: " + ChatColor.WHITE + x1 + " — " + (x1 + 15),
                    ChatColor.GRAY + "Blocks Z: " + ChatColor.WHITE + z1 + " — " + (z1 + 15),
                    "",
                    ChatColor.YELLOW + "Actions for this chunk"
            );
            meta.setLore(lore);
            currentChunk.setItemMeta(meta);
        }
        gui.setItem(4, currentChunk);

        gui.setItem(10, createActionItem(player, Material.BOOK, "gui.chunk_info", "View Chunk Details"));
        gui.setItem(12, createActionItem(player, Material.ENDER_PEARL, "gui.chunk_teleport", "Teleport to Chunk"));
        gui.setItem(14, createActionItem(player, Material.REDSTONE_BLOCK, "gui.chunk_remove_redstone", "Remove Redstone"));
        gui.setItem(15, createActionItem(player, Material.BLAZE_ROD, "gui.chunk_remove_entities", "Remove Entities"));
        gui.setItem(16, createActionItem(player, Material.EMERALD, "gui.chunk_restore_redstone", "Restore Redstone"));

        gui.setItem(22, createActionItem(player, Material.ARROW, "gui.back_to_chunks", "Back to Chunk List"));

        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_ACTIONS);
        state.world = coord.world();
        state.chunkCoord = coord;
        state.page = playerStates.getOrDefault(player.getUniqueId(), new PlayerGuiState(GuiState.WORLD_SELECTION)).page;
        state.sortMode = playerStates.getOrDefault(player.getUniqueId(), new PlayerGuiState(GuiState.WORLD_SELECTION)).sortMode;
        playerStates.put(player.getUniqueId(), state);

        player.openInventory(gui);
    }

    private ItemStack createActionItem(Player player, Material material, String msgKey, String fallback) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + plugin.getMessage(player, msgKey, fallback));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (!(event.getView().getTopInventory().getHolder() instanceof GuiManager)) {
            return;
        }

        PlayerGuiState state = playerStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
        }

        if (event.getClickedInventory() == event.getView().getBottomInventory() && !event.isShiftClick()) {
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        if (state.state == GuiState.CHUNK_LIST && event.getSlot() == 4 && clicked.getType() == Material.COMPASS) {
            player.closeInventory();
            plugin.sendSearchPrompt(player);
            ChatListener.waitingForChunkSearch.put(player.getUniqueId(), state.world);
            return;
        }

        if (!clicked.hasItemMeta() || !clicked.getItemMeta().hasDisplayName()) {
            return;
        }

        String displayName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        switch (state.state) {
            case WORLD_SELECTION:
                handleWorldSelectionClick(player, displayName);
                break;
            case CHUNK_LIST:
                handleChunkListClick(player, state, displayName, clicked, event.isShiftClick(), event.isRightClick(), event.getSlot());
                break;
            case CHUNK_ACTIONS:
                handleChunkActionsClick(player, state, displayName);
                break;
            case DASHBOARD:
                handleDashboardClick(player, displayName, event.isShiftClick(), event.isRightClick());
                break;
            case CHUNK_INFO:
                handleChunkInfoClick(player, state, displayName);
                break;
            case CHUNK_HISTORY:
                handleChunkHistoryClick(player, state, displayName);
                break;
            case FROZEN_LIST:
                handleFrozenListClick(player, state, displayName, event.getSlot());
                break;
        }
    }

    private void handleWorldSelectionClick(Player player, String displayName) {
        String back = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                plugin.getMessage(player, "gui.world_back", "&7Back to Menu")));
        if (ChatColor.stripColor(displayName).equals(back)) {
            openDashboard(player);
            return;
        }
        openChunksGUI(player, displayName, 0);
    }

    private void handleChunkListClick(Player player, PlayerGuiState state, String displayName, ItemStack item,
                                      boolean isShiftClick, boolean isRightClick, int slot) {
        String strippedDisplayName = ChatColor.stripColor(displayName);
        String backToWorlds = ChatColor.stripColor(plugin.getMessage(player, "gui.back_to_worlds", "Back to Worlds"));
        String previousPage = ChatColor.stripColor(plugin.getMessage(player, "gui.previous_page", "Previous Page"));
        String nextPage = ChatColor.stripColor(plugin.getMessage(player, "gui.next_page", "Next Page"));
        String sortTitle = ChatColor.stripColor(plugin.getMessage(player, "gui.sort_title", "Sort Chunks"));

        if (strippedDisplayName.equals(backToWorlds)) {
            openWorldSelectionGUI(player);
        } else if (strippedDisplayName.equals(previousPage)) {
            openChunksGUI(player, state.world, state.page - 1);
        } else if (strippedDisplayName.equals(nextPage)) {
            openChunksGUI(player, state.world, state.page + 1);
        } else if (strippedDisplayName.equals(sortTitle) && slot == 50) {
            SortMode newSort;
            SortMode[] modes = SortMode.values();
            int currentIndex = Arrays.asList(modes).indexOf(state.sortMode);
            if (isRightClick) {
                newSort = modes[(currentIndex - 1 + modes.length) % modes.length];
            } else {
                newSort = modes[(currentIndex + 1) % modes.length];
            }
            state.sortMode = newSort;
            openChunksGUI(player, state.world, state.page);
        } else if (item != null && item.getType() == Material.MAP) {
            String chunkText = ChatColor.stripColor(plugin.getMessage(player, "gui.chunk_item_name", "Chunk {coord}"));
            String chunkName = strippedDisplayName.replace(chunkText.replace("{coord}", ""), "").trim();
            chunkName = chunkName.replace("[", "").replace("]", "");
            String[] parts = chunkName.split(", ");

            if (parts.length == 2) {
                try {
                    int x = Integer.parseInt(parts[0]);
                    int z = Integer.parseInt(parts[1]);
                    ChunkCoordinate coord = new ChunkCoordinate(state.world, x, z);

                    if (isShiftClick && isRightClick) {
                        plugin.disableRedstoneInChunk(player, coord);
                        player.closeInventory();
                        openChunksGUI(player, state.world, state.page);
                    } else if (!isShiftClick && !isRightClick) {
                        openChunkInfoGui(player, coord);
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage(player, "gui.error_chunk_processing", "Error processing coordinates"));
                }
            }
        }
    }

    private void handleChunkActionsClick(Player player, PlayerGuiState state, String displayName) {
        String strippedDisplayName = ChatColor.stripColor(displayName);
        String backToChunks = ChatColor.stripColor(plugin.getMessage(player, "gui.back_to_chunks", "Back to Chunks"));
        String chunkInfo = ChatColor.stripColor(plugin.getMessage(player, "gui.chunk_info", "View Chunk Details"));
        String chunkTeleport = ChatColor.stripColor(plugin.getMessage(player, "gui.chunk_teleport", "Teleport to Chunk"));
        String removeRedstone = ChatColor.stripColor(plugin.getMessage(player, "gui.chunk_remove_redstone", "Remove Redstone"));
        String removeEntities = ChatColor.stripColor(plugin.getMessage(player, "gui.chunk_remove_entities", "Remove Entities"));
        String restoreRedstone = ChatColor.stripColor(plugin.getMessage(player, "gui.chunk_restore_redstone", "Restore Redstone"));

        if (strippedDisplayName.equals(backToChunks)) {
            openChunksGUI(player, state.world, state.page);
        } else if (strippedDisplayName.equals(chunkInfo)) {
            plugin.openChunkDetails(player, state.chunkCoord);
            player.closeInventory();
        } else if (strippedDisplayName.equals(chunkTeleport)) {
            plugin.teleportToChunk(player, state.chunkCoord);
            player.closeInventory();
        } else if (strippedDisplayName.equals(removeRedstone)) {
            plugin.disableRedstoneInChunk(player, state.chunkCoord);
            player.closeInventory();
        } else if (strippedDisplayName.equals(removeEntities)) {
            plugin.removeEntitiesInChunk(player, state.chunkCoord);
            player.closeInventory();
        } else if (strippedDisplayName.equals(restoreRedstone)) {
            plugin.restoreRedstoneInChunk(player, state.chunkCoord);
            player.closeInventory();
        }
    }

    @EventHandler public void onPlayerQuit(PlayerQuitEvent event) { savePlayerStates(); }
    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player && event.getPlayer().hasPermission("redstonedetector.admin")) {
            ru.stepanyaa.redstoneDetector.platform.Platforms.scheduler().async(this::savePlayerStates);
        }
    }

    public void savePlayerStates() {
        File file = new File(plugin.getDataFolder(), "player_states.yml");
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<UUID, PlayerGuiState> entry : playerStates.entrySet()) {
            String path = "states." + entry.getKey().toString() + ".";
            PlayerGuiState state = entry.getValue();

            config.set(path + "state", state.state.name());
            config.set(path + "world", state.world);
            config.set(path + "page", state.page);
            config.set(path + "sortMode", state.sortMode.name());
            if (state.chunkCoord != null) {
                config.set(path + "chunkCoord", state.chunkCoord.toString());
            } else {
                config.set(path + "chunkCoord", null);
            }
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить состояния игроков: " + e.getMessage());
        }
    }

    public void loadPlayerStates() {
        try {
            File file = new File(plugin.getDataFolder(), "player_states.yml");
            if (!file.exists()) return;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection statesSection = config.getConfigurationSection("states");
            if (statesSection == null) return;

            for (String key : statesSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(key);
                    String path = key + ".";

                    String stateName = statesSection.getString(path + "state");
                    if (stateName == null) continue;

                    PlayerGuiState state = new PlayerGuiState(GuiState.valueOf(stateName));
                    state.world = statesSection.getString(path + "world");
                    state.page = statesSection.getInt(path + "page", 0);

                    String coordStr = statesSection.getString(path + "chunkCoord");
                    if (coordStr != null && !coordStr.isEmpty()) {
                        try {
                            state.chunkCoord = ChunkCoordinate.fromString(coordStr);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Не удалось загрузить координаты чанка для " + key);
                        }
                    }

                    String sortStr = statesSection.getString(path + "sortMode", "COORDINATE");
                    try {
                        state.sortMode = SortMode.valueOf(sortStr);
                    } catch (IllegalArgumentException e) {
                        state.sortMode = SortMode.COORDINATE;
                    }

                    playerStates.put(playerId, state);
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка при загрузке состояния игрока " + key + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка loadPlayerStates: " + e.getMessage());
        }
    }
    private void markTransition(Player player) { transitioningPlayers.add(player.getUniqueId()); ru.stepanyaa.redstoneDetector.platform.Platforms.scheduler().delay(1L, () -> transitioningPlayers.remove(player.getUniqueId())); }
}
