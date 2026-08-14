# SmartGambling 配置指南

所有文本文件均使用 UTF-8。首次启动会把默认配置写入：

```text
plugins/SmartGambling/config.yml
plugins/SmartGambling/placeholders.yml
plugins/SmartGambling/machines/
```

升级旧服时，JAR 不会覆盖已经存在的 YAML。请备份后使用
`SmartGambling-CraftEngine/smartgambling-config-overlay` 合并 CE ID 与 GUI 标签。

## 主配置 `config.yml`

### Messages 与 helpMenu

`Messages` 控制聊天、ActionBar 和创建向导提示；`%prefix%` 会替换成
`Messages.prefix`。`String.format` 类消息中的 `%s` 数量不可随意改变。

```yaml
Messages:
  prefix: '&7[&3SmartGambling&7]'
  creationRotated: '%prefix% &a预览朝向已旋转为 &f%s&a。'
  creationSummary: '%prefix% &a已创建 &f%s&a，原点 &f%s %s %s&a，朝向 &f%s&a，共 &f%s &a个交互方块。机器 ID：&f%s'
```

缺失的新消息会使用 JAR 内置中文默认值；格式写错时创建向导会回退到安全
中文提示，不会因为发送总结失败而破坏已经持久化的机器。

### 创建向导

```yaml
CreationGuide:
  timeoutSeconds: 300
  maxInteractionBlocks: 32
  maxRadius: 16
  previewIntervalTicks: 10
```

| 字段 | 范围 | 说明 |
| --- | --- | --- |
| `timeoutSeconds` | 30–3600 | 无操作多久后取消会话 |
| `maxInteractionBlocks` | 1–256 | 原点之外可添加的方块数 |
| `maxRadius` | 1–256 | 交互方块距原点的最大距离 |
| `previewIntervalTicks` | 2–200 | BossBar 与粒子预览刷新周期 |

这些值会随事务化 `/sg reload` 一起验证并切换。无效值会拒绝重载，旧运行时
继续工作。

### 老虎机强制结果测试

```yaml
Testing:
  forcedSlotResults:
    enabled: false
    expiresSeconds: 120
```

该能力默认关闭，只应在隔离测试环境临时开启。`expiresSeconds` 必须为正整数，
表示未使用的一次性指令的有效期。命令中的图案名称只能来自目标老虎机的
`Items`，不能使用 `Categories`；数量必须与 `GUI.displaySlots` 的滚轴数相同。

```text
/sg slot test force <玩家> <机器类型> <图案1> ... <图案N>
/sg slot test show <玩家>
/sg slot test clear <玩家> <机器类型|all>
```

指令按“玩家 UUID + 机器类型”绑定，仅在资金账本成功接受下注后消耗一次。
余额不足不会消耗；已开始的旋转不会被中途改写；玩家退出、成功重载或插件
关闭会清除尚未使用的指令。测试局会真实扣款、派奖和执行奖励命令，并写入审计日志。

### 椅子

```yaml
Chair:
  craftEngineItem: smartgambling:casino_chair
  Offset:
    x: 2
    y: -1
    z: 0
```

`craftEngineItem` 必须是已经由 CraftEngine 加载的稳定 ID。偏移必须是有限数值；
插件会按机器朝向旋转，并在创建时自动生成座椅 ArmorStand。

## CraftEngine 物品字段

单物品：

```yaml
Machine:
  craftEngineItem: smartgambling:crash_machine
```

变体列表（例如红/黑两套牌）：

```yaml
Cards:
  1:
    craftEngineItems:
      - smartgambling:blackjack_red_ace
      - smartgambling:blackjack_black_ace
```

不要再使用 `customModelData` 或 `customModelDataList`。CE 自动分配的客户端编号
不是配置契约，唯一稳定契约是 `smartgambling:*` ID。

## GUI 与物品

所有箱子 GUI 的 `size` 必须是 9–54 之间的 9 的倍数；槽位从 0 开始，必须
小于 `size`。必需按钮必须指向 `Items` 中实际填充的槽位，否则启动或重载会
直接拒绝配置。

CraftEngine GUI 背景示例：

```yaml
GUI:
  size: 54
  title: '<shift:-8><image:smartgambling:slot_ui><shift:-200>'
```

可用背景 ID：

```text
smartgambling:money_ui
smartgambling:blackjack_bet_ui
smartgambling:blackjack_ui
smartgambling:crash_bet_ui
smartgambling:crash_game_ui
smartgambling:crash_crashed_ui
smartgambling:jackpot_bet_ui
smartgambling:jackpot_game_ui
smartgambling:slot_ui
```

上述背景沿用迁移前的原素材，个别 PNG 仍可能含有英文装饰字样；这不影响
中文聊天、ActionBar 和 BossBar 提示。Mineflayer 无法对这些图像的视觉效果做验收。

`network.intercept-packets.container` 必须保持启用，客户端也必须成功加载 CE
最终资源包，否则标题标签虽被服务端解析，玩家仍看不到正确背景。

## 玩法配置

### `machines/slots/*.yml`

