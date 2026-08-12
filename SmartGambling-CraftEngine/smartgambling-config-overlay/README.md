# SmartGambling 配置覆盖包

这里的 YAML 来自实际部署目录，并已转换为 CraftEngine 稳定 ID。它保留原服
的概率、奖励、价格、槽位、文本和实体偏移，不包含持久化 `data.json`。

开始部署前请先阅读[项目 README](../../README.md)和[完整配置指南](../../CONFIGURATION.md)；
CE 资源包结构与版本要求见[上级说明](../README.md)。目标环境为 Paper 1.21.4、
Java 21、CraftEngine 26.7.4。

## 部署

1. 停服并备份整个 `plugins/SmartGambling`。
2. 先把同级 `smartgambling` 内容包部署到
   `plugins/CraftEngine/resources/smartgambling`。
3. 将本目录中的 `config.yml`、`placeholders.yml` 和 `machines` 复制到
   `plugins/SmartGambling`，允许覆盖旧 YAML。
4. 将 CraftEngine 的 `resource-pack.supported-version.min/max` 都保持为
   `server`（或都显式设为 `1.21.4`），并保持
   `item.always-use-item-model: true`、`network.disable-item-operations: false`。
5. 从 Maven 构建输出 `target/SmartGambling-1.0.10-CE.jar` 或项目 GitHub Release
   获取同版本插件 JAR。
6. 执行 `/ce reload all`，确认所有 `smartgambling:*` ID 加载成功，然后重启。

不要复制本 README，也不要删除 `plugins/SmartGambling/data.json`、
`data.json.bak`、`economy-ledger.db`（含 `-wal/-shm`）或 CraftEngine 的
`cache/custom_model_data`。旧 `pending-payouts.yml` 会由 1.0.10 自动迁移并保留备份。

## 已完成的转换

- 所有 `customModelData` 改为 `craftEngineItem`；
- 所有牌组 `customModelDataList` 改为 `craftEngineItems`；
- 21 点完整红/黑牌、牌背、牌桌已接入真实素材；
- Crash、Jackpot 和赌场椅已接入真实三维模型；
- 9 个游戏 GUI 状态全部使用 CE 图像标签；
- 货币字符恢复为原包 U+E307；
- 所有点击占位统一为 `smartgambling:gui_transparent`。

示例：

```yaml
Chair:
  craftEngineItem: smartgambling:casino_chair

Table:
  craftEngineItem: smartgambling:blackjack_table

CardBack:
  craftEngineItem: smartgambling:blackjack_card_back
```

GUI 标题示例：

```yaml
title: '<shift:-8><image:smartgambling:blackjack_ui><shift:-200>'
```

CraftEngine 自动分配模型编号。`smartgambling:*` ID 才是运行时契约，不要把
旧 STICK/CMD 编号或 CE 缓存里的自动编号重新写进配置。

插件仅在目标 YAML 不存在时写入默认文件，所以只替换 JAR 不会自动迁移旧
服配置；需要覆盖本 Overlay，或手动合并同样的 CE ID 与图像标签。

本覆盖包不包含素材许可声明；部署和分发前请自行确认相关素材的使用权限。
