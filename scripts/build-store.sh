#!/usr/bin/env bash
# 打包应用商店版（oppo 渠道：不联网、没有应用内更新、正式签名），输出 Tooodo-store.apk。
#   scripts/build-store.sh
# 正式签名在 ~/.tooodo/release-signing.properties 和 ~/.tooodo/tooodo-release.jks，不在仓库里。
# 这两个文件丢了，商店里的应用就再也无法更新，务必另外备份。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -s "$HOME/.tooodo/release-signing.properties" ] || { echo "缺少 ~/.tooodo/release-signing.properties（正式签名）" >&2; exit 1; }

JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew -q testOppoReleaseUnitTest assembleOppoRelease
APK=app/build/outputs/apk/oppo/release/app-oppo-release.apk

SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
BT="$(ls -d "$SDK"/build-tools/* | tail -1)"
CERT="$(JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH "$BT/apksigner" verify --print-certs "$APK" | grep 'certificate DN')"
case "$CERT" in *"Android Debug"*) echo "商店版不能用 debug 签名：$CERT" >&2; exit 1;; esac
if "$BT/aapt2" dump permissions "$APK" | grep -qE 'INTERNET|REQUEST_INSTALL_PACKAGES'; then
  echo "商店版不应包含联网或安装应用权限" >&2; exit 1
fi

cp "$APK" Tooodo-store.apk
"$BT/aapt2" dump badging Tooodo-store.apk | sed -n 1p
echo "$CERT"
echo "完成：Tooodo-store.apk"
