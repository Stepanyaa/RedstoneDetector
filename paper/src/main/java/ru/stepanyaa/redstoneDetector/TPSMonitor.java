package ru.stepanyaa.redstoneDetector;

import org.bukkit.Bukkit;
import ru.stepanyaa.redstoneDetector.platform.Platforms;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;

public class TPSMonitor {
    private final RedstoneDetector plugin;
    private ServerBackend serverBackend;
    private volatile double mspt = 50.0;
    private final java.util.LinkedList<Long> msptHistory = new java.util.LinkedList<>();
    private java.lang.reflect.Method avgTickTimeMethod;
    private Object serverForMspt;

    public interface ServerBackend {
        double getTPS();
    }

    public TPSMonitor(RedstoneDetector plugin) {
        this.plugin = plugin;
        setupServerBackend();
        startMsptSampler();
        setupMsptReflection();
    }

    private void setupMsptReflection() {
        try {
            serverForMspt = org.bukkit.Bukkit.getServer();
            avgTickTimeMethod = serverForMspt.getClass().getMethod("getAverageTickTime");
            Object v = avgTickTimeMethod.invoke(serverForMspt);
            if (!(v instanceof Number)) {
                avgTickTimeMethod = null;
            } else {
                plugin.getLogger().info("Using server getAverageTickTime() for real MSPT.");
            }
        } catch (Throwable t) {
            avgTickTimeMethod = null;
            plugin.getLogger().info("Real MSPT API unavailable; using tick-interval estimate.");
        }
    }

    public boolean hasRealMspt() {
        return avgTickTimeMethod != null;
    }

    private void startMsptSampler() {
        Platforms.scheduler().timer(1L, 1L, new Runnable() {
            private long last = System.nanoTime();
            @Override
            public void run() {
                long now = System.nanoTime();
                long deltaMs = (now - last) / 1_000_000L;
                last = now;
                if (deltaMs < 0 || deltaMs > 5000) return;
                msptHistory.addLast(deltaMs);
                while (msptHistory.size() > 100) msptHistory.removeFirst();
                double sum = 0;
                for (long v : msptHistory) sum += v;
                mspt = msptHistory.isEmpty() ? 50.0 : sum / msptHistory.size();
            }
        });
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

    public double getMSPT() {
        if (avgTickTimeMethod != null) {
            try {
                Object v = avgTickTimeMethod.invoke(serverForMspt);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {
            }
        }
        return mspt;
    }

    private class InternalTpsCalculator implements Runnable, ServerBackend {
        private final LinkedList<Long> history = new LinkedList<>();
        private long lastTickTime = System.currentTimeMillis();
        private double currentTps = 20.0;

        public InternalTpsCalculator() {
            Platforms.scheduler().timer(1L, 1L, this);
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
