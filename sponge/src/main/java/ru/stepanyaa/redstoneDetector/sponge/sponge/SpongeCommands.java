package ru.stepanyaa.redstoneDetector.sponge;

import ru.stepanyaa.redstoneDetector.ActivityAnalyzer;
import ru.stepanyaa.redstoneDetector.ChunkCoordinate;
import ru.stepanyaa.redstoneDetector.ChunkData;
import ru.stepanyaa.redstoneDetector.core.DetectorEngine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class SpongeCommands {

    private static final String PERMISSION = "redstonedetector.command";
    private static final String ADMIN = "redstonedetector.admin";

    private static final String[] SUBCOMMANDS = {"gui", "status", "scan", "list", "top", "info",
            "freeze", "unfreeze", "stop", "resume", "entities", "tp", "forget", "lang",
            "version", "reload", "help"};

    private final SpongeDetector detector;
    private final Logger logger;

    public SpongeCommands(SpongeDetector detector, Logger logger) {
        this.detector = detector;
        this.logger = logger;
    }

    public void register(Object registerEvent, Object pluginContainer) {
        try {
            Class<?> rawClass = SpongeApi.type("org.spongepowered.api.command.Command$Raw");
            Object command = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{rawClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            return dispatch(proxy, method, args);
                        }
                    });

            Method register = null;
            for (Method candidate : registerEvent.getClass().getMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals("register") && parameters.length == 4
                        && parameters[2] == String.class && parameters[3] == String[].class) {
                    register = candidate;
                    break;
                }
            }
            if (register == null) {
                logger.warning("Sponge command registration method not found; /rd is unavailable.");
                return;
            }
            register.setAccessible(true);
            register.invoke(registerEvent, pluginContainer, command, "rd",
                    new String[]{"redstonedetector", "reddetect"});

            Object cancelCommand = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{rawClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            if (method.getName().equals("process")) {
                                cancelSearch(args[0]);
                                return SpongeApi.staticCall(
                                        "org.spongepowered.api.command.CommandResult", "success");
                            }
                            return dispatch(proxy, method, args);
                        }
                    });
            register.invoke(registerEvent, pluginContainer, cancelCommand, "rdcancel",
                    new String[0]);
            logger.info("Registered /rd on Sponge.");
        } catch (Throwable failure) {
            logger.warning("Could not register /rd: " + SpongeApi.describe(failure));
        }
    }

    private Object dispatch(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("process")) {
            handle(args[0], remaining(args[1]));
            return SpongeApi.staticCall("org.spongepowered.api.command.CommandResult", "success");
        }
        if (name.equals("complete")) {
            return complete(remaining(args[1]));
        }
        if (name.equals("commandTree")) {
            try {
                return commandTree();
            } catch (Throwable treeFailure) {
                logger.warning("Could not build /rd client tree; using basic tree: "
                        + SpongeApi.describe(treeFailure));
                Class<?> node = SpongeApi.type(
                        "org.spongepowered.api.command.registrar.tree.CommandTreeNode");
                Object root = SpongeApi.method(node, "root").invoke(null);
                return SpongeApi.call(root, "executable");
            }
        }
        if (name.equals("canExecute")) {
            return Boolean.TRUE;
        }
        if (name.equals("shortDescription") || name.equals("extendedDescription")
                || name.equals("help")) {
            return java.util.Optional.of(SpongeApi.component("Redstone lag detector"));
        }
        if (name.equals("usage")) {
            return SpongeApi.component("/rd status|scan|list|info|freeze|unfreeze|reload");
        }
        if (name.equals("toString")) {
            return "RedstoneDetectorCommand";
        }
        if (name.equals("hashCode")) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if (name.equals("equals")) {
            return Boolean.valueOf(proxy == args[0]);
        }
        return null;
    }

    private List<String> complete(String line) {
        String typed = line.toLowerCase(Locale.ROOT);
        String[] parts = typed.trim().isEmpty() ? new String[0] : typed.trim().split("\\s+");
        boolean typingNext = typed.endsWith(" ");
        List<String> suggestions = new ArrayList<String>();

        if (parts.length == 0 || (parts.length == 1 && !typingNext)) {
            String token = parts.length == 0 ? "" : parts[0];
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(token)) {
                    suggestions.add(option);
                }
            }
            return suggestions;
        }
        String action = parts[0];
        String token = typingNext ? "" : parts[parts.length - 1];
        if (action.equals("lang")) {
            suggestions.add("list");
            for (String locale : detector.messages().availableLanguages()) {
                suggestions.add(locale);
            }
        } else if (action.equals("scan")) {
            suggestions.add("cancel");
        } else if (parts.length == 2 || (parts.length == 1 && typingNext)) {
            suggestions.addAll(detector.worlds().worldNames());
        }
        List<String> filtered = new ArrayList<String>();
        for (String option : suggestions) {
            if (option.toLowerCase(Locale.ROOT).startsWith(token)) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    private Object commandTree() throws ReflectiveOperationException {
        Class<?> nodeClass = SpongeApi.type(
                "org.spongepowered.api.command.registrar.tree.CommandTreeNode");
        Object root = SpongeApi.method(nodeClass, "root").invoke(null);
        root = SpongeApi.call(root, "executable");

        Class<?> typesClass = SpongeApi.type(
                "org.spongepowered.api.command.registrar.tree.CommandTreeNodeTypes");
        Object stringReference = typesClass.getField("STRING").get(null);
        Object stringType = SpongeApi.call(stringReference, "get");
        Object argument = SpongeApi.call(stringType, "createNode");
        argument = SpongeApi.call(argument, "greedy");
        argument = SpongeApi.call(argument, "executable");
        argument = SpongeApi.call(argument, "customCompletions");

        for (Method candidate : root.getClass().getMethods()) {
            if (candidate.getName().equals("child")
                    && candidate.getParameterTypes().length == 2
                    && candidate.getParameterTypes()[0] == String.class) {
                return candidate.invoke(root, "arguments", argument);
            }
        }
        return root;
    }

    private String remaining(Object reader) {
        Object text = SpongeApi.callOrNull(reader, "remaining");
        return text == null ? "" : String.valueOf(text);
    }

    private void cancelSearch(Object cause) {
        detector.gui().cancelSearch(SpongeViewers.uniqueId(cause));
        reply(cause, "chat.search.cancelled", "&7Chunk search cancelled.");
    }

    private void handle(Object cause, String line) {
        if (!SpongeViewers.hasPermission(cause, PERMISSION)) {
            reply(cause, "cmd.no_permission", "&cYou do not have permission.");
            return;
        }
        String[] args = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");
        String action = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);

        if (action.equals("status")) {
            status(cause);
        } else if (action.equals("gui")) {
            Object player = SpongeViewers.player(cause);
            if (player == null) {
                reply(cause, "gui.players_only", "&cOnly a player can open the GUI.");
            } else {
                detector.gui().openDashboard(player);
            }
        } else if (action.equals("scan")) {
            scan(cause, args);
        } else if (action.equals("list") || action.equals("top")) {
            list(cause);
        } else if (action.equals("reload")) {
            if (!admin(cause)) {
                return;
            }
            detector.reloadFiles();
            reply(cause, "cmd.reload.success", "&aConfiguration reloaded.");
        } else if (action.equals("version")) {
            version(cause);
        } else if (action.equals("lang")) {
            language(cause, args);
        } else if (action.equals("info") && args.length >= 3) {
            info(cause, coord(args));
        } else if (action.equals("search") && args.length >= 3) {

            Object player = SpongeViewers.player(cause);
            if (player == null) {
                reply(cause, "gui.players_only", "&cOnly players can use this.");
            } else {
                SpongePlayerEvents.openSearchResult(detector, player, coord(args));
            }
        } else if (action.equals("freeze") && args.length >= 3) {
            if (admin(cause)) {
                freeze(cause, coord(args));
            }
        } else if (action.equals("unfreeze") && args.length >= 3) {
            if (admin(cause)) {
                unfreeze(cause, coord(args));
            }
        } else if (action.equals("stop") && args.length >= 3) {
            if (admin(cause)) {
                ChunkCoordinate target = coord(args);
                detector.stopRedstone(target);
                reply(cause, "cmd.state.on", "&cRedstone stopped in &f{chunk}&c.",
                        "{chunk}", target.toDisplayString());
            }
        } else if (action.equals("stop")) {
            if (admin(cause)) {
                detector.setGlobalStop(true);
                reply(cause, "cmd.freeze.on", "&aGlobal redstone freeze &cENABLED&a.");
            }
        } else if (action.equals("resume") && args.length >= 3) {
            if (admin(cause)) {
                ChunkCoordinate target = coord(args);
                detector.resumeRedstone(target);
                reply(cause, "cmd.state.off", "&aRedstone resumed in &f{chunk}&a.",
                        "{chunk}", target.toDisplayString());
            }
        } else if (action.equals("resume")) {
            if (admin(cause)) {
                detector.setGlobalStop(false);
                reply(cause, "cmd.freeze.off", "&aGlobal redstone freeze &2DISABLED&a.");
            }
        } else if (action.equals("forget") && args.length >= 3) {
            if (admin(cause)) {
                ChunkCoordinate target = coord(args);
                boolean removed = detector.forget(target);
                reply(cause, removed ? "cmd.forget.done" : "cmd.info.no_data",
                        removed ? "&aChunk &f{chunk}&a is no longer monitored."
                                : "&7No data for &f{chunk}&7.",
                        "{chunk}", target.toDisplayString());
            }
        } else if (action.equals("entities") && args.length >= 3) {
            if (admin(cause)) {
                ChunkCoordinate target = coord(args);
                reply(cause, "cmd.entities.removed",
                        "&aRemoved &f{count}&a entities in &f{chunk}&a.",
                        "{count}", String.valueOf(detector.worlds().removeEntities(target)),
                        "{chunk}", target.toDisplayString());
            }
        } else if (action.equals("tp") && args.length >= 3) {
            Object player = SpongeViewers.player(cause);
            if (player == null) {
                reply(cause, "gui.players_only", "&cOnly a player can teleport.");
            } else if (detector.worlds().teleport(player, coord(args))) {
                reply(cause, "cmd.tp.done", "&aTeleported.");
            } else {
                reply(cause, "cmd.tp.failed", "&cCould not teleport there.");
            }
        } else {
            help(cause);
        }
    }

    private boolean admin(Object cause) {
        if (SpongeViewers.hasPermission(cause, ADMIN)) {
            return true;
        }
        reply(cause, "cmd.no_permission", "&cYou do not have permission.");
        return false;
    }

    private void help(Object cause) {
        reply(cause, "command.help_header", "&6RedstoneDetector &7- commands");
        reply(cause, "command.help_gui", "&e/rd gui &7- detector dashboard");
        reply(cause, "command.help_status", "&e/rd status &7- server and detector state");
        reply(cause, "command.help_scan", "&e/rd scan [cancel] &7- scan every loaded chunk");
        reply(cause, "command.help_list", "&e/rd list &7- worst chunks right now");
        reply(cause, "command.help_info", "&e/rd info <world> <x> <z> &7- chunk details");
        reply(cause, "command.help_freeze",
                "&e/rd freeze|unfreeze <world> <x> <z> &7- clear or restore redstone");
        reply(cause, "command.help_stop",
                "&e/rd stop|resume [world x z] &7- pause redstone without breaking blocks");
        reply(cause, "command.help_entities", "&e/rd entities <world> <x> <z> &7- clear entities");
        reply(cause, "command.help_tp", "&e/rd tp <world> <x> <z> &7- teleport to a chunk");
        reply(cause, "command.help_forget",
                "&e/rd forget <world> <x> <z> &7- stop monitoring a chunk");
        reply(cause, "command.help_lang", "&e/rd lang [list|<code>] &7- language settings");
        reply(cause, "command.help_reload", "&e/rd reload &7- reload configuration");
    }

    private void scan(Object cause, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            if (detector.scans().requestCancel()) {
                reply(cause, "cmd.scan.cancelled", "&7Scan cancelled.");
            } else {
                reply(cause, "cmd.scan.busy", "&cNo scan is running right now.");
            }
            return;
        }
        if (detector.scans().start(cause)) {
            reply(cause, "cmd.scan.started", "&aScan started.");
        } else {
            reply(cause, "cmd.scan.busy", "&cA scan is already running, please wait.");
        }
    }

    private void version(Object cause) {
        reply(cause, "update.latest", "&aRedstoneDetector {version} is up to date.",
                "{version}", detector.version());
        if (detector.updates().updateAvailable()) {
            detector.updates().notifyViewer(cause);
        }
    }

    private void language(Object cause, String[] args) {
        SpongeMessages messages = detector.messages();
        if (args.length < 2) {
            reply(cause, "command.lang_current", "&7Current language: &f{lang}",
                    "{lang}", messages.resolveLanguage(cause));
            reply(cause, "command.lang_available", "&7Available: &f{list}",
                    "{list}", String.join(", ", messages.availableLanguages()));
            return;
        }
        String requested = args[1].toLowerCase(Locale.ROOT);
        if (requested.equals("list")) {
            reply(cause, "command.lang_available", "&7Available: &f{list}",
                    "{list}", String.join(", ", messages.availableLanguages()));
            return;
        }
        if (!SpongeViewers.hasPermission(cause, ADMIN)) {
            reply(cause, "command.no_permission_lang",
                    "&cYou may not change the server language.");
            return;
        }
        String resolved = messages.match(SpongeMessages.normalize(requested));
        if (resolved == null) {
            reply(cause, "command.lang_unknown", "&cUnknown language: &f{lang}",
                    "{lang}", requested);
            return;
        }
        detector.config().setLanguage(resolved);
        detector.reloadFiles();
        reply(cause, "command.lang_changed", "&aLanguage changed to &f{lang}&a.",
                "{lang}", resolved);
    }

    private void status(Object cause) {
        DetectorEngine engine = detector.engine();
        reply(cause, "cmd.status.header", "&6Server state");
        reply(cause, "cmd.status.tps", "&7TPS: &f{tps} &7MSPT: &f{mspt}",
                "{tps}", round(engine.serverTps()), "{mspt}", round(engine.serverMspt()));
        reply(cause, "cmd.status.problems", "&7Problem chunks: &f{count} &7(tracked: &f{tracked}&7)",
                "{count}", String.valueOf(engine.suspiciousCount()),
                "{tracked}", String.valueOf(engine.chunks().size()));
        reply(cause, "cmd.status.frozen", "&7Frozen chunks: &f{count}",
                "{count}", String.valueOf(engine.frozenChunks().size()));
        reply(cause, "cmd.status.global_freeze", "&7Global freeze: &f{state}",
                "{state}", String.valueOf(detector.globalStop()));
        String lastScan = detector.scans().isRunning()
                ? detector.scans().progressPercent() + "%"
                : (detector.scans().lastScanTime() == 0 ? "-"
                        : (detector.scans().lastScanDurationMs() / 1000) + "s");
        reply(cause, "cmd.status.last_scan", "&7Last scan: &f{value}", "{value}", lastScan);
    }

    private void list(Object cause) {
        final Map<ChunkCoordinate, ChunkData> chunks = detector.engine().chunks();
        List<ChunkCoordinate> sorted = new ArrayList<ChunkCoordinate>(chunks.keySet());
        sorted.sort(new Comparator<ChunkCoordinate>() {
            @Override
            public int compare(ChunkCoordinate left, ChunkCoordinate right) {
                ChunkData first = chunks.get(left);
                ChunkData second = chunks.get(right);
                double leftWeight = first == null ? 0.0
                        : first.msptContribution * 1000.0 + first.updatesPerSec;
                double rightWeight = second == null ? 0.0
                        : second.msptContribution * 1000.0 + second.updatesPerSec;
                return Double.compare(rightWeight, leftWeight);
            }
        });
        if (sorted.isEmpty()) {
            reply(cause, "data.empty", "&7Nothing tracked yet.");
            return;
        }
        reply(cause, "cmd.list.header", "&6Busiest chunks");
        int shown = Math.min(10, sorted.size());
        for (int index = 0; index < shown; index++) {
            ChunkCoordinate coord = sorted.get(index);
            ChunkData data = chunks.get(coord);
            if (data == null) {
                continue;
            }
            reply(cause, "cmd.list.line",
                    "&e{world} {chunk} &7updates/s: &f{updates} &7mspt: &f{mspt} &7level: &f{level}",
                    "{world}", coord.world(),
                    "{chunk}", coord.toDisplayString(),
                    "{updates}", String.valueOf(data.updatesPerSec),
                    "{mspt}", round(data.msptContribution),
                    "{level}", data.dangerLevel);
        }
    }

    private void info(Object cause, ChunkCoordinate coord) {
        ChunkData data = detector.engine().peek(coord);
        if (data == null) {
            reply(cause, "cmd.info.no_data", "&7No data for &f{chunk}&7.",
                    "{chunk}", coord.toDisplayString());
            return;
        }
        reply(cause, "cmd.info.header", "&6Chunk &f{world} {chunk}",
                "{world}", coord.world(), "{chunk}", coord.toDisplayString());
        reply(cause, "cmd.info.counts", "&7Mechanisms: &f{redstone} &7Entities: &f{entities}",
                "{redstone}", String.valueOf(data.redstoneCount.get()),
                "{entities}", String.valueOf(data.entityCount.get()));
        reply(cause, "cmd.info.activity", "&7Updates/s: &f{updates} &7Estimated mspt: &f{mspt}",
                "{updates}", String.valueOf(data.updatesPerSec),
                "{mspt}", round(data.msptContribution));
        reply(cause, "cmd.info.level", "&7Level: &f{level} &7Machine: &f{machine}",
                "{level}", data.dangerLevel,
                "{machine}", ActivityAnalyzer.machineDisplay(
                        detector.messages().forViewer(cause), data.machineType));
        reply(cause, "cmd.info.frozen", "&7Frozen: &f{state} &7Stopped: &f{stopped}",
                "{state}", String.valueOf(detector.engine().isFrozen(coord)),
                "{stopped}", String.valueOf(detector.isStopped(coord)));
    }

    private void freeze(final Object cause, final ChunkCoordinate coord) {
        detector.worlds().runAtChunk(coord, new Runnable() {
            @Override
            public void run() {
                int cleared = detector.worlds().removeRedstone(coord);
                detector.engine().markFrozen(coord);
                reply(cause, "cmd.freeze.cleared",
                        "&aCleared &f{count}&a mechanisms in &f{chunk}&a.",
                        "{count}", String.valueOf(cleared),
                        "{chunk}", coord.toDisplayString());
            }
        });
    }

    private void unfreeze(final Object cause, final ChunkCoordinate coord) {
        detector.worlds().runAtChunk(coord, new Runnable() {
            @Override
            public void run() {
                int restored = detector.worlds().restoreRedstone(coord);
                detector.engine().clearFrozen(coord);
                detector.resumeRedstone(coord);
                reply(cause, "cmd.freeze.restored",
                        "&aRestored &f{count}&a blocks in &f{chunk}&a.",
                        "{count}", String.valueOf(restored),
                        "{chunk}", coord.toDisplayString());
            }
        });
    }

    private ChunkCoordinate coord(String[] args) {
        String world = args[1];
        int x = parse(args[2], 0);
        int z = args.length > 3 ? parse(args[3], 0) : 0;
        return new ChunkCoordinate(world, x, z);
    }

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    private void reply(Object cause, String key, String fallback, String... replacements) {
        String message = detector.messages().format(cause, key, fallback, replacements);
        SpongeApi.send(SpongeViewers.audience(cause), message);
    }
}
