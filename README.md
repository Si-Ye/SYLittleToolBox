# 一袖 · 随身工具集

一袖是原生 Android 工具箱。应用采用 Kotlin 与 Jetpack Compose，工具分别开发，统一从首页、分类、搜索和组合进入。

## 开发构建

需要 JDK 21、Android SDK Platform 36 与 Build Tools 36.0.0。Android Studio 或 `ANDROID_HOME` 可提供 SDK 路径。Windows 下也可在项目根目录的 `local.properties` 设置 `sdk.dir`。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 开发版功能

- 首页搜索、分类、自建组合、主题与密度设置、首页排序及工具帮助。
- 即记支持桌面快捷方式、快捷设置磁贴、共享文本、本地草稿和历史记录。
- 字数统计支持中文、英文、字符、行数及字节口径。
- Wi‑Fi 探查提供接入点兼容性、权限、扫描以及 RTT 测距的诊断入口。观测结果取决于真机与接入点。

首发范围与真机验收见[项目方案与实施计划](项目方案与实施计划.md)。开发版会在目录中标明正在开发的回迹、图片快转和视频取声。

## 模块

`:app` 提供共用界面和工具目录，`:core:tool-api` 定义工具入口。工具模块位于 `tools/`，每个模块拥有自己的界面与数据处理逻辑。

源代码使用 [Apache-2.0](LICENSE)。
