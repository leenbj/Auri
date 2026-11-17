#!/usr/bin/env bash
# 把已准备好的模型与示例音频放进调试宿主的应用私有目录（模拟器与真机用同一个脚本）。
#
# 前置：prepare_model.py 已运行；debug 版 APK 已安装（run-as 只对已安装的 debuggable 应用有效）。
# 做法：adb push 到 /data/local/tmp → run-as 复制进 files/models/<model_id>/<model_version>/ 与 files/samples/ →
#       删掉 /data/local/tmp 里的副本。模型放应用私有目录，校验之后别的进程换不了文件。
#
# 用法：install_on_device.sh [清单文件]        （多台设备时先 export ANDROID_SERIAL=<序列号>）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
MANIFEST="${1:-$REPO_ROOT/apps/android/manifests/sensevoice-small-int8-2024-07-17.manifest.json}"
CACHE="${AURI_CACHE_DIR:-$HOME/.cache/auri}"
APP_ID="app.auri.android"
# macOS 上 adb 的 USB 初始化可能卡在 IOKit（见 README 的“已知问题”）；固定走 libusb 后端可避开
export ADB_LIBUSB="${ADB_LIBUSB:-1}"

read -r MODEL_ID MODEL_VERSION < <(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(m["model_id"], m["model_version"])' "$MANIFEST")
MODEL_DIR="$CACHE/models/$MODEL_ID/$MODEL_VERSION"
SAMPLES_DIR="$CACHE/samples"
[ -d "$MODEL_DIR" ] || { echo "没有 ${MODEL_DIR}：先运行 prepare_model.py" >&2; exit 1; }
[ -d "$SAMPLES_DIR" ] || { echo "没有 ${SAMPLES_DIR}：先运行 prepare_model.py" >&2; exit 1; }
adb get-state >/dev/null || { echo "没有可用的 adb 设备" >&2; exit 1; }
adb shell pm path "$APP_ID" >/dev/null || { echo "设备上没有安装 ${APP_ID}：先安装 debug APK（见 README）" >&2; exit 1; }

REMOTE_MODEL=/data/local/tmp/auri-model
REMOTE_SAMPLES=/data/local/tmp/auri-samples
cleanup() { adb shell rm -rf "$REMOTE_MODEL" "$REMOTE_SAMPLES" >/dev/null 2>&1 || true; }
trap cleanup EXIT

cleanup
adb push "$MODEL_DIR/." "$REMOTE_MODEL" >/dev/null
adb push "$SAMPLES_DIR/." "$REMOTE_SAMPLES" >/dev/null

# 命令从标准输入交给设备上的 sh：避免 adb shell 对引号与空格的二次解析
adb shell run-as "$APP_ID" sh <<EOF
set -e
rm -rf files/models/$MODEL_ID/$MODEL_VERSION files/samples
mkdir -p files/models/$MODEL_ID/$MODEL_VERSION files/samples
cp $REMOTE_MODEL/* files/models/$MODEL_ID/$MODEL_VERSION/
cp $REMOTE_SAMPLES/* files/samples/
EOF

echo "已安装到 $APP_ID 的私有目录："
adb shell run-as "$APP_ID" ls -l "files/models/$MODEL_ID/$MODEL_VERSION" files/samples
