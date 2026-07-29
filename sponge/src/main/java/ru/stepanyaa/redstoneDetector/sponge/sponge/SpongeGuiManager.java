package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ActivityAnalyzer;
import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.ChunkData;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class SpongeGuiManager {
    private enum Screen { DASHBOARD, CHUNKS, FROZEN, CHUNK }

    private enum Sort { COST, UPDATES, MECHANISMS, ENTITIES }

    private final SpongeDetector detector;
    private final Object plugin;
    private final Logger logger;

    private final Map<UUID, Sort> sortModes = new ConcurrentHashMap<UUID, Sort>();

    private final Map<UUID, String> pendingSearch = new ConcurrentHashMap<UUID, String>();

    private static final class Live {
        private Screen screen;
        private Object inventory;
        private Object viewer;
        private List<ChunkCoordinate> data;
        private boolean frozenOnly;
        private volatile int page;
        private volatile int pages;
    }

    private final Map<UUID, Live> openScreens = new ConcurrentHashMap<UUID, Live>();

    public SpongeGuiManager(SpongeDetector detector, Object plugin, Logger logger) {
        this.detector = detector;
        this.plugin = plugin;
        this.logger = logger;
    }

    public void awaitSearch(Object player, String worldName) {
        UUID id = SpongeViewers.uniqueId(player);
        if (id != null) {
            pendingSearch.put(id, worldName == null ? "" : worldName);
        }
    }

    public boolean isAwaitingSearch(UUID id) {
        return id != null && pendingSearch.containsKey(id);
    }

    public String pendingWorld(UUID id) {
        String world = id == null ? null : pendingSearch.get(id);
        return world == null || world.isEmpty() ? null : world;
    }

    public void cancelSearch(UUID id) {
        if (id != null) {
            pendingSearch.remove(id);
        }
    }

    public void openDashboard(Object player) {
        try {
            final Object inv = inventory54();
            paintDashboard(player, inv);
            open(player, inv,
                    title(text(player, "gui.dashboard.title", "RedstoneDetector"),
                            allWorlds(player), 1, 1),
                    Screen.DASHBOARD, null, 0, 1);
        } catch (Throwable failure) {
            fail(player, "Could not open dashboard", failure);
        }
    }

    private void paintDashboard(Object player, Object inv) throws ReflectiveOperationException {
            fill(inv, "GRAY_STAINED_GLASS_PANE", " ");
            set(inv, 10, item("CLOCK", text(player, "gui.dashboard.status", "&6Server status"), list(
                    value(text(player, "gui.dashboard.tps", "&7TPS: &f{value}"),
                            round(detector.engine().serverTps())),
                    value(text(player, "gui.dashboard.mspt", "&7MSPT: &f{value}"),
                            round(detector.engine().serverMspt())),
                    value(text(player, "gui.dashboard.tracked", "&7Tracked chunks: &f{value}"),
                            String.valueOf(detector.chunks().size())),
                    value(text(player, "gui.dashboard.problems", "&7Problem chunks: &f{value}"),
                            String.valueOf(detector.engine().suspiciousCount())))));
            set(inv, 12, item("SPYGLASS", text(player, "gui.dashboard.scan", "&bScan loaded chunks"),
                    list(text(player, "gui.dashboard.scan_lore", "&7Click to start a full scan."),
                            value(text(player, "gui.dashboard.scan_progress", "&7Progress: &f{value}%"),
                                    String.valueOf(
                                            detector.scans().isRunning()
                                                    ? detector.scans().progressPercent() : 100)))));
            set(inv, 14, item("CHEST", text(player, "gui.dashboard.list", "&eChunk list"),
                    list(text(player, "gui.dashboard.list_lore", "&7Open monitored chunks."))));
            set(inv, 16, item("PACKED_ICE", text(player, "gui.dashboard.frozen", "&bFrozen chunks"),
                    list(value(text(player, "gui.dashboard.frozen_lore",
                                    "&7Currently frozen: &f{value}"),
                            String.valueOf(detector.engine().frozenChunks().size())))));
            set(inv, 20, item("REDSTONE",
                    text(player, "gui.dashboard.statistics", "&6Activity per second"),
                    statisticsLore(player)));
            set(inv, 24, item("SCULK_SENSOR",
                    text(player, "gui.dashboard.detections", "&dActive detections"),
                    detectionLore(player)));
            set(inv, 22, item("REDSTONE_TORCH",
                    text(player, "gui.dashboard.smart_freeze", "&cFreeze lagging chunks"),
                    list(text(player, "gui.dashboard.smart_freeze_lore",
                                    "&7Freezes only measured offenders."),
                            text(player, "gui.dashboard.smart_freeze_lore2",
                                    "&7Small normal mechanisms remain active."))));
            set(inv, 30, item("COMPASS", text(player, "gui.search_compass_name", "&bFind a chunk"),
                    list(text(player, "gui.search_compass_lore",
                            "&7Click, then type the coordinates in the chat."))));
            set(inv, 32, item(detector.globalStop() ? "LIME_DYE" : "GRAY_DYE",
                    detector.globalStop()
                            ? text(player, "gui.dashboard.resume_all", "&aResume all redstone")
                            : text(player, "gui.dashboard.stop_all", "&cStop all redstone"),
                    list(text(player, "gui.dashboard.stop_all_lore",
                            "&7Pauses redstone without breaking blocks."))));
            set(inv, 31, item("PAPER",
                    text(player, "gui.dashboard.plugin_status", "&fPlugin status"),
                    list(value(text(player, "gui.dashboard.version", "&7Version: &f{value}"),
                                    detector.version()),
                            value(text(player, "gui.dashboard.suspended",
                                            "&7Suspended chunks: &f{value}"),
                                    String.valueOf(detector.freezes().suspendedCount())),
                            value(text(player, "gui.dashboard.global_freeze",
                                            "&7Global freeze: &f{value}"),
                                    String.valueOf(detector.globalStop())),
                            value(text(player, "gui.dashboard.memory",
                                            "&7Memory: &f{value} MB"), memoryUsage()),
                            text(player, "gui.dashboard.live", "&7Updates automatically."))));
    }

    private String memoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
        long max = runtime.maxMemory() / 1048576L;
        return used + "/" + max;
    }

    private List<String> statisticsLore(Object player) {
        int redstone = 0;
        int piston = 0;
        int hopper = 0;
        int observer = 0;
        int comparator = 0;
        int sculk = 0;
        int trapdoor = 0;
        int scheduled = 0;
        int entities = 0;
        int blockEntities = 0;
        double impact = 0.0;
        for (ChunkData d : detector.chunks().values()) {
            redstone += d.redstonePerSec;
            piston += d.pistonPerSec;
            hopper += d.hopperPerSec;
            observer += d.observerPerSec;
            comparator += d.comparatorPerSec;
            sculk += d.sculkPerSec;
            trapdoor += d.trapdoorPerSec;
            scheduled += d.scheduledPerSec;
            entities += d.entityCount.get();
            blockEntities += d.blockEntityCount;
            impact += d.impactScore;
        }
        return list(
                value(text(player, "gui.dashboard.stat_redstone", "&7Redstone: &f{value}"),
                        String.valueOf(redstone)),
                value(text(player, "gui.dashboard.stat_piston", "&7Pistons: &f{value}"),
                        String.valueOf(piston)),
                value(text(player, "gui.dashboard.stat_hopper", "&7Hoppers: &f{value}"),
                        String.valueOf(hopper)),
                value(text(player, "gui.dashboard.stat_observer", "&7Observers: &f{value}"),
                        String.valueOf(observer)),
                value(text(player, "gui.dashboard.stat_comparator", "&7Comparators: &f{value}"),
                        String.valueOf(comparator)),
                value(text(player, "gui.dashboard.stat_sculk", "&7Sculk sensors: &f{value}"),
                        String.valueOf(sculk)),
                value(text(player, "gui.dashboard.stat_trapdoor", "&7Trapdoors: &f{value}"),
                        String.valueOf(trapdoor)),
                value(text(player, "gui.dashboard.stat_scheduled", "&7Scheduled: &f{value}"),
                        String.valueOf(scheduled)),
                value(text(player, "gui.dashboard.stat_entities", "&7Entities: &f{value}"),
                        String.valueOf(entities)),
                value(text(player, "gui.dashboard.stat_block_entities",
                        "&7Block entities: &f{value}"), String.valueOf(blockEntities)),
                value(text(player, "gui.dashboard.stat_lag_score", "&7Lag score: &f{value}"),
                        round(impact)));
    }

    private List<String> detectionLore(Object player) {
        return list(
                value(text(player, "gui.dashboard.detections_active", "&7Active freezes: &f{value}"),
                        String.valueOf(detector.engine().journal().activeCount())),
                value(text(player, "gui.dashboard.detections_sculk",
                                "&7Sculk watchlist: &f{value}"),
                        String.valueOf(detector.engine().sculkDetector().trackedChunks())),
                value(text(player, "gui.dashboard.detections_trapdoor",
                                "&7Trapdoor watchlist: &f{value}"),
                        String.valueOf(detector.engine().trapdoorDetector().trackedChunks())),
                value(text(player, "gui.dashboard.detections_suspended",
                                "&7Suspended chunks: &f{value}"),
                        String.valueOf(detector.freezes().suspendedCount())));
    }

    public void openChunks(Object player, int page, boolean frozenOnly) {
        Live screen = new Live();
        screen.screen = frozenOnly ? Screen.FROZEN : Screen.CHUNKS;
        screen.viewer = player;
        screen.frozenOnly = frozenOnly;
        screen.page = Math.max(0, page);
        try {
            screen.inventory = inventory54();
            String heading = paintChunkList(player, screen);
            open(player, screen.inventory, heading, screen.screen, screen.data, screen.page,
                    screen.pages);
        } catch (Throwable failure) {
            fail(player, "Could not open chunk list", failure);
        }
    }

    private String paintChunkList(Object player, Live screen) throws ReflectiveOperationException {
            final boolean frozenOnly = screen.frozenOnly;
            int page = screen.page;
            final Sort sort = sortOf(player);
            final List<ChunkCoordinate> coords = new ArrayList<ChunkCoordinate>();
            if (frozenOnly) {
                coords.addAll(detector.engine().frozenChunks());
            } else {

                for (Map.Entry<ChunkCoordinate, ChunkData> entry : detector.chunks().entrySet()) {
                    ChunkCoordinate key = entry.getKey();
                    ChunkData d = entry.getValue();

                    boolean tracked = d.scanned
                            && d.redstoneCount.get() >= detector.config().minTrackedMechanisms();
                    boolean active = d.updatesPerSec > 0 || d.msptContribution > 0.0
                            || detector.engine().isSuspicious(key)
                            || detector.engine().isFrozen(key) || detector.isStopped(key);
                    if (tracked || active) {
                        coords.add(key);
                    }
                }
                Collections.sort(coords, new Comparator<ChunkCoordinate>() {
                    @Override public int compare(ChunkCoordinate a, ChunkCoordinate b) {
                        int byWeight = Double.compare(weight(b, sort), weight(a, sort));

                        return byWeight != 0 ? byWeight : a.toString().compareTo(b.toString());
                    }
                });
            }
            final int pages = Math.max(1, (coords.size() + 44) / 45);
            page = Math.max(0, Math.min(page, pages - 1));
            final Object inv = screen.inventory;
            final int start = page * 45;
            final List<ChunkCoordinate> shown = new ArrayList<ChunkCoordinate>();
            for (int i = start; i < Math.min(start + 45, coords.size()); i++) {
                ChunkCoordinate c = coords.get(i); shown.add(c);
                ChunkData d = detector.chunks().get(c);
                String material = detector.engine().isFrozen(c) ? "PACKED_ICE" : "REDSTONE";
                set(inv, shown.size() - 1, item(material, "&e" + c.world() + " " + c.toDisplayString(),
                        d == null ? list(text(player, "gui.chunkinfo.no_data", "&7No measurements"))
                                : lore(player, d)));
            }
            fillRange(inv, 45, 53, "BLACK_STAINED_GLASS_PANE", " ");
            if (page > 0) {
                set(inv, 45, item("ARROW", text(player, "gui.previous_page", "&ePrevious page"), list()));
            }
            set(inv, 47, item("HOPPER", value(text(player, "gui.sort_mode", "&6Sorting: &f{value}"),
                            sortName(player, sort)),
                    list(text(player, "gui.sort_hint", "&7Click to switch the sorting."))));
            set(inv, 49, item("BARRIER", text(player, "gui.back", "&cBack"),
                    list(text(player, "gui.back_lore", "&7Return to dashboard."))));
            set(inv, 51, item("COMPASS", text(player, "gui.search_compass_name", "&bFind a chunk"),
                    list(text(player, "gui.search_compass_lore",
                            "&7Click, then type the coordinates in the chat."))));
            if (page + 1 < pages) {
                set(inv, 53, item("ARROW", text(player, "gui.next_page", "&eNext page"), list()));
            }
            String worldName = shown.isEmpty() ? allWorlds(player) : shown.get(0).world();
            for (ChunkCoordinate c : shown) {
                if (!c.world().equals(worldName)) {
                    worldName = allWorlds(player);
                    break;
                }
            }
            screen.data = shown;
            screen.page = page;
            screen.pages = pages;
            return title(frozenOnly
                            ? text(player, "gui.frozen.title", "Frozen chunks")
                            : text(player, "gui.list.title", "Monitored chunks"),
                    worldName, page + 1, pages);
    }

    private double weight(ChunkCoordinate coord, Sort sort) {
        ChunkData data = detector.chunks().get(coord);
        if (data == null) {
            return 0.0;
        }
        switch (sort) {
            case UPDATES: return data.updatesPerSec;
            case MECHANISMS: return data.redstoneCount.get();
            case ENTITIES: return data.entityCount.get();
            case COST:
            default:

                return data.impactScore > 0.0
                        ? data.impactScore
                        : data.msptContribution * 1000.0 + data.updatesPerSec * 10.0
                                + data.redstoneCount.get() * 0.01 + data.entityCount.get() * 0.01;
        }
    }

    private Sort sortOf(Object player) {
        UUID id = SpongeViewers.uniqueId(player);
        Sort sort = id == null ? null : sortModes.get(id);
        return sort == null ? Sort.COST : sort;
    }

    private void cycleSort(Object player) {
        UUID id = SpongeViewers.uniqueId(player);
        if (id == null) {
            return;
        }
        Sort[] values = Sort.values();
        Sort next = values[(sortOf(player).ordinal() + 1) % values.length];
        sortModes.put(id, next);
    }

    private String sortName(Object player, Sort sort) {
        switch (sort) {
            case UPDATES: return text(player, "gui.sort_updates", "Updates per second");
            case MECHANISMS: return text(player, "gui.sort_mechanisms", "Mechanisms");
            case ENTITIES: return text(player, "gui.sort_entities", "Entities");
            case COST:
            default: return text(player, "gui.sort_cost", "Server cost");
        }
    }

    public void openChunk(Object player, ChunkCoordinate coord) {
        try {
            Object inv = inventory54();
            String heading = paintChunk(player, inv, coord);
            open(player, inv, heading, Screen.CHUNK, Collections.singletonList(coord), 0, 1);
        } catch (Throwable failure) {
            fail(player, "Could not open chunk actions", failure);
        }
    }

    private String paintChunk(Object player, Object inv, ChunkCoordinate coord)
            throws ReflectiveOperationException {
            ChunkData d = detector.chunks().get(coord);
            fill(inv, "GRAY_STAINED_GLASS_PANE", " ");
            set(inv, 4, item("COMPASS", "&6" + coord.world() + " " + coord.toDisplayString(),
                    d == null ? list(text(player, "gui.chunkinfo.no_data", "&7No measurements"))
                            : lore(player, d)));
            boolean frozen = detector.engine().isFrozen(coord);
            set(inv, 20, item(frozen ? "REDSTONE_TORCH" : "PACKED_ICE",
                    frozen ? text(player, "gui.chunkinfo.unfreeze", "&aUnfreeze chunk")
                           : text(player, "gui.chunkinfo.freeze", "&cFreeze chunk"),
                    list(text(player, "gui.chunkinfo.click", "&7Click to apply."))));
            set(inv, 22, item("ZOMBIE_HEAD",
                    text(player, "gui.chunkinfo.remove_entities", "&cRemove entities"),
                    list(text(player, "gui.chunkinfo.remove_entities_lore",
                            "&7Players are never removed."))));
            set(inv, 24, item("ENDER_PEARL", text(player, "gui.chunkinfo.teleport", "&bTeleport"),
                    list(text(player, "gui.chunkinfo.teleport_lore", "&7Teleport to this chunk."))));
            boolean stopped = detector.isStopped(coord);
            set(inv, 30, item(stopped ? "LIME_DYE" : "GRAY_DYE",
                    stopped ? text(player, "gui.chunkinfo.resume", "&aResume redstone")
                            : text(player, "gui.chunkinfo.stop", "&cStop redstone"),
                    list(text(player, "gui.chunkinfo.stop_lore",
                            "&7Pauses redstone without breaking blocks."))));
            set(inv, 32, item("BUCKET", text(player, "gui.chunkinfo.forget", "&7Forget this chunk"),
                    list(text(player, "gui.chunkinfo.forget_lore",
                            "&7Removes it from the monitored list."))));
            set(inv, 49, item("BARRIER", text(player, "gui.back", "&cBack"),
                    list(text(player, "gui.back_list_lore", "&7Return to chunk list."))));
            String chunkTitle = text(player, "gui.chunkinfo.title", "Chunk {coord}");
            chunkTitle = chunkTitle.contains("{coord}") || chunkTitle.contains("{chunk}")
                    ? chunkTitle.replace("{coord}", coord.toDisplayString())
                            .replace("{chunk}", coord.toDisplayString())
                            .replace("{world}", coord.world())
                    : chunkTitle + " " + coord.toDisplayString();
            return chunkTitle;
    }

    private List<String> lore(Object player, ChunkData d) {
        return list(
                value(text(player, "gui.chunkinfo.mechanisms", "&7Mechanisms: &f{value}"),
                        String.valueOf(d.redstoneCount.get())),
                value(text(player, "gui.chunkinfo.entities", "&7Entities: &f{value}"),
                        String.valueOf(d.entityCount.get())),
                value(text(player, "gui.chunkinfo.block_entities", "&7Block entities: &f{value}"),
                        String.valueOf(d.blockEntityCount)),
                value(text(player, "gui.chunkinfo.updates", "&7Updates/s: &f{value}"),
                        String.valueOf(d.updatesPerSec)),
                value(text(player, "gui.chunkinfo.activity", "&7Activity: &f{value}"),
                        activitySummary(d)),
                value(text(player, "gui.chunkinfo.impact", "&7Impact: &f{value}"),
                        round(d.impactScore)),
                value(text(player, "gui.chunkinfo.detector", "&7Detector: &f{value}"),
                        d.detectorType + " / " + d.detectorReason),
                value(text(player, "gui.chunkinfo.suspended", "&7Suspended blocks: &f{value}"),
                        String.valueOf(d.suspendedBlocks)),
                value(text(player, "gui.chunkinfo.mspt", "&7MSPT: &f{value}"),
                        round(d.msptContribution)),
                value(text(player, "gui.chunkinfo.danger", "&7Danger: &f{value}"),
                        d.dangerLevel),
                value(text(player, "gui.chunkinfo.machine", "&7Machine: &f{value}"),
                        ActivityAnalyzer.machineDisplay(
                                detector.messages().forViewer(player), d.machineType)),
                text(player, "gui.chunkinfo.click_actions", "&aClick for actions"));
    }

    private static String activitySummary(ChunkData d) {
        return "R" + d.redstonePerSec + " P" + d.pistonPerSec + " H" + d.hopperPerSec
                + " O" + d.observerPerSec + " C" + d.comparatorPerSec + " S" + d.sculkPerSec
                + " T" + d.trapdoorPerSec + " Q" + d.scheduledPerSec;
    }

    public void refreshOpenScreens() {
        if (openScreens.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Live>> cursor = openScreens.entrySet().iterator();
        while (cursor.hasNext()) {
            Map.Entry<UUID, Live> entry = cursor.next();
            Live screen = entry.getValue();
            if (screen.inventory == null || !stillOpen(screen)) {
                cursor.remove();
                continue;
            }
            try {
                if (screen.screen == Screen.DASHBOARD) {
                    paintDashboard(screen.viewer, screen.inventory);
                } else if (screen.screen == Screen.CHUNK) {
                    if (screen.data != null && !screen.data.isEmpty()) {
                        paintChunk(screen.viewer, screen.inventory, screen.data.get(0));
                    }
                } else {
                    paintChunkList(screen.viewer, screen);
                }
            } catch (Throwable failure) {
                cursor.remove();
            }
        }
    }

    private boolean stillOpen(Live screen) {
        try {
            Object real = SpongeViewers.player(screen.viewer);
            if (real == null) {
                return false;
            }
            return SpongeApi.unwrap(SpongeApi.callOrNull(real, "openInventory")) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void closeAll() {
        for (Live screen : openScreens.values()) {
            try {
                Object real = SpongeViewers.player(screen.viewer);
                if (real != null) {
                    SpongeApi.callOrNull(real, "closeInventory");
                }
            } catch (Throwable ignored) {
                continue;
            }
        }
        openScreens.clear();
    }

    public void forget(UUID id) {
        if (id != null) {
            openScreens.remove(id);
        }
    }

    private String title(String template, String world, int page, int pages) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        boolean hasPlaceholder = template.contains("{world}") || template.contains("{page}")
                || template.contains("{total}") || template.contains("{pages}");
        String result = template
                .replace("{world}", world == null ? "" : world)
                .replace("{page}", String.valueOf(page))
                .replace("{total}", String.valueOf(pages))
                .replace("{pages}", String.valueOf(pages));
        if (hasPlaceholder) {
            return result;
        }

        return pages > 1 ? result + " " + page + "/" + pages : result;
    }

    private String allWorlds(Object player) {
        return text(player, "gui.all_worlds", "*");
    }

    private void click(Object player, Screen screen, List<ChunkCoordinate> data, int page,
                       int pages, int slot) {
        if (screen == Screen.DASHBOARD) {
            if (slot == 12) { detector.scans().start(player); openDashboard(player); }
            else if (slot == 14) openChunks(player, 0, false);
            else if (slot == 16) openChunks(player, 0, true);
            else if (slot == 22) {
                int frozen = detector.freezeCulprits();
                SpongeApi.send(SpongeViewers.audience(player), frozen > 0
                        ? value(text(player, "cmd.smart_freeze_done",
                                "&bFroze {count} lagging chunk(s)."), String.valueOf(frozen))
                        : text(player, "cmd.smart_freeze_none",
                                "&aNo lagging chunks right now - nothing frozen."));
                openDashboard(player);
            }
            else if (slot == 30) startSearch(player, null);
            else if (slot == 32) { detector.setGlobalStop(!detector.globalStop()); openDashboard(player); }
            else if (slot == 31 || slot == 10) openDashboard(player);
            return;
        }
        if (screen == Screen.CHUNKS || screen == Screen.FROZEN) {
            if (slot >= 0 && data != null && slot < data.size()) {
                openChunk(player, data.get(slot));
                return;
            }

            if (slot == 45) {
                if (page > 0) openChunks(player, page - 1, screen == Screen.FROZEN);
            }
            else if (slot == 47) { cycleSort(player); openChunks(player, page, screen == Screen.FROZEN); }
            else if (slot == 51) startSearch(player, null);
            else if (slot == 53) {
                if (page + 1 < pages) openChunks(player, page + 1, screen == Screen.FROZEN);
            }
            else if (slot == 49) openDashboard(player);
            return;
        }
        if (screen == Screen.CHUNK && data != null && !data.isEmpty()) {
            final ChunkCoordinate c = data.get(0);
            if (slot == 20) {
                if (detector.engine().isFrozen(c) || detector.freezes().isSuspended(c)) {
                    detector.resumeRedstone(c);
                } else {
                    detector.freezes().freeze(c, "manual", "gui_request");
                }
                openChunk(player, c);
            } else if (slot == 22) {
                detector.worlds().removeEntities(c); openChunk(player, c);
            } else if (slot == 24) {
                detector.worlds().teleport(player, c);
            } else if (slot == 30) {
                if (detector.isStopped(c)) detector.resumeRedstone(c); else detector.stopRedstone(c);
                openChunk(player, c);
            } else if (slot == 32) {
                detector.forget(c);
                openChunks(player, 0, false);
            } else if (slot == 49) {
                openChunks(player, 0, false);
            }
        }
    }

    private void startSearch(Object player, String worldName) {
        awaitSearch(player, worldName);
        try {
            Object real = SpongeViewers.player(player);
            if (real != null) {
                SpongeApi.callOrNull(real, "closeInventory");
            }
        } catch (Throwable ignored) {

        }
        SpongeApi.send(SpongeViewers.audience(player), text(player, "gui.search_compass_prompt",
                "&eType the chunk or block coordinates in the chat, or &c/rdcancel&e."));
    }

    private String text(Object player, String key, String fallback) {
        return detector.messages().get(player, key, fallback);
    }

    private String value(String label, String replacement) {
        String shown = replacement == null ? "" : replacement;
        if (label == null || label.isEmpty()) {
            return shown;
        }
        String[] tokens = {"{value}", "{count}", "{percent}", "{amount}", "{number}",
            "{tps}", "{mspt}", "{time}", "{state}", "{level}", "{chunk}", "{world}"};
        String result = label;
        boolean filled = false;
        for (String token : tokens) {
            if (result.contains(token)) {
                result = result.replace(token, shown);
                filled = true;
            }
        }
        if (filled) {
            return result;
        }
        String separator = result.endsWith(" ") || result.endsWith(":") ? "" : " ";
        return result + separator + "&f" + shown;
    }

    private Object inventory54() throws ReflectiveOperationException {
        Class<?> viewable = SpongeApi.type("org.spongepowered.api.item.inventory.type.ViewableInventory");
        Object builder = SpongeApi.method(viewable, "builder").invoke(null);
        Object ref = SpongeApi.type("org.spongepowered.api.item.inventory.ContainerTypes")
                .getField("GENERIC_9X6").get(null);
        Object type = SpongeApi.call(ref, "get");
        Object step = SpongeApi.invoke(builder, "type", type);
        Object end = SpongeApi.call(step, "completeStructure");
        end = SpongeApi.invoke(end, "plugin", plugin);
        return SpongeApi.call(end, "build");
    }

    private void open(final Object viewer, Object inventory, String title, final Screen screen,
                      final List<ChunkCoordinate> data, final int page, final int pages)
            throws ReflectiveOperationException {
        final Object player = SpongeViewers.player(viewer);
        if (player == null) {
            SpongeApi.send(SpongeViewers.audience(viewer),
                    detector.messages().get(viewer, "gui.players_only",
                            "&cOnly players can open this menu."));
            return;
        }
        Object menu = SpongeApi.call(inventory, "asMenu");
        SpongeApi.invoke(menu, "setTitle", SpongeApi.component("&0" + title));
        SpongeApi.invoke(menu, "setReadOnly", Boolean.TRUE);
        Class<?> handlerClass = SpongeApi.type(
                "org.spongepowered.api.item.inventory.menu.handler.SlotClickHandler");
        Object handler = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{handlerClass},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().equals("handle") && args != null && args.length >= 4) {
                            Live current = openScreens.get(SpongeViewers.uniqueId(player));
                            click(player, screen,
                                    current == null ? data : current.data,
                                    current == null ? page : current.page,
                                    current == null ? pages : current.pages,
                                    ((Number) args[3]).intValue());
                            return Boolean.FALSE;
                        }
                        if (method.getName().equals("toString")) return "RedstoneDetectorGuiClick";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return null;
                    }
                });
        UUID id = SpongeViewers.uniqueId(player);
        if (id != null) {
            Live live = new Live();
            live.screen = screen;
            live.inventory = inventory;
            live.viewer = player;
            live.data = data;
            live.page = page;
            live.pages = pages;
            live.frozenOnly = screen == Screen.FROZEN;
            openScreens.put(id, live);
        }
        SpongeApi.invoke(menu, "registerSlotClick", handler);
        SpongeApi.invoke(menu, "open", player);
    }

    private Object item(String field, String name, List<String> lore) throws ReflectiveOperationException {
        Object reference;
        try { reference = SpongeApi.type("org.spongepowered.api.item.ItemTypes").getField(field).get(null); }
        catch (NoSuchFieldException missing) { reference = SpongeApi.type("org.spongepowered.api.item.ItemTypes").getField("PAPER").get(null); }
        Object type = SpongeApi.call(reference, "get");
        Class<?> stackClass = SpongeApi.type("org.spongepowered.api.item.inventory.ItemStack");
        Object builder = SpongeApi.method(stackClass, "builder").invoke(null);
        builder = SpongeApi.invoke(builder, "itemType", type);
        builder = SpongeApi.invoke(builder, "quantity", Integer.valueOf(1));
        Object stack = SpongeApi.call(builder, "build");
        offer(stack, "CUSTOM_NAME", SpongeApi.component(name));
        List<Object> components = new ArrayList<Object>();
        for (String line : lore) components.add(SpongeApi.component(line));
        offer(stack, "LORE", components);
        return stack;
    }

    private void offer(Object stack, String keyName, Object value) {
        try {
            Object key = SpongeApi.type("org.spongepowered.api.data.Keys").getField(keyName).get(null);
            SpongeApi.invoke(stack, "offer", key, value);
        } catch (Throwable ignored) { }
    }

    private void set(Object inventory, int index, Object stack) throws ReflectiveOperationException {
        int i = 0;
        for (Object slot : SpongeApi.iterable(SpongeApi.call(inventory, "slots"))) {
            if (i++ == index) { SpongeApi.invoke(slot, "set", stack); return; }
        }
    }

    private void fill(Object inv, String material, String name) throws ReflectiveOperationException {
        fillRange(inv, 0, 53, material, name);
    }
    private void fillRange(Object inv, int from, int to, String material, String name) throws ReflectiveOperationException {
        Object stack = item(material, name, list());
        for (int i = from; i <= to; i++) set(inv, i, stack);
    }
    private static List<String> list(String... values) {
        List<String> out = new ArrayList<String>(); Collections.addAll(out, values); return out;
    }
    private static String round(double v) { return String.valueOf(Math.round(v * 100.0) / 100.0); }
    private void fail(Object player, String message, Throwable failure) {
        logger.warning(message + ": " + SpongeApi.describe(failure));
        SpongeApi.send(SpongeViewers.audience(player), "&c" + message + ". Check the server log.");
    }
}
