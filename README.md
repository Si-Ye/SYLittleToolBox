# 一袖 · 随身工具集

一袖是原生 Android 工具箱。应用采用 Kotlin 与 Jetpack Compose，工具分别开发，统一从首页、分类、搜索和组合进入。

## 开发构建

需要 JDK 21、Android SDK Platform 36 与 Build Tools 36.0.0。Android Studio 或 `ANDROID_HOME` 可提供 SDK 路径。Windows 下也可在项目根目录的 `local.properties` 设置 `sdk.dir`。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 0.2.0-dev 功能

- 首页搜索、分类卡片与对应工具页、自建组合、首页排序及工具帮助。
- 默认石墨灰，九套明暗配色可在「主题色」子页实时切换。首次启动说明权限用途，权限页逐项显示系统授权状态。
- 即记支持桌面快捷方式、快捷设置磁贴、共享文本、本地草稿和历史记录。
- 字数统计支持中文、英文、字符、行数及字节口径。
- Wi‑Fi 探查提供接入点扫描、RTT 测距诊断，以及 GitHub、Google、X、YouTube 的 HTTPS 可达性检测。
- 回迹可用前台服务记录 GPS 轨迹，离线查看路线、停车点与直线距离，并保存楼层、车位和照片。
- 图片快转可从相册选取或分享导入，批量转换 JPG、PNG、WebP，可指定长边和每张文件的体积上限。
- 视频取声可从文件选取或分享导入，选择音轨，导出 AAC 原码流封装的 M4A，或选择声道后导出 PCM WAV。

MP3 导出、上下行测速、局域网设备核查、工具分包和应用内更新仍在首发计划中。详见[项目方案与实施计划](项目方案与实施计划.md)。

## 模块

`:app` 提供共用界面和工具目录，`:core:tool-api` 定义工具入口。工具模块位于 `tools/`，每个模块拥有自己的界面与数据处理逻辑。

源代码使用 [Apache-2.0](LICENSE)。
