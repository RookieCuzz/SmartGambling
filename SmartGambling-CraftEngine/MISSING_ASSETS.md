# 缺失素材状态：已解决

之前的缺失报告只扫描了 ItemsAdder 数据，因此没有找到 21 点、Crash、
Jackpot 和完整赌场 GUI。`Smart Survival Server.zip` 的
`server.properties` 表明实际资源包通过 mc-packs 外链下发；下载文件 SHA-1
与配置值 `346a6bf5bdcef915c9053e62a2b8e17d2ad95062` 完全一致。

从这个已验证的原包中恢复了：

- STICK/501–508：8 个老虎机符号；
- STICK/509–514：21 点桌、牌堆、赌场椅、Crash、Jackpot、老虎机模型；
- STICK/515–540：26 张红/黑牌面；
- STICK/541：牌背；
- STICK/542：Crash 玩家停止状态；
- U+E408–U+E40E、U+E66C、U+E6CA：完整游戏 GUI；
- U+E63E–U+E641：四种机器悬浮标题；
- U+E307：货币图标。

当前核心玩法没有待补的模型或贴图。`smartgambling:gui_transparent` 是原包
STICK/3 的透明点击层；可见按钮已经画在 256x256 GUI 背景上，因此它保持
透明是正确行为。

新增德州扑克后，红桃/黑桃继续使用上述红/黑牌面；原包未提供的 26 张
方块/梅花已按同一 32×32 像素布局补齐。它们属于新增派生素材，不冒充为
原资源包内容，生成参考与转换说明见 `SOURCE_MAP.md`。

旧 ItemsAdder 的 `lucky-gem`、`empty-slot` 和 `forge` 外观只是编号碰撞，
不是赌博素材，仍然不会迁入正式 CE 映射。
