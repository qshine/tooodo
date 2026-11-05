#!/usr/bin/env bash
# 打包并发布到 Gitee，手机上「设置 → 检查更新」就能拿到。
#   scripts/release.sh "这一版的更新说明"
# 发布前先在 app/build.gradle.kts 里调高 versionCode（和 versionName）。
# Gitee 私人令牌放在 ~/.gitee/token（需要 projects 权限），脚本不会打印它。
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:?用法: scripts/release.sh \"更新说明\"}"
TOKEN_FILE="$HOME/.gitee/token"
[ -s "$TOKEN_FILE" ] || { echo "缺少 $TOKEN_FILE" >&2; exit 1; }
TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"

# 仓库地址只在 Updater.kt 里写一次
REPO="$(sed -n 's/.*const val REPO = "\(.*\)".*/\1/p' app/src/main/java/com/tooodo/app/Updater.kt)"
case "$REPO" in OWNER/*) echo "先在 Updater.kt 里把 REPO 改成你的 Gitee 仓库" >&2; exit 1;; esac
CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)"
NAME="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
TAG="v$NAME"
API="https://gitee.com/api/v5/repos/$REPO"

# 同一个 versionCode 不能发两次，否则手机认不出是新版本
# 还没有发布过时这里是 404，不算错
LATEST="$(curl -fsS "$API/releases/latest" 2>/dev/null || true)"
LATEST_TAG="$(printf '%s' "$LATEST" | python3 -c 'import sys,json
try: print((json.load(sys.stdin) or {}).get("tag_name",""))
except Exception: print("")')"
if [ "$LATEST_TAG" = "$TAG" ]; then echo "$TAG 已经发布过了，先调高版本号" >&2; exit 1; fi

echo "打包 $NAME ($CODE)…"
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew -q testReleaseUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk Tooodo.apk

OUT="build/release"
rm -rf "$OUT"; mkdir -p "$OUT"
APK="Tooodo-$NAME.apk"
cp Tooodo.apk "$OUT/$APK"
SHA="$(shasum -a 256 "$OUT/$APK" | cut -d' ' -f1)"
SIZE="$(stat -f%z "$OUT/$APK")"
CODE="$CODE" NAME="$NAME" APK="$APK" SHA="$SHA" SIZE="$SIZE" NOTES="$NOTES" python3 - > "$OUT/version.json" <<'PY'
import json, os
print(json.dumps({
    "versionCode": int(os.environ["CODE"]), "versionName": os.environ["NAME"], "apk": os.environ["APK"],
    "sha256": os.environ["SHA"], "size": int(os.environ["SIZE"]), "notes": os.environ["NOTES"],
}, ensure_ascii=False, indent=2))
PY

BRANCH="$(curl -fsS "$API?access_token=$TOKEN" | python3 -c 'import sys,json; print(json.load(sys.stdin)["default_branch"])')"
echo "创建发布 $TAG…"
RELEASE_ID="$(curl -fsS -X POST "$API/releases" \
  --data-urlencode "access_token=$TOKEN" --data-urlencode "tag_name=$TAG" --data-urlencode "name=Tooodo $NAME" \
  --data-urlencode "body=$NOTES" --data-urlencode "target_commitish=$BRANCH" --data-urlencode "prerelease=false" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"

for f in "$OUT/$APK" "$OUT/version.json"; do
  echo "上传 $(basename "$f")…"
  curl -fsS -X POST "$API/releases/$RELEASE_ID/attach_files" -F "access_token=$TOKEN" -F "file=@$f" > /dev/null
done
echo "完成：https://gitee.com/$REPO/releases/tag/$TAG"
