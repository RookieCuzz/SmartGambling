package me.arthed.smartgambling.commands;

import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.games.slots.odalita.OdalitaSlotMachineManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OdalitaMenus老虎机测试命令
 * 用于切换和测试两种GUI实现
 */
public class OdalitaTestCommand implements CommandExecutor, TabCompleter {
    
    private final SmartGambling plugin;
    private OdalitaSlotMachineManager odalitaManager;
    
    public OdalitaTestCommand(SmartGambling plugin) {
        this.plugin = plugin;
    }
    
    public void setOdalitaManager(OdalitaSlotMachineManager manager) {
        this.odalitaManager = manager;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartgambling.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        
        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "switch":
                handleSwitchCommand(sender, args);
                break;
            case "status":
                handleStatusCommand(sender);
                break;
            case "test":
                handleTestCommand(sender, args);
                break;
            case "reload":
                handleReloadCommand(sender);
                break;
            case "list":
                handleListCommand(sender);
                break;
            default:
                sendHelpMessage(sender);
                break;
        }
        
        return true;
    }
    
    private void handleSwitchCommand(CommandSender sender, String[] args) {
        if (odalitaManager == null) {
            sender.sendMessage(ChatColor.RED + "OdalitaSlotMachineManager未初始化！");
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /odalita switch <odalita|original>");
            return;
        }
        
        String implementation = args[1].toLowerCase();
        switch (implementation) {
            case "odalita":
                odalitaManager.switchToOdalitaMenus();
                sender.sendMessage(ChatColor.GREEN + "已切换到OdalitaMenus老虎机实现！");
                break;
            case "original":
                odalitaManager.switchToOriginal();
                sender.sendMessage(ChatColor.GREEN + "已切换到原有老虎机实现！");
                break;
            default:
                sender.sendMessage(ChatColor.RED + "无效的实现类型！使用 'odalita' 或 'original'");
                break;
        }
    }
    
    private void handleStatusCommand(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "=== 老虎机GUI实现状态 ===");
        
        if (odalitaManager == null) {
            sender.sendMessage(ChatColor.WHITE + "当前实现: " + ChatColor.RED + "OdalitaSlotMachineManager未初始化");
            sender.sendMessage(ChatColor.WHITE + "使用OdalitaMenus: " + ChatColor.RED + "否");
            sender.sendMessage(ChatColor.WHITE + "可用老虎机数量: " + ChatColor.RED + "0");
        } else {
            String currentImpl = odalitaManager.getCurrentImplementation();
            boolean isOdalita = odalitaManager.isUsingOdalitaMenus();
            
            sender.sendMessage(ChatColor.WHITE + "当前实现: " + ChatColor.AQUA + currentImpl);
            sender.sendMessage(ChatColor.WHITE + "使用OdalitaMenus: " + (isOdalita ? ChatColor.GREEN + "是" : ChatColor.RED + "否"));
            
            try {
                int machineCount = odalitaManager.getAvailableMachines().size();
                sender.sendMessage(ChatColor.WHITE + "可用老虎机数量: " + ChatColor.YELLOW + machineCount);
            } catch (Exception e) {
                sender.sendMessage(ChatColor.WHITE + "可用老虎机数量: " + ChatColor.RED + "获取失败");
            }
        }
        
        // 检查OdalitaMenus框架状态
        if (plugin.getOdalitaMenus() != null) {
            sender.sendMessage(ChatColor.WHITE + "OdalitaMenus框架: " + ChatColor.GREEN + "已加载");
        } else {
            sender.sendMessage(ChatColor.WHITE + "OdalitaMenus框架: " + ChatColor.RED + "未加载");
        }
        
        // 检查OdalitaMenus插件状态
        if (Bukkit.getPluginManager().getPlugin("OdalitaMenus") != null) {
            sender.sendMessage(ChatColor.WHITE + "OdalitaMenus插件: " + ChatColor.GREEN + "已加载");
        } else {
            sender.sendMessage(ChatColor.WHITE + "OdalitaMenus插件: " + ChatColor.RED + "未找到");
        }
    }
    
    private void handleTestCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "此命令只能由玩家执行！");
            return;
        }
        
        Player player = (Player) sender;
        
        if (odalitaManager == null) {
            sender.sendMessage(ChatColor.RED + "OdalitaSlotMachineManager未初始化！");
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /odalita test <机器名称>");
            sender.sendMessage(ChatColor.YELLOW + "可用机器: " + String.join(", ", odalitaManager.getAvailableMachines()));
            return;
        }
        
        String machineName = args[1];
        if (!odalitaManager.getAvailableMachines().contains(machineName)) {
            sender.sendMessage(ChatColor.RED + "未找到老虎机: " + machineName);
            sender.sendMessage(ChatColor.YELLOW + "可用机器: " + String.join(", ", odalitaManager.getAvailableMachines()));
            return;
        }
        
        try {
            // 获取对应的机器实例
            me.arthed.smartgambling.games.common.machine.Machine machine = null;
            if (odalitaManager.isUsingOdalitaMenus()) {
                machine = odalitaManager.getOdalitaMachine(machineName);
            }
            if (machine == null) {
                machine = odalitaManager.getOriginalMachine(machineName);
            }
            
            if (machine == null) {
                sender.sendMessage(ChatColor.RED + "未找到老虎机: " + machineName);
                return;
            }
            
            // 创建测试用的OpenInterface
            me.arthed.smartgambling.games.common.machine.OpenInterface openInterface = 
                new me.arthed.smartgambling.games.common.machine.OpenInterface(machine);
            openInterface.betAmount = 100; // 默认下注金额
            
            // 打开老虎机
            odalitaManager.openSlotMachine(player, machineName, openInterface);
            
            String impl = odalitaManager.isUsingOdalitaMenus() ? "OdalitaMenus" : "原有";
            sender.sendMessage(ChatColor.GREEN + "已使用" + impl + "实现打开老虎机: " + machineName);
            
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "打开老虎机时发生错误: " + e.getMessage());
            plugin.getLogger().severe("测试老虎机时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleReloadCommand(CommandSender sender) {
        if (odalitaManager == null) {
            sender.sendMessage(ChatColor.RED + "OdalitaSlotMachineManager未初始化！");
            return;
        }
        
        try {
            odalitaManager.reload();
            sender.sendMessage(ChatColor.GREEN + "OdalitaSlotMachineManager已重新加载！");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "重新加载时发生错误: " + e.getMessage());
            plugin.getLogger().severe("重新加载OdalitaSlotMachineManager时发生错误: " + e.getMessage());
        }
    }
    
    private void handleListCommand(CommandSender sender) {
        if (odalitaManager == null) {
            sender.sendMessage(ChatColor.RED + "OdalitaSlotMachineManager未初始化！");
            return;
        }
        
        java.util.Set<String> machines = odalitaManager.getAvailableMachines();
        
        sender.sendMessage(ChatColor.YELLOW + "=== 可用老虎机列表 ===");
        if (machines.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "没有可用的老虎机");
        } else {
            for (String machineName : machines) {
                boolean hasOdalita = odalitaManager.getOdalitaMachine(machineName) != null;
                boolean hasOriginal = odalitaManager.getOriginalMachine(machineName) != null;
                
                String status = "";
                if (hasOdalita && hasOriginal) {
                    status = ChatColor.GREEN + " [双实现]";
                } else if (hasOdalita) {
                    status = ChatColor.BLUE + " [仅OdalitaMenus]";
                } else if (hasOriginal) {
                    status = ChatColor.YELLOW + " [仅原有实现]";
                } else {
                    status = ChatColor.RED + " [无实现]";
                }
                
                sender.sendMessage(ChatColor.WHITE + "- " + machineName + status);
            }
        }
    }
    
    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== OdalitaMenus老虎机测试命令 ===");
        sender.sendMessage(ChatColor.WHITE + "/odalita switch <odalita|original> " + ChatColor.GRAY + "- 切换GUI实现");
        sender.sendMessage(ChatColor.WHITE + "/odalita status " + ChatColor.GRAY + "- 查看当前状态");
        sender.sendMessage(ChatColor.WHITE + "/odalita test <机器名称> " + ChatColor.GRAY + "- 测试指定老虎机");
        sender.sendMessage(ChatColor.WHITE + "/odalita list " + ChatColor.GRAY + "- 列出所有可用老虎机");
        sender.sendMessage(ChatColor.WHITE + "/odalita reload " + ChatColor.GRAY + "- 重新加载管理器");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("smartgambling.admin")) {
            return Arrays.asList();
        }
        
        if (args.length == 1) {
            return Arrays.asList("switch", "status", "test", "list", "reload")
                    .stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "switch":
                    return Arrays.asList("odalita", "original")
                            .stream()
                            .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                case "test":
                    if (odalitaManager != null) {
                        return odalitaManager.getAvailableMachines()
                                .stream()
                                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                    break;
            }
        }
        
        return Arrays.asList();
    }
}