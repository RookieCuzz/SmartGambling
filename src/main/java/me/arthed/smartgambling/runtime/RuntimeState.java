package me.arthed.smartgambling.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.config.ForcedSlotTestSettings;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.inventories.ConfirmGameInventory;
import me.arthed.smartgambling.games.common.inventories.MoneyInventory;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.sound.CustomSound;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.games.poker.Poker;
import me.arthed.smartgambling.handlers.PlaceholderMessages;
import me.arthed.smartgambling.creation.CreationGuideSettings;
import org.bukkit.inventory.ItemStack;

/**
 * A complete, side-effect-free snapshot of configuration-backed runtime objects.
 *
 * <p>The reload coordinator prepares one of these while the current runtime is
 * still installed. Applying the snapshot is deliberately a short main-thread
 * operation so event handlers can never observe a half-loaded configuration.</p>
 */
public record RuntimeState(
        Map<String, Machine> machineTypes,
        Map<String, CustomSound> customSounds,
        Map<String, String> messages,
        List<String> helpMenu,
        MoneyInventory moneyInventory,
        ConfirmGameInventory confirmGameInventory,
        JackpotMachine jackpotMachine,
        CrashMachine crashMachine,
        BlackJack blackJack,
        Poker poker,
        PlaceholderMessages placeholderMessages,
        CreationGuideSettings creationGuideSettings,
        ForcedSlotTestSettings forcedSlotTestSettings,
        ItemStack chairItem,
        double[] chairOffset
) {
    public RuntimeState {
        machineTypes = Map.copyOf(machineTypes);
        customSounds = Map.copyOf(customSounds);
        messages = Map.copyOf(messages);
        helpMenu = List.copyOf(helpMenu);
        placeholderMessages = Objects.requireNonNull(placeholderMessages, "placeholderMessages");
        creationGuideSettings = Objects.requireNonNull(creationGuideSettings, "creationGuideSettings");
        forcedSlotTestSettings = Objects.requireNonNull(forcedSlotTestSettings, "forcedSlotTestSettings");
        chairItem = chairItem == null ? null : chairItem.clone();
        chairOffset = chairOffset == null ? new double[0] : chairOffset.clone();
    }

    public static RuntimeState capture(SmartGambling plugin) {
        return new RuntimeState(
                safeMap(plugin.machineTypes),
                safeMap(plugin.customSounds),
                safeMap(plugin.configManager.messages),
                plugin.configManager.helpMenu == null ? List.of() : plugin.configManager.helpMenu,
                plugin.moneyInventory,
                plugin.confirmGameInventory,
                plugin.jackpotMachine,
                plugin.crashMachine,
                plugin.blackJack,
                plugin.poker,
                plugin.configManager.getPlaceholderMessages(),
                plugin.configManager.getCreationGuideSettings(),
                plugin.configManager.getForcedSlotTestSettings(),
                plugin.chairItem,
                plugin.chairOffset
        );
    }

    public void apply(SmartGambling plugin) {
        plugin.machineTypes = new HashMap<>(this.machineTypes);
        plugin.customSounds = new HashMap<>(this.customSounds);
        plugin.configManager.messages = new HashMap<>(this.messages);
        plugin.configManager.helpMenu = List.copyOf(this.helpMenu);
        plugin.moneyInventory = this.moneyInventory;
        plugin.confirmGameInventory = this.confirmGameInventory;
        plugin.jackpotMachine = this.jackpotMachine;
        plugin.crashMachine = this.crashMachine;
        plugin.blackJack = this.blackJack;
        plugin.poker = this.poker;
        plugin.configManager.applyPlaceholderMessages(this.placeholderMessages);
        plugin.configManager.applyCreationGuideSettings(this.creationGuideSettings);
        plugin.configManager.applyForcedSlotTestSettings(this.forcedSlotTestSettings);
        plugin.chairItem = this.chairItem == null ? null : this.chairItem.clone();
        plugin.chairOffset = this.chairOffset.clone();
    }

    private static <K, V> Map<K, V> safeMap(Map<K, V> source) {
        return source == null ? Map.of() : source;
    }
}
