package me.arthed.smartgambling.integrations;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import me.arthed.smartgambling.handlers.PlaceholderSnapshot;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The only SmartGambling class linked to PlaceholderAPI.
 *
 * <p>Core code must load this class by name through {@link OptionalIntegration};
 * a server without PlaceholderAPI must never resolve this class.</p>
 */
public final class PapiBridge extends PlaceholderExpansion {
    private static final Object REGISTRATION_LOCK = new Object();
    private static boolean registered;
    private static PapiBridge instance;

    private final JavaPlugin owner;
    private final Supplier<PlaceholderSnapshot> snapshots;

    private PapiBridge(JavaPlugin owner, Supplier<PlaceholderSnapshot> snapshots) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public static boolean registerOnce(
            JavaPlugin owner,
            Supplier<PlaceholderSnapshot> snapshots
    ) {
        synchronized (REGISTRATION_LOCK) {
            if (registered) {
                return true;
            }
            PapiBridge candidate = new PapiBridge(owner, snapshots);
            registered = candidate.register();
            if (registered) {
                instance = candidate;
            }
            return registered;
        }
    }

    public static boolean unregisterOnce() {
        synchronized (REGISTRATION_LOCK) {
            if (!registered || instance == null) {
                return true;
            }
            boolean unregistered = instance.unregister();
            if (unregistered) {
                registered = false;
                instance = null;
            }
            return unregistered;
        }
    }

    @Override
    public String getAuthor() {
        List<String> authors = this.owner.getDescription().getAuthors();
        return authors.isEmpty() ? "Styro" : String.join(", ", authors);
    }

    @Override
    public String getIdentifier() {
        return "sg";
    }

    @Override
    public String getVersion() {
        return this.owner.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String parameters) {
        try {
            PlaceholderSnapshot snapshot = this.snapshots.get();
            return snapshot == null ? null : snapshot.resolve(parameters);
        } catch (RuntimeException exception) {
            this.owner.getLogger().log(Level.WARNING, "Could not resolve SmartGambling placeholder", exception);
            return null;
        }
    }
}
