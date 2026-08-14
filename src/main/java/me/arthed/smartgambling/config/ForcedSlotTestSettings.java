package me.arthed.smartgambling.config;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/** Runtime settings for the one-shot forced slot result test facility. */
public record ForcedSlotTestSettings(boolean enabled, int expiresSeconds) {
    public static final ForcedSlotTestSettings DEFAULTS = new ForcedSlotTestSettings(false, 120);

    private static final String BASE_PATH = "Testing.forcedSlotResults";

    public ForcedSlotTestSettings {
        if (expiresSeconds <= 0) {
            throw new IllegalArgumentException("expiresSeconds must be positive");
        }
    }

    public static ForcedSlotTestSettings from(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        String enabledPath = BASE_PATH + ".enabled";
        String expiresPath = BASE_PATH + ".expiresSeconds";
        if (!config.isBoolean(enabledPath)) {
            throw new IllegalArgumentException(
                    "Invalid configuration at '" + enabledPath + "': must be true or false"
            );
        }
        if (!config.isInt(expiresPath)) {
            throw new IllegalArgumentException(
                    "Invalid configuration at '" + expiresPath + "': must be a positive integer"
            );
        }
        int expiresSeconds = config.getInt(expiresPath);
        if (expiresSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Invalid configuration at '" + expiresPath + "': must be positive (was "
                            + expiresSeconds + ')'
            );
        }
        return new ForcedSlotTestSettings(config.getBoolean(enabledPath), expiresSeconds);
    }
}
