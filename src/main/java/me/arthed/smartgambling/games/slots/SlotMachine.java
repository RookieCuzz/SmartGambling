package me.arthed.smartgambling.games.slots;

import java.util.HashMap;
import java.util.List;
import java.util.NavigableMap;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.inventories.SubInventory;
import me.arthed.smartgambling.games.common.inventories.animation.InventoryAnimations;
import me.arthed.smartgambling.games.common.inventories.objects.Button;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.common.machine.OpenMachine;
import me.arthed.smartgambling.games.slots.objects.SlotItem;
import me.arthed.smartgambling.games.slots.objects.rewards.Reward;
import me.arthed.smartgambling.utils.DisplayUtils;
import me.clip.placeholderapi.PlaceholderAPI;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

public class SlotMachine implements Machine {
    public final String name;
    private final ItemStack machineItem;
    private final double[] entityOffset;
    private final Inventory baseInventory;
    private final Inventory newInv;
    public final String inventoryTitle;
    private final List<List<Integer>> displaySlots;
    private final Button spinButton;
    private final Button moneyButton;
    private final Button rewardsGuiButton;
    private final Button closeButton;
    private final SubInventory rewardsGUI;
    private final NavigableMap<Integer, SlotItem> itemsWeighed;
    private final int itemsTotalWeight;
    private final List<Reward> rewards;
    public final int defaultBet;
    private final int animationDuration;
    private final int animationStartingSpeed;
    private final InventoryAnimations animations;
    private final HashMap<Player, SlotMachine.PlayerInventoryData> playerInventoryData;

    public SlotMachine(String name, ItemStack machineItem, double[] entityOffset, String inventoryTitle, Inventory baseInventory, List<List<Integer>> displaySlots, Button spinButton, Button moneyButton, Button rewardsGuiButton, Button closeButton, SubInventory rewardsGUI, NavigableMap<Integer, SlotItem> itemsWeighed, int itemsTotalWeight, List<Reward> rewards, InventoryAnimations animations, int animationDuration, int defaultBet, int animationStartingSpeed) {
        this.name = name;
        this.machineItem = machineItem;
        this.entityOffset = entityOffset;
        this.inventoryTitle = inventoryTitle;
        this.baseInventory = baseInventory;
        this.displaySlots = displaySlots;
        this.spinButton = spinButton;
        this.moneyButton = moneyButton;
        this.rewardsGuiButton = rewardsGuiButton;
        this.closeButton = closeButton;
        this.newInv=baseInventory;
        this.rewardsGUI = rewardsGUI;
        this.itemsWeighed = itemsWeighed;
        this.itemsTotalWeight = itemsTotalWeight;
        this.rewards = rewards;
        this.animationDuration = animationDuration;
        this.defaultBet = defaultBet;
        this.animations = animations;
        this.animationStartingSpeed = animationStartingSpeed;
        this.playerInventoryData = new HashMap();
    }

    public void open(Player player, OpenInterface openSlot) {
        SmartGambling.getInstance().openMachines.put(player, openSlot);
        OpenMachine openMachine = (OpenMachine)openSlot;
        openMachine.machineData.inUse = true;

        for(Entity e : openMachine.machineData.entities[0].getPassengers()) {
            if (!e.equals(player)) {
                openMachine.machineData.entities[0].removePassenger(e);
            }
        }

        Location location = openMachine.machineData.entities[0].getLocation();
        player.setRotation(location.getYaw(), location.getPitch());
        if (!openMachine.machineData.entities[0].getPassengers().contains(player)) {
            openMachine.machineData.entities[0].addPassenger(player);
        }

        if (openSlot.betAmount == 0) {
            openSlot.betAmount = this.defaultBet;
        }
      //  String balance= PlaceholderAPI.setPlaceholders(player,"%vault_eco_balance_fixed%");
        Inventory playerInventory = Bukkit.createInventory(player, this.baseInventory.getSize(), ":offset_-8::mctown_slotui::offset_-200:");
        playerInventory.setContents(this.baseInventory.getContents());

        for(int slot : this.spinButton.getSlots()) {
            ItemStack item = playerInventory.getItem(slot);
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                meta.setDisplayName(meta.getDisplayName().replace("%bet%", "" + openSlot.betAmount));
                item.setItemMeta(meta);
                playerInventory.setItem(slot, item);
            }
        }

