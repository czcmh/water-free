# 云端构建

## GitHub Actions

工作流：[Android Debug APK](../.github/workflows/android-debug.yml)。

1. 推送 `android-app/` 的代码变更会自动触发构建，也可进入仓库 **Actions → Android Debug APK → Run workflow** 手动运行。
2. 工作流准备 JDK 17、Android SDK 34，并执行 `./gradlew assembleDebug`。
3. 成功后在该次运行的 **Artifacts** 中下载 `min-water-debug-apk`。

产物是调试 APK，仅用于开发和测试，不是正式签名的发布包。

## Codemagic（可选）

连接仓库，选择 Configuration as code，使用根目录的 [codemagic.yaml](../codemagic.yaml) 运行 `android-debug`，完成后从 Artifacts 下载 APK。

## 安全边界

构建不需要业务账号、密码或 token，也不需要提供正式签名证书。不要上传 `.env`、`login.json`、本地配置、签名密钥或真实用户数据。

后续如需正式发布，应通过平台的 Secrets 管理签名凭据，不要写入源码或打印到构建日志。
