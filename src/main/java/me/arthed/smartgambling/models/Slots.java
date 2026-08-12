package me.arthed.smartgambling.models;

import me.arthed.smartgambling.models.behavior.Interactive;
import org.bukkit.Location;

import java.util.UUID;

public class Slots extends Machine implements Interactive {


    //机器的模型id
    String modelId;

    //记录这台老虎机是否被使用
    Boolean isUse;

    //生成位置
    Location location;
    //机器类型
    MachineType machineType;

    UUID uuid;
    //朝向
    MachineDirection machineDirection=MachineDirection.EAST;




    private Slots(Builder builder){

    }

    public static class Builder {

        //机器的模型id
        String modelId;

        //记录这台老虎机是否被使用
        Boolean isUse=false;

        //生成位置
        Location location;
        //机器类型
        MachineType machineType;
        UUID uuid;
        //朝向
        MachineDirection machineDirection=MachineDirection.EAST;

        public Builder(UUID uuid,Location location) {
            this.uuid = uuid;
            this.location = location;
        }

        public Builder setModelId(String modelId){
            this.modelId=modelId;
            return this;
        }
        public Builder setLocation(Location location){
            this.location=location;
            return this;
        }
        public Builder setMachineDirection(MachineDirection machineDirection){
            this.machineDirection=machineDirection;
            return this;
        }
    }


}
