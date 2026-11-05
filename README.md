# Tooodo

一个把未完成待办放在锁屏上的 Android 待办应用：每次亮屏、解锁之前，先看一眼还有哪些事没做。

## 功能

- **锁屏提醒**：亮屏时在锁屏上列出未完成的待办，点一下即可完成，解锁后自动消失。全部做完时可以不再打扰。
- **安排日期**：单次待办默认今天，也可以一键排到明天、后天，或在日历里选任意一天。排到以后的放在「以后要做」，到了那天自动进入「未完成」。
- **周期任务**：每天，或每周的某几天，每天各自完成。
- **历史任务**：以前没做完的单次待办按日期归档，仍会在锁屏上提醒，可以一键清空。
- **编辑和撤销**：点一下待办可以改内容和日期；删除后几秒内可以撤销。
- **复制和分享**：长按进入多选，复制到剪贴板或分享到微信等应用。
- **检查更新**：在「设置 → 检查更新」里下载并安装新版本。

## 安装

1. 在 [发行版](https://gitee.com/qqinling/tooodo/releases) 里下载最新的 `Tooodo-x.y.z.apk`，在手机上打开安装（需要允许浏览器或文件管理器「安装未知应用」）。
2. 添加第一条待办时，按提示允许通知，并打开「显示在其他应用上层」。

要求 Android 8.0 及以上。

### 国产系统需要额外设置

小米、华为、OPPO / realme（ColorOS）、vivo 等系统会限制后台运行，锁屏提醒可能不出现。请在应用详情里：

- 允许「自启动」「后台弹出界面」「锁屏显示」，以及「允许后台行为」；
- 在最近任务里给 Tooodo 加锁，避免「一键清理」把它强制停止。

被强制停止后，要重新打开一次 Tooodo 提醒才会恢复。

### 更新

在「设置 → 检查更新」里点「检查」。第一次更新时需要允许 Tooodo「安装未知应用」，之后由系统弹出安装确认。

## 从源码构建

需要 JDK 17 和 Android SDK（compileSdk 35）。SDK 路径写在 `local.properties` 的 `sdk.dir`。

```bash
./gradlew testReleaseUnitTest   # 单元测试
./gradlew assembleRelease       # 输出 app/build/outputs/apk/release/app-release.apk
```

发布包用 debug 签名，方便侧载和覆盖升级。Android 只允许同一签名覆盖安装，换签名后需要先卸载（会丢失待办）。

### 发布新版本

1. 在 `app/build.gradle.kts` 里调高 `versionCode`（只能增加）和 `versionName`。
2. 把 Gitee 私人令牌（`projects` 权限）放在 `~/.gitee/token`。
3. 运行：

```bash
scripts/release.sh "这一版的更新说明"
```

脚本会跑测试、打包，并在 Gitee 上创建发行版，附件是 APK 和 `version.json`（版本号、文件校验值、更新说明），应用内的「检查更新」就是读这两个文件。

## 代码结构

| 文件 | 作用 |
|---|---|
| `ScreenWatchService` | 前台服务，监听亮屏 / 灭屏，决定是否弹出锁屏提醒 |
| `WakeActivity` | 锁屏上显示的待办列表 |
| `MainActivity` | 主界面：新建、编辑、分栏列表、多选 |
| `TodoStore` | 待办存储（SharedPreferences）和按日期分类 |
| `Updater` | 检查更新、下载、安装 |
| `WatchdogJob` / `RestartReceiver` / `BootReceiver` | 进程被杀、重启、升级后把服务拉起来 |

更详细的设计说明（包括各家系统后台限制的绕过方式）见 [CLAUDE.md](CLAUDE.md)。
