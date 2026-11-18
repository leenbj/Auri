#!/usr/bin/env bash
# Ticket 16 模拟器验收（E2）：起一台一次性的无头模拟器，跑三组仪器测试，核对它们都真的跑了，然后把模拟器、AVD 与锁全部清理掉。
#   1. :platform 的 SQLite Record 库：11 个契约测试（与测试里的内存库同一套）+ 持久化/触发器/事务测试；
#   2. :app 的“没有耳机 → 拒绝”：零字节写入、零转录请求、系统里没有任何录音配置、手机麦克风没被打开；
#   3. :app 的麦克风类前台服务：能起来、类型是 microphone、能停。
# 模拟器没有蓝牙耳机，所以这里证明的是拒绝路径与存储/服务，不是“用耳机录到音”：那一步要真机（README 的产品负责人步骤，NOT_RUN）。
# 证据等级：E2（模拟器），不是 E3。
#
# 这台机器是共享的（16 GiB，同时有别的工作进程会起模拟器）：同一时刻只允许一台模拟器，靠锁目录互斥：
#   ~/.cache/auri/emulator.lock（mkdir 原子），owner 文件写持有者（分支名）；超过 3 小时且没有任何 qemu/emulator 进程的锁视为残留，可以清掉。
# 无论成功、失败还是被中断，退出时一律：adb emu kill → 等模拟器进程退出 → 删 AVD 与磁盘镜像 → 如果 adb 服务是本脚本启动的就停掉 → 释放锁。
#
# 前置：JAVA_HOME 指向 JDK 17、Android SDK/模拟器/arm64 API 35 google_apis 镜像已就绪（source ~/.config/auri/android-env.sh）、
#       仓库根目录有 .venv（Python 3.12，装了 tests/contract/requirements.txt）。
# 用法：run_emulator_acceptance.sh
# 可选环境变量：AURI_DEVICE_OUT（输出目录）、AURI_LOCK_OWNER（写进 owner 的名字）、AURI_GRADLE_ARGS、PYTHON、
#   AURI_LOCK_POLL_SECONDS（等锁时的轮询间隔，默认 30）、AURI_BOOT_TIMEOUT（等开机的秒数，默认 300）、
#   AURI_SKIP_BUILD=1、AURI_APP_APK、AURI_APP_TEST_APK、AURI_PLATFORM_TEST_APK、AURI_EMULATOR_LOCK、AURI_AVD_NAME：
#   这几个只给 test_run_emulator_acceptance.py 用，平时别设。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
ANDROID_DIR="$REPO_ROOT/apps/android"
OUT="${AURI_DEVICE_OUT:-$ANDROID_DIR/build/device-out-capture}"
PYTHON="${PYTHON:-$REPO_ROOT/.venv/bin/python}"
LOCK="${AURI_EMULATOR_LOCK:-$HOME/.cache/auri/emulator.lock}"
LOCK_OWNER="${AURI_LOCK_OWNER:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
POLL="${AURI_LOCK_POLL_SECONDS:-30}"
BOOT_TIMEOUT="${AURI_BOOT_TIMEOUT:-300}"
AVD_NAME="${AURI_AVD_NAME:-auri-t16-capture}"
IMAGE="system-images;android-35;google_apis;arm64-v8a"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.cache/auri/avd}"
# macOS 上 adb 的 USB 初始化可能卡在 IOKit；固定走 libusb 后端可避开
export ADB_LIBUSB="${ADB_LIBUSB:-1}"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

HAVE_LOCK=0
EMU_PID=""
AVD_CREATED=0
ADB_WAS_RUNNING=0

fail() { echo "失败：$*" >&2; exit 1; }
step() { echo; echo "== $*"; }

cleanup() {
  local status=$?
  set +e
  if [ -n "$EMU_PID" ]; then
    adb emu kill >/dev/null 2>&1
    for _ in $(seq 1 60); do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$EMU_PID" 2>/dev/null && kill "$EMU_PID" 2>/dev/null
  fi
  if [ "$AVD_CREATED" -eq 1 ]; then
    avdmanager delete avd -n "$AVD_NAME" >/dev/null 2>&1
    rm -rf "$ANDROID_AVD_HOME/$AVD_NAME.avd" "$ANDROID_AVD_HOME/$AVD_NAME.ini"
  fi
  # 只有 adb 服务是本脚本带起来的、并且已经没有任何设备连着时才停它（别人的 adb 服务和手机不动）
  if [ "$ADB_WAS_RUNNING" -eq 0 ] && [ -n "$EMU_PID" ] && [ -z "$(adb devices 2>/dev/null | sed 1d | tr -d '[:space:]')" ]; then
    adb kill-server >/dev/null 2>&1
  fi
  [ "$HAVE_LOCK" -eq 1 ] && rm -rf "$LOCK"
  exit "$status"
}

[ -n "${JAVA_HOME:-}" ] || fail "没有设置 JAVA_HOME（需要 JDK 17）"
[ -x "$PYTHON" ] || fail "找不到 ${PYTHON}：先建 .venv 并安装 tests/contract/requirements.txt（见 README）"
for tool in adb emulator avdmanager; do command -v "$tool" >/dev/null || fail "找不到 $tool：先 source ~/.config/auri/android-env.sh"; done
if pgrep -x adb >/dev/null 2>&1; then ADB_WAS_RUNNING=1; fi

