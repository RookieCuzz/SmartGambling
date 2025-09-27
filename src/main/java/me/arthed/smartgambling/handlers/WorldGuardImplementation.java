package me.arthed.smartgambling.handlers;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Level;
import me.arthed.smartgambling.SmartGambling;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class WorldGuardImplementation {
    private final Plugin owningPlugin = SmartGambling.getInstance();
    private Object worldGuard = null;
    private WorldGuardPlugin worldGuardPlugin = null;
    private Object regionContainer = null;
    private Method regionContainerGetMethod = null;
    private Method worldAdaptMethod = null;
    private Method regionManagerGetMethod = null;
    private Constructor<?> vectorConstructor = null;
    private Method vectorConstructorAsAMethodBecauseWhyNot = null;
    private boolean initialized = false;
    public static StateFlag CAN_USE_JACKPOT;

    public boolean isEnabled() {
        return this.worldGuardPlugin != null;
    }

    public WorldGuardImplementation(Plugin plugin, Plugin owningPlugin) {
        if (plugin instanceof WorldGuardPlugin) {
            this.worldGuardPlugin = (WorldGuardPlugin)plugin;

            try {
                Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Method getInstanceMethod = worldGuardClass.getMethod("getInstance");
                this.worldGuard = getInstanceMethod.invoke((Object)null);
                owningPlugin.getLogger().info("Found WorldGuard 7+");
            } catch (Exception var7) {
                owningPlugin.getLogger().info("Found WorldGuard <7");
            }

            try {
                owningPlugin.getLogger().info("Pre-check for WorldGuard custom flag registration");
                this.registerFlag();
            } catch (NoSuchMethodError var5) {
                owningPlugin.getLogger().log(Level.WARNING, "NOFLAGS", var5);
            } catch (Throwable var6) {
                owningPlugin.getLogger().log(Level.WARNING, "Unexpected error setting up custom flags, please make sure you are on WorldGuard 6.2 or above", var6);
            }
        }

    }

    protected RegionAssociable getAssociable(Player player) {
        RegionAssociable associable;
        if (player == null) {
            associable = Associables.constant(Association.NON_MEMBER);
        } else {
            associable = this.worldGuardPlugin.wrapPlayer(player);
        }

        return associable;
    }

    private void registerFlag() {
        FlagRegistry registry = null;

        try {
            Method getFlagRegistryMethod = this.worldGuard.getClass().getMethod("getFlagRegistry");
            registry = (FlagRegistry)getFlagRegistryMethod.invoke(this.worldGuard);

            try {
                StateFlag flag = new StateFlag("jackpotUse", false);
                registry.register(flag);
                CAN_USE_JACKPOT = flag;
            } catch (FlagConflictException var5) {
                Flag<?> existing = registry.get("jackpotUse");
                if (existing instanceof StateFlag) {
                    CAN_USE_JACKPOT = (StateFlag)existing;
                }
            }
        } catch (Exception var6) {
            var6.printStackTrace();
        }

    }

    private void initialize() {
        if (!this.initialized) {
            this.initialized = true;
            if (this.worldGuard != null) {
                try {
                    Method getPlatFormMethod = this.worldGuard.getClass().getMethod("getPlatform");
                    Object platform = getPlatFormMethod.invoke(this.worldGuard);
                    Method getRegionContainerMethod = platform.getClass().getMethod("getRegionContainer");
                    this.regionContainer = getRegionContainerMethod.invoke(platform);
                    Class<?> worldEditWorldClass = Class.forName("com.sk89q.worldedit.world.World");
                    Class<?> worldEditAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                    this.worldAdaptMethod = worldEditAdapterClass.getMethod("adapt", World.class);
                    this.regionContainerGetMethod = this.regionContainer.getClass().getMethod("get", worldEditWorldClass);
                } catch (Exception var9) {
                    this.owningPlugin.getLogger().log(Level.WARNING, "Failed to bind to WorldGuard, integration will not work!", var9);
                    this.regionContainer = null;
                    return;
                }
            } else {
                try {
                    this.regionContainerGetMethod = this.regionContainer.getClass().getMethod("get", World.class);
                } catch (Exception var8) {
                    this.owningPlugin.getLogger().log(Level.WARNING, "Failed to bind to WorldGuard, integration will not work!", var8);
                    this.regionContainer = null;
                    return;
                }
            }

            try {
                Class<?> vectorClass = Class.forName("com.sk89q.worldedit.Vector");
                this.vectorConstructor = vectorClass.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE);
                this.regionManagerGetMethod = RegionManager.class.getMethod("getApplicableRegions", vectorClass);
            } catch (Exception var7) {
                try {
                    Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                    this.vectorConstructorAsAMethodBecauseWhyNot = vectorClass.getMethod("at", Double.TYPE, Double.TYPE, Double.TYPE);
                    this.regionManagerGetMethod = RegionManager.class.getMethod("getApplicableRegions", vectorClass);
                } catch (Exception var6) {
                    this.owningPlugin.getLogger().log(Level.WARNING, "Failed to bind to WorldGuard (no Vector class?), integration will not work!", var7);
                    this.regionContainer = null;
                    return;
                }
            }

            if (this.regionContainer == null) {
                this.owningPlugin.getLogger().warning("Failed to find RegionContainer, WorldGuard integration will not function!");
            }
        }

    }

    private RegionManager getRegionManager(World world) {
        this.initialize();
        if (this.regionContainer != null && this.regionContainerGetMethod != null) {
            RegionManager regionManager = null;

            try {
                if (this.worldAdaptMethod != null) {
                    Object worldEditWorld = this.worldAdaptMethod.invoke((Object)null, world);
                    regionManager = (RegionManager)this.regionContainerGetMethod.invoke(this.regionContainer, worldEditWorld);
                } else {
                    regionManager = (RegionManager)this.regionContainerGetMethod.invoke(this.regionContainer, world);
                }
            } catch (Exception var4) {
                this.owningPlugin.getLogger().log(Level.WARNING, "An error occurred looking up a WorldGuard RegionManager", var4);
            }

            return regionManager;
        } else {
            return null;
        }
    }

    private ApplicableRegionSet getRegionSet(Location location) {
        RegionManager regionManager = this.getRegionManager(location.getWorld());
        if (regionManager == null) {
            return null;
        } else {
            try {
                Object vector = this.vectorConstructorAsAMethodBecauseWhyNot == null ? this.vectorConstructor.newInstance(location.getX(), location.getY(), location.getZ()) : this.vectorConstructorAsAMethodBecauseWhyNot.invoke((Object)null, location.getX(), location.getY(), location.getZ());
                return (ApplicableRegionSet)this.regionManagerGetMethod.invoke(regionManager, vector);
            } catch (Exception var4) {
                this.owningPlugin.getLogger().log(Level.WARNING, "An error occurred looking up a WorldGuard ApplicableRegionSet", var4);
                return null;
            }
        }
    }

    public boolean canUseJackpot(Player player) {
        Location location = player.getLocation();
        if (this.worldGuardPlugin != null && location != null) {
            ApplicableRegionSet checkSet = this.getRegionSet(location);
            if (checkSet == null) {
                return true;
            } else {
                return checkSet.queryState(this.getAssociable(player), new StateFlag[]{CAN_USE_JACKPOT}) == State.ALLOW;
            }
        } else {
            return true;
        }
    }
}