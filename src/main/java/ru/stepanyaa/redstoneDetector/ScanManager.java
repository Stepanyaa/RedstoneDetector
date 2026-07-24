/**
 * MIT License
 *
 * RedstoneDetector
 * Copyright (c) 2025 Stepanyaa
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction.
 */
package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScanManager {
    private final RedstoneDetector plugin;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelRequested = false;

    private final Deque<Chunk> queue = new ArrayDeque<>();
    private volatile int totalChunks = 0;
    private volatile int scannedChunks = 0;
    private volatile int suspiciousFound = 0;

    private volatile UUID initiatorId = null;
    private volatile int nextMilestoneIndex = 0;
    private static final int[] MILESTONES = {25, 50, 75, 100};

    private volatile long lastScanStart = 0L;
    private volatile long lastScanEnd = 0L;
    private volatile long lastScanDurationMs = 0L;
    private volatile long lastManualScan = 0L;

    private BukkitRunnable task;

    public ScanManager(RedstoneDetector plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getScannedChunks() {
        return scannedChunks;
    }

    public int getSuspiciousFound() {
        return suspiciousFound;
    }

    public int getProgressPercent() {
        int total = totalChunks;
        if (total <= 0) return running.get() ? 0 : 100;
        return Math.min(100, (int) ((scannedChunks * 100L) / total));
    }

    public long getLastScanTime() {
        return lastScanEnd;
    }

    public long getLastScanDurationMs() {
        return lastScanDurationMs;
    }

    public boolean startScan(CommandSender initiator) {
        long cooldown = plugin.getConfigManager().getScanCooldownMs();
        long now = System.currentTimeMillis();
        if (initiator != null && now - lastManualScan < cooldown) {
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        lastManualScan = now;
        cancelRequested = false;
        scannedChunks = 0;
        suspiciousFound = 0;
        nextMilestoneIndex = 0;
        initiatorId = (initiator instanceof Player) ? ((Player) initiator).getUniqueId() : null;
        lastScanStart = now;

        queue.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queue.add(chunk);
            }
        }
        totalChunks = queue.size();

        final int perTick = Math.max(1, plugin.getConfigManager().getChunksPerTick());

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (cancelRequested) {
                    finish(true);
                    cancel();
                    return;
                }
                int processed = 0;
                while (processed < perTick && !queue.isEmpty()) {
                    Chunk chunk = queue.poll();
                    processed++;
                    scannedChunks++;
                    if (chunk == null || !chunk.isLoaded()) {
                        continue;
                    }
                    if (plugin.scanSingleChunk(chunk)) {
                        suspiciousFound++;
                    }
                }
                int percent = getProgressPercent();
                while (nextMilestoneIndex < MILESTONES.length && percent >= MILESTONES[nextMilestoneIndex]) {
                    announceProgress(MILESTONES[nextMilestoneIndex]);
                    nextMilestoneIndex++;
                }
                if (queue.isEmpty()) {
                    finish(false);
                    cancel();
                }
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);

        plugin.getLogger().info(plugin.formatMessage("log.scan_start", "Scanning worlds...")
                .replace("{world}", "all worlds"));
        return true;
    }

    public boolean cancel() {
        return true;
    }

    public boolean requestCancel() {
        if (!running.get()) return false;
        cancelRequested = true;
        return true;
    }

    private void announceProgress(int percent) {
        String msg = ChatColor.translateAlternateColorCodes('&',
                plugin.getMessage("cmd.scan.progress", "&7Scan progress: &e{percent}% &7({scanned}/{total})")
                        .replace("{percent}", String.valueOf(percent))
                        .replace("{scanned}", String.valueOf(scannedChunks))
                        .replace("{total}", String.valueOf(totalChunks)));
        Player p = initiatorId != null ? Bukkit.getPlayer(initiatorId) : null;
        if (p != null && p.isOnline()) {
            p.sendMessage(msg);
        } else {
            plugin.getLogger().info(ChatColor.stripColor(msg));
        }
    }

    private void finish(boolean cancelled) {
        lastScanEnd = System.currentTimeMillis();
        lastScanDurationMs = lastScanEnd - lastScanStart;
        queue.clear();
        running.set(false);

        plugin.saveChunkData();

        double seconds = lastScanDurationMs / 1000.0;
        if (cancelled) {
            plugin.getLogger().info(plugin.formatMessage("log.scan_cancelled",
                            "Scan cancelled after {scanned}/{total} chunks.")
                    .replace("{scanned}", String.valueOf(scannedChunks))
                    .replace("{total}", String.valueOf(totalChunks)));
        } else {
            plugin.getLogger().info(plugin.formatMessage("log.scan_finished",
                            "Found {count} suspicious chunks. Finished in {seconds} seconds.")
                    .replace("{count}", String.valueOf(suspiciousFound))
                    .replace("{seconds}", String.format(java.util.Locale.US, "%.1f", seconds)));
        }
    }
}
