package me.arthed.smartgambling.data;

import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

public class MachineData {
    public final UUID id;
    public final Machine machineType;
    public final Block[] blocks;
    public Entity[] entities;
    public boolean inUse;
    public final BlockFace direction;

    public MachineData(UUID id, Machine machineType, Block[] blocks, Entity[] entities, BlockFace direction) {
        this.id = id;
        this.blocks = blocks;
        this.entities = entities;
        if (machineType.equals(SmartGambling.getInstance().crashMachine)) {
            this.machineType = SmartGambling.getInstance().crashMachine.clone();
        } else {
            this.machineType = machineType;
        }

        this.direction = direction;

        for(Entity entity : this.entities) {
            if (entity == null) {
                for(Entity entity2 : this.entities) {
                    if (entity2 != null) {
                        entity2.remove();
                    }
                }

                this.creteEntities();
                break;
            }
        }

    }

    public void creteEntities() {
        Block origin = this.blocks[0];

        for(Entity e : origin.getWorld().getNearbyEntities(origin.getLocation().add(0.5D, 0.0D, 0.5D), 0.4D, 0.4D, 0.4D)) {
            if (e.getCustomName() != null && e.getCustomName().equalsIgnoreCase("SMARTGAMBLING_MACHINE")) {
                e.remove();
            }
        }

        Machine var8 = this.machineType;
        if (var8 instanceof BlackJack) {
            BlackJack blackJack = (BlackJack)var8;
            this.entities = new Entity[2];
            double[] chair1Offset = blackJack.chair1Offset;
            ArmorStand armorStand2 = (ArmorStand)origin.getWorld().spawnEntity(origin.getLocation().add(0.5D, 0.0D, 0.5D).add(chair1Offset[0], chair1Offset[1], chair1Offset[2]), EntityType.ARMOR_STAND);
            armorStand2.setInvisible(true);
            armorStand2.setInvulnerable(true);
            armorStand2.setGravity(false);
            armorStand2.setPersistent(true);
            armorStand2.setCustomName("SMARTGAMBLING_MACHINE");
            armorStand2.setRotation(180.0F, 0.0F);
            double[] chair2Offset = blackJack.chair2Offset;
            ArmorStand armorStand3 = (ArmorStand)origin.getWorld().spawnEntity(origin.getLocation().add(0.5D, 0.0D, 0.5D).add(chair2Offset[0], chair2Offset[1], chair2Offset[2]), EntityType.ARMOR_STAND);
            armorStand3.setInvisible(true);
            armorStand3.setInvulnerable(true);
            armorStand3.setGravity(false);
            armorStand3.setPersistent(true);
            armorStand3.setCustomName("SMARTGAMBLING_MACHINE");
            this.entities[0] = armorStand2;
            this.entities[1] = armorStand3;
        } else if (!(this.machineType instanceof JackpotMachine) && !(this.machineType instanceof CrashMachine)) {
            this.entities = new Entity[1];
            double[] chairOffset = SmartGambling.getInstance().chairOffset;
            Location originLocation = origin.getLocation().add(0.5D, 0.0D, 0.5D);
            if (this.direction.equals(BlockFace.NORTH)) {
                originLocation.add(chairOffset[2], chairOffset[1], -chairOffset[0]);
            } else if (this.direction.equals(BlockFace.SOUTH)) {
                originLocation.add(chairOffset[2], chairOffset[1], chairOffset[0]);
            } else if (this.direction.equals(BlockFace.EAST)) {
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
            if (this.direction.equals(BlockFace.NORTH)) {
                armorStand2.setRotation(0.0F, 0.0F);
            } else if (this.direction.equals(BlockFace.SOUTH)) {
                armorStand2.setRotation(180.0F, 0.0F);
            } else if (this.direction.equals(BlockFace.EAST)) {
                armorStand2.setRotation(90.0F, 0.0F);
            } else {
                armorStand2.setRotation(-90.0F, 0.0F);
            }

            this.entities[0] = armorStand2;
        } else {
            this.entities = new Entity[0];
        }

        Bukkit.getScheduler().runTaskLater(SmartGambling.getInstance(), () -> {
            DataManager.removeMachine(origin.getChunk(), this);
            DataManager.addMachine(origin.getChunk(), this);
            DataManager.save();
        }, 160L);
    }
}
 