if [ "${AURI_SKIP_BUILD:-0}" != "1" ]; then
  step "1/6 构建 debug APK 与两个仪器测试 APK（JVM 单元测试请先跑完，见 README）"
  (cd "$ANDROID_DIR" && ./gradlew ${AURI_GRADLE_ARGS:-} --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest)
fi
APP_APK="${AURI_APP_APK:-$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk}"
APP_TEST_APK="${AURI_APP_TEST_APK:-$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
PLATFORM_TEST_APK="${AURI_PLATFORM_TEST_APK:-$ANDROID_DIR/platform/build/outputs/apk/androidTest/debug/platform-debug-androidTest.apk}"
for apk in "$APP_APK" "$APP_TEST_APK" "$PLATFORM_TEST_APK"; do [ -f "$apk" ] || fail "没有 $apk"; done

step "2/6 取得模拟器锁（同一时刻只许一台模拟器）"
# bash 收到 INT/TERM/HUP 这类致命信号退出时同样会执行 EXIT 陷阱（test_run_emulator_acceptance.py 里逐个信号验证）
trap cleanup EXIT
mkdir -p "$(dirname "$LOCK")"
until mkdir "$LOCK" 2>/dev/null; do
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +180 2>/dev/null)" ] && ! pgrep -f 'qemu-system|/emulator' >/dev/null 2>&1; then
    echo "锁 $LOCK 超过 3 小时且没有模拟器进程：视为残留，清掉（原持有者：$(cat "$LOCK/owner" 2>/dev/null || echo 未知)）"
    rm -rf "$LOCK"
    continue
  fi
  echo "模拟器被 $(cat "$LOCK/owner" 2>/dev/null || echo 未知) 占用，$POLL 秒后再试"
  sleep "$POLL"
done
HAVE_LOCK=1
echo "$LOCK_OWNER" > "$LOCK/owner"

step "3/6 建一次性 AVD 并无头启动（-no-window -no-audio；内存 2048 MB，data 4G）"
mkdir -p "$ANDROID_AVD_HOME" "$OUT"
AVD_CREATED=1
echo no | avdmanager create avd -n "$AVD_NAME" -k "$IMAGE" -d pixel_6 --force >/dev/null
"$PYTHON" - "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
wanted = {"hw.ramSize": "2048", "disk.dataPartition.size": "4G"}
lines = [line for line in path.read_text().splitlines() if line.split("=", 1)[0].strip() not in wanted]
lines += [f"{key}={value}" for key, value in wanted.items()]
path.write_text("\n".join(lines) + "\n")
PY
emulator -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
# 只认这台模拟器：别的设备（比如插着的手机）上不装应用、不跑测试
waited=0
until ANDROID_SERIAL="$(adb devices | tr -d '\r' | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1; exit}')" && [ -n "$ANDROID_SERIAL" ]; do
  sleep 2
  waited=$((waited + 2))
  [ "$waited" -lt "$BOOT_TIMEOUT" ] || fail "等了 ${BOOT_TIMEOUT} 秒 adb 仍没有看到模拟器"
done
export ANDROID_SERIAL
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
  waited=$((waited + 2))
  [ "$waited" -lt "$BOOT_TIMEOUT" ] || fail "等了 ${BOOT_TIMEOUT} 秒模拟器仍没开机完成"
done
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || fail "当前设备不是模拟器：本脚本只在一次性模拟器上跑"
{
  echo "api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "evidence=E2 模拟器（没有蓝牙耳机）"
} | tee "$OUT/device.txt"

step "4/6 安装"
adb install -r -t "$APP_APK" >/dev/null
adb install -r -t "$APP_TEST_APK" >/dev/null
adb install -r -t "$PLATFORM_TEST_APK" >/dev/null

run_instrumentation() { # <名字> <测试包/运行器> [am instrument 的额外参数...]
  local name="$1" target="$2"
  shift 2
  adb shell am instrument -w -r "$@" "$target/$RUNNER" > "$OUT/instrumentation-$name.log" 2>&1 || true
  local line
  line="$(tr -d '\r' < "$OUT/instrumentation-$name.log" | grep -E '^(OK \(|Tests run:)' | tail -1 || true)"
  echo "$name 仪器测试结果：${line:-（没有结果行，看 $OUT/instrumentation-$name.log）}"
}

step "5/6 仪器测试"
run_instrumentation platform app.auri.platform.test
# 只跑本 Ticket 的设备端测试：Ticket 14 的设备测试需要 239 MB 的模型，不在这里
run_instrumentation app app.auri.android.test -e package app.auri.android.capture

step "6/6 核对每个 @Test 都真的跑了并通过（只看 OK (N tests) 不够：被忽略的不计数，假设失败也算通过）"
STATUS=0
"$PYTHON" "$HERE/check_capture_instrumentation.py" "$OUT/instrumentation-platform.log" "$ANDROID_DIR/platform/src/androidTest" || STATUS=1
"$PYTHON" "$HERE/check_capture_instrumentation.py" "$OUT/instrumentation-app.log" "$ANDROID_DIR/app/src/androidTest/kotlin/app/auri/android/capture" || STATUS=1
[ "$STATUS" -eq 0 ] || fail "仪器测试没有全部真正通过（见上面逐项列出的问题与 $OUT 下的日志）"
echo
echo "全部通过（E2 模拟器）。输出目录：$OUT"
