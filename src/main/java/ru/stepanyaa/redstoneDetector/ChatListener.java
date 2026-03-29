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

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatListener implements Listener {
    public static final Map<UUID, String> waitingForChunkSearch = new HashMap<>();

    private final RedstoneDetector plugin;
    private final Map<UUID, BukkitTask> cleanupTasks = new HashMap<>();

    public ChatListener(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();

        String waitingFlag = waitingForChunkSearch.get(uuid);
        if (waitingFlag == null) return;

        e.setCancelled(true);
        String input = e.getMessage().trim();

        RedstoneDetector plugin = (RedstoneDetector) Bukkit.getPluginManager().getPlugin("RedstoneDetector");
        if (plugin == null) {
            waitingForChunkSearch.remove(uuid);
            return;
        }

        if (input.equalsIgnoreCase("/rdcancel")) {
            cancelSearch(player);
            return;
        }

        String[] parts = input.split("\\s+");
        if (parts.length != 2) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("chat.search.invalid_format",
                    "Invalid format! Use: X Z (e.g. 5 -3 or 80 -48)"));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                int inputX = Integer.parseInt(parts[0]);
                int inputZ = Integer.parseInt(parts[1]);

                int chunkX = (Math.abs(inputX) >= 300 || Math.abs(inputZ) >= 300) ? Math.floorDiv(inputX, 16) : inputX;
                int chunkZ = (Math.abs(inputZ) >= 300 || Math.abs(inputX) >= 300) ? Math.floorDiv(inputZ, 16) : inputZ;

                ChunkCoordinate target = new ChunkCoordinate(
                        waitingFlag, chunkX, chunkZ
                );

                ChunkData data = plugin.getChunkMap().get(target);
                if (data != null) {

                    plugin.getGuiManager().openChunkActionsMenu(player, target);
                    plugin.openChunkDetails(player, target);
                } else {
                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage("chat.search.not_found",
                                    "Chunk {coord} not found in cache.")
                            .replace("{coord}", target.toDisplayString()));
                    player.sendMessage(ChatColor.GRAY + plugin.getMessage("chat.search.not_found_hint",
                            "It may not have been scanned yet or was already cleared."));
                }
            } catch (NumberFormatException ex) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("chat.search.not_numbers",
                        "Coordinates must be numbers!"));
            } finally {
                waitingForChunkSearch.remove(uuid);
            }
        });
    }

    public static void cancelSearch(Player player) {
        waitingForChunkSearch.remove(player.getUniqueId());

        RedstoneDetector plugin = (RedstoneDetector) player.getServer()
                .getPluginManager().getPlugin("RedstoneDetector");

        if (plugin != null) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("chat.search.cancelled", "Chunk search cancelled."));
        } else {
            player.sendMessage(ChatColor.RED + "Chunk search cancelled.");
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (!player.hasPermission("redstonedetector.admin") && !player.isOp()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        BukkitTask pendingCleanup = cleanupTasks.remove(uuid);

        if (pendingCleanup != null) {
            pendingCleanup.cancel();
        }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!player.hasPermission("redstonedetector.admin") && !player.isOp()) {
            return;
        }

        RedstoneDetector plugin = (RedstoneDetector) Bukkit.getPluginManager().getPlugin("RedstoneDetector");
        if (plugin == null) return;

        BukkitTask cleanupTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            waitingForChunkSearch.remove(uuid);
            plugin.playerChunkDetailsLines.remove(uuid);
            plugin.playerChunkDetailsPage.remove(uuid);
            plugin.playerChunkDetailsTitle.remove(uuid);

            cleanupTasks.remove(uuid);

            plugin.getLogger().info("Данные игрока " + player.getName() + " очищены после выхода.");
        }, 20L * 60 * 5);

        cleanupTasks.put(uuid, cleanupTask);
    }
}