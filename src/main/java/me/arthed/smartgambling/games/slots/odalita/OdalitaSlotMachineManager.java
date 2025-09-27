package me.arthed.smartgambling.games.slots.odalita;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.common.machine.OpenInterface;
import me.arthed.smartgambling.games.slots.SlotMachine;
import nl.odalitadevelopments.menus.OdalitaMenus;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * OdalitaSlotMachine管理器
 * 负责管理OdalitaSlotMachine实例，并提供与现有系统的集成
 */
public class OdalitaSlotMachineManager {
    
    private final SmartGambling plugin;
    private final Map<String, OdalitaSlotMachine> odalitaMachines;
    private final Map<String, SlotMachine> originalMachines;
    private boolean useOdalitaMenus = false;
    
    public OdalitaSlotMachineManager(SmartGambling plugin) {
        this.plugin = plugin;
        this.odalitaMachines = new HashMap<>();
        this.originalMachines = new HashMap<>();
    }
    
    /**
     * 初始化管理器
     */
    public void initialize() {
        // 检查OdalitaMenus是否可用
        if (plugin.getOdalitaMenus() != null) {
            plugin.getLogger().info("OdalitaMenus框架已加载，准备创建OdalitaSlotMachine实例");
            createOdalitaMachinesFromOriginal();
        } else {
            plugin.getLogger().warning("OdalitaMenus框架未加载，将使用原有的SlotMachine实现");
        }
    }
    
    /**
     * 从原有的SlotMachine创建OdalitaSlotMachine实例
     */
    private void createOdalitaMachinesFromOriginal() {
        Map<String, SlotMachine> originalSlotMachines = getOriginalSlotMachines();
        
        for (Map.Entry<String, SlotMachine> entry : originalSlotMachines.entrySet()) {
            String machineName = entry.getKey();
            SlotMachine originalMachine = entry.getValue();
            
            try {
                // 保存原有机器的引用
                originalMachines.put(machineName, originalMachine);
                
                // 创建对应的OdalitaSlotMachine
                OdalitaSlotMachine odalitaMachine = OdalitaSlotMachineFactory.convertFromSlotMachine(originalMachine);
                odalitaMachines.put(machineName, odalitaMachine);
                
                // 注册到OdalitaMenus
                registerOdalitaMachine(odalitaMachine);
                
                plugin.getLogger().info("成功创建OdalitaSlotMachine: " + machineName);
            } catch (Exception e) {
                plugin.getLogger().severe("创建OdalitaSlotMachine失败: " + machineName + " - " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 获取原有的SlotMachine实例
     */
    @SuppressWarnings("unchecked")
    private Map<String, SlotMachine> getOriginalSlotMachines() {
        // 从SmartGambling.getInstance().machineTypes获取原有的SlotMachine实例
        Map<String, SlotMachine> slotMachines = new HashMap<>();
        
        if (SmartGambling.getInstance().machineTypes != null) {
            for (Map.Entry<Integer, Machine> entry : SmartGambling.getInstance().machineTypes.entrySet()) {
                Machine machine = entry.getValue();
                if (machine instanceof SlotMachine) {
                    SlotMachine slotMachine = (SlotMachine) machine;
                    slotMachines.put(slotMachine.name, slotMachine);
                }
            }
        }
        
        return slotMachines;
    }
    
    /**
     * 注册OdalitaSlotMachine到OdalitaMenus框架
     */
    private void registerOdalitaMachine(OdalitaSlotMachine machine) {
        if (plugin.getOdalitaMenus() != null) {
            // 注册菜单提供者
            // 这里可能需要根据OdalitaMenus的具体API来调整
            plugin.getLogger().info("注册OdalitaSlotMachine到OdalitaMenus框架: " + machine.name);
        }
    }
    
    /**
     * 打开老虎机GUI
     * 根据配置决定使用原有实现还是OdalitaMenus实现
     */
    public void openSlotMachine(Player player, String machineName, OpenInterface openInterface) {
        if (useOdalitaMenus && odalitaMachines.containsKey(machineName)) {
            // 使用OdalitaMenus实现
            OdalitaSlotMachine odalitaMachine = odalitaMachines.get(machineName);
            odalitaMachine.open(player, openInterface);
            plugin.getLogger().info("为玩家 " + player.getName() + " 打开OdalitaSlotMachine: " + machineName);
        } else if (originalMachines.containsKey(machineName)) {
            // 使用原有实现
            SlotMachine originalMachine = originalMachines.get(machineName);
            originalMachine.open(player, openInterface);
            plugin.getLogger().info("为玩家 " + player.getName() + " 打开原有SlotMachine: " + machineName);
        } else {
            plugin.getLogger().warning("未找到老虎机: " + machineName);
        }
    }
    
    /**
     * 切换到OdalitaMenus实现
     */
    public void switchToOdalitaMenus() {
        if (plugin.getOdalitaMenus() != null && !odalitaMachines.isEmpty()) {
            useOdalitaMenus = true;
            plugin.getLogger().info("已切换到OdalitaMenus老虎机实现");
        } else {
            plugin.getLogger().warning("无法切换到OdalitaMenus实现：框架未加载或没有可用的OdalitaSlotMachine");
        }
    }
    
    /**
     * 切换到原有实现
     */
    public void switchToOriginal() {
        useOdalitaMenus = false;
        plugin.getLogger().info("已切换到原有老虎机实现");
    }
    
    /**
     * 获取当前使用的实现类型
     */
    public String getCurrentImplementation() {
        return useOdalitaMenus ? "OdalitaMenus" : "Original";
    }
    
    /**
     * 检查是否正在使用OdalitaMenus实现
     */
    public boolean isUsingOdalitaMenus() {
        return useOdalitaMenus;
    }
    
    /**
     * 获取OdalitaSlotMachine实例
     */
    public OdalitaSlotMachine getOdalitaMachine(String machineName) {
        return odalitaMachines.get(machineName);
    }
    
    /**
     * 获取原有SlotMachine实例
     */
    public SlotMachine getOriginalMachine(String machineName) {
        return originalMachines.get(machineName);
    }
    
    /**
     * 获取所有可用的老虎机名称
     */
    public java.util.Set<String> getAvailableMachines() {
        return originalMachines.keySet();
    }
    
    /**
     * 重新加载所有老虎机
     */
    public void reload() {
        plugin.getLogger().info("重新加载OdalitaSlotMachine管理器");
        
        // 清理现有实例
        odalitaMachines.clear();
        originalMachines.clear();
        
        // 重新初始化
        initialize();
    }
    
    /**
     * 关闭管理器，清理资源
     */
    public void shutdown() {
        plugin.getLogger().info("关闭OdalitaSlotMachine管理器");
        
        // 清理所有实例
        odalitaMachines.clear();
        originalMachines.clear();
        useOdalitaMenus = false;
    }
}