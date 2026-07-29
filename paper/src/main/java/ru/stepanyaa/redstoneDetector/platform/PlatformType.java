package ru.stepanyaa.redstoneDetector.platform;

public enum PlatformType {

    FOLIA("Folia", true),

    PURPUR("Purpur", false),

    PAPER("Paper", false),

    SPIGOT("Spigot", false),

    BUKKIT("Bukkit", false),

    UNKNOWN("Unknown", false);

    private final String displayName;
    private final boolean regionised;

    PlatformType(String displayName, boolean regionised) {
        this.displayName = displayName;
        this.regionised = regionised;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isRegionised() {
        return regionised;
    }
}
