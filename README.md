# SmartGambling 1.0.10-CE

SmartGambling 是面向 Paper 1.21.4 的多玩法赌场插件。本分支已将所有自定义
物品从固定 `Material + CustomModelData` 迁移为 CraftEngine 稳定 ID，并加入
SQLite 资金账本、dataVersion 3 机器仓库、事务化配置重载和中文 BossBar
机器创建向导。

## 玩法与能力

- **老虎机**：滚轴动画、组合奖励、现金与命令奖励。
- **二十一点**：双人牌桌、独立座位、牌面模型与原子结算。
- **德州扑克**：双人 Heads-Up、大小盲、四轮下注、全下与标准七选五摊牌。
- **Crash 爆点**：每台物理机器拥有独立轮次，支持倍率兑现。
- **累积奖池**：全服共享奖池、按下注额抽取赢家。
- **CraftEngine 资源**：赌场机器、椅子、牌桌、牌面、图标和 GUI 背景。
- **资金安全**：SQLite WAL 账本、幂等 operation ID、安全恢复与人工核对。
- **机器数据**：dataVersion 3、实体 role/PDC、跨区块恢复与复制写持久化。
- **可选集成**：PlaceholderAPI 和 WorldGuard；未安装 PAPI 时仍可正常启动。

## 运行环境

| 组件 | 要求 |
| --- | --- |
| 服务端 | Paper 1.21.4 |
| Java | 21 |
| CraftEngine | 26.7.4 |
| Vault | 1.7.x |
| 经济实现 | 任意正确注册到 Vault 的经济插件 |
| PlaceholderAPI | 可选，建议 2.11.7+ |
| WorldGuard | 可选，1.21.4 建议 WorldGuard 7.0.13 + WorldEdit 7.3.10 |

CraftEngine 和 Vault 是硬依赖。SmartGambling 会等待 CraftEngine 完成物品加载、
且等待 Vault 经济提供者真正启用后，才开放玩法。

## 构建

```powershell
mvn clean package
```

输出文件：

```text
target/SmartGambling-1.0.10-CE.jar
```

SQLite JDBC 会被打入最终 JAR；CraftEngine、Vault、PlaceholderAPI、WorldGuard
和 WorldEdit 不会被 shade 进插件。

## 安装与升级

1. 正常停止服务器，并备份 `plugins/SmartGambling`、经济插件数据和
   `plugins/CraftEngine`。
2. 安装 CraftEngine 26.7.4、Vault 和一个 Vault 经济实现。
3. 将 [CraftEngine 内容包](SmartGambling-CraftEngine/smartgambling) 复制到：

   ```text
   plugins/CraftEngine/resources/smartgambling
   ```

4. 首次从旧版升级时，将
   [配置覆盖包](SmartGambling-CraftEngine/smartgambling-config-overlay) 中的
   `config.yml`、`placeholders.yml` 和 `machines/` 合并到
   `plugins/SmartGambling`。覆盖包不包含 `data.json`。
5. 将构建出的 `SmartGambling-1.0.10-CE.jar` 放入 `plugins/`，并移走旧版
   SmartGambling JAR，避免重复加载。
6. 执行 `/ce reload all` 后重启服务器，让客户端加载唯一的最终资源包。

Paper 1.21.4 上建议锁定服务端资源包格式，并保持 CE 物品网络转换开启：

```yaml
resource-pack:
  supported-version:
    min: server
    max: server
item:
  always-use-item-model: true
  always-use-custom-model-data: false
  always-generate-model-overrides: false
network:
  disable-item-operations: false
```

不要手工删除 CraftEngine 的 `cache/custom_model_data`，也不要同时让
ItemsAdder 和 CraftEngine 分别发送互相竞争的资源包。

## 机器创建向导

管理员权限：`sg.admin`（默认仅 OP）。固定玩法的机器类型 ID 为：

```text
blackjack（二十一点）
poker（双人德州扑克）
crash（Crash 爆点）
lottery（累积奖池）
```

老虎机 ID 不是固定值，而是由 `machines/slots/*.yml` 的文件名决定。
JAR 内置示例 `slotExample.yml` 对应 `slotexample`；应用本仓库的 CE 配置覆盖包
`SlotMachine.yml` 后对应 `slotmachine`。可通过 `/sg list all` 确认当前已加载 ID。

