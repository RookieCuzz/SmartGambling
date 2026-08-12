package me.arthed.smartgambling.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.arthed.smartgambling.SmartGambling;
import org.junit.jupiter.api.Test;

class OptionalIntegrationTest {
    private static final Logger LOGGER = quietLogger();

    @Test
    void missingBridgeAndLinkageErrorsAreContained() {
        assertFalse(OptionalIntegration.invokeStaticBoolean(
                LOGGER,
                getClass().getClassLoader(),
                "missing.optional.Bridge",
                "register",
                new Class<?>[0]
        ));
        assertFalse(OptionalIntegration.invokeStaticBoolean(
                LOGGER,
                getClass().getClassLoader(),
                LinkageBrokenBridge.class.getName(),
                "register",
                new Class<?>[0]
        ));
        assertTrue(OptionalIntegration.invokeStaticBoolean(
                LOGGER,
                getClass().getClassLoader(),
                WorkingBridge.class.getName(),
                "register",
                new Class<?>[0]
        ));
    }

    @Test
    void coreClassesLoadWhenPlaceholderApiPackagesAreBlocked() throws Exception {
        URL classes = SmartGambling.class.getProtectionDomain().getCodeSource().getLocation();
        try (FilteringClassLoader loader = new FilteringClassLoader(
                new URL[]{classes},
                getClass().getClassLoader()
        )) {
            Class<?> pluginClass = Class.forName(
                    "me.arthed.smartgambling.SmartGambling",
                    false,
                    loader
            );
            Class<?> configClass = Class.forName(
                    "me.arthed.smartgambling.config.ConfigManager",
                    false,
                    loader
            );
            Class<?> optionalClass = Class.forName(
                    "me.arthed.smartgambling.integrations.OptionalIntegration",
                    false,
                    loader
            );

            assertNotNull(pluginClass);
            assertNotNull(configClass);
            assertNotNull(optionalClass);
            assertSame(loader, pluginClass.getClassLoader());
        }
    }

    public static final class WorkingBridge {
        public static boolean register() {
            return true;
        }
    }

    public static final class LinkageBrokenBridge {
        static {
            failLinkage();
        }

        private static void failLinkage() {
            throw new NoClassDefFoundError("simulated optional dependency");
        }

        public static boolean register() {
            return true;
        }
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(OptionalIntegrationTest.class.getName());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static final class FilteringClassLoader extends URLClassLoader {
        private FilteringClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this.getClassLoadingLock(name)) {
                if (name.startsWith("me.clip.placeholderapi")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith("me.arthed.smartgambling.")
                        && !name.startsWith("me.arthed.smartgambling.integrations.OptionalIntegrationTest")) {
                    Class<?> loaded = this.findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            loaded = this.findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            // Some generated/test-only classes may only exist in the parent loader.
                        }
                    }
                    if (loaded != null) {
                        if (resolve) {
                            this.resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
