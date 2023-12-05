package me.arthed.smartgambling.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.commands.SelectBlocksRoutine;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.listeners.WorldSaveListener;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Content;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

public class MainCommand
        implements CommandExecutor,
        TabExecutor {
    private static final String ADMIN_PERMISSION = "sg.admin";
    private final ConfigManager configManager;
    private final SelectBlocksRoutine selectBlocksRoutine;
    private final HashMap<Player, Machine> machineTypes;

    public MainCommand() {
        this.configManager = SmartGambling.getInstance().configManager;
        this.selectBlocksRoutine = SmartGambling.getInstance().selectBlocksRoutine;
        this.machineTypes = new HashMap();
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("sg")) {
            return false;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Command can only be used by players.");
            return false;
        }
        Player player = (Player)sender;
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(this.configManager.messages.get("noPermission"));
            return false;
        }
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("reload")) {
                return this.reloadCommand(player);
            }
            if (args[0].equalsIgnoreCase("cancel")) {
                return this.cancelCommand(player);
            }
            if (args[0].equalsIgnoreCase("confirm")) {
                return this.confirmCommand(player);
            }
            if (args[0].equalsIgnoreCase("list")) {
                if (args.length == 2 && args[1].equalsIgnoreCase("all")) {
                    return this.listMachines(player, true);
                }
                return this.listMachines(player, false);
            }
            if (args[0].equalsIgnoreCase("remove")) {
                return this.removeCommand(player, args);
            }
            if (args[0].equalsIgnoreCase("add")) {
                return this.addCommand(player, args);
            }
            if (args[0].equalsIgnoreCase("fixentities")) {
                this.removeOldEntities(player);
                return true;
            }
        }
        return this.showHelpMenu(player);
    }

    private boolean reloadCommand(Player player) {
        SmartGambling.getInstance().saveDefaultConfig();
        SmartGambling.getInstance().reloadConfig();
        this.configManager.load();
        for (MachineData machineData : SmartGambling.getInstance().uuidMachines.values()) {
            Machine machine = machineData.machineType;
            if (!(machine instanceof CrashMachine)) continue;
            CrashMachine crashMachine = (CrashMachine)machine;
            crashMachine.timerTask.cancel();
        }
        SmartGambling.getInstance().machines.clear();
        DataManager.load();
        player.sendMessage(this.configManager.messages.get("reload"));
        return true;
    }

    private boolean cancelCommand(Player player) {
        this.selectBlocksRoutine.playersInRoutine.remove(player);
        this.machineTypes.remove(player);
        player.sendMessage(this.configManager.messages.get("canceledRoutine"));
        return true;
    }

    private boolean confirmCommand(Player player) {
        if (!this.selectBlocksRoutine.playersInRoutine.containsKey(player)) {
            return false;
        }
        List<Block> blocks = this.selectBlocksRoutine.playersInRoutine.remove(player);
        Machine machineType = this.machineTypes.remove(player);
        this.createMachine(blocks, machineType, player);
        player.sendMessage(this.configManager.messages.get("createdSlotMachine"));
        return true;
    }

    private boolean removeCommand(Player player, String[] args) {
        if (args.length == 2) {
            UUID id;
            try {
                id = UUID.fromString(args[1]);
            }
            catch (IllegalArgumentException e) {
                player.sendMessage(this.configManager.messages.get("slotsRemoveCorrectUsage"));
                return false;
            }
            this.removeMachine(player, id);
            return true;
        }
        player.sendMessage(this.configManager.messages.get("slotsRemoveCorrectUsage"));
        return false;
    }

    private boolean showHelpMenu(Player player) {
        if (player.hasPermission(ADMIN_PERMISSION)) {
            for (String s : this.configManager.helpMenu) {
                player.sendMessage(s);
            }
            return true;
        }
        return false;
    }

    private boolean addCommand(Player player, String[] args) {
        if (args.length == 1) {
            player.sendMessage(this.configManager.messages.get("slotsAddCorrectUsage"));
            return false;
        }
        Machine sm = SmartGambling.getInstance().machineTypes.get(args[1].hashCode());
        if (!this.machineTypes.containsKey(player)) {
            this.machineTypes.put(player, sm);
            this.selectBlocksRoutine.startRoutine(player);
            player.sendMessage(this.configManager.messages.get("slotsAddStart"));
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String[] subcommands = new String[]{"help", "add", "list", "reload", "confirm", "cancel", "fixentities"};
            ArrayList<String> result = new ArrayList<String>();
            for (String subcommand : subcommands) {
                if (!subcommand.startsWith(args[0].toLowerCase())) continue;
                result.add(subcommand);
            }
            return result.size() == 0 ? Arrays.asList(subcommands) : result;
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("add")) {
                ArrayList<String> result = new ArrayList<String>();
                for (Machine machine : SmartGambling.getInstance().machineTypes.values()) {
                    String name = SmartGambling.getMachineName(machine);
                    if (!name.toLowerCase().startsWith(args[1].toLowerCase())) continue;
                    result.add(name);
                }
                return result;
            }
            if (args[0].equalsIgnoreCase("remove")) {
                return Arrays.asList("all", "0");
            }
        }
        return null;
    }

    private void createMachine(List<Block> blocks, Machine machineType, Player player) {
        Block[] blockArray = blocks.toArray(new Block[0]);
        Block origin = blocks.get(0);
        double[] machineOffset = machineType.getMachineEntityOffset();
        MachineData machineData = null;
        if (machineType instanceof BlackJack) {
            boolean x;
            BlackJack blackJack = (BlackJack)machineType;
            Entity[] entities = new Entity[2];
            BlockFace blockFace = BlockFace.NORTH;
            Location playerLocation = player.getLocation();
            boolean bl = x = Math.abs(playerLocation.getX() - (double)origin.getX()) > Math.abs(playerLocation.getZ() - (double)origin.getZ());
            if (x) {
                blockFace = playerLocation.getX() > (double)origin.getX() ? BlockFace.EAST : BlockFace.WEST;
            } else if (playerLocation.getZ() > (double)origin.getZ()) {
                blockFace = BlockFace.SOUTH;
            }
            double[] chair1Offset = blackJack.chair1Offset;
            double[] chair2Offset = blackJack.chair2Offset;
            if (blockFace.equals((Object)BlockFace.EAST) || blockFace.equals((Object)BlockFace.WEST)) {
                double xOffset = chair1Offset[0];
                chair1Offset[0] = chair1Offset[2];
                chair1Offset[2] = xOffset;
                xOffset = chair2Offset[0];
                chair2Offset[0] = chair2Offset[2];
                chair2Offset[2] = xOffset;
            }
            ArmorStand armorStand2 = (ArmorStand)origin.getWorld().spawnEntity(origin.getLocation().add(0.5, 0.0, 0.5).add(chair1Offset[0], chair1Offset[1], chair1Offset[2]), EntityType.ARMOR_STAND);
            armorStand2.setInvisible(true);
            armorStand2.setInvulnerable(true);
            armorStand2.setGravity(false);
            armorStand2.setPersistent(true);
            armorStand2.setCustomName("SMARTGAMBLING_MACHINE");
            armorStand2.setRotation(180.0f, 0.0f);
            ArmorStand armorStand3 = (ArmorStand)origin.getWorld().spawnEntity(origin.getLocation().add(0.5, 0.0, 0.5).add(chair2Offset[0], chair2Offset[1], chair2Offset[2]), EntityType.ARMOR_STAND);
            armorStand3.setInvisible(true);
            armorStand3.setInvulnerable(true);
            armorStand3.setGravity(false);
            armorStand3.setPersistent(true);
            armorStand3.setCustomName("SMARTGAMBLING_MACHINE");
            if (blockFace.equals((Object)BlockFace.NORTH)) {
                armorStand2.setRotation(0.0f, 0.0f);
                armorStand3.setRotation(0.0f, 0.0f);
            } else if (blockFace.equals((Object)BlockFace.SOUTH)) {
                armorStand2.setRotation(180.0f, 0.0f);
                armorStand3.setRotation(180.0f, 0.0f);
            } else if (blockFace.equals((Object)BlockFace.EAST)) {
                armorStand2.setRotation(90.0f, 0.0f);
                armorStand3.setRotation(90.0f, 0.0f);
            } else {
                armorStand2.setRotation(-90.0f, 0.0f);
                armorStand3.setRotation(-90.0f, 0.0f);
            }
            entities[0] = armorStand2;
            entities[1] = armorStand3;
            machineData = new MachineDataBlackjack(UUID.randomUUID(), machineType, blockArray, entities, blockFace);
        } else if (machineType instanceof JackpotMachine || machineType instanceof CrashMachine) {
            boolean x;
            Entity[] entities = new Entity[]{};
            BlockFace blockFace = BlockFace.NORTH;
            Location playerLocation = player.getLocation();
            boolean bl = x = Math.abs(playerLocation.getX() - (double)origin.getX()) > Math.abs(playerLocation.getZ() - (double)origin.getZ());
            if (x) {
                blockFace = playerLocation.getX() > (double)origin.getX() ? BlockFace.EAST : BlockFace.WEST;
            } else if (playerLocation.getZ() > (double)origin.getZ()) {
                blockFace = BlockFace.SOUTH;
            }
            machineData = new MachineData(UUID.randomUUID(), machineType, blockArray, entities, blockFace);
        } else {
            boolean x;
            Entity[] entities = new Entity[1];
            double[] chairOffset = SmartGambling.getInstance().chairOffset;
            BlockFace blockFace = BlockFace.NORTH;
            Location playerLocation = player.getLocation();
            boolean bl = x = Math.abs(playerLocation.getX() - (double)origin.getX()) > Math.abs(playerLocation.getZ() - (double)origin.getZ());
            if (x) {
                blockFace = playerLocation.getX() > (double)origin.getX() ? BlockFace.EAST : BlockFace.WEST;
            } else if (playerLocation.getZ() > (double)origin.getZ()) {
                blockFace = BlockFace.SOUTH;
            }
            Location originLocation = origin.getLocation().add(0.5, 0.0, 0.5);
            if (blockFace.equals((Object)BlockFace.NORTH)) {
                originLocation.add(chairOffset[2], chairOffset[1], -chairOffset[0]);
            } else if (blockFace.equals((Object)BlockFace.SOUTH)) {
                originLocation.add(chairOffset[2], chairOffset[1], chairOffset[0]);
            } else if (blockFace.equals((Object)BlockFace.EAST)) {
                originLocation.add(chairOffset[0], chairOffset[1], chairOffset[2]);
            } else {
                originLocation.add(-chairOffset[0], chairOffset[1], chairOffset[2]);
            }
            ArmorStand armorStand2 = (ArmorStand)origin.getWorld().spawnEntity(originLocation, EntityType.ARMOR_STAND);
            armorStand2.setInvisible(true);
            armorStand2.setInvulnerable(true);
            armorStand2.setGravity(false);
            armorStand2.setPersistent(true);
            armorStand2.setCustomName("SMARTGAMBLING_MACHINE");
            entities[0] = armorStand2;
            if (blockFace.equals((Object)BlockFace.NORTH)) {
                armorStand2.setRotation(0.0f, 0.0f);
            } else if (blockFace.equals((Object)BlockFace.SOUTH)) {
                armorStand2.setRotation(180.0f, 0.0f);
            } else if (blockFace.equals((Object)BlockFace.EAST)) {
                armorStand2.setRotation(90.0f, 0.0f);
            } else {
                armorStand2.setRotation(-90.0f, 0.0f);
            }
            machineData = new MachineData(UUID.randomUUID(), machineType, blockArray, entities, blockFace);
        }
        SmartGambling.getInstance().machinesToAdd.add(machineData);
        SmartGambling.getInstance().uuidMachines.put(machineData.id, machineData);
        HashMap chunkMachines = SmartGambling.getInstance().machines.computeIfAbsent(origin.getWorld(), k -> new HashMap());
        if (chunkMachines.containsKey(origin.getChunk())) {
            ((List)chunkMachines.get(origin.getChunk())).add(machineData);
        } else {
            ArrayList<MachineData> list = new ArrayList<MachineData>();
            list.add(machineData);
            chunkMachines.put(origin.getChunk(), list);
        }
        WorldSaveListener.save();
    }

    private boolean listMachines(Player player, boolean all) {
        List<MachineData> machines = new ArrayList<MachineData>();
        if (all) {
            if (SmartGambling.getInstance().machines.containsKey(player.getWorld())) {
                for (List<MachineData> machinesList : SmartGambling.getInstance().machines.get(player.getWorld()).values()) {
                    machines.addAll(machinesList);
                }
            }
        } else if (SmartGambling.getInstance().machines.containsKey(player.getWorld()) && SmartGambling.getInstance().machines.get(player.getWorld()).containsKey(player.getLocation().getChunk())) {
            machines = SmartGambling.getInstance().machines.get(player.getWorld()).get(player.getLocation().getChunk());
        }
        if (machines.size() == 0) {
            player.sendMessage(this.configManager.messages.get("listSlotsNoResult"));
            return true;
        }
        player.sendMessage(all ? this.configManager.messages.get("listAllSlots") : this.configManager.messages.get("listSlots"));
        for (MachineData machineData : machines) {
            Block origin = machineData.blocks[0];
            String blockString = "[" + origin.getX() + ", " + origin.getY() + ", " + origin.getZ() + "]";
            BaseComponent[] slotMachineResultMessage = new BaseComponent[4];
            String message = String.format(this.configManager.messages.get("listSlotsResult"), SmartGambling.getMachineName(machineData.machineType), blockString);
            slotMachineResultMessage[0] = new TextComponent(message);
            slotMachineResultMessage[1] = new TextComponent(this.configManager.messages.get("removeButton"));
            slotMachineResultMessage[1].setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sg remove " + machineData.id.toString()));
            slotMachineResultMessage[1].setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Content[]{new Text(this.configManager.messages.get("click"))}));
            slotMachineResultMessage[2] = new TextComponent(this.configManager.messages.get("teleportButton"));
            slotMachineResultMessage[2].setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + origin.getX() + " " + origin.getY() + " " + origin.getZ()));
            slotMachineResultMessage[2].setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Content[]{new Text(this.configManager.messages.get("click"))}));
            slotMachineResultMessage[3] = new TextComponent(this.configManager.messages.get("idButton"));
            slotMachineResultMessage[3].setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, machineData.id.toString()));
            slotMachineResultMessage[3].setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Content[]{new Text(this.configManager.messages.get("click"))}));
            player.spigot().sendMessage(slotMachineResultMessage);
        }
        return true;
    }

    private void removeMachine(Player player, UUID id) {
        for (List<MachineData> machinesList : SmartGambling.getInstance().machines.get(player.getWorld()).values()) {
            for (MachineData machineData : machinesList) {
                if (!id.equals(machineData.id)) continue;
                player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), 1));
                for (Entity entity : machineData.entities) {
                    if (entity == null) continue;
                    entity.setCustomName("");
                    entity.remove();
                }
                machinesList.remove(machineData);
                SmartGambling.getInstance().uuidMachines.remove(machineData.id);
                if (SmartGambling.getInstance().machinesToAdd.contains(machineData)) {
                    SmartGambling.getInstance().machinesToAdd.remove(machineData);
                } else {
                    SmartGambling.getInstance().machinesToRemove.add(machineData);
                }
                return;
            }
        }
        player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), 0));
        WorldSaveListener.save();
    }

    private void removeOldEntities(Player player) {
        HashMap<Chunk, List<MachineData>> worldData = SmartGambling.getInstance().machines.get(player.getWorld());
        if (worldData == null) {
            return;
        }
        List<MachineData> machines = worldData.get(player.getLocation().getChunk());
        if (machines == null) {
            return;
        }
        for (Entity entity : player.getLocation().getChunk().getEntities()) {
            if (!entity.getType().equals((Object)EntityType.ARMOR_STAND) || entity.getCustomName() == null || !entity.getCustomName().equals("SMARTGAMBLING_MACHINE")) continue;
            boolean found = false;
            for (MachineData machine : machines) {
                for (Entity machineEntity : machine.entities) {
                    if (!machineEntity.equals(entity)) continue;
                    found = true;
                    break;
                }
                if (!found) continue;
                break;
            }
            if (found) continue;
            entity.remove();
        }
    }
}