创建流程：

1. `/sg add <机器类型>` 获取创建选择棒并显示中文 BossBar。
2. 使用选择棒**左键**方块设置机器原点。
3. 使用选择棒**右键**添加额外交互方块；**潜行右键**移除。
4. `/sg rotate left|right` 旋转粒子预览。
5. `/sg confirm` 执行 CE ID、位置、冲突、WorldGuard 和写盘校验。
6. 创建成功后模型与座椅会自动生成；位置有误可立即执行 `/sg undo`。

校验或写盘失败时，选区、选择棒和 BossBar 会保留，可修正后再次确认；只有
成功创建、主动取消、退出或超时才会清理会话。

## 管理命令

```text
/sg help
/sg add <机器类型>
/sg rotate <left|right>
/sg confirm
/sg cancel
/sg undo
/sg list [all]
/sg remove <机器UUID|all>
/sg fixentities
/sg reload
/sg ledger list [page]
/sg ledger resolve <transaction-id> applied|not-applied
/sg slot test force <玩家> <机器类型> <图案1> ... <图案N>
/sg slot test show <玩家>
/sg slot test clear <玩家> <机器类型|all>
/jackpot
```

`/sg reload` 在存在活动下注或未解决资金时会拒绝。空闲重载先完整解析 YAML、
CE ID、槽位、权重和创建向导设置，成功后才切换运行时；它不会重新读取或
清空 `data.json`。

老虎机强制组合仅用于隔离环境的管理员测试，默认关闭。开启
`Testing.forcedSlotResults.enabled` 后，需同时拥有 `sg.admin` 和
`sg.admin.slot-test`。强制结果只作用于指定玩家在对应机器类型的下一次
成功下注，仍会真实扣款、派奖和执行奖励命令。

## 资金账本

数据库位置：

```text
plugins/SmartGambling/economy-ledger.db
```

Vault 调用前会先写入持久状态。明确未入账的款项可安全重试；无法判断调用
是否已经生效时，交易进入 `UNKNOWN`，不会自动重放，并仅冻结对应玩家的新
下注。管理员必须先核对经济插件的交易记录，再执行：

- `applied`：确认该 Vault 调用已经发生；
- `not-applied`：确认该 Vault 调用没有发生。

备份时必须一起保留数据库的 `-wal/-shm`、`data.json` 和 `data.json.bak`。

## 文档

- [完整配置指南](CONFIGURATION.md)
- [部署与验收清单](SmartGambling-1.0.10-CE-验收指南.md)
- [CraftEngine 内容包说明](SmartGambling-CraftEngine/README.md)
- [原素材到 CE 的映射](SmartGambling-CraftEngine/SOURCE_MAP.md)
- [Mineflayer 协议级测试](integration-test/mineflayer/README.md)

## 测试边界

自动测试覆盖账本状态机、故障注入、玩法结算身份、配置校验和 dataVersion 3
仓库；Mineflayer 用于 Paper 协议级的多玩家、GUI、创建向导、重连与持久化
验证。Mineflayer 不会真正渲染资源包，因此 CE 模型朝向、GUI 像素对齐和字体
仍需用原版 1.21.4 客户端做最终视觉验收。GUI 背景沿用原素材，部分
图片仍可能含有英文装饰字样。

德州扑克每次由桌主选择买入，第二名玩家确认相同买入后开局；桌主为按钮位/
小盲，翻牌前先行动，对手为大盲并在翻牌后各圈先行动。双方买入先进入资金
账本托管，弃牌、摊牌或平分底池后一次性原子结算；操作超时会自动过牌或弃牌。

2026-08-14 的最新 `mvn clean package` 结果为 27 个测试类、121 个用例，
0 failure / 0 error / 0 skipped。后续数字以当次构建输出为准。Mineflayer 的
`--hard-kill-recovery` 场景已编写，但当前不宣称已通过，发布或上生产前必须在干净
隔离服务端重新执行并保留报告。

## 授权与素材说明

仓库当前未包含 `LICENSE` 文件，也不对原始服务端素材、贴图、模型或 GUI
背景的转授权作出声明。在公开发布、销售或再分发 CraftEngine 内容包前，请先
由仓库维护者确认每项素材的来源与授权。
