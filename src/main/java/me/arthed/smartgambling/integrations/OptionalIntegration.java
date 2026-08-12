package me.arthed.smartgambling.integrations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.arthed.smartgambling.handlers.PlaceholderSnapshot;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads optional, dependency-linked bridge classes without linking core code to them. */
public final class OptionalIntegration {
    private static final String PAPI_PLUGIN = "PlaceholderAPI";
    private static final String PAPI_BRIDGE = "me.arthed.smartgambling.integrations.PapiBridge";
    private static boolean papiRegistrationAttempted;
    private static boolean papiRegistered;

    private OptionalIntegration() {
    }

    public static synchronized boolean registerPapi(
            JavaPlugin owner,
            Supplier<PlaceholderSnapshot> snapshots
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(snapshots, "snapshots");
        if (!owner.getServer().getPluginManager().isPluginEnabled(PAPI_PLUGIN)) {
            return false;
        }
        if (papiRegistrationAttempted) {
            return papiRegistered;
        }
        papiRegistrationAttempted = true;
        papiRegistered = invokeStaticBoolean(
                owner.getLogger(),
                owner.getClass().getClassLoader(),
                PAPI_BRIDGE,
                "registerOnce",
                new Class<?>[]{JavaPlugin.class, Supplier.class},
                owner,
                snapshots
        );
        if (!papiRegistered) {
            owner.getLogger().warning(
                    "PlaceholderAPI was detected, but the SmartGambling placeholder bridge could not be registered."
            );
        }
        return papiRegistered;
    }

    public static synchronized void unregisterPapi(JavaPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (!papiRegistered) {
            return;
        }
        if (invokeStaticBoolean(
                owner.getLogger(),
                owner.getClass().getClassLoader(),
                PAPI_BRIDGE,
                "unregisterOnce",
                new Class<?>[0]
        )) {
            papiRegistered = false;
            papiRegistrationAttempted = false;
        }
    }

    /** Package-visible seam used to verify missing classes and linkage failures. */
    static boolean invokeStaticBoolean(
            Logger logger,
            ClassLoader loader,
            String className,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        Objects.requireNonNull(logger, "logger");
        try {
            Class<?> bridge = Class.forName(className, true, loader);
            Method method = bridge.getMethod(methodName, parameterTypes);
            return Boolean.TRUE.equals(method.invoke(null, arguments));
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logger.log(
                    Level.WARNING,
                    "Optional integration bridge '" + className + "' failed during registration.",
                    cause
            );
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logger.log(
                    Level.WARNING,
                    "Optional integration bridge '" + className + "' is unavailable.",
                    exception
            );
            return false;
        }
    }
}
