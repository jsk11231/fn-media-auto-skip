# 飞牛影视自动跳过 V1

一个运行在 fnOS 上的伴随应用。它读取飞牛影视媒体库，对同一季的多集进行音频指纹、章节和黑场分析，得到保守的片头/片尾建议值，再通过飞牛影视官方接口保存整季设置。

## V1 功能

- 自动发现电视节目、剧季和剧集文件。
- 多集 Chromaprint 音频指纹比对，辅以章节和黑场检测。
- 使用中位数、一致率、样本覆盖率和安全余量聚合整季结果。
- 默认仅生成建议；可选择高置信度结果自动写入。
- 默认保护已有手动设置；可显式允许覆盖。
- 定时扫描新入库剧集。
- 实时显示逐季分析进度，并可按一致率筛选后一键应用达标剧季。
- 飞牛密码和 token 使用本机随机 AES-256-GCM 密钥加密保存。

## 安装与使用

1. 在 fnOS 应用中心手动安装生成的 `fn-media-auto-skip.fpk`。
2. 系统会自动安装 `java-21-openjdk` 运行时依赖。
3. 从 fnOS 桌面打开“飞牛影视自动跳过”。
4. 填写飞牛地址、用户名和密码，点击“测试并保存连接”。
5. 首次使用保持“自动应用”关闭，扫描后抽查建议值，再按需开启自动应用。

可选的豆瓣弹幕搜索不再内置第三方标识。如确实需要此功能，请在运行环境中设置
`DOUBAN_API_KEY` 和 `DOUBAN_WECHAT_APP_ID`；自动跳过功能不依赖这两个变量。

服务端口默认为 `5366`。应用需要读取媒体文件，因此 V1 以 fnOS root 应用身份运行；分析过程只读媒体文件，不修改文件内容或飞牛影视数据库。

## 构建

```bash
./gradlew :fly-narwhal-web:bootJar -x test -x :fly-narwhal-web:buildUpdaterBinaries
fnpack build --directory packaging/fn-media-auto-skip
```

## 许可证与来源

本项目基于 [fly-narwhal-server](https://github.com/FNOSP/fly-narwhal-server) 修改，遵循 GNU Affero General Public License v3。使用或提供网络服务时应同时提供对应源代码。
