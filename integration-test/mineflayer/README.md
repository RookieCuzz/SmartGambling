# Mineflayer 集成测试

该目录提供 Paper 1.20.1 的协议级黑盒测试。它会启动隔离服务端、连接多个
离线模式机器人，并检查 GUI、下注账本、并发点击、退出/重连、机器持久化和
中文创建向导。

## 环境

- Node.js 22+
- `npm ci`
- 本地隔离服目录 `server/`（已被 Git 忽略）
- Paper 1.20.1、CraftEngine 26.7.4、Vault、经济实现和待测 JAR

```powershell
cd integration-test/mineflayer
npm ci
node run.js --creation-guide-only
node run.js --persistence-only
node run.js --hard-kill-recovery
node run.js
```

可用专项参数以 `run.js` 为准。测试会把日志与报告写入 `artifacts/`，该目录也
不会提交到仓库。

Mineflayer 能验证网络协议、窗口槽位、聊天、BossBar、实体交互和服务端账本，
但不会真正下载并渲染资源包。CE 模型、字体与 GUI 像素效果仍需原版客户端验收。
原 GUI 背景中可能仍有英文装饰字样，这也不在机器人的验证能力内。

## 当前验证状态

- 2026-08-12 的最新 `mvn clean package` 通过 19 个测试类、76 个用例
  （0 failure / 0 error / 0 skipped）；后续以当次构建输出为准。
- `--hard-kill-recovery` 会强制终止隔离 Paper 进程以验证账本恢复。该场景当前
  **尚不宣称通过**，必须在干净隔离服务端重新执行并检查 `artifacts/`
  后，才能将它记为验收证据。
- 请勿将 `server/` 指向生产服务端；该工具会写入数据并在 hard-kill 模式下主动终止服务端进程。

此目录与项目根目录当前均未提供 `LICENSE`；测试工具的存在不代表对 CE
内容包中的贴图、模型或 GUI 素材授予任何再分发权利。
