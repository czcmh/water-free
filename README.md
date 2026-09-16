# Water Free｜极简用水助手

基于 Kotlin 的非官方 Android 用水客户端，把常用设备管理和启动用水集中到一个简洁界面。

## 项目经历 · STAR

- **S（背景）**：日常使用校园用水设备时，需要重复查找设备、扫码和进入操作页面。
- **T（目标）**：做一个轻量客户端，保留登录、设备管理与启动用水的核心流程，减少重复操作。
- **A（行动）**：先用 Python 验证接口流程，再以 Kotlin + Material Components 实现 Android 应用；接入扫码、最近设备导入、余额查询、设备状态检查，以及登录失效后的自动重登重试，并配置 GitHub Actions 构建。
- **R（成果）**：实现从登录、添加设备到一键启动用水的完整客户端流程，支持设备状态刷新、滑动删除与撤销；保留 Python 原型和命令行工具，方便调试与维护。暂无量化效率或用户规模数据。

## 技术栈

Kotlin · Android ViewBinding · Coroutines · OkHttp · Gson · ZXing · Python 标准库

## 快速开始

使用 Android Studio 打开 `android-app/`，准备 **JDK 17、Android SDK 34**（最低支持 Android 8.0），然后运行应用，填写本人账号登录。

```bash
cd android-app
./gradlew assembleDebug
# Windows：gradlew.bat assembleDebug
```

APK 输出：`android-app/app/build/outputs/apk/debug/app-debug.apk`。也可通过 [GitHub Actions](docs/cloud-build.md) 构建并下载。

## 项目结构

```text
android-app/   Android 主应用
app/           Python Tkinter 桌面原型
tools/         Python 命令行调试工具
docs/          云端构建说明
.github/       GitHub Actions 工作流
build.ps1      Windows 构建脚本
```

Python 原型使用 Python 3.10+（需 Tkinter）：`python app/min_water_app.py`。

## 使用与安全

- 仅用于本人账号及获授权设备，遵守服务方规则；项目名称不代表免费用水或免除正常计费。
- 依赖第三方服务，接口兼容性与可用性不作保证。
- Android 优先使用加密配置存储，初始化失败时会回退到普通存储；Python 原型配置为明文，请妥善保管。
- 账号密码、登录快照、原始调研记录、签名文件和安装包均不纳入版本管理。
