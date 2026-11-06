# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Tooodo is a single-module Android app (Kotlin, plain Views/XML, no Compose): a todo list that shows unfinished todos on the lock screen every time the screen turns on, before the user unlocks. UI copy is Chinese. Screen designs live on a Claude Design canvas: https://claude.ai/artifact/HNuLWbMHH7e8SMYdL77NSd

## Repositories

- Gitee (public): https://gitee.com/qqinling/tooodo.git, git remote `origin`. Its releases are also where in-app updates come from (`Updater.REPO`), so renaming or moving it breaks updates for installed builds.
- GitHub (public mirror): https://github.com/qshine/tooodo, git remote `github`. Push to both remotes (`git push origin main && git push github main --tags`). `main` tracks `origin`. Releases and in-app updates stay on Gitee only, unless `Updater.REPO` and `scripts/release.sh` are changed.

## Build & install

The toolchain was installed via Homebrew; nothing is on PATH by default.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk Tooodo.apk   # deliverable kept at repo root
```

- SDK: `/opt/homebrew/share/android-commandlinetools` (set in `local.properties`); adb is `$SDK/platform-tools/adb`.
- Release builds are signed with the **debug key** (`~/.android/debug.keystore`) so they can be sideloaded and upgraded in place. Losing that key means the phone must uninstall (and lose its todos) to upgrade.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every APK handed to the user. `versionName` restarted at `0.1.0`, but `versionCode` must only ever increase (it was already 10 before the reset), or the phone can't upgrade in place.

## In-app updates

- 设置 → 检查更新 (`Updater` + `InstallReceiver`) reads the latest release of the Gitee repo (`Updater.REPO` = `qqinling/tooodo`), downloads the APK attached to it, checks the sha256 from the attached `version.json`, and installs it through `PackageInstaller`. Nothing checks in the background, by design.
- Builds before `REPO` was set (versionCode ≤ 11) can't find updates, so the first build with it has to be installed via adb.
- A Gitee release needs a branch to point at, so the repo must have at least one pushed commit before the first `scripts/release.sh`.
- Publish with `scripts/release.sh "更新说明"` after bumping the version. It reads the Gitee token from `~/.gitee/token` and never prints it.
- Unit tests cover only the pure date logic (`TodoStore.pendingOn/historyOn/upcomingOn`, `Dates`): `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testReleaseUnitTest`. Keep that logic free of Android types so it stays testable. No lint config; everything else is verified on a device or emulator (below).

## Testing on emulator / real phone

- AVD `tooodo` (Android 15, arm64): `$SDK/emulator/emulator -avd tooodo -no-window -no-audio -no-snapshot -gpu swiftshader_indirect`, then wait for `sys.boot_completed=1`.
- The release build is not debuggable (no `run-as`). Diagnose through logcat: the service logs under tag `Tooodo` (`broadcast …`, `screen on: keyguardLocked=… deviceSecure=…`, `wake visible=…`, `task removed, scheduling restart`). Check for `START … WakeActivity … result code=` (2 = existing task brought to front, 0 = new instance).
- Emulator pitfalls hit before: the screen never auto-times out while "plugged in" (`UndimDetectorWakeLock`), so use the power key instead of waiting for a timeout. Sending Enter/taps while `WakeActivity` is up completes todos, which turns the next wake into a silent skip (no pending todos). Simulate a PIN lock with `locksettings set-pin 1234`.
- Real-device logs are captured with `adb logcat -b main,system,events,crash -v time > logs/phone_<ts>.txt` (`logs/` is git-ignored). Running adb from the sandbox may need the sandbox disabled to reach the adb server.

## Architecture: how the lock-screen reminder works

The core feature spans several components and works around OEM (realme/OPPO/ColorOS, vivo) background restrictions. Read these together before changing any of them:

- **`ScreenWatchService`**: a foreground service (`specialUse`). `SCREEN_ON`/`OFF` can't be declared in the manifest, so this service registers them dynamically.
  - On `SCREEN_ON` it shows the reminder only if `isKeyguardLocked || isDeviceSecure`. The second condition covers the post-timeout grace period where the keyguard isn't up yet. Devices with no lock screen are skipped. It also skips when there are no pending todos and `skipWhenEmpty` is on.
  - It starts `WakeActivity` directly (allowed via the `SYSTEM_ALERT_WINDOW` BAL exemption). After 1.5s, if `WakeActivity.visible` is still false and the user hasn't unlocked, it posts a full-screen-intent notification as a fallback.
  - `ScreenWatchService.sync(context)` is the single entry point to start, stop or refresh the service and to (un)schedule the watchdog. Call it after any todo or settings change.
- **`WakeActivity`**: `singleInstance`, its own `taskAffinity`, `showWhenLocked`, translucent theme.
  - ColorOS blocks *creating* activities from the background but allows bringing an *existing* task to front. So this activity is never finished: dismiss, Back and unlock (`USER_PRESENT`) all call `moveTaskToBack`.
  - It is pre-created invisibly via `WakeActivity.warmUp(context)` (extra `warm_up`). This happens from `MainActivity.onResume` and from the service on `SCREEN_OFF` when `alive` is false.
  - Do **not** add `excludeFromRecents`: the system then trims the hidden background task and the warm instance dies.
- **Recovery after the process is killed**:
  - `onTaskRemoved` (recents swipe) schedules a 1s exact alarm, and `RestartReceiver` restarts the service.
  - `WatchdogJob` is a persisted 15-minute periodic job that calls `sync`.
  - `BootReceiver` handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
  - None of these survive a **force-stop**. On realme the alarm restart also needs the user to enable 自启动 (otherwise logcat shows `OplusAppStartupManager: prevent start`), plus 允许后台行为.
- **`TodoStore`**: SharedPreferences holding a JSON array of todos plus flags. `wakeEnabled` defaults to **true**. `permPrompted` makes the first-add permission dialog in `MainActivity` (notifications, then overlay settings) appear only once.
- **`Perms`**: checks for the notification and overlay permissions. **`Insets.padForSystemBars()`** must be applied to every activity root, because targetSdk 35 forces edge-to-edge.
