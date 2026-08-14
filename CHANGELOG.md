# 更新日志

## 未发布 - Paper 1.21.4 兼容升级

- 构建基线切换为 Paper API 1.21.4，`plugin.yml` 精确声明 `api-version: 1.21.4`。
- 修复 1.21.4 中 `EntityDismountEvent` 的 Bukkit API 包名迁移。
- WorldGuard/WorldEdit 改用官方 1.21.4 兼容依赖，不再依赖仓库内旧版 JAR。
- Mineflayer 协议与隔离 Paper 启动器升级到 1.21.4，并支持环境变量指定测试服。
- CraftEngine 文档改为 1.21.4 原生 `item_model` 与 `pack_format: 46` 生成路径。
- 修复老虎机 Wild 无法替代类别奖励成员及 `exact` 组合匹配方向错误，并新增判奖回归测试。

## 1.0.10-CE - 2026-08-12

### 新功能

- 将全部赌场自定义物品迁移为 CraftEngine 稳定 ID，并提供完整 CE 内容包。
- 新增中文 BossBar 机器创建向导、选择棒、粒子预览、旋转与安全撤销。
- 新增 SQLite WAL 资金账本、幂等下注/结算和管理员人工核对命令。
- 新增 dataVersion 3 机器仓库、实体 role/PDC、跨区块恢复和复制写事务。
- PlaceholderAPI 改为真正可选的反射桥接；WorldGuard 增加创建权限校验。

### 修复

- 修复 Crash 多玩家重启集合修改、兑现/爆点竞争和停服结算问题。
- 修复二十一点多人占位、退出锁桌、批量结算、座椅旋转与延迟清理串玩法问题。
- 修复老虎机结算空窗、动画任务串扰、背包清空和重复下注问题。
- 修复 Jackpot 列表刷新、库存关闭判断、赔率显示和结算生命周期问题。
- 修复 Vault 提供者尚未启用时恢复付款会进入 `UNKNOWN` 的启动时序问题。

### 文档与测试

- 新增中文安装、配置、迁移、验收和资金账本说明。
- 新增 JUnit 故障注入/玩法身份/数据仓库测试与 Mineflayer 协议级测试工具。

### 已知边界

- 2026-08-12 的最新 Maven 构建通过 19 个测试类、76 个用例（0 failure /
  0 error / 0 skipped）；Mineflayer `--hard-kill-recovery` 场景已实现，但当前不标记为
  已通过，需在隔离服务端复验。
- Mineflayer 不渲染资源包；CE 模型、字体、GUI 像素效果及原素材中可能存在的
  英文装饰字样，仍需真实客户端验收。
- 仓库当前未包含 `LICENSE`，本版本不对贴图、模型或 GUI 素材的转授权作出声明。