        openSlot.inventory = playerInventory;
        this.animations.startAnimations(playerInventory);

        this.playerInventoryData.put(player, new SlotMachine.PlayerInventoryData(new int[this.displaySlots.size()], new SlotItem[this.displaySlots.size()], new SlotItem[this.displaySlots.size()]));
        OpeningPlayer openingPlayer = new OpeningPlayer(player);
        SmartGambling.getInstance().getPlaybackManager().openingPlayers.put(player.getUniqueId(), openingPlayer);
        player.openInventory(playerInventory);

      //  return playerInventory;
    }


    public static String convertToDownNumber(int number) {
        //
        // 缅甸语的数字字符数组섎섏섐섑섒섓섔섕섖섗
        char[] burmaDigits = {'섎', '섏', '섐', '섐', '섒', '섓', '섔', '섕', '섖', '섗'};
        StringBuilder result = new StringBuilder();

        int digitCount = 0; // 用于跟踪位数

        // 将阿拉伯数值转换为缅甸数值
        while (number > 0) {
            int digit = number % 10;
            result.insert(0, burmaDigits[digit]);

            digitCount++;
            number /= 10;

            // 判断是否需要添加连字符
            if (digitCount < 10 && number > 0) {
                result.insert(0, '\uF801');
                //result.insert(0, '-');
            }
        }

        return result.toString();
    }
    public Inventory changeInventoryTitle(Inventory inv,Player player, String newTitle) {
        Inventory newInventory = Bukkit.createInventory(player, 54, newTitle);

        newInventory.setContents(inv.getContents());
        return newInventory;
    }

    public void close(Player player, Inventory inventory) {

        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
        if (invData != null && invData.spinning) {
            Bukkit.getScheduler().runTask(SmartGambling.getInstance(), () -> player.openInventory(inventory));
        } else {
            this.playerInventoryData.remove(player);
            OpenMachine openMachine = (OpenMachine)SmartGambling.getInstance().openMachines.get(player);
            this.animations.stopAnimations(openMachine.inventory);
            openMachine.machineData.inUse = false;
            SmartGambling.getInstance().openMachines.remove(player);
            PlaybackManager.removeOpeningPlayer(player);
        }
    }
    public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!((SlotMachine.PlayerInventoryData)this.playerInventoryData.get((Player)event.getWhoClicked())).spinning) {
            if (this.spinButton.isClicked(event.getSlot())) {
                this.spin(event.getInventory(), (Player)event.getWhoClicked());
            } else if (this.moneyButton.isClicked(event.getSlot())) {
                SmartGambling.getInstance().moneyInventory.open((Player)event.getWhoClicked(), (OpenInterface)SmartGambling.getInstance().openMachines.get((Player)event.getWhoClicked()));
            } else if (this.rewardsGuiButton.isClicked(event.getSlot())) {
                this.rewardsGUI.open((Player)event.getWhoClicked(), (OpenInterface)SmartGambling.getInstance().openMachines.get((Player)event.getWhoClicked()));
            } else if (this.closeButton.isClicked(event.getSlot())) {
                event.getWhoClicked().closeInventory();
            }

        }
    }


  /*  public void inventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player= (Player) event.getWhoClicked();
        if (!((SlotMachine.PlayerInventoryData)this.playerInventoryData.get((Player)event.getWhoClicked())).spinning) {
            if (this.spinButton.isClicked(event.getSlot())) {
          //     changeInventoryTitle(event.getInventory(),"变了吗");
                int bet = ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).betAmount;
                SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
                {
                    SmartGambling.getEconomy().withdrawPlayer(player, (double)bet);
                    player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).betAmount, SmartGambling.getEconomy().getBalance(player)));
                    invData.spinning = true;
                //    player.closeInventory();
                    Inventory inventory= open(player,(OpenInterface)SmartGambling.getInstance().openMachines.get(player));

                    this.animations.startDependentAnimations(inventory);
                    this.startAnimation(inventory, player);
                    invData.spinEndTask = Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                        invData.spinning = false;
                        this.animations.stopDependentAnimations(inventory);
                        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> this.stoppedSpinning(inventory, player), 8L);
                    }, (long)(this.animationDuration + 21) + 6L * (long)this.displaySlots.size());
                    player.sendMessage("我爱你");
                }





            //    this.spin(event.getInventory(), (Player)event.getWhoClicked());
            } else if (this.moneyButton.isClicked(event.getSlot())) {
                SmartGambling.getInstance().moneyInventory.open((Player)event.getWhoClicked(), (OpenInterface)SmartGambling.getInstance().openMachines.get((Player)event.getWhoClicked()));
            } else if (this.rewardsGuiButton.isClicked(event.getSlot())) {
                this.rewardsGUI.open((Player)event.getWhoClicked(), (OpenInterface)SmartGambling.getInstance().openMachines.get((Player)event.getWhoClicked()));
            } else if (this.closeButton.isClicked(event.getSlot())) {
                event.getWhoClicked().closeInventory();
            }

        }
    } */

    public ItemStack getMachineItem() {
        return this.machineItem;
    }

    public double[] getMachineEntityOffset() {
        return this.entityOffset;
    }

    public void spin(Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
        if (!invData.spinning) {
            int bet = ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).betAmount;
            Economy economy = SmartGambling.getEconomy();
            if (economy.getBalance(player) < (double)bet) {
                DisplayUtils.displayActionBar(player, String.format((String)SmartGambling.getInstance().configManager.messages.get("notEnoughMoneyActionBar"), bet, economy.getBalance(player)));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F);
            } else {
                SmartGambling.getEconomy().withdrawPlayer(player, (double)bet);
                player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("moneyExtracted"), ((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).betAmount, SmartGambling.getEconomy().getBalance(player)));
                invData.spinning = true;
                this.animations.startDependentAnimations(inventory);
                this.startAnimation(inventory, player);
                invData.spinEndTask = Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    invData.spinning = false;
                    this.animations.stopDependentAnimations(inventory);
                    Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> this.stoppedSpinning(inventory, player), 8L);
                }, (long)(this.animationDuration + 21) + 6L * (long)this.displaySlots.size());
            }
        }
    }

    public void stoppedSpinning(Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
        Reward reward = this.checkRewards(invData.finalItems);
        if (reward != null) {
            float amountWon = (float)Math.round((float)((OpenInterface)SmartGambling.getInstance().openMachines.get(player)).betAmount * reward.moneyMultiplier);
            SmartGambling.getEconomy().depositPlayer(player, (double)amountWon);
            double balance = SmartGambling.getEconomy().getBalance(player);
            DisplayUtils.displayActionBar(player, String.format((String)SmartGambling.getInstance().configManager.messages.get("wonMoneyActionBar"), amountWon, balance));
            player.sendMessage(String.format((String)SmartGambling.getInstance().configManager.messages.get("wonMoney"), amountWon, balance));
            if (reward.winningCommands != null) {
                for(String command : reward.winningCommands) {
                    if (command.startsWith("message:")) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', command.replace("message: ", "").replace("%player%", player.getName())));
                    } else if (command.startsWith("bossbar")) {
                        String[] data = command.split(": ");
                        String[] bossbarData = data[0].split(" ");
                        BarColor color = BarColor.valueOf(bossbarData[1].toUpperCase());
                        BarStyle style = BarStyle.valueOf(bossbarData[2].toUpperCase());
                        int seconds = Integer.parseInt(bossbarData[3]);
                        DisplayUtils.displayBossBar(player, data[1].replace("%player%", player.getName()), color, style, seconds);
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
                    }
                }
            }
            player.closeInventory();
            reward.sound.play(player);
        }

    }

    private SlotItem getRandomItem() {
        int number = SmartGambling.getInstance().random.nextInt(this.itemsTotalWeight);
        return (SlotItem)this.itemsWeighed.higherEntry(number).getValue();
    }

    private Reward checkRewards(SlotItem[] results) {
        Reward maxReward = null;

        for(Reward reward : this.rewards) {
            if (reward.check(results) && (maxReward == null || reward.moneyMultiplier > maxReward.moneyMultiplier)) {
                maxReward = reward;
            }
        }

        return maxReward;
    }

    private void playSpinningMusic(Player player, boolean pitch) {
        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
        if (invData != null) {
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5F, pitch ? 1.0F : 1.5F);
            if (invData.animationSpeed[1] > 0) {
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> this.playSpinningMusic(player, !pitch), invData.animationSpeed[3] == 1 ? 4L : (long)invData.animationSpeed[1] * 3L);
            }

        }
    }

    private void spinLine(int line, Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);
        if (invData != null) {
            invData.finalItems[line] = invData.lastItems[line];
            invData.lastItems[line] = this.getRandomItem();
            inventory.setItem((Integer) ((List)this.displaySlots.get(line)).get(2), inventory.getItem((Integer) ((List)this.displaySlots.get(line)).get(1)));
            inventory.setItem((Integer) ((List)this.displaySlots.get(line)).get(1), inventory.getItem((Integer) ((List)this.displaySlots.get(line)).get(0)));
            inventory.setItem((Integer) ((List)this.displaySlots.get(line)).get(0), invData.lastItems[line].itemStack);
            if (line == 1) {
                this.animations.animateDependent(inventory);
            }

            player.playSound(player, Sound.BLOCK_BAMBOO_HIT, 0.02F, 0.5F);
            if (invData.animationSpeed[line] > 0) {
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> this.spinLine(line, inventory, player), (long)invData.animationSpeed[line]);
            }

        }
    }

    private void startAnimation(Inventory inventory, Player player) {
        SlotMachine.PlayerInventoryData invData = (SlotMachine.PlayerInventoryData)this.playerInventoryData.get(player);

        for(int i = 0; i < this.displaySlots.size(); ++i) {
            invData.animationSpeed[i] = this.animationStartingSpeed;
        }

        this.playSpinningMusic(player, true);

        for(int i = 0; i < this.displaySlots.size(); ++i) {
            int k = i;
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                int var10002 = invData.animationSpeed[k]--;
                this.spinLine(k, inventory, player);
            }, 2L * (long)i);
        }

        for(int j = 0; j < 2; ++j) {
            Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                for(int i = 0; i < this.displaySlots.size(); ++i) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                        int var10002 = invData.animationSpeed[k]--;
                    }, 2L * (long)i);
                }

            }, (long)j * 4L);
        }

        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            for(int j = 0; j < 3; ++j) {
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    for(int i = 0; i < this.displaySlots.size(); ++i) {
                        int k = i;
                        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                            int var10002 = invData.animationSpeed[k]++;
                        }, 4L * (long)i);
                    }

                }, (long)j * 5L);
            }

        }, (long)this.animationDuration);
        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            for(int i = 0; i < this.displaySlots.size(); ++i) {
                int k = i;
                Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
                    invData.animationSpeed[k] = 0;
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 2.0F, 1.0F);
                }, 6L * (long)i);
            }

        }, (long)(this.animationDuration + 20));

    }

    private static class PlayerInventoryData {
        public final int[] animationSpeed;
        public final SlotItem[] lastItems;
        public final SlotItem[] finalItems;
        public boolean spinning = false;
        public BukkitTask spinEndTask;

        private PlayerInventoryData(int[] animationSpeed, SlotItem[] lastItems, SlotItem[] finalItems) {
            this.animationSpeed = animationSpeed;
            this.lastItems = lastItems;
            this.finalItems = finalItems;
        }
    }
}
 