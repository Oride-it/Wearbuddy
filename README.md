# Wearbuddy

独立运行在 Wear OS 上的 WorkBuddy 客户端，使用官方 Wear Compose Material 3 / Material 3 Expressive 组件。

## 功能

- 云端会话、任务、产物下载与本地预览。
- 多账号保存与切换，独立 Cookie 存储。
- 内置文件夹和图片选择器，无需系统文件管理器。
- GitHub Release 更新检测、更新说明、下载进度与系统安装。

## 安装

从本仓库 Releases 下载名称为 `Wearbuddy-v版本号-release.apk` 的正式安装包。
首次安装含更新功能的版本后，后续正式版本可通过应用启动检查或“我的 → 检测更新”获取。
下载包会验证 SHA-256、包名、版本和签名；Android 仍会要求用户确认安装。
部分 Wear OS 设备没有系统安装器或未知来源授权页面，此时需要通过 ADB 安装。

## 构建

需要 JDK 25、Android SDK 37.0（sdkmanager 包名 `platforms;android-37.0`）和 Android SDK Build Tools 36.0.0。

```sh
./gradlew :wear:testDebugUnitTest :wear:assembleDebug
```

正式版使用 `keystore.properties.example` 中的字段配置本机 `keystore.properties`，或设置对应的
`WEARBUDDY_STORE_FILE`、`WEARBUDDY_STORE_PASSWORD`、`WEARBUDDY_KEY_ALIAS`、`WEARBUDDY_KEY_PASSWORD` 环境变量。

```sh
./gradlew :wear:testDebugUnitTest :wear:assembleRelease
```

正式版开启 R8 混淆、代码优化/收缩及资源压缩。私钥和密码不在仓库中；自行签名的构建不能覆盖安装官方签名版。

## 后续发布

维护者可以在本机登录 GitHub CLI 后运行 `scripts/publish-release.ps1 -Tag v版本号`。也可通过 GitHub Actions 自动构建：配置以下加密 Secrets，更新版本号及
`docs/releases/版本号.md`，然后推送相应的 `v版本号` 标签：

- `ANDROID_KEYSTORE_BASE64`：原正式版 JKS 文件的 Base64。
- `ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

工作流会测试并构建正式 APK、生成 SHA-256 校验文件，再发布 Release。签名必须始终与最初版本一致。
更新源在 `wear/build.gradle.kts` 的 `UPDATE_REPOSITORY` 中配置；Fork 后请修改为自己的仓库。

## 说明

非 WorkBuddy 官方应用。云端协议依赖服务端兼容性；账户凭据仅保存在本机应用私有目录。
项目不包含任何使用者的账号、Cookie、签名私钥或本地配置。

## 许可证

MIT，见 LICENSE。

官方仓库已配置加密签名 Secrets。推送版本标签会自动构建发布；已发布版本不会被重复运行覆盖。
也可在 Actions 的 Signed release 中选择 Run workflow 并输入已有标签，仅验证已有正式发行版。
