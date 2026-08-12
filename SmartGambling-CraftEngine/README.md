# SmartGambling CraftEngine 资源包

这个目录是当前 SmartGambling CE 版本的完整内容包。插件通过
`CraftEngineItems.byId(...)` 创建自定义物品，不再依赖写死的
`CustomModelData`。

## 素材状态

最初只检查 ItemsAdder 数据时，21 点、Crash 和 Jackpot 素材被列为缺失。
后续根据用户提供的原始服务器归档及其中的 `server.properties`，定位并校验了
服务器实际下发的资源包。原包中的 42 个赌场模型 JSON、60 张赌场 PNG 以及
货币图标已经迁入 `smartgambling` 命名空间。现在包含：

- 26 张红/黑牌面、牌背和完整牌堆模型；
- 21 点桌、赌场椅、老虎机、Crash 机器、Jackpot 机器；
- 8 个老虎机符号和奖励菜单别名；
- 投注、确认、21 点、Crash 三状态、Jackpot 两状态和老虎机 GUI；
- 机器悬浮标题、货币、`fuhao1`、`fuhao3` 图像。

`gui_transparent` 仍然是透明物品，这是原设计：按钮图案画在 GUI 背景上，
透明物品只负责承载名称、Lore 和点击区域，并不是素材缺失。

## 安装

目标环境为 Paper 1.20.1、Java 21、CraftEngine 26.7.4。

1. 停止服务器并备份 `plugins/SmartGambling`。
2. 安装 CraftEngine，首次启动一次以生成 `plugins/CraftEngine`。
3. 将本目录的 `smartgambling` 复制到：

   ```text
   plugins/CraftEngine/resources/smartgambling
   ```

4. 将 `smartgambling-config-overlay` 中的 `config.yml`、
   `placeholders.yml` 和 `machines` 覆盖到 `plugins/SmartGambling`。
   Overlay 不包含 `data.json`，不会覆盖现有机器存档。
5. 安装 Maven 构建生成的 `target/SmartGambling-1.0.10-CE.jar`，或从本项目的
   GitHub Release 下载同版本 JAR。执行 `/ce reload all` 后重启服务器。
6. 确认客户端只接收一个最终合并资源包，不要让 ItemsAdder 与 CraftEngine
   分别发送互相竞争的材质包。

Paper 1.20.1 上保持：

```yaml
resource-pack:
  supported-version:
    min: server
network:
  disable-item-operations: false
```

不要为了这个包启用 `always-use-custom-model-data` 或
`always-generate-model-overrides`。保留 CraftEngine 自动生成的
`cache/custom_model_data`，不要手工删除或修改。

## CE 物品配置

单个物品使用稳定 ID：

```yaml
Machine:
  craftEngineItem: smartgambling:crash_machine
```

21 点牌色变体使用列表：

```yaml
Cards:
  Ace:
    craftEngineItems:
      - smartgambling:blackjack_red_ace
      - smartgambling:blackjack_black_ace
```

不要把原包的 STICK/CMD 501–542 写回新配置。那些编号只用于定位原始素材；
CraftEngine 会自动分配客户端模型编号，插件只依赖 `smartgambling:*` ID。

## GUI 图像标签

第三方容器标题使用 CraftEngine 网络标签：

```yaml
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

Overlay 已经把所有相关标题换成这些标签。固定旧字符也按原包保留，便于
迁移旧配置；新配置仍建议使用 `<image:...>`。

## 加载时序与实体模型

SmartGambling 在 `plugin.yml` 中硬依赖 `CraftEngine`，并等待首次
`CraftEngineReloadEvent` 后才加载配置。未知 CE ID 会直接阻止初始化，不会
悄悄退回原版 STICK/PAPER。

物理机器继续使用 SmartGambling 自己持久化的 ArmorStand 和座位逻辑，CE
物品装备在头部，使用原模型的 `display.head` 变换。这样可以保留现有机器
UUID、区块存档和玩法交互。1.0.10 会将旧 `data.json` 原子迁移到 dataVersion 3：
实体按稳定 role、UUID、坐标和区块保存，并写入机器 PDC。迁移前请保留原文件；
插件同时维护 `data.json.bak`，写盘失败不会发布半完成的机器或删除现有机器。

修改 CE 物品或模型后，先 `/ce reload all`，再重启服务器，让活动游戏、
计时器与新的 CE 注册表一起重建。

## 资金安全

1.0.10 使用 WAL + FULL 同步的 SQLite 账本：
`plugins/SmartGambling/economy-ledger.db`。每次调用 Vault 前先持久化，明确失败的
入账进入安全重试；无法判断是否已执行的调用进入 `UNKNOWN`，绝不会自动重付。
受影响玩家会被冻结新下注，其他玩家仍可继续。

旧 `pending-payouts.yml` 会在首次启动时幂等导入，然后重命名为 `.migrated-*`
备份。不要删除 `economy-ledger.db`、`-wal`、`-shm`、`data.json` 或 `data.json.bak`。

管理员核对命令：

```text
/sg ledger list [page]
/sg ledger resolve <transaction-id> applied|not-applied
```

`/sg reload` 在存在活动 wager、待付款或未知交易时会拒绝执行；空闲时会先完整
解析所有 YAML/CE ID，再一次性交换运行时，而且不会重读或清空 `data.json`。

素材来源和逐组映射见 `SOURCE_MAP.md`；`MISSING_ASSETS.md` 记录了原“缺失”
判断为何已被撤销。

项目安装、升级、命令和机器创建流程见[项目 README](../README.md)，完整 YAML
字段说明见[配置指南](../CONFIGURATION.md)。

本目录只记录技术转换与部署方式，不声明原素材为开源，也不授予任何额外的
复制或分发许可。部署者应自行确认其对相关素材的使用和分发权限。
