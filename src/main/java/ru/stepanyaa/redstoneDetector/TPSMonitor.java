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
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;

public class TPSMonitor {
    private final RedstoneDetector plugin;
    private ServerBackend serverBackend;
    
    public interface ServerBackend {
        double getTPS();
    }

    public TPSMonitor(RedstoneDetector plugin) {
        this.plugin = plugin;
        setupServerBackend();
    }

    private void setupServerBackend() {
        String serverVersion = Bukkit.getVersion().toLowerCase();

        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Method getTpsMethod = bukkitClass.getMethod("getTPS");

            serverBackend = () -> {
                try {
                    double[] tpsArray = (double[]) getTpsMethod.invoke(null);
                    return tpsArray[0];
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to get TPS from modern API: " + e.getMessage());
                    return 20.0;
                }
            };
            plugin.getLogger().info("Using modern Paper/Purpur/Pufferfish TPS API");
            return;
        } catch (Exception ignored) {
            plugin.getLogger().info("Modern TPS API (Bukkit.getTPS) not available");
        }

        if (serverVersion.contains("spigot")) {
            try {
                String nmsVersion = getNMSVersion();
                Class<?> serverClass = Class.forName("org.bukkit.craftbukkit." + nmsVersion + ".CraftServer");
                Method getServerMethod = Bukkit.class.getMethod("getServer");
                Object server = getServerMethod.invoke(null);
                Field tpsField = server.getClass().getSuperclass().getDeclaredField("recentTps");
                tpsField.setAccessible(true);

                serverBackend = () -> {
                    try {
                        double[] tps = (double[]) tpsField.get(server);
                        return tps[0];
                    } catch (Exception e) {
                        return 20.0;
                    }
                };
                plugin.getLogger().info("Using Spigot TPS via reflection");
                return;
            } catch (Exception e) {
                plugin.getLogger().info("Spigot reflection failed: " + e.getMessage());
            }
        }

        serverBackend = new InternalTpsCalculator();
        plugin.getLogger().info("Using internal TPS calculator (fallback)");
    }

    private String getNMSVersion() {
        String version = Bukkit.getServer().getClass().getPackage().getName();
        return version.substring(version.lastIndexOf('.') + 1);
    }

    public double getTPS() {
        return serverBackend != null ? serverBackend.getTPS() : 20.0;
    }

    private class InternalTpsCalculator extends BukkitRunnable implements ServerBackend {
        private final LinkedList<Long> history = new LinkedList<>();
        private long lastTickTime = System.currentTimeMillis();
        private double currentTps = 20.0;

        public InternalTpsCalculator() {
            this.runTaskTimer(plugin, 1L, 1L);
        }

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long diff = now - lastTickTime;
            lastTickTime = now;

            if (diff > 5000) return;

            history.addLast(diff);
            if (history.size() > 100) {
                history.removeFirst();
            }

            if (history.size() < 40) {
                currentTps = 20.0;
                return;
            }

            double avg = history.stream().mapToLong(Long::longValue).average().orElse(50.0);
            currentTps = avg <= 51.0 ? 20.0 : 1000.0 / avg;
        }

        @Override
        public double getTPS() {
            return currentTps;
        }
    }
}
