package fr.xephi.authme.command.help;

/**
 * Keys for messages used when showing command help.
 */
public enum HelpMessageKey {

    SHORT_DESCRIPTION("description.short", "Short description"),

    DETAILED_DESCRIPTION("description.detailed", "Detailed description"),

    USAGE("usage", "Usage");


    private final String key;
    private final String fallback;

    HelpMessageKey(String key, String fallback) {
        this.key = "common." + key;
        this.fallback = fallback;
    }

    public String getKey() {
        return key;
    }

    public String getFallback() {
        return fallback;
    }

}
