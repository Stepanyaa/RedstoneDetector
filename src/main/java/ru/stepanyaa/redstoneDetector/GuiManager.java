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
    @Override
    public Inventory getInventory() {
        return null;
    }

    public enum GuiState {
        WORLD_SELECTION, CHUNK_LIST, CHUNK_ACTIONS
    }

    public static class PlayerGuiState {
        public GuiState state;
        public String world;
        public int page;
        public ChunkCoordinate chunkCoord;
        public SortMode sortMode;

        public PlayerGuiState(GuiState state) {
            this.state = state;
            this.sortMode = SortMode.COORDINATE;
        }
    }

    private final RedstoneDetector plugin;
    private final Map<UUID, PlayerGuiState> playerStates = new HashMap<>();

    public GuiManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void openWorldSelectionGUI(Player player) {
        markTransition(player);
        String title = plugin.getMessage("gui.world_selection_title", "Select a World");
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
                String viewChunksText = plugin.getMessage("gui.world_view_chunks", "Click to view chunks");
                meta.setLore(Collections.singletonList(ChatColor.GRAY + viewChunksText));
                item.setItemMeta(meta);
            }
            gui.setItem(centerSlots[i], item);
        }

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
                    !data.clearedByAdmin &&
                    (data.redstoneCount.get() > plugin.getMaxRedstone() || data.entityCount.get() > plugin.getMaxEntities())) {
                filteredChunks.add(entry);
            }
        }

        filteredChunks.sort(getChunkComparator(currentSort));

        int chunksPerPage = 36;
        int totalPages = Math.max(1, (int) Math.ceil((double) filteredChunks.size() / chunksPerPage));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = plugin.getMessage("gui.chunk_list_title", "Chunks in {world} (Page {page}/{total})")
                .replace("{world}", worldName)
                .replace("{page}", String.valueOf(page + 1))
                .replace("{total}", String.valueOf(totalPages));

        Inventory gui = Bukkit.createInventory(this, 54, title);

        ItemStack border = createItem(Material.AIR, " ");
        for (int i = 0; i < 9; i++) {
            gui.setItem(i, border);
        }

        ItemStack compass = createItem(Material.COMPASS,
                ChatColor.AQUA + plugin.getMessage("gui.search_compass_title", "Search Chunk"));
        ItemMeta compassMeta = compass.getItemMeta();
        if (compassMeta != null) {
            compassMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + plugin.getMessage("gui.search_compass_line1", "Click to search for a chunk"),
                    ChatColor.GRAY + plugin.getMessage("gui.search_compass_line2", "Supports chunk coordinates (e.g. 5 -3)"),
                    ChatColor.GRAY + plugin.getMessage("gui.search_compass_line3", "or block coordinates (e.g. 80 -48 → 5 -3)")
            ));
            compass.setItemMeta(compassMeta);
        }
        gui.setItem(4, compass);

        int start = page * chunksPerPage;
        int end = Math.min(start + chunksPerPage, filteredChunks.size());

        for (int i = start; i < end; i++) {
            Map.Entry<ChunkCoordinate, ChunkData> entry = filteredChunks.get(i);
            gui.setItem(9 + (i - start), createChunkItem(entry.getKey(), entry.getValue()));
        }

        addNavigationButtons(gui, page, totalPages, worldName);

        ItemStack sortButton = createItem(Material.HOPPER,
                ChatColor.YELLOW + plugin.getMessage("gui.sort_title", "Sort Chunks"));
        ItemMeta sortMeta = sortButton.getItemMeta();
        if (sortMeta != null) {
            List<String> sortLore = new ArrayList<>();
            SortMode[] modes = SortMode.values();
            for (SortMode mode : modes) {
                String sortName = getSortDisplayName(mode);
                if (mode == currentSort) {
                    sortLore.add(ChatColor.GREEN + sortName + " " + plugin.getMessage("gui.current_sort", "(Current)"));
                } else {
                    sortLore.add(ChatColor.GRAY + sortName);
                }
            }
            sortLore.add("");
            sortLore.add(ChatColor.YELLOW + plugin.getMessage("gui.sort_lclick", "Left-click: Next sort"));
            sortLore.add(ChatColor.YELLOW + plugin.getMessage("gui.sort_rclick", "Right-click: Previous sort"));
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

    private String getSortDisplayName(SortMode mode) {
        switch (mode) {
            case COORDINATE:
                return plugin.getMessage("gui.sort_coordinate", "By Coordinate");
            case REDSTONE:
                return plugin.getMessage("gui.sort_redstone", "By Redstone Count");
            case ENTITIES:
                return plugin.getMessage("gui.sort_entities", "By Entity Count");
            default:
                return "Unknown";
        }
    }

    private ItemStack createChunkItem(ChunkCoordinate coord, ChunkData data) {
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.chunk_item_name", "Chunk {coord}")
                .replace("{coord}", coord.toDisplayString()));

        int bx1 = coord.x() * 16;
        int bz1 = coord.z() * 16;

        long minutesAgo = (System.currentTimeMillis() - data.lastScanned) / 60000;
        String timeAgo;
        if (minutesAgo == 0) {
            timeAgo = plugin.getMessage("gui.time_just_now", "Just now");
        } else if (minutesAgo < 60) {
            timeAgo = minutesAgo + plugin.getMessage("gui.time_minutes_ago", " min. ago");
        } else {
            timeAgo = (minutesAgo / 60) + plugin.getMessage("gui.time_hours_ago", " h. ago");
        }

        List<String> lore = Arrays.asList(
                ChatColor.GRAY + "§l" + plugin.getMessage("gui.chunk_coordinates", "Chunk Coordinates:"),
                ChatColor.WHITE + "  X: " + bx1 + " — " + (bx1 + 15),
                ChatColor.WHITE + "  Z: " + bz1 + " — " + (bz1 + 15),
                ChatColor.AQUA + "  ID: " + coord.x() + ", " + coord.z(),
                "",
                ChatColor.GRAY + plugin.getMessage("gui.chunk_world", "World: {world}").replace("{world}", coord.world()),
                ChatColor.RED + plugin.getMessage("gui.chunk_redstone", "Redstone: {count}").replace("{count}", String.valueOf(data.redstoneCount.get())),
                ChatColor.GREEN + plugin.getMessage("gui.chunk_entities", "Entities: {count}").replace("{count}", String.valueOf(data.entityCount.get())),
                ChatColor.GRAY + plugin.getMessage("gui.chunk_detected", "Detected: {time}").replace("{time}", timeAgo),
                "",
                ChatColor.YELLOW + plugin.getMessage("gui.chunk_lclick", "Left click → Open actions"),
                ChatColor.GOLD + plugin.getMessage("gui.chunk_shift_rclick", "Shift + Right click → Remove redstone")
        );
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void addNavigationButtons(Inventory gui, int page, int totalPages, String worldName) {
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.previous_page", "Previous Page"));
                prev.setItemMeta(meta);
            }
            gui.setItem(45, prev);
        }

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.next_page", "Next Page"));
                next.setItemMeta(meta);
            }
            gui.setItem(53, next);
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta meta = back.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back_to_worlds", "Back to Worlds"));
            back.setItemMeta(meta);
        }
        gui.setItem(49, back);
    }

    public void openChunkActionsMenu(Player player, ChunkCoordinate coord) {
        markTransition(player);
        String title = plugin.getMessage("gui.chunk_actions_title", "Chunk Actions");
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

        gui.setItem(10, createActionItem(Material.BOOK, "gui.chunk_info", "View Chunk Details"));
        gui.setItem(12, createActionItem(Material.ENDER_PEARL, "gui.chunk_teleport", "Teleport to Chunk"));
        gui.setItem(14, createActionItem(Material.REDSTONE_BLOCK, "gui.chunk_remove_redstone", "Remove Redstone"));
        gui.setItem(15, createActionItem(Material.BLAZE_ROD, "gui.chunk_remove_entities", "Remove Entities"));
        gui.setItem(16, createActionItem(Material.EMERALD, "gui.chunk_restore_redstone", "Restore Redstone"));

        gui.setItem(22, createActionItem(Material.ARROW, "gui.back_to_chunks", "Back to Chunk List"));

        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_ACTIONS);
        state.world = coord.world();
        state.chunkCoord = coord;
        state.page = playerStates.getOrDefault(player.getUniqueId(), new PlayerGuiState(GuiState.WORLD_SELECTION)).page;
        state.sortMode = playerStates.getOrDefault(player.getUniqueId(), new PlayerGuiState(GuiState.WORLD_SELECTION)).sortMode;
        playerStates.put(player.getUniqueId(), state);

        player.openInventory(gui);
    }

    private ItemStack createActionItem(Material material, String msgKey, String fallback) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + plugin.getMessage(msgKey, fallback));
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
        }
    }

    private void handleWorldSelectionClick(Player player, String displayName) {
        openChunksGUI(player, displayName, 0);
    }

    private void handleChunkListClick(Player player, PlayerGuiState state, String displayName, ItemStack item,
                                      boolean isShiftClick, boolean isRightClick, int slot) {
        String strippedDisplayName = ChatColor.stripColor(displayName);
        String backToWorlds = ChatColor.stripColor(plugin.getMessage("gui.back_to_worlds", "Back to Worlds"));
        String previousPage = ChatColor.stripColor(plugin.getMessage("gui.previous_page", "Previous Page"));
        String nextPage = ChatColor.stripColor(plugin.getMessage("gui.next_page", "Next Page"));
        String sortTitle = ChatColor.stripColor(plugin.getMessage("gui.sort_title", "Sort Chunks"));

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
            String chunkText = ChatColor.stripColor(plugin.getMessage("gui.chunk_item_name", "Chunk {coord}"));
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
                        openChunkActionsMenu(player, coord);
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("gui.error_chunk_processing", "Error processing coordinates"));
                }
            }
        }
    }

    private void handleChunkActionsClick(Player player, PlayerGuiState state, String displayName) {
        String strippedDisplayName = ChatColor.stripColor(displayName);
        String backToChunks = ChatColor.stripColor(plugin.getMessage("gui.back_to_chunks", "Back to Chunks"));
        String chunkInfo = ChatColor.stripColor(plugin.getMessage("gui.chunk_info", "View Chunk Details"));
        String chunkTeleport = ChatColor.stripColor(plugin.getMessage("gui.chunk_teleport", "Teleport to Chunk"));
        String removeRedstone = ChatColor.stripColor(plugin.getMessage("gui.chunk_remove_redstone", "Remove Redstone"));
        String removeEntities = ChatColor.stripColor(plugin.getMessage("gui.chunk_remove_entities", "Remove Entities"));
        String restoreRedstone = ChatColor.stripColor(plugin.getMessage("gui.chunk_restore_redstone", "Restore Redstone"));

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
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::savePlayerStates);
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
    private void markTransition(Player player) { transitioningPlayers.add(player.getUniqueId()); Bukkit.getScheduler().runTaskLater(plugin, () -> transitioningPlayers.remove(player.getUniqueId()), 1L); }
}