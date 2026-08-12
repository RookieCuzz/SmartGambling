# SmartGambling 1.0.10-CE 部署与验收指南

本版本目标环境：Paper 1.20.1、Java 21、CraftEngine 26.7.4、Vault，以及一个
Vault 经济实现。请先在测试服验收，不要直接替换生产服。

## 升级前

1. 正常停止旧服，确保旧版正在进行的下注已经结清。
2. 备份整个 `plugins/SmartGambling`、CraftEngine 内容包及经济插件数据。
3. 保留旧 `data.json` 和 `pending-payouts.yml`，不要手工修改或删除。
4. 把 `SmartGambling-CraftEngine/smartgambling` 放到
   `plugins/CraftEngine/resources/smartgambling`。
5. 把 `smartgambling-config-overlay` 中的 YAML 合并或覆盖到测试服。
6. 安装 Maven 构建生成的 `target/SmartGambling-1.0.10-CE.jar`（或从
   GitHub Release 下载同版本 JAR），确认旧 SmartGambling JAR 已移出
   `plugins`，避免同插件重复加载。

CraftEngine 在 1.20.1 上应保持：

```yaml
resource-pack:
  supported-version:
    min: server
network:
  disable-item-operations: false
```

## 首次启动检查

- 控制台显示 CraftEngine 完成加载后 SmartGambling 才初始化。
- 没有未知 `smartgambling:*` ID、SQLite、Vault 或 YAML 错误。
- 生成 `plugins/SmartGambling/economy-ledger.db`。
- 旧 `pending-payouts.yml` 被导入后保留为 `.migrated-*` 备份。
- 旧 `data.json` 升级为 dataVersion 3，并生成/更新 `data.json.bak`。
- 无 PlaceholderAPI 时插件正常启动；安装 PlaceholderAPI 后 `%sg_*%` 只注册一次。
- 客户端成功接收唯一的最终资源包，五种物理模型及 GUI 背景方向正常。

## 四玩法冒烟

每个测试玩家先记录余额。每次验证下注只扣一次，最终只能出现一次 loss、退款
或派奖。

### 老虎机

- 连点旋转按钮，只允许创建一笔 wager。
- 无奖、现金奖励、命令奖励各跑一轮。
- 动画开始后退出：结果尚未生成时退款；结果已生成时按该结果结算。
- 在停止动画与延迟结算之间退出，不能 NPE、吞款或重复派奖。

### 21 点

- 两名玩家选择相同金额，只有两笔都持久化并锁定后才发牌。
- 分别验证一方胜、平局、双方爆牌、玩家中途退出。
- 同时抢同一座位时只允许一名 host 和一名 challenger。
- 两张桌同时等待/开局，任务与状态不能串桌。

### Crash

- 两名以上玩家连续跑两轮，计时器不能因集合修改停止。
- 兑现按钮连点，只有第一次 CAS 成功；兑现玩家按锁定倍率支付。
- 兑现与爆点同 tick 竞争时，只能 payout 或 loss 其一。
- 已兑现后执行正常停服，只能获得锁定 payout，不能退回本金替代利润。

### Jackpot

- 多人下注后开奖，赢家得到总奖池，其余为 loss，整组结算先原子落账。
- 开奖结果提交前停服应退款全部；提交后不得改写为退款。
- 撤注只退一次；退出 GUI 不应覆盖其他玩法会话。

## 账本与重载

```text
/sg ledger list [page]
/sg ledger resolve <transaction-id> applied|not-applied
```

只有核对经济插件自己的交易记录后才能 resolve `UNKNOWN`：

- `applied`：确认该次 Vault 调用已经发生。
- `not-applied`：确认该次 Vault 调用没有发生；未知入账会转入安全重试。

测试 `/sg reload`：

- 有活动 wager、READY 或 UNKNOWN 时必须拒绝并显示数量。
- 故意写坏一个 YAML 或填写不存在的 CE ID，重载失败后旧玩法和任务仍可用。
- 修复配置后连续执行 100 次空闲重载，机器实体和后台任务不能持续增长。
- 重载不得清空或重新读取手工改动的 `data.json`；修改机器数据应停服重启。

## 数据恢复

- 删除一台测试机的某个 ArmorStand，连续重启三次，只补建一次且新 UUID 会写回。
- 测试座位偏移跨区块时，原点区块和实体区块都能恢复。
- `/sg fixentities` 只按 PDC 的 machine ID/role 修复，不删除相邻机器的实体。
- 模拟只读目录或写盘失败：新增机器不能发布，删除失败必须保留原机器和实体。

## 当前验收边界

最新干净构建已通过 19 个测试类、76 个测试（0 failure/error/skipped）。隔离
Paper 测试服上的 Mineflayer 协议级测试已覆盖四玩法、dataVersion 3 干净重启及
中文创建向导；它不会渲染资源包，也不能替代真人客户端的模型、字体、音效和像素
对齐验收。

强制终止进程后的账本恢复路径已修复启动时序，但最新修复尚未重新取得
`--hard-kill-recovery` 通过工件，发布前仍需复验。不要把 Maven 或 Mineflayer
通过等同于生产服完整验收。
