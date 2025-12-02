package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatListener implements Listener {
    public static final Map<UUID, String> waitingForChunkSearch = new HashMap<>();

    private final RedstoneDetector plugin;

    public ChatListener(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!waitingForChunkSearch.containsKey(uuid)) return;

        e.setCancelled(true);
        String input = e.getMessage().trim();

        if (input.equalsIgnoreCase("/rdcancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> cancelSearch(player));
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

                int chunkX = (Math.abs(inputX) > 300 ? Math.floorDiv(inputX, 16) : inputX);
                int chunkZ = (Math.abs(inputZ) > 300 ? Math.floorDiv(inputZ, 16) : inputZ);

                RedstoneDetector.ChunkCoordinate target = new RedstoneDetector.ChunkCoordinate(
                        waitingForChunkSearch.get(uuid), chunkX, chunkZ
                );

                if (plugin.getChunkMap().containsKey(target)) {
                    plugin.getGuiManager().openChunkActionsMenu(player, target);

                    String foundMsg = plugin.getMessage("chat.search.found",
                                    "Found chunk {coord} (X: {x1}..{x2} | Z: {z1}..{z2})")
                            .replace("{coord}", target.toDisplayString())
                            .replace("{x1}", String.valueOf(target.x() * 16))
                            .replace("{x2}", String.valueOf(target.x() * 16 + 15))
                            .replace("{z1}", String.valueOf(target.z() * 16))
                            .replace("{z2}", String.valueOf(target.z() * 16 + 15));

                    player.sendMessage(ChatColor.GREEN + foundMsg);
                } else {
                    String notFound = plugin.getMessage("chat.search.not_found",
                                    "Chunk {coord} not found in cache.")
                            .replace("{coord}", target.toDisplayString());
                    String hint = plugin.getMessage("chat.search.not_found_hint",
                            "It may not have been scanned yet or was already cleared.");

                    player.sendMessage(ChatColor.YELLOW + notFound);
                    player.sendMessage(ChatColor.GRAY + hint);
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
    public void onQuit(PlayerQuitEvent e) {
        waitingForChunkSearch.remove(e.getPlayer().getUniqueId());
    }
}