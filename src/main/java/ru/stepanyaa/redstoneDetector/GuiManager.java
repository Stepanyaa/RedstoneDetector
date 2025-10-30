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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class GuiManager implements Listener {

    public enum GuiState {
        WORLD_SELECTION, CHUNK_LIST, CHUNK_ACTIONS
    }

    public static class PlayerGuiState {
        public GuiState state;
        public String world;
        public int page;
        public RedstoneDetector.ChunkCoordinate chunkCoord;

        public PlayerGuiState(GuiState state) {
            this.state = state;
        }
    }

    private final RedstoneDetector plugin;
    private final Map<UUID, PlayerGuiState> playerStates = new HashMap<>();

    public GuiManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public void openWorldSelectionGUI(Player player) {
        String title = plugin.getMessage("gui.world_selection_title", "Select a World");
        Inventory gui = Bukkit.createInventory(null, 45, title);
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int[] centerSlots = {12, 13, 14, 21, 22, 23, 30, 31, 32};
        int slotIndex = 0;

        for (World world : worlds) {
            Material icon = getWorldIcon(world);
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GREEN + world.getName());
                String viewChunksText = plugin.getMessage("gui.world_view_chunks", "Click to view chunks");
                meta.setLore(Collections.singletonList(ChatColor.GRAY + viewChunksText));
                item.setItemMeta(meta);
            }

            if (slotIndex < centerSlots.length) {
                gui.setItem(centerSlots[slotIndex++], item);
            }
        }

        PlayerGuiState state = new PlayerGuiState(GuiState.WORLD_SELECTION);
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
    }

    private Material getWorldIcon(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    public void openChunksGUI(Player player, String worldName, int page) {
        List<Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData>> filteredChunks = new ArrayList<>();

        long retentionTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

        for (Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData> entry : plugin.getChunkMap().entrySet()) {
            if (entry.getKey().world().equals(worldName) &&
                    entry.getValue().lastScanned >= retentionTime &&
                    (entry.getValue().redstoneCount.get() > plugin.getMaxRedstone() ||
                            entry.getValue().entityCount.get() > plugin.getMaxEntities()) &&
                    !entry.getValue().clearedByAdmin) {
                filteredChunks.add(entry);
            }
        }

        filteredChunks.sort((a, b) -> {
            int xCompare = Integer.compare(a.getKey().x(), b.getKey().x());
            if (xCompare != 0) return xCompare;
            return Integer.compare(a.getKey().z(), b.getKey().z());
        });

        int totalPages = Math.max(1, (int) Math.ceil((double) filteredChunks.size() / 45));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages > 0 ? totalPages - 1 : 0;

        String title = plugin.getMessage("gui.chunk_list_title", "Chunks in {world} (Page {page}/{total})")
                .replace("{world}", worldName)
                .replace("{page}", String.valueOf(page + 1))
                .replace("{total}", String.valueOf(totalPages));
        Inventory gui = Bukkit.createInventory(null, 54, title);
        int start = page * 45;
        int end = Math.min(start + 45, filteredChunks.size());

        for (int i = start; i < end; i++) {
            Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData> entry = filteredChunks.get(i);
            RedstoneDetector.ChunkCoordinate coord = entry.getKey();
            RedstoneDetector.ChunkData data = entry.getValue();

            ItemStack item = createChunkItem(coord, data);
            gui.setItem(i - start, item);
        }

        addNavigationButtons(gui, page, totalPages);

        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_LIST);
        state.world = worldName;
        state.page = page;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
    }

    private ItemStack createChunkItem(RedstoneDetector.ChunkCoordinate coord, RedstoneDetector.ChunkData data) {
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String chunkName = plugin.getMessage("gui.chunk_item_name", "Chunk {coord}").replace("{coord}", coord.toDisplayString());
            meta.setDisplayName(ChatColor.YELLOW + chunkName);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.chunk_world", "World: {world}").replace("{world}", coord.world()));
            lore.add(ChatColor.RED + plugin.getMessage("gui.chunk_redstone", "Redstone: {count}").replace("{count}", String.valueOf(data.redstoneCount.get())));
            lore.add(ChatColor.GREEN + plugin.getMessage("gui.chunk_entities", "Entities: {count}").replace("{count}", String.valueOf(data.entityCount.get())));
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.chunk_detected", "Detected: {time}").replace("{time}", formatTime(data.lastScanned)));
            lore.add("");
            lore.add(ChatColor.GOLD + plugin.getMessage("gui.chunk_lclick", "Left-click: Open actions"));
            lore.add(ChatColor.GOLD + plugin.getMessage("gui.chunk_shift_rclick", "Shift + Right-click: Remove redstone"));

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long minutes = diff / (60 * 1000);
        long hours = diff / (60 * 60 * 1000);

        if (hours > 0) {
            return hours + plugin.getMessage("gui.time_hours_ago", " hours ago");
        } else if (minutes > 0) {
            return minutes + plugin.getMessage("gui.time_minutes_ago", " minutes ago");
        } else {
            return plugin.getMessage("gui.time_just_now", "Just now");
        }
    }

    private void addNavigationButtons(Inventory gui, int page, int totalPages) {
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            if (meta != null) {
                String prevText = plugin.getMessage("gui.previous_page", "Previous Page");
                meta.setDisplayName(ChatColor.YELLOW + prevText);
                prev.setItemMeta(meta);
            }
            gui.setItem(45, prev);
        }

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            if (meta != null) {
                String nextText = plugin.getMessage("gui.next_page", "Next Page");
                meta.setDisplayName(ChatColor.YELLOW + nextText);
                next.setItemMeta(meta);
            }
            gui.setItem(53, next);
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta meta = back.getItemMeta();
        if (meta != null) {
            String backText = plugin.getMessage("gui.back_to_worlds", "Back to Worlds");
            meta.setDisplayName(ChatColor.RED + backText);
            back.setItemMeta(meta);
        }
        gui.setItem(49, back);
    }

    public void openChunkActionsMenu(Player player, RedstoneDetector.ChunkCoordinate coord) {
        String title = plugin.getMessage("gui.chunk_actions_title", "Chunk Actions");
        Inventory gui = Bukkit.createInventory(null, 27, title);

        gui.setItem(10, createItem(Material.BOOK, ChatColor.YELLOW + plugin.getMessage("gui.chunk_info", "View Chunk Details")));
        gui.setItem(12, createItem(Material.ENDER_PEARL, ChatColor.GREEN + plugin.getMessage("gui.chunk_teleport", "Teleport to Chunk")));
        gui.setItem(14, createItem(Material.REDSTONE_BLOCK, ChatColor.RED + plugin.getMessage("gui.chunk_remove_redstone", "Remove Redstone")));
        gui.setItem(16, createItem(Material.EMERALD, ChatColor.GREEN + plugin.getMessage("gui.chunk_restore_redstone", "Restore Redstone")));
        gui.setItem(22, createItem(Material.ARROW, ChatColor.GRAY + plugin.getMessage("gui.back_to_chunks", "Back to Chunks")));

        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_ACTIONS);
        if (playerStates.containsKey(player.getUniqueId())) {
            PlayerGuiState prevState = playerStates.get(player.getUniqueId());
            state.world = prevState.world;
            state.page = prevState.page;
        }
        state.chunkCoord = coord;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
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
        PlayerGuiState state = playerStates.get(player.getUniqueId());
        if (state == null) return;

        String title = event.getView().getTitle();
        ItemStack item = event.getCurrentItem();

        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;

        String displayName = ChatColor.stripColor(item.getItemMeta().getDisplayName());

        if (state.state == GuiState.WORLD_SELECTION) {
            handleWorldSelectionClick(player, displayName);
        }
        else if (state.state == GuiState.CHUNK_LIST) {
            handleChunkListClick(player, state, displayName, item, event.isShiftClick(), event.isRightClick());
        }
        else if (state.state == GuiState.CHUNK_ACTIONS) {
            handleChunkActionsClick(player, state, displayName);
        }

        event.setCancelled(true);
    }

    private void handleWorldSelectionClick(Player player, String displayName) {
        // Просто используем displayName как название мира (без цветовых кодов)
        openChunksGUI(player, displayName, 0);
    }

    private void handleChunkListClick(Player player, PlayerGuiState state, String displayName, ItemStack item, boolean isShiftClick, boolean isRightClick) {
        String backToWorlds = ChatColor.stripColor(plugin.getMessage("gui.back_to_worlds", "Back to Worlds"));
        String previousPage = ChatColor.stripColor(plugin.getMessage("gui.previous_page", "Previous Page"));
        String nextPage = ChatColor.stripColor(plugin.getMessage("gui.next_page", "Next Page"));

        if (displayName.equals(backToWorlds)) {
            openWorldSelectionGUI(player);
        }
        else if (displayName.equals(previousPage)) {
            openChunksGUI(player, state.world, state.page - 1);
        }
        else if (displayName.equals(nextPage)) {
            openChunksGUI(player, state.world, state.page + 1);
        }
        else if (item != null && item.getType() == Material.MAP) {
            // Извлекаем координаты из названия чанка
            String chunkText = ChatColor.stripColor(plugin.getMessage("gui.chunk_item_name", "Chunk {coord}"));
            String chunkName = displayName.replace(chunkText.replace("{coord}", ""), "").trim();
            chunkName = chunkName.replace("[", "").replace("]", "");
            String[] parts = chunkName.split(", ");

            if (parts.length == 2) {
                try {
                    int x = Integer.parseInt(parts[0]);
                    int z = Integer.parseInt(parts[1]);
                    RedstoneDetector.ChunkCoordinate coord = new RedstoneDetector.ChunkCoordinate(state.world, x, z);

                    if (isShiftClick && isRightClick) {
                        plugin.disableRedstoneInChunk(player, coord);
                        player.closeInventory();
                        openChunksGUI(player, state.world, state.page);
                    } else if (!isShiftClick && !isRightClick) {
                        openChunkActionsMenu(player, coord);
                    }
                } catch (NumberFormatException e) {
                    String errorMsg = plugin.getMessage("gui.error_chunk_processing", "Error processing chunk coordinates!");
                    player.sendMessage(ChatColor.RED + errorMsg);
                }
            }
        }
    }

    private void handleChunkActionsClick(Player player, PlayerGuiState state, String displayName) {
        String backToChunks = ChatColor.stripColor(plugin.getMessage("gui.back_to_chunks", "Back to Chunks"));
        String chunkInfo = ChatColor.stripColor(plugin.getMessage("gui.chunk_info", "View Chunk Details"));
        String chunkTeleport = ChatColor.stripColor(plugin.getMessage("gui.chunk_teleport", "Teleport to Chunk"));
        String removeRedstone = ChatColor.stripColor(plugin.getMessage("gui.chunk_remove_redstone", "Remove Redstone"));
        String restoreRedstone = ChatColor.stripColor(plugin.getMessage("gui.chunk_restore_redstone", "Restore Redstone"));

        if (displayName.equals(backToChunks)) {
            openChunksGUI(player, state.world, state.page);
        }
        else if (displayName.equals(chunkInfo)) {
            plugin.openChunkDetails(player, state.chunkCoord);
            player.closeInventory();
        }
        else if (displayName.equals(chunkTeleport)) {
            plugin.teleportToChunk(player, state.chunkCoord);
            player.closeInventory();
        }
        else if (displayName.equals(removeRedstone)) {
            plugin.disableRedstoneInChunk(player, state.chunkCoord);
            player.closeInventory();
        }
        else if (displayName.equals(restoreRedstone)) {
            plugin.restoreRedstoneInChunk(player, state.chunkCoord);
            player.closeInventory();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        savePlayerStates();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        savePlayerStates();
    }

    public void savePlayerStates() {
        try {
            File file = new File(plugin.getDataFolder(), "player_states.yml");
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, PlayerGuiState> entry : playerStates.entrySet()) {
                String path = "states." + entry.getKey();
                PlayerGuiState state = entry.getValue();
                config.set(path + ".state", state.state.name());
                config.set(path + ".world", state.world);
                config.set(path + ".page", state.page);
                if (state.chunkCoord != null) {
                    config.set(path + ".chunkCoord", state.chunkCoord.toString());
                }
            }
            config.save(file);
        } catch (IOException e) {
            String errorMsg = plugin.getMessage("gui.error_saving_states", "Error saving player states: ");
            plugin.getLogger().severe(errorMsg + e.getMessage());
        }
    }

    public void loadPlayerStates() {
        try {
            File file = new File(plugin.getDataFolder(), "player_states.yml");
            if (!file.exists()) return;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (!config.contains("states")) return;

            for (String key : config.getConfigurationSection("states").getKeys(false)) {
                UUID playerId = UUID.fromString(key);
                String path = "states." + key;
                PlayerGuiState state = new PlayerGuiState(
                        GuiState.valueOf(config.getString(path + ".state"))
                );
                state.world = config.getString(path + ".world");
                state.page = config.getInt(path + ".page");
                String coordStr = config.getString(path + ".chunkCoord");
                if (coordStr != null) {
                    state.chunkCoord = RedstoneDetector.ChunkCoordinate.fromString(coordStr);
                }
                playerStates.put(playerId, state);
            }
        } catch (Exception e) {
            String errorMsg = plugin.getMessage("gui.error_loading_states", "Error loading player states: ");
            plugin.getLogger().severe(errorMsg + e.getMessage());
        }
    }

    public void restorePlayerState(Player player) {
        PlayerGuiState state = playerStates.get(player.getUniqueId());
        if (state == null) {
            openWorldSelectionGUI(player);
            return;
        }

        switch (state.state) {
            case WORLD_SELECTION:
                openWorldSelectionGUI(player);
                break;
            case CHUNK_LIST:
                openChunksGUI(player, state.world, state.page);
                break;
            case CHUNK_ACTIONS:
                openChunkActionsMenu(player, state.chunkCoord);
                break;
            default:
                openWorldSelectionGUI(player);
        }
    }
}