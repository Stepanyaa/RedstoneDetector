package ru.stepanyaa.redstoneDetector.sponge;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.invoke.MethodHandles;

import org.spongepowered.api.Server;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import java.util.logging.Logger;

public final class SpongeBootstrap {

    private static final String PLUGIN_ID = "redstonedetector";
    private static final Logger LOGGER = Logger.getLogger("RedstoneDetector");

    static {
        try {
            Class<?> lookups = Class.forName("org.spongepowered.common.event.ListenerLookups");
            Method set = lookups.getMethod("set", Class.class, MethodHandles.Lookup.class);
            set.invoke(null, SpongeBootstrap.class, MethodHandles.lookup());
        } catch (Throwable failure) {
            LOGGER.warning("Could not register Sponge listener lookup: " + failure);
        }
    }

    private final File dataFolder;

    private volatile Object pluginContainer;
    private volatile SpongeDetector detector;
    private volatile SpongeCommands commands;
    private volatile boolean started;

    private final SpongeMetrics metrics = new SpongeMetrics(LOGGER);

    public SpongeBootstrap() {
        this.dataFolder = resolveFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            LOGGER.warning("Could not create " + dataFolder.getAbsolutePath());
        }
        LOGGER.info("RedstoneDetector on Sponge, data folder: " + dataFolder.getAbsolutePath());
    }

    private File resolveFolder() {
        try {
            Object container = container();
            Object configManager = SpongeApi.call(SpongeApi.game(), "configManager");
            Method pluginConfig = SpongeApi.method(configManager.getClass(), "pluginConfig",
                    SpongeApi.type("org.spongepowered.plugin.PluginContainer"));
            Object reference = pluginConfig.invoke(configManager, container);
            Object directory = SpongeApi.call(reference, "directory");
            return ((java.nio.file.Path) directory).toFile();
        } catch (Throwable unavailable) {
            return new File("config" + File.separator + PLUGIN_ID);
        }
    }

    private String version() {
        try {
            Object metadata = SpongeApi.callOrNull(container(), "metadata");
            Object version = metadata == null ? null : SpongeApi.callOrNull(metadata, "version");
            if (version != null) {
                return String.valueOf(version);
            }
        } catch (Throwable unavailable) {

        }
        return "1.2.0";
    }

    private Object container() {
        Object cached = pluginContainer;
        if (cached != null) {
            return cached;
        }
        Object resolved = SpongeApi.pluginContainer(PLUGIN_ID);
        pluginContainer = resolved;
        return resolved;
    }

    @Listener
    public void onStarted(StartedEngineEvent<Server> event) {
        lifecycle("start", event);
    }

    @Listener
    public void onStopping(StoppingEngineEvent<Server> event) {
        lifecycle("stop", event);
    }

    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Raw> event) {
        lifecycle("commands", event);
    }

    @SuppressWarnings("unused")
    private void subscribe() {
        listen("org.spongepowered.api.event.lifecycle.StartedEngineEvent", "start");
        listen("org.spongepowered.api.event.lifecycle.StoppingEngineEvent", "stop");
        listen("org.spongepowered.api.event.lifecycle.RegisterCommandEvent", "commands");
    }

    private void listen(String eventClassName, final String action) {
        Class<?> eventClass = SpongeApi.typeOrNull(eventClassName);
        if (eventClass == null) {
            return;
        }
        try {
            Object manager = SpongeApi.eventManager();
            Class<?> listenerClass = SpongeApi.type("org.spongepowered.api.event.EventListener");
            Object listener = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{listenerClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getName().equals("handle") && args != null
                                    && args.length == 1) {
                                lifecycle(action, args[0]);
                            } else if (method.getName().equals("hashCode")) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            } else if (method.getName().equals("equals")) {
                                return Boolean.valueOf(proxy == args[0]);
                            } else if (method.getName().equals("toString")) {
                                return "RedstoneDetectorLifecycle";
                            }
                            return null;
                        }
                    });

            Object owner = container();
            if (owner == null) {
                throw new IllegalStateException("PluginContainer is not available yet");
            }
            SpongeApi.registerListener(manager, owner, eventClass, listener);
        } catch (Throwable failure) {
            LOGGER.severe("Could not listen to " + eventClassName + ": "
                    + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private void lifecycle(String action, Object event) {
        try {
            if (action.equals("stop")) {
                shutdown();
                return;
            }
            if (action.equals("commands")) {

                prepare();
                if (commands != null) {
                    commands.register(event, container());
                }
                return;
            }
            if (action.equals("start")) {
                Object engine = SpongeApi.callOrNull(event, "engine");
                SpongeApi.setServer(engine);
                startup();
            }
        } catch (Throwable failure) {
            LOGGER.severe("Sponge lifecycle step '" + action + "' failed: "
                    + SpongeApi.describe(failure));
        }
    }

    private synchronized void prepare() throws ReflectiveOperationException {
        if (detector == null) {
            detector = new SpongeDetector(container(), dataFolder, LOGGER, version());
        }
        if (commands == null) {
            commands = new SpongeCommands(detector, LOGGER);
        }
    }

    private synchronized void startup() {
        if (started) {
            return;
        }
        try {
            prepare();
            detector.start();

            metrics.start(container(), detector, version());
            started = true;
        } catch (Throwable failure) {
            LOGGER.severe("RedstoneDetector could not start on Sponge: "
                    + SpongeApi.describe(failure));
        }
    }

    private synchronized void shutdown() {
        metrics.stop();
        if (detector != null) {
            detector.stop();
        }
        detector = null;
        commands = null;
        started = false;
        SpongeApi.setServer(null);
    }

    public SpongeDetector detector() {
        return detector;
    }
}
