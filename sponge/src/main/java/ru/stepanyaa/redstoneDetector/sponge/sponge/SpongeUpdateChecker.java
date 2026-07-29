package ru.stepanyaa.redstoneDetector.sponge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public final class SpongeUpdateChecker {

    private static final String PLUGIN_NAME = "RedstoneDetector";
    private static final String API_URL =
            "https://api.modrinth.com/v2/project/redstonedetector/version";
    private static final String DOWNLOAD_URL =
            "https://modrinth.com/plugin/redstonedetector/versions";
    private static final String PERMISSION = "redstonedetector.admin";

    private final SpongeScheduler scheduler;
    private final SpongeMessages messages;
    private final Logger logger;
    private final String currentVersion;

    private volatile boolean updateAvailable;
    private volatile String latestVersion;

    public SpongeUpdateChecker(SpongeScheduler scheduler, SpongeMessages messages,
            String currentVersion, Logger logger) {
        this.scheduler = scheduler;
        this.messages = messages;
        this.logger = logger;
        this.currentVersion = currentVersion == null || currentVersion.isEmpty()
                ? "unknown" : currentVersion;
    }

    public void check() {
        scheduler.async(new Runnable() {
            @Override
            public void run() {
                request();
            }
        });
    }

    private void request() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", PLUGIN_NAME + "/" + currentVersion);

            int code = connection.getResponseCode();
            if (code != 200) {
                logger.warning(SpongeLog.strip(messages.formatServer("update.check_failed",
                        "Failed to check for updates (code: {code})",
                        "{code}", String.valueOf(code))));
                return;
            }

            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            } finally {
                reader.close();
            }

            String newest = extractVersion(body.toString());
            if (newest == null) {
                logger.warning(SpongeLog.strip(messages.get("update.parse_failed",
                        "Failed to extract version from the Modrinth response")));
                return;
            }
            if (newest.equals(currentVersion)) {
                logger.info(SpongeLog.strip(messages.formatServer("update.latest",
                        PLUGIN_NAME + ": you have the latest version ({version})",
                        "{version}", currentVersion)));
                return;
            }
            updateAvailable = true;
            latestVersion = newest;
            logger.info(SpongeLog.strip(message(null)));
        } catch (Throwable failure) {
            logger.warning(SpongeLog.strip(messages.formatServer("update.error",
                    "Error checking for updates: {message}",
                    "{message}", String.valueOf(failure.getMessage()))));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String extractVersion(String json) {
        String marker = "\"version_number\"";
        int at = json.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + marker.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        String version = json.substring(open + 1, close).trim();
        return version.isEmpty() ? null : version;
    }

    private String message(Object viewer) {
        return messages.format(viewer, "update.available",
                "&cAvailable update &f{plugin}&c Current: &f{current} &c\u2192 New: &f{new} &c| &e{url}",
                "{plugin}", PLUGIN_NAME,
                "{current}", currentVersion,
                "{new}", String.valueOf(latestVersion),
                "{url}", DOWNLOAD_URL);
    }

    public boolean updateAvailable() {
        return updateAvailable;
    }

    public String latestVersion() {
        return latestVersion;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public void notifyViewer(Object viewer) {
        if (!updateAvailable || viewer == null) {
            return;
        }
        if (!SpongeViewers.hasPermission(viewer, PERMISSION)) {
            return;
        }
        SpongeApi.send(SpongeViewers.audience(viewer), message(viewer));
    }
}
