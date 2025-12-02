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

        PlayerGuiState state = new PlayerGuiState(GuiState.WORLD_SELECTION);
        playerStates.put(player.getUniqueId(), state);
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
        List<Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData>> filteredChunks = new ArrayList<>();

        long retentionTime = System.currentTimeMillis() - (plugin.getConfig().getLong("chunk-data-retention", 24) * 60 * 60 * 1000L);

        for (Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData> entry : plugin.getChunkMap().entrySet()) {
            RedstoneDetector.ChunkCoordinate coord = entry.getKey();
            RedstoneDetector.ChunkData data = entry.getValue();

            if (coord.world().equals(worldName) &&
                    data.lastScanned >= retentionTime &&
                    !data.clearedByAdmin &&
                    (data.redstoneCount.get() > plugin.getMaxRedstone() || data.entityCount.get() > plugin.getMaxEntities())) {
                filteredChunks.add(entry);
            }
        }

        filteredChunks.sort(new Comparator<Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData>>() {
            @Override
            public int compare(Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData> a,
                               Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData> b) {
                int xDiff = Integer.compare(a.getKey().x(), b.getKey().x());
                return xDiff != 0 ? xDiff : Integer.compare(a.getKey().z(), b.getKey().z());
            }
        });

        int totalPages = Math.max(1, (int) Math.ceil((double) filteredChunks.size() / 45));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = plugin.getMessage("gui.chunk_list_title", "Chunks in {world} (Page {page}/{total})")
                .replace("{world}", worldName)
                .replace("{page}", String.valueOf(page + 1))
                .replace("{total}", String.valueOf(totalPages));

        Inventory gui = Bukkit.createInventory(null, 54, title);

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
                    ChatColor.GRAY + plugin.getMessage("gui.search_compass_line2", "Supports chunk coordinates (e.g. 5 -3)")
            ));
            compass.setItemMeta(compassMeta);
        }
        gui.setItem(4, compass);

        int start = page * 45;
        int end = Math.min(start + 45, filteredChunks.size());

        for (int i = start; i < end; i++) {
            Map.Entry<RedstoneDetector.ChunkCoordinate, RedstoneDetector.ChunkData> entry = filteredChunks.get(i);
            gui.setItem(9 + (i - start), createChunkItem(entry.getKey(), entry.getValue()));
        }

        addNavigationButtons(gui, page, totalPages, worldName);

        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_LIST);
        state.world = worldName;
        state.page = page;
        playerStates.put(player.getUniqueId(), state);
        player.openInventory(gui);
    }

    private ItemStack createChunkItem(RedstoneDetector.ChunkCoordinate coord, RedstoneDetector.ChunkData data) {
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

    private void addNavigationButtons(Inventory gui, int page, int totalPages, String worldName) {
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
        gui.setItem(16, createActionItem(Material.EMERALD, "gui.chunk_restore_redstone", "Restore Redstone"));

        gui.setItem(22, createActionItem(Material.ARROW, "gui.back_to_chunks", "Back to Chunk List"));

        PlayerGuiState state = new PlayerGuiState(GuiState.CHUNK_ACTIONS);
        state.world = coord.world();
        state.chunkCoord = coord;
        state.page = playerStates.getOrDefault(player.getUniqueId(), new PlayerGuiState(GuiState.WORLD_SELECTION)).page;
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

            net.md_5.bungee.api.chat.TextComponent main = new net.md_5.bungee.api.chat.TextComponent(
                    ChatColor.YELLOW + plugin.getMessage("chat.search.enter_coords", "Enter chunk coordinates (X Z): ")
            );

            net.md_5.bungee.api.chat.TextComponent example = new net.md_5.bungee.api.chat.TextComponent(
                    ChatColor.GRAY + "5 -3 "
            );
            example.setItalic(true);

            net.md_5.bungee.api.chat.TextComponent cancel = new net.md_5.bungee.api.chat.TextComponent(
                    plugin.getMessage("plugin.cancel", " [Cancel]")
            );
            cancel.setColor(net.md_5.bungee.api.ChatColor.RED);
            cancel.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/rdcancel"
            ));
            cancel.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(
                            plugin.getMessage("chat.search.cancel_hover", "Click to cancel search")
                    )
            ));

            main.addExtra(example);
            main.addExtra(cancel);
            player.spigot().sendMessage(main);

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
                handleChunkListClick(player, state, displayName, clicked, event.isShiftClick(), event.isRightClick());
                break;
            case CHUNK_ACTIONS:
                handleChunkActionsClick(player, state, displayName);
                break;
        }
    }

    private void handleWorldSelectionClick(Player player, String displayName) {
        openChunksGUI(player, displayName, 0);
    }

    private void handleChunkListClick(Player player, PlayerGuiState state, String displayName, ItemStack item, boolean isShiftClick, boolean isRightClick) {
        String backToWorlds = ChatColor.stripColor(plugin.getMessage("gui.back_to_worlds", "Back to Worlds"));
        String previousPage = ChatColor.stripColor(plugin.getMessage("gui.previous_page", "Previous Page"));
        String nextPage = ChatColor.stripColor(plugin.getMessage("gui.next_page", "Next Page"));

        if (displayName.equals(backToWorlds)) {
            openWorldSelectionGUI(player);
        } else if (displayName.equals(previousPage)) {
            openChunksGUI(player, state.world, state.page - 1);
        } else if (displayName.equals(nextPage)) {
            openChunksGUI(player, state.world, state.page + 1);
        } else if (item != null && item.getType() == Material.MAP) {
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
        } else if (displayName.equals(chunkInfo)) {
            plugin.openChunkDetails(player, state.chunkCoord);
            player.closeInventory();
        } else if (displayName.equals(chunkTeleport)) {
            plugin.teleportToChunk(player, state.chunkCoord);
            player.closeInventory();
        } else if (displayName.equals(removeRedstone)) {
            plugin.disableRedstoneInChunk(player, state.chunkCoord);
            player.closeInventory();
        } else if (displayName.equals(restoreRedstone)) {
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