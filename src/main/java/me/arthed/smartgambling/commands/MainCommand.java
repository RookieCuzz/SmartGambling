package me.arthed.smartgambling.commands;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import me.arthed.smartgambling.SmartGambling;
import me.arthed.smartgambling.config.ConfigManager;
import me.arthed.smartgambling.creation.CreationSession;
import me.arthed.smartgambling.creation.MachineCreationValidator;
import me.arthed.smartgambling.data.MachineData;
import me.arthed.smartgambling.data.DataManager;
import me.arthed.smartgambling.economy.EconomyService;
import me.arthed.smartgambling.games.blackjack.BlackJack;
import me.arthed.smartgambling.games.blackjack.MachineDataBlackjack;
import me.arthed.smartgambling.games.common.machine.Machine;
import me.arthed.smartgambling.games.crash.CrashMachine;
import me.arthed.smartgambling.games.jackpot.JackpotMachine;
import me.arthed.smartgambling.games.slots.SlotMachine;
import me.arthed.smartgambling.games.slots.testing.ForcedSlotResultRegistry;
import me.arthed.smartgambling.runtime.PreparedRuntime;
import me.arthed.smartgambling.runtime.ReloadCoordinator;
import me.arthed.smartgambling.utils.DisplayUtils;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Content;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public class MainCommand
        implements CommandExecutor,
        TabExecutor {
    private static final String ADMIN_PERMISSION = "sg.admin";
    private static final String SLOT_TEST_PERMISSION = "sg.admin.slot-test";
    private final ConfigManager configManager;
    private final SelectBlocksRoutine selectBlocksRoutine;
    private final MachineCreationValidator creationValidator;

    public MainCommand() {
        this.configManager = SmartGambling.getInstance().configManager;
        this.selectBlocksRoutine = SmartGambling.getInstance().selectBlocksRoutine;
        this.creationValidator = new MachineCreationValidator(SmartGambling.getInstance());
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("sg")) {
            return false;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(this.configManager.messages.getOrDefault(
                    "noPermission", ChatColor.RED + "你没有权限使用此命令。"));
            return false;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("ledger")) {
            return this.ledgerCommand(sender, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("slot")) {
            return this.slotTestCommand(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "此子命令只能由游戏内玩家执行。");
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
            if (args[0].equalsIgnoreCase("rotate")) {
                return this.rotateCommand(player, args);
            }
            if (args[0].equalsIgnoreCase("undo")) {
                return this.undoCommand(player);
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

    private boolean ledgerCommand(CommandSender sender, String[] args) {
        EconomyService ledger = SmartGambling.getInstance().getEconomyService();
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            int page = 1;
            if (args.length >= 3) {
                try {
                    page = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException exception) {
                    sender.sendMessage(ChatColor.RED + "页码必须是正整数。");
                    return false;
                }
            }
            int pageSize = 10;
            List<EconomyService.LedgerTransaction> rows = ledger.list(
                    new EconomyService.LedgerQuery(null, null, pageSize, (page - 1) * pageSize)
            );
            sender.sendMessage(ChatColor.GOLD + "SmartGambling 资金账本第 " + page + " 页"
                    + ChatColor.GRAY + "（活动下注=" + ledger.activeWagerCount()
                    + "，未解决交易=" + ledger.unresolvedCount() + "）");
            if (rows.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "本页没有交易记录。");
            }
            for (EconomyService.LedgerTransaction row : rows) {
                sender.sendMessage(ChatColor.YELLOW + row.id().toString()
                        + ChatColor.GRAY + " " + row.state() + " " + row.direction() + "/" + row.purpose()
                        + " 玩家=" + row.playerId() + " 金额=" + row.amount().decimal().toPlainString());
            }
            return true;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("resolve")) {
            UUID transactionId;
            try {
                transactionId = UUID.fromString(args[2]);
            } catch (IllegalArgumentException exception) {
                sender.sendMessage(ChatColor.RED + "交易 UUID 无效。");
                return false;
            }
            EconomyService.UnknownDecision decision;
            if (args[3].equalsIgnoreCase("applied")) {
                decision = EconomyService.UnknownDecision.APPLIED;
            } else if (args[3].equalsIgnoreCase("not-applied")) {
                decision = EconomyService.UnknownDecision.NOT_APPLIED;
            } else {
                sender.sendMessage(ChatColor.RED + "请使用 applied 或 not-applied。");
                return false;
            }
            EconomyService.ReconcileResult result = ledger.resolveUnknown(
                    transactionId, decision, sender.getName(), "resolved with /sg ledger"
            );
            sender.sendMessage((result.status() == EconomyService.ReconcileResult.Status.RESOLVED
                    || result.status() == EconomyService.ReconcileResult.Status.ALREADY_RESOLVED
                    ? ChatColor.GREEN : ChatColor.RED) + result.status().name() + ": " + result.detail());
            return result.status() == EconomyService.ReconcileResult.Status.RESOLVED
                    || result.status() == EconomyService.ReconcileResult.Status.ALREADY_RESOLVED;
        }
        sender.sendMessage(ChatColor.YELLOW + "/sg ledger list [page]");
        sender.sendMessage(ChatColor.YELLOW + "/sg ledger resolve <transaction-id> applied|not-applied");
        return false;
    }

    private boolean slotTestCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(SLOT_TEST_PERMISSION)) {
            sender.sendMessage(this.configManager.messages.getOrDefault(
                    "noPermission", ChatColor.RED + "你没有权限使用此命令。"));
            return false;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("test")) {
            this.sendSlotTestUsage(sender);
            return false;
        }

        SmartGambling plugin = SmartGambling.getInstance();
        if (!plugin.isForcedSlotResultsEnabled()) {
            sender.sendMessage(ChatColor.RED
                    + "老虎机强制结果测试模式已关闭。请先在 config.yml 中启用 "
                    + "Testing.forcedSlotResults.enabled，再执行 /sg reload。");
            return false;
        }

        ForcedSlotResultRegistry registry = plugin.getForcedSlotResultRegistry();
        plugin.auditExpiredForcedSlotDirectives();
        if (args[2].equalsIgnoreCase("force")) {
            return this.forceSlotResult(
                    sender, args, plugin.getForcedSlotResultExpirySeconds(), registry);
        }
        if (args[2].equalsIgnoreCase("show")) {
            return this.showForcedSlotResults(sender, args, registry);
        }
        if (args[2].equalsIgnoreCase("clear")) {
            return this.clearForcedSlotResults(sender, args, registry);
        }
        this.sendSlotTestUsage(sender);
        return false;
    }

    private boolean forceSlotResult(
            CommandSender sender,
            String[] args,
            int expirySeconds,
            ForcedSlotResultRegistry registry
    ) {
        if (args.length < 6) {
            sender.sendMessage(ChatColor.YELLOW
                    + "用法：/sg slot test force <玩家> <机器类型> <图案1> ... <图案N>");
            return false;
        }
        Player target = this.onlinePlayer(sender, args[3]);
        if (target == null) {
            return false;
        }
        SlotMachine slotMachine = this.configuredSlotMachine(sender, args[4]);
        if (slotMachine == null) {
            return false;
        }

        List<String> rawSymbols = Arrays.asList(args).subList(5, args.length);
        List<String> canonicalSymbols;
        try {
            canonicalSymbols = slotMachine.canonicalizeSymbolIds(rawSymbols);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + "无法设置测试组合：" + exception.getMessage());
            sender.sendMessage(ChatColor.GRAY + "该机器需要 " + slotMachine.getReelCount()
                    + " 个图案，可用图案：" + String.join(", ", slotMachine.getSymbolIds()));
            return false;
        }

        String machineTypeId = SmartGambling.getMachineTypeId(slotMachine);
        UUID issuerId = sender instanceof Player player ? player.getUniqueId() : null;
        ForcedSlotResultRegistry.QueueResult queued = registry.queue(
                target.getUniqueId(),
                machineTypeId,
                issuerId,
                sender.getName(),
                canonicalSymbols,
                Duration.ofSeconds(expirySeconds)
        );
        queued.replaced().ifPresent(previous -> SmartGambling.getInstance().auditForcedSlotDirective(
                "replaced", previous, "replacedBy=" + sender.getName() + " targetName=" + target.getName()));
        SmartGambling.getInstance().auditForcedSlotDirective(
                "queued", queued.directive(), "targetName=" + target.getName());

        sender.sendMessage(ChatColor.GREEN + "已为 " + target.getName() + " 预设机器 "
                + machineTypeId + " 的下一次成功下注："
                + ChatColor.WHITE + String.join(" ", canonicalSymbols)
                + ChatColor.GRAY + "（" + expirySeconds + " 秒后过期）");
        if (queued.replaced().isPresent()) {
            sender.sendMessage(ChatColor.YELLOW + "已覆盖该玩家、该机器类型的旧测试结果。");
        }
        return true;
    }

    private boolean showForcedSlotResults(
            CommandSender sender,
            String[] args,
            ForcedSlotResultRegistry registry
    ) {
        if (args.length != 4) {
            sender.sendMessage(ChatColor.YELLOW + "用法：/sg slot test show <玩家>");
            return false;
        }
        Player target = this.onlinePlayer(sender, args[3]);
        if (target == null) {
            return false;
        }
        List<ForcedSlotResultRegistry.Directive> directives = registry.list(target.getUniqueId());
        if (directives.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + target.getName() + " 当前没有待执行的老虎机测试结果。");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + target.getName() + " 的待执行老虎机测试结果：");
        Instant now = Instant.now();
        for (ForcedSlotResultRegistry.Directive directive : directives) {
            long secondsLeft = Math.max(0L, Duration.between(now, directive.expiresAt()).toSeconds());
            sender.sendMessage(ChatColor.YELLOW + "- " + directive.machineTypeId()
                    + ChatColor.WHITE + ": " + String.join(" ", directive.symbolIds())
                    + ChatColor.GRAY + "（剩余 " + secondsLeft + " 秒，设置者 "
                    + directive.issuerName().orElse("控制台") + "）");
        }
        return true;
    }

    private boolean clearForcedSlotResults(
            CommandSender sender,
            String[] args,
            ForcedSlotResultRegistry registry
    ) {
        if (args.length != 5) {
            sender.sendMessage(ChatColor.YELLOW
                    + "用法：/sg slot test clear <玩家> <机器类型|all>");
            return false;
        }
        Player target = this.onlinePlayer(sender, args[3]);
        if (target == null) {
            return false;
        }
        if (args[4].equalsIgnoreCase("all")) {
            List<ForcedSlotResultRegistry.Directive> removed = registry.clearAll(target.getUniqueId());
            for (ForcedSlotResultRegistry.Directive directive : removed) {
                SmartGambling.getInstance().auditForcedSlotDirective(
                        "cleared", directive, "clearedBy=" + sender.getName()
                                + " targetName=" + target.getName());
            }
            sender.sendMessage(removed.isEmpty()
                    ? ChatColor.GRAY + target.getName() + " 没有待清除的老虎机测试结果。"
                    : ChatColor.GREEN + "已清除 " + target.getName() + " 的 " + removed.size()
                            + " 条老虎机测试结果。");
            return true;
        }

        SlotMachine slotMachine = this.configuredSlotMachine(sender, args[4]);
        if (slotMachine == null) {
            return false;
        }
        String machineTypeId = SmartGambling.getMachineTypeId(slotMachine);
        Optional<ForcedSlotResultRegistry.Directive> removed = registry.clear(
                target.getUniqueId(), machineTypeId);
        removed.ifPresent(directive -> SmartGambling.getInstance().auditForcedSlotDirective(
                "cleared", directive, "clearedBy=" + sender.getName()
                        + " targetName=" + target.getName()));
        sender.sendMessage(removed.isPresent()
                ? ChatColor.GREEN + "已清除 " + target.getName() + " 在 " + machineTypeId
                        + " 上的待执行测试结果。"
                : ChatColor.GRAY + target.getName() + " 在 " + machineTypeId
                        + " 上没有待清除的测试结果。");
        return true;
    }

    private Player onlinePlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "玩家 '" + name + "' 当前不在线。");
            return null;
        }
        return target;
    }

    private SlotMachine configuredSlotMachine(CommandSender sender, String rawTypeId) {
        Machine machineType = SmartGambling.getInstance().findMachineType(rawTypeId);
        if (!(machineType instanceof SlotMachine slotMachine)) {
            sender.sendMessage(ChatColor.RED + "'" + rawTypeId + "' 不是已配置的老虎机类型。");
            return null;
        }
        return slotMachine;
    }

    private void sendSlotTestUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW
                + "/sg slot test force <玩家> <机器类型> <图案1> ... <图案N>");
        sender.sendMessage(ChatColor.YELLOW + "/sg slot test show <玩家>");
        sender.sendMessage(ChatColor.YELLOW + "/sg slot test clear <玩家> <机器类型|all>");
    }

    private boolean reloadCommand(Player player) {
        if (CraftEngine.instance().isReloading() || CraftEngine.instance().isInitializing()) {
            player.sendMessage(ChatColor.RED + "CraftEngine 仍在加载，请完成后再执行 /sg reload。");
            return false;
        }
        SmartGambling plugin = SmartGambling.getInstance();
        EconomyService ledger = plugin.getEconomyService();
        long active = ledger.activeWagerCount();
        long unresolved = ledger.unresolvedCount();
        if (active > 0 || unresolved > 0) {
            player.sendMessage(ChatColor.RED + "已拒绝重载：当前有 " + active + " 笔活动下注、"
                    + unresolved + " 笔未解决交易，请先结算或核对。");
            return false;
        }
        PreparedRuntime prepared;
        try {
            prepared = new ReloadCoordinator(plugin).prepare();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "SmartGambling reload validation failed", exception);
            player.sendMessage(ChatColor.RED + "重载校验失败，旧运行时仍在工作："
                    + exception.getMessage());
            return false;
        }
        try {
            new ReloadCoordinator(plugin).commit(prepared);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "SmartGambling reload commit failed", exception);
            player.sendMessage(ChatColor.RED + "重载提交失败，请查看控制台后再继续。");
            return false;
        }
        this.selectBlocksRoutine.cancelAll();
        player.sendMessage(this.configManager.messages.get("reload"));
        return true;
    }

    private boolean cancelCommand(Player player) {
        if (!this.selectBlocksRoutine.cancel(player)) {
            player.sendMessage(this.configManager.messages.get("creationNoOrigin"));
            return false;
        }
        player.sendMessage(this.configManager.messages.get("canceledRoutine"));
        return true;
    }

    private boolean confirmCommand(Player player) {
        Optional<CreationSession> optionalSession = this.selectBlocksRoutine.session(player.getUniqueId());
        if (optionalSession.isEmpty()) {
            player.sendMessage(this.configManager.messages.get("slotsAddCorrectUsage"));
            return false;
        }
        CreationSession session = optionalSession.get();
        MachineCreationValidator.ValidationResult validation = this.creationValidator.validate(player, session);
        if (!validation.valid()) {
            player.sendMessage(this.safeFormat("creationValidationFailed",
                    ChatColor.RED + "无法创建机器：%s。当前选区已保留。", validation.message()));
            return false;
        }
        List<Block> blocks;
        try {
            blocks = session.resolveBlocks();
        } catch (RuntimeException exception) {
            player.sendMessage(this.safeFormat("creationValidationFailed",
                    ChatColor.RED + "无法创建机器：%s。当前选区已保留。", "所选世界当前未加载"));
            return false;
        }
        MachineData machineData = this.createMachine(blocks, session, player);
        if (machineData == null) {
            return false;
        }
        this.selectBlocksRoutine.complete(player.getUniqueId(), machineData.id);
        Block origin = blocks.get(0);
        player.sendMessage(this.safeFormat(
                "creationSummary",
                ChatColor.GREEN + "机器 %s 已创建：原点 %s %s %s，朝向 %s，共 %s 个交互方块，ID %s。",
                session.machineTypeId(),
                origin.getX(), origin.getY(), origin.getZ(),
                directionChinese(session.direction()), blocks.size(), machineData.id
        ));
        player.sendMessage(this.configManager.messages.get("creationUndoAvailable"));
        return true;
    }

    private boolean rotateCommand(Player player, String[] args) {
        boolean left = args.length >= 2 && args[1].equalsIgnoreCase("left");
        if (args.length >= 2
                && !args[1].equalsIgnoreCase("left")
                && !args[1].equalsIgnoreCase("right")) {
            player.sendMessage(ChatColor.RED + "用法：/sg rotate <left|right>");
            return false;
        }
        if (!this.selectBlocksRoutine.rotate(player.getUniqueId(), left)) {
            player.sendMessage(ChatColor.RED + "当前没有进行中的机器创建向导。");
            return false;
        }
        CreationSession session = this.selectBlocksRoutine.session(player.getUniqueId()).orElseThrow();
        player.sendMessage(this.safeFormat("creationRotated",
                ChatColor.GREEN + "预览朝向已旋转为 %s。", directionChinese(session.direction())));
        return true;
    }

    private boolean undoCommand(Player player) {
        Optional<UUID> undoId = this.selectBlocksRoutine.lastCreated(player.getUniqueId());
        if (undoId.isEmpty()) {
            player.sendMessage(this.configManager.messages.get("creationNothingToUndo"));
            return false;
        }
        MachineData machineData = SmartGambling.getInstance().uuidMachines.get(undoId.get());
        if (machineData == null) {
            this.selectBlocksRoutine.clearLastCreated(player.getUniqueId());
            player.sendMessage(this.configManager.messages.get("creationNothingToUndo"));
            return false;
        }
        if (machineData.inUse || !this.prepareMachineRemoval(machineData, player)) {
            player.sendMessage(this.configManager.messages.get("creationUndoBlocked"));
            return false;
        }
        try {
            if (!DataManager.removeMachine(machineData.blocks[0].getChunk(), machineData)) {
                player.sendMessage(this.configManager.messages.get("creationUndoBlocked"));
                return false;
            }
            this.stopRemovedRuntime(machineData);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    java.util.logging.Level.SEVERE, "Could not undo machine " + machineData.id, exception);
            player.sendMessage(ChatColor.RED + "撤销失败，机器数据和实体已保持不变，请查看控制台。 ");
            return false;
        }
        this.selectBlocksRoutine.clearLastCreated(player.getUniqueId());
        player.sendMessage(this.configManager.messages.get("creationUndoSuccess"));
        return true;
    }

    private boolean removeCommand(Player player, String[] args) {
        if (args.length == 2) {
            if (args[1].equalsIgnoreCase("all")) {
                return this.removeAllMachines(player);
            }
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
        String typeId;
        try {
            typeId = me.arthed.smartgambling.utils.MachineTypeIds.normalize(args[1]);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(this.configManager.messages.get("slotsAddCorrectUsage"));
            return false;
        }
        Machine sm = SmartGambling.getInstance().findMachineType(typeId);
        if (sm == null) {
            player.sendMessage(this.configManager.messages.get("slotsAddCorrectUsage"));
            return false;
        }
        if (this.selectBlocksRoutine.session(player.getUniqueId()).isPresent()) {
            player.sendMessage(this.configManager.messages.get("creationSessionExists"));
            return false;
        }
        if (!this.selectBlocksRoutine.startRoutine(player, typeId, sm)) {
            player.sendMessage(this.configManager.messages.get("creationWandNoSpace"));
            return false;
        }
        player.sendMessage(this.configManager.messages.get("slotsAddStart"));
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("slot")) {
            return this.slotTestTabComplete(sender, args);
        }
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>(List.of(
                    "help", "add", "list", "remove", "reload", "confirm", "cancel",
                    "rotate", "undo", "fixentities", "ledger"
            ));
            SmartGambling plugin = SmartGambling.getInstance();
            if (sender.hasPermission(SLOT_TEST_PERMISSION) && plugin.isForcedSlotResultsEnabled()) {
                subcommands.add("slot");
            }
            return matching(subcommands, args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("add")) {
                ArrayList<String> result = new ArrayList<String>();
                for (String id : SmartGambling.getInstance().machineTypes.keySet()) {
                    if (!id.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) continue;
                    result.add(id);
                }
                return result;
            }
            if (args[0].equalsIgnoreCase("remove")) {
                return Arrays.asList("all", "0");
            }
            if (args[0].equalsIgnoreCase("ledger")) {
                return Arrays.asList("list", "resolve");
            }
            if (args[0].equalsIgnoreCase("rotate")) {
                return Arrays.asList("left", "right");
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("ledger")
                && args[1].equalsIgnoreCase("resolve")) {
            return Arrays.asList("applied", "not-applied");
        }
        return null;
    }

    private List<String> slotTestTabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(SLOT_TEST_PERMISSION)
                || !SmartGambling.getInstance().isForcedSlotResultsEnabled()) {
            return List.of();
        }
        if (args.length == 2) {
            return matching(List.of("test"), args[1]);
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("test")) {
            return List.of();
        }
        if (args.length == 3) {
            return matching(List.of("force", "show", "clear"), args[2]);
        }
        if (!args[2].equalsIgnoreCase("force")
                && !args[2].equalsIgnoreCase("show")
                && !args[2].equalsIgnoreCase("clear")) {
            return List.of();
        }
        if (args.length == 4) {
            List<String> onlineNames = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlineNames.add(player.getName());
            }
            onlineNames.sort(String.CASE_INSENSITIVE_ORDER);
            return matching(onlineNames, args[3]);
        }
        if (args[2].equalsIgnoreCase("show")) {
            return List.of();
        }
        if (args.length == 5) {
            List<String> machineTypes = this.slotMachineTypeIds();
            if (args[2].equalsIgnoreCase("clear")) {
                machineTypes.add(0, "all");
            }
            return matching(machineTypes, args[4]);
        }
        if (!args[2].equalsIgnoreCase("force")) {
            return List.of();
        }

        Machine machine = SmartGambling.getInstance().findMachineType(args[4]);
        if (!(machine instanceof SlotMachine slotMachine)
                || args.length - 5 > slotMachine.getReelCount()) {
            return List.of();
        }
        return matching(slotMachine.getSymbolIds(), args[args.length - 1]);
    }

    private List<String> slotMachineTypeIds() {
        List<String> ids = new ArrayList<>();
        for (Machine machine : SmartGambling.getInstance().machineTypes.values()) {
            if (machine instanceof SlotMachine slotMachine) {
                String id = SmartGambling.getMachineTypeId(slotMachine);
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    private static List<String> matching(List<String> candidates, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private MachineData createMachine(List<Block> blocks, CreationSession session, Player player) {
        Block[] blockArray = blocks.toArray(new Block[0]);
        Block origin = blocks.get(0);
        Machine machineType = session.machineType();
        try {
            MachineData machineData = machineType instanceof BlackJack
                    ? new MachineDataBlackjack(UUID.randomUUID(), machineType, blockArray, null, session.direction())
                    : new MachineData(UUID.randomUUID(), machineType, blockArray, null, session.direction());
            if (!DataManager.addMachine(origin.getChunk(), machineData)) {
                return null;
            }
            return machineData;
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    java.util.logging.Level.SEVERE, "Could not create machine at " + origin.getLocation(), exception);
            player.sendMessage(ChatColor.RED + "机器数据写入失败，没有创建任何可用机器；当前选区已保留。 ");
            return null;
        }
    }

    private static String directionChinese(org.bukkit.block.BlockFace direction) {
        return switch (direction) {
            case NORTH -> "北";
            case EAST -> "东";
            case SOUTH -> "南";
            case WEST -> "西";
            default -> "未知";
        };
    }

    private String safeFormat(String key, String fallback, Object... values) {
        String template = this.configManager.messages.getOrDefault(key, fallback);
        try {
            return String.format(template, values);
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().warning("消息格式无效：" + key + "，已使用默认中文提示");
            return String.format(fallback, values);
        }
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
        HashMap<Chunk, List<MachineData>> worldMachines = SmartGambling.getInstance().machines.get(player.getWorld());
        if (worldMachines == null) {
            player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), 0));
            return;
        }
        for (List<MachineData> machinesList : worldMachines.values()) {
            for (MachineData machineData : new ArrayList<>(machinesList)) {
                if (!id.equals(machineData.id)) continue;
                if (machineData.inUse) {
                    DisplayUtils.displayActionBar(player, this.configManager.messages.get("machineAlreadyInUse"));
                    return;
                }
                if (!this.prepareMachineRemoval(machineData, player)) {
                    return;
                }
                try {
                    DataManager.removeMachine(machineData.blocks[0].getChunk(), machineData);
                    this.stopRemovedRuntime(machineData);
                } catch (RuntimeException exception) {
                    SmartGambling.getInstance().getLogger().log(
                            java.util.logging.Level.SEVERE, "Could not remove machine " + machineData.id, exception);
                    player.sendMessage(ChatColor.RED + "机器数据无法写入，已保留原机器。");
                    return;
                }
                player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), 1));
                return;
            }
        }
        player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), 0));
    }

    private boolean removeAllMachines(Player player) {
        HashMap<Chunk, List<MachineData>> worldMachines = SmartGambling.getInstance().machines.get(player.getWorld());
        if (worldMachines == null || worldMachines.isEmpty()) {
            player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), 0));
            return true;
        }

        int removed = 0;
        for (List<MachineData> machineList : new ArrayList<>(worldMachines.values())) {
            for (MachineData machineData : new ArrayList<>(machineList)) {
                if (machineData.inUse) {
                    continue;
                }
                if (!this.prepareMachineRemoval(machineData, player)) {
                    continue;
                }
                try {
                    if (DataManager.removeMachine(machineData.blocks[0].getChunk(), machineData)) {
                        this.stopRemovedRuntime(machineData);
                        removed++;
                    }
                } catch (RuntimeException exception) {
                    SmartGambling.getInstance().getLogger().log(
                            java.util.logging.Level.SEVERE, "Could not remove machine " + machineData.id, exception);
                }
            }
        }
        worldMachines.values().removeIf(List::isEmpty);
        player.sendMessage(String.format(this.configManager.messages.get("removedSlotMachines"), removed));
        return true;
    }

    private boolean prepareMachineRemoval(MachineData machineData, Player player) {
        EconomyService ledger = SmartGambling.getInstance().getEconomyService();
        if (ledger.activeWagerCount() > 0L || ledger.hasUnresolvedFunds()) {
            player.sendMessage(ChatColor.RED
                    + "资金账本仍有活动下注或未解决交易，已禁止移除机器。");
            return false;
        }
        if (machineData.machineType instanceof CrashMachine crashMachine) {
            if (crashMachine.hasOutstandingFunds()) {
                player.sendMessage(ChatColor.RED
                        + "这台爆点机器仍有活动或未解决的下注，请完成结算后再移除。");
                return false;
            }
        }
        return true;
    }

    private void stopRemovedRuntime(MachineData machineData) {
        if (machineData.machineType instanceof CrashMachine crashMachine) {
            crashMachine.shutdownAndRefund();
        }
    }

    private void removeOldEntities(Player player) {
        try {
            int repaired = DataManager.fixEntities(player.getLocation().getChunk());
            player.sendMessage(ChatColor.GREEN + "已核对并修复当前区块内的 " + repaired + " 台机器。");
        } catch (RuntimeException exception) {
            SmartGambling.getInstance().getLogger().log(
                    java.util.logging.Level.SEVERE, "Could not repair machine entities", exception);
            player.sendMessage(ChatColor.RED + "实体修复失败，未删除任何归属不确定的实体。");
        }
    }
}
