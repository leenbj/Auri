#!/usr/bin/env bash
# Ticket 14 设备侧验收：在一台已连接的 Android 设备（模拟器或真机）上跑完整的仪器测试、冷启动基准，
# 把输出拉回主机，用真实合同 schema 与状态机校验，并汇总性能表。
#
# 前置：JAVA_HOME 指向 JDK 17、Android SDK 已就绪（见 apps/android/README 与本目录 README）、
#       仓库根目录有 .venv（Python 3.12，按 CLAUDE.md 的合同测试命令装了 tests/contract/requirements.txt）、
#       adb 能看到恰好一台设备（多台时先 export ANDROID_SERIAL=<序列号>）。
# 用法：run_device_acceptance.sh
# 可选环境变量：AURI_GRADLE_ARGS（如 "-I tools/mirror-init.gradle"）、AURI_DEVICE_OUT（输出目录）、
#               AURI_EVIDENCE（覆盖结果表里的证据等级标注）、PYTHON（默认仓库 .venv 的 python）。
#
# 证据等级：脚本在模拟器上标 "E2 模拟器"；其他设备只标 "真机运行（E3 待登记）"，
# 是否算 E3 由运行的人核对设备与步骤后在 README 结果表里登记，脚本不自己宣布。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
ANDROID_DIR="$REPO_ROOT/apps/android"
OUT="${AURI_DEVICE_OUT:-$ANDROID_DIR/build/device-out}"
PYTHON="${PYTHON:-$REPO_ROOT/.venv/bin/python}"
APP_ID="app.auri.android"
RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
COLD_RUNS=3
WARM_REPEATS=3
# macOS 上 adb 的 USB 初始化可能卡在 IOKit；固定走 libusb 后端可避开
export ADB_LIBUSB="${ADB_LIBUSB:-1}"

fail() { echo "失败：$*" >&2; exit 1; }
step() { echo; echo "== $*"; }

[ -n "${JAVA_HOME:-}" ] || fail "没有设置 JAVA_HOME（需要 JDK 17）"
[ -x "$PYTHON" ] || fail "找不到 ${PYTHON}：先建 .venv 并安装 tests/contract/requirements.txt（见 README）"
adb get-state >/dev/null 2>&1 || fail "adb 没有看到可用设备（只有一台时才不需要 ANDROID_SERIAL）"

# 等 logcat 里出现 TranscribeFileActivity 写的状态行；超时就失败，不往下猜。
# 先把日志存进变量再 grep：直接 `logcat | grep -q` 在 pipefail 下会因 SIGPIPE 误判为“没找到”
wait_for_log_line() {
  local pattern="$1" timeout_s="$2" waited=0 log
  while true; do
    log="$(adb logcat -d -s AuriAsr:I | tr -d '\r')"
    # 只认“结果行”（state=… 或 error=…）：入口在转录开始时还会写一行 start out=…，它不是结果
    if grep -q -E -- "(state|error)=.*$pattern" <<<"$log"; then
      grep -E -- "(state|error)=.*$pattern" <<<"$log" | tail -1 | sed 's/.*AuriAsr *: *//'
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
    [ "$waited" -lt "$timeout_s" ] || fail "等了 ${timeout_s} 秒仍没有日志行 $pattern"
  done
}

step "1/7 准备模型与示例音频（已缓存则只做哈希校验）"
"$PYTHON" "$HERE/prepare_model.py"

step "2/7 构建 debug APK 与仪器测试 APK"
(cd "$ANDROID_DIR" && ./gradlew ${AURI_GRADLE_ARGS:-} --console=plain :app:assembleDebug :app:assembleDebugAndroidTest)
APP_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

step "3/7 安装（不用 gradle connectedAndroidTest：它跑完会卸载应用，带走私有目录里的模型）"
adb install -r -t "$APP_APK" >/dev/null
adb install -r -t "$TEST_APK" >/dev/null
"$HERE/install_on_device.sh" >/dev/null
adb shell run-as "$APP_ID" rm -rf files/contract-out files/runs

step "4/7 仪器测试（真引擎、真模型）"
mkdir -p "$OUT"
adb shell am instrument -w -r "$RUNNER" > "$OUT/instrumentation.log" 2>&1 || true
RESULT_LINE="$(tr -d '\r' < "$OUT/instrumentation.log" | grep -E '^(OK \(|Tests run:)' | tail -1 || true)"
echo "仪器测试结果：${RESULT_LINE:-（没有结果行，看 $OUT/instrumentation.log）}"
case "$RESULT_LINE" in
  "OK (0 tests)") fail "没有跑任何测试" ;;
  "OK ("*) ;;
  *) grep -E 'stack=|INSTRUMENTATION_STATUS: test=' "$OUT/instrumentation.log" | tail -20 >&2; fail "仪器测试没有全部通过" ;;
esac
# 只看 OK (N tests) 不够：被 @Ignore 的不计数，Assume 失败也算“通过”。按源码里的每个 @Test 逐个核对
"$PYTHON" "$HERE/check_instrumentation.py" "$OUT/instrumentation.log" "$ANDROID_DIR/app/src/androidTest" \
  || fail "仪器测试没有全部真正通过（见上面逐项列出的问题）"

step "5/7 冷启动基准：每次 am start -S -W 都是全新进程，各跑一次 + 同引擎再推理 $WARM_REPEATS 次"
for i in $(seq 1 "$COLD_RUNS"); do
  adb logcat -c
  adb shell am start -S -W -n "$APP_ID/.TranscribeFileActivity" \
    --es wav samples/zh.wav --es out "bench-$i" --ei warm_repeats "$WARM_REPEATS" >/dev/null
  LINE="$(wait_for_log_line "out=runs/bench-$i" 180)"
  echo "bench-${i}：$LINE"
  case "$LINE" in state=SUCCEEDED*) ;; *) fail "bench-$i 没有成功：$LINE" ;; esac
done

step "6/7 把设备上的输出拉回 $OUT"
rm -rf "$OUT/contract-out" "$OUT/runs"
adb exec-out run-as "$APP_ID" tar -c -C files contract-out runs | tar -x -C "$OUT"

step "7/7 用真实合同校验并汇总"
IS_EMULATOR="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
if [ -n "${AURI_EVIDENCE:-}" ]; then EVIDENCE="$AURI_EVIDENCE"
elif [ "$IS_EMULATOR" = "1" ]; then EVIDENCE="E2 模拟器，数字不代表真机"
else EVIDENCE="真机运行（E3 待运行者核对后登记）"; fi

STATUS=0
# 两个预设都是严格模式：多出来的场景目录也算失败（runs 预设里的 bench-1..3 对应上面的 COLD_RUNS=3）
"$PYTHON" "$HERE/contract_check.py" "$OUT/contract-out" --preset device || STATUS=1
"$PYTHON" "$HERE/contract_check.py" "$OUT/runs" --preset runs || STATUS=1
REPORTS=()
for i in $(seq 1 "$COLD_RUNS"); do REPORTS+=("冷启动 $i=$OUT/runs/bench-$i/run-report.json"); done
"$PYTHON" "$HERE/benchmark_summary.py" --evidence "$EVIDENCE" --warm "$WARM_REPEATS" "${REPORTS[@]}" | tee "$OUT/benchmark.md" || STATUS=1

[ "$STATUS" -eq 0 ] || fail "合同校验或基准校验没有通过（见上面的输出）"
echo
echo "全部通过。输出目录：$OUT"
