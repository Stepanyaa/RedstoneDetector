package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.stepanyaa.redstoneDetector.platform.DetectorTask;
import ru.stepanyaa.redstoneDetector.platform.Platforms;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatListener implements Listener {
    public static final Map<UUID, String> waitingForChunkSearch = new HashMap<>();

    private final RedstoneDetector plugin;
    private final Map<UUID, DetectorTask> cleanupTasks = new HashMap<>();

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
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "chat.search.invalid_format",
                    "Invalid format! Use: X Z (e.g. 5 -3 or 80 -48)"));
            return;
        }

        Platforms.scheduler().run(() -> {
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
                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "chat.search.not_found",
                                    "Chunk {coord} not found in cache.")
                            .replace("{coord}", target.toDisplayString()));
                    player.sendMessage(ChatColor.GRAY + plugin.getMessage(player, "chat.search.not_found_hint",
                            "It may not have been scanned yet or was already cleared."));
                }
            } catch (NumberFormatException ex) {
                player.sendMessage(ChatColor.RED + plugin.getMessage(player, "chat.search.not_numbers",
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
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "chat.search.cancelled", "Chunk search cancelled."));
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
        DetectorTask pendingCleanup = cleanupTasks.remove(uuid);

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

        DetectorTask cleanupTask = Platforms.scheduler().delay(20L * 60 * 5, () -> {
            waitingForChunkSearch.remove(uuid);
            plugin.playerChunkDetailsLines.remove(uuid);
            plugin.playerChunkDetailsPage.remove(uuid);
            plugin.playerChunkDetailsTitle.remove(uuid);

            cleanupTasks.remove(uuid);

            plugin.getLogger().info("Данные игрока " + player.getName() + " очищены после выхода.");
        });

        cleanupTasks.put(uuid, cleanupTask);
    }
}
