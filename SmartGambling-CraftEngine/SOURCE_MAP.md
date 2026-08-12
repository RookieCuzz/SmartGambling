# 素材来源与转换映射

## 权威来源

- 用户提供的原始服务器归档；
- 归档中 `server.properties` 指定的服务器资源包；
- 资源包 SHA-1：`346a6bf5bdcef915c9053e62a2b8e17d2ad95062`
- 归档中的 SmartGambling 插件配置目录。

资源包标识和 SHA-1 直接取自归档的 `server.properties`，迁移输入经过哈希校验。
本文不公开原始下载地址或本机归档路径。

## 导入内容

- `assets/minecraft/models/item/casino/*.json`：42 个模型；
- `assets/minecraft/textures/item/casino/*.png`：60 张贴图和 GUI；
- `assets/minecraft/textures/item/logo/bank.png`：原货币图标；
- `assets/minecraft/font/default.json`：用于核对每个 GUI 字符的尺寸、ascent
  和原始码点。

导入时把模型路径迁到：

```text
assets/smartgambling/models/item/casino
assets/smartgambling/textures/item/casino
```

JSON 内的 `item/casino/...` 纹理引用改为
`smartgambling:item/casino/...`，几何、UV、display 变换和作者数据保持原样。

## 原包路由

所有赌场 CMD 都位于原包的
`assets/minecraft/models/item/stick.json`，不是 PAPER：

- 501–508：seven、cherries、lemon、plum、melon、star、orange、grapes；
- 509：blackjacktable；510：carddeck；511：chair；
- 512：crashgame；513：lotterymachine；514：slotmachine；
- 515–527：红色 Ace、2–10、J、K、Q；
- 528–540：黑色 Ace、2–10、J、K、Q；
- 541：flippedcard；542：crash_stopped。

这些数字仅用于证明素材对应关系。CE 配置没有
`custom_model_data`，CraftEngine 会自动分配模型编号；SmartGambling 只按
`smartgambling:*` ID 构建物品。

早期从 ItemsAdder 导入的兼容图标和透明 PNG 仍作为未接线源文件保留，但
正式赌场物品、模型和 GUI 已全部切换到经哈希验证的原始服务器材质包。

本转换只记录技术迁移关系，不声明原素材为开源，不变更其版权与许可，也不
授予任何额外的复制或分发权。部署者应自行确认其对相关素材的使用权限。

部署说明见[CE 资源包 README](README.md)，项目总览与配置字段分别见
[项目 README](../README.md)和[配置指南](../CONFIGURATION.md)。
