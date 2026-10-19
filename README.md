# 多玩 Android 纯 Java 工具

一个包含两个独立 Android 应用的 Gradle 工程，用于浏览、预览多玩《我的世界》资源并处理部分加密资源。界面使用 Material 3 / Material You 动态配色，核心业务以 Java 实现。

## 应用

### 资源解密器（`decryptor`）

- 通过 Android 文件选择器选择目录并批量处理资源。
- 支持标准头部容器中的 AES-256-CBC 解密、ZIP 内容处理及部分地图数据库 WAL 修复。
- 提供处理日志导出和性能统计。

### 皮肤预览器（`previewer`）

- 浏览文件夹中的标准皮肤与多玩 4D 皮肤，并预览模型。
- 支持 Steve / Alex 标准皮肤模型切换和文件夹内前后切换。
- 导出透明背景正等轴测 PNG（2048 × 2048）。
- 导出 4D 模型六面图：分开保存六张图片，或合并为 3 × 2 排列的一张图片。

## 环境要求

- Android Studio 或兼容的 Android Gradle 构建环境
- JDK 17 或更新版本
- Android SDK Platform 35
- Windows 可直接使用仓库附带的 `gradlew.bat`；macOS / Linux 使用 `./gradlew`

## 构建

在仓库根目录执行：

```powershell
.\gradlew.bat :decryptor:assembleDebug :previewer:assembleDebug
```

macOS / Linux：

```bash
./gradlew :decryptor:assembleDebug :previewer:assembleDebug
```

APK 输出位置：

- `decryptor/build/outputs/apk/debug/decryptor-debug.apk`
- `previewer/build/outputs/apk/debug/previewer-debug.apk`

仅构建某个应用时，把 Gradle 任务替换为 `:decryptor:assembleDebug` 或 `:previewer:assembleDebug`。

## 安装

连接已开启 USB 调试或无线调试的 Android 设备后：

```bash
adb install -r decryptor/build/outputs/apk/debug/decryptor-debug.apk
adb install -r previewer/build/outputs/apk/debug/previewer-debug.apk
```

无线调试可先按 Android 系统提示完成配对，再连接设备显示的调试地址：

```bash
adb pair <手机IP>:<配对端口>
adb connect <手机IP>:<连接端口>
```

## 已知限制

解密器实现覆盖标准头部容器、内嵌 16 字节 key、AES-256-CBC、SHA-1 `EVP_BytesToKey(count=5)` 与长度/Base64 分段数据。专用 `.modpkg` `decryptModule` 变换尚未还原；此类条目可能无法解密。请先用自己有权处理的样本验证输出，并保留原始文件备份。

项目不包含预编译 APK、构建缓存、私有测试皮肤样本或本地维护记录。应用只处理用户通过系统文件选择器授权的数据。

## 技术栈

- Java、Android SDK、Gradle
- AndroidX DocumentFile
- Material Components for Android
- OpenGL ES 1.x 离屏渲染（皮肤预览器）
