# Android 用水客户端

## 功能

- 登录、配置与设备列表分离，登录失效后自动重登并重试一次。
- 支持导入最近 3 台设备、扫码添加、状态刷新及一键启动用水。
- 支持余额查询、设备滑动删除与撤销。
- 优先使用 EncryptedSharedPreferences 保存配置；加密初始化失败时回退到普通 SharedPreferences。

## 构建

使用 Android Studio 打开本目录，或准备 JDK 17、Android SDK 34 后运行：

```bash
./gradlew assembleDebug
# Windows：gradlew.bat assembleDebug
```

最低 Android 版本：8.0（API 26）。产物：`app/build/outputs/apk/debug/app-debug.apk`。

云端构建见 [构建说明](../docs/cloud-build.md)。项目概述见 [根目录 README](../README.md)。

## 配置说明

登录后保存 token、账户 ID 和区域 ID。默认使用登录返回的账户 ID；如确需手动配置覆盖值，只能填写本人或获授权的账户 ID。

启动用水前会查询设备状态，阻止对离线、占用中或已在当前账号下使用的设备重复发起请求。实际计费和设备控制以服务端为准。
