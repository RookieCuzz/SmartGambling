package me.arthed.smartgambling.creation;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable, validated settings for the guided machine placement workflow. */
public record CreationGuideSettings(
        long timeoutSeconds,
        int maxInteractionBlocks,
        double maxRadius,
        long previewIntervalTicks
) {
    public static final CreationGuideSettings DEFAULTS =
            new CreationGuideSettings(300L, 32, 16.0D, 10L);

    public CreationGuideSettings {
        if (timeoutSeconds < 30L || timeoutSeconds > 3600L) {
            throw new IllegalArgumentException("CreationGuide.timeoutSeconds 必须在 30 到 3600 之间");
        }
        if (maxInteractionBlocks < 1 || maxInteractionBlocks > 256) {
            throw new IllegalArgumentException("CreationGuide.maxInteractionBlocks 必须在 1 到 256 之间");
        }
        if (!Double.isFinite(maxRadius) || maxRadius < 1.0D || maxRadius > 256.0D) {
            throw new IllegalArgumentException("CreationGuide.maxRadius 必须是 1 到 256 之间的有限数字");
        }
        if (previewIntervalTicks < 2L || previewIntervalTicks > 200L) {
            throw new IllegalArgumentException("CreationGuide.previewIntervalTicks 必须在 2 到 200 之间");
        }
    }

    public static CreationGuideSettings from(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        return new CreationGuideSettings(
                config.getLong("CreationGuide.timeoutSeconds", DEFAULTS.timeoutSeconds),
                config.getInt("CreationGuide.maxInteractionBlocks", DEFAULTS.maxInteractionBlocks),
                config.getDouble("CreationGuide.maxRadius", DEFAULTS.maxRadius),
                config.getLong("CreationGuide.previewIntervalTicks", DEFAULTS.previewIntervalTicks)
        );
    }
}