文件名（去掉 `.yml` 并规范为小写）就是老虎机类型 ID。JAR 首次启动时
写出的 `slotExample.yml` 对应 `/sg add slotexample`；CE 配置覆盖包中的
`SlotMachine.yml` 对应 `/sg add slotmachine`。重命名文件会改变机器类型 ID，因此请勿
在已有持久化机器时随意改名。
主要字段：

- `Items`：滚轴图案、权重和等价图案；
- `Rewards`：匹配条件、现金倍数、命令和音效；
- `defaultBet`：默认下注额；
- `GUI.displaySlots`：每列滚轴的显示槽位；
- `Machine` 与 `Offset`：物理模型及相对原点位置。

所有权重和金额必须为正数，累计权重不能溢出整数范围。

### `machines/blackjack/blackjack.yml`

- `Cards`：13 种牌值及红/黑两套 `craftEngineItems`；
- `Table`：牌桌模型、模型偏移和两把椅子偏移；
- `GUI.cardSlots` / `opponentCardSlots`：双方牌面槽位；
- `hitButton` / `standButton`：要牌与停牌按钮。

两把椅子会按牌桌朝向旋转，并自动保持面对面。

### `machines/poker/poker.yml`

- `Rules.smallBlind` / `bigBlind`：大小盲，且大盲必须高于小盲；
- `Rules.actionTimeoutSeconds`：单次操作限时，超时无待跟注时自动过牌，否则弃牌；
- `Rules.resultDisplayTicks`：摊牌或弃牌结算结果保留时间；
- `Cards`：必须完整配置红桃、方块、梅花、黑桃各 13 张，不能缺牌或重复；
- `GUI.ownCardSlots` / `opponentCardSlots`：双方两张底牌；
- `GUI.communityCardSlots`：翻牌、转牌、河牌共五张公共牌；
- 五组操作按钮依次为弃牌、过牌/跟注、最小加注、底池加注与全下。

玩法为单手双人 Heads-Up。桌主选择买入并支付小盲，对手确认同额买入并支付
大盲；买入至少覆盖大盲。双方完整买入由账本托管，牌桌筹码只在本手内计算，
最终按剩余筹码和底池份额原子返还。技术中断退款，主动离桌按弃牌处理。
默认 GUI 和牌桌沿用二十一点材质；红桃/黑桃复用既有牌面，方块/梅花使用
新增的同风格完整牌组。

### `machines/crash/crash.yml`

- `gameDuration`：下注倒计时；
- `timeBetweenGames`：轮次间隔；
- `timeAddedOnBet`：新下注增加的时间；
- `Chances`：爆点上限及权重；
- `BetGUI` / `GameGUI`：下注、兑现和玩家头像槽位。

每台物理 Crash 机器都有独立 runtime 和稳定 machine UUID。

### `machines/jackpot/jackpot.yml`

- `gameDuration`、`timeBetweenGames`、`timeAddedOnBet`：全局轮次时间；
- `BetGUI`：玩家下注列表与撤注按钮；
- `GameGUI.headSlots`：抽奖动画路径；
- `winningHeadSlot`：最终赢家槽位。

### 公共金额与确认菜单

- `machines/moneyInventory.yml`：预设金额、自定义金额按钮；
- `machines/confirmInventory.yml`：二十一点挑战确认/拒绝按钮。

金额必须为正整数；所有真实扣款与入账都由 SQLite 账本统一处理。

## PlaceholderAPI

PlaceholderAPI 是可选依赖。安装后，插件通过反射桥接注册 `%sg_*%`，并只读取
主线程发布的不可变快照。非法 UUID 或未知机器返回空值/unknown，不会抛异常。
显示文字在 `placeholders.yml` 中配置。

## WorldGuard

安装 WorldGuard 后，创建向导会同时检查：

- 每个原点和交互方块是否允许破坏/构建；
- 每个模型与座椅预期位置是否允许放置 ArmorStand。

WorldGuard 查询异常时创建会安全失败并保留选区，不会越权生成持久实体。

## 安全重载

推荐顺序：

1. 确认没有活动下注或 `UNKNOWN` 交易；
2. 修改 YAML；
3. 修改 CE 内容时先执行 `/ce reload all`；
4. 执行 `/sg reload`；
5. 查看控制台是否有 YAML、CE ID、槽位或权重错误。

重载失败不会替换当前玩法对象、任务或机器绑定。`data.json` 只在停服/机器仓库
事务中维护；不要依靠 `/sg reload` 读取手工修改的机器数据。

## 持久文件与备份

请同时备份：

```text
plugins/SmartGambling/config.yml
plugins/SmartGambling/placeholders.yml
plugins/SmartGambling/machines/
plugins/SmartGambling/data.json
plugins/SmartGambling/data.json.bak
plugins/SmartGambling/economy-ledger.db
plugins/SmartGambling/economy-ledger.db-wal
plugins/SmartGambling/economy-ledger.db-shm
```

不要在服务器运行时单独复制 SQLite 主文件，也不要删除 `-wal/-shm` 后继续运行。

## 授权与发布

仓库当前未提供 `LICENSE`，CraftEngine 内容包中的贴图、模型、字体和 GUI
背景也没有在本指南中被声明为可公开转授权。配置完成不等于获得素材许可；
对外发布或商用前应单独确认素材来源与授权。
