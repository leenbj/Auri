#!/usr/bin/env bash
# 把已准备好的模型与示例音频放进调试宿主的应用私有目录（模拟器与真机用同一个脚本）。
#
# 前置：prepare_model.py 已运行；debug 版 APK 已安装（run-as 只对已安装的 debuggable 应用有效）。
# 做法：adb push 到 /data/local/tmp 下带进程号的暂存目录 → run-as 先完整复制进应用私有目录里的暂存目录，
#       复制成功之后才换掉旧的 files/models/<model_id>/<model_version>/ 与 files/samples/（中途失败，旧模型原样保留）→
#       删掉所有暂存。模型放应用私有目录，校验之后别的进程换不了文件。
#
# 用法：install_on_device.sh [清单文件]        （多台设备时先 export ANDROID_SERIAL=<序列号>）
# 不要对同一台设备并发运行两份：最后换目录那一步没有加锁。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
MANIFEST="${1:-$REPO_ROOT/apps/android/manifests/sensevoice-small-int8-2024-07-17.manifest.json}"
CACHE="${AURI_CACHE_DIR:-$HOME/.cache/auri}"
APP_ID="app.auri.android"
# macOS 上 adb 的 USB 初始化可能卡在 IOKit（见 README 的“已知问题”）；固定走 libusb 后端可避开
export ADB_LIBUSB="${ADB_LIBUSB:-1}"

# 名字要拼进交给设备 shell 的命令，所以走 prepare_model.load_manifest 的同一套格式校验（不合格就退出，不重复写一份正则）。
# 进程替换里失败不会让 set -e 生效，但此时 read 读到 EOF 会返回非零，脚本同样在这里停下。
read -r MODEL_ID MODEL_VERSION < <(python3 - "$HERE" "$MANIFEST" <<'PY'
import pathlib, sys
sys.path.insert(0, sys.argv[1])
import prepare_model
manifest = prepare_model.load_manifest(pathlib.Path(sys.argv[2]))
print(manifest["model_id"], manifest["model_version"])
PY
)
MODEL_DIR="$CACHE/models/$MODEL_ID/$MODEL_VERSION"
SAMPLES_DIR="$CACHE/samples"
[ -d "$MODEL_DIR" ] || { echo "没有 ${MODEL_DIR}：先运行 prepare_model.py" >&2; exit 1; }
[ -d "$SAMPLES_DIR" ] || { echo "没有 ${SAMPLES_DIR}：先运行 prepare_model.py" >&2; exit 1; }
adb get-state >/dev/null || { echo "没有可用的 adb 设备" >&2; exit 1; }
adb shell pm path "$APP_ID" >/dev/null || { echo "设备上没有安装 ${APP_ID}：先安装 debug APK（见 README）" >&2; exit 1; }

# 暂存目录带进程号：固定路径会让两次同时运行互相覆盖
REMOTE_MODEL="/data/local/tmp/auri-model-$$"
REMOTE_SAMPLES="/data/local/tmp/auri-samples-$$"
STAGE="files/.install-$$"
cleanup() { adb shell rm -rf "$REMOTE_MODEL" "$REMOTE_SAMPLES" >/dev/null 2>&1 || true; }
trap cleanup EXIT

cleanup
adb push "$MODEL_DIR/." "$REMOTE_MODEL" >/dev/null
adb push "$SAMPLES_DIR/." "$REMOTE_SAMPLES" >/dev/null

# 命令从标准输入交给设备上的 sh：避免 adb shell 对引号与空格的二次解析。
# cp -R 源/. 目标/ 递归复制并保留清单里的子目录；旧目录先改名 .old，新目录就位后才删，换的过程出错旧模型还在。
adb shell run-as "$APP_ID" sh <<EOF
set -e
rm -rf files/.install-*
mkdir -p $STAGE/models $STAGE/samples
cp -R $REMOTE_MODEL/. $STAGE/models/
cp -R $REMOTE_SAMPLES/. $STAGE/samples/
mkdir -p files/models/$MODEL_ID
# .old 只有在对应的正式目录还在时才是垃圾；正式目录不在、.old 在，是上次换到一半被打断，它是旧的唯一一份，不能删
[ ! -e files/models/$MODEL_ID/$MODEL_VERSION ] || rm -rf files/models/$MODEL_ID/$MODEL_VERSION.old
[ ! -e files/samples ] || rm -rf files/samples.old
[ ! -e files/models/$MODEL_ID/$MODEL_VERSION ] || mv files/models/$MODEL_ID/$MODEL_VERSION files/models/$MODEL_ID/$MODEL_VERSION.old
mv $STAGE/models files/models/$MODEL_ID/$MODEL_VERSION
[ ! -e files/samples ] || mv files/samples files/samples.old
mv $STAGE/samples files/samples
rm -rf files/models/$MODEL_ID/$MODEL_VERSION.old files/samples.old $STAGE
EOF

echo "已安装到 $APP_ID 的私有目录："
adb shell run-as "$APP_ID" ls -l "files/models/$MODEL_ID/$MODEL_VERSION" files/samples
