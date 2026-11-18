"""run_emulator_acceptance.sh 的行为测试：用假的 adb / emulator / avdmanager / pgrep 跑真脚本。

这台机器是共享的，脚本的清理义务写进了规矩：成功、失败、被中断都必须杀掉模拟器、删掉 AVD 与磁盘镜像、释放锁；
别人占着锁就等，不许同时起第二台模拟器；只认模拟器，不碰插着的手机；别人的 adb 服务不动。
这些全是“出了事才看得出来”的行为，所以在这里用假工具逐条钉住。真模拟器上的结果见 README（E2）。
"""
import importlib.util
import os
import pathlib
import signal
import subprocess
import time

import pytest

import check_sync_instrumentation as ci

_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("capture_support", _HERE.parent / "android-capture" / "capture_support.py")
_support = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_support)
ANDROID_DIR = _support.ANDROID_DIR
all_pass = _support.all_pass
instrumentation_log = _support.instrumentation_log

SCRIPT = _HERE / "run_emulator_acceptance.sh"
SYNCPLATFORM_SRC = ANDROID_DIR / "syncplatform" / "src" / "androidTest"

FAKE_ADB = r"""#!/usr/bin/env bash
echo "adb $* [serial=${ANDROID_SERIAL:-}]" >> "$FAKE_LOG"
case "$1" in
  devices)
    printf 'List of devices attached\n'
    [ -n "${FAKE_PHONE:-}" ] && printf '%s\tdevice\n' "$FAKE_PHONE"
    if [ -f "$FAKE_STATE/emulator-up" ] && [ ! -f "$FAKE_STATE/killed" ]; then printf 'emulator-5554\tdevice\n'; fi
    printf '\n' ;;
  emu) touch "$FAKE_STATE/killed" ;;
  shell)
    case "$2 $3" in
      "getprop sys.boot_completed") echo 1 ;;
      "getprop ro.kernel.qemu") echo "${FAKE_QEMU:-1}" ;;
      "getprop ro.build.version.sdk") echo 35 ;;
      "getprop ro.product.cpu.abi") echo arm64-v8a ;;
      "getprop ro.build.fingerprint") echo fake/fingerprint ;;
      "am instrument")
        [ -n "${FAKE_INSTRUMENT_SLEEP:-}" ] && sleep "$FAKE_INSTRUMENT_SLEEP"
        cat "$FAKE_SYNCPLATFORM_LOG" ;;
    esac ;;
esac
exit 0
"""

FAKE_EMULATOR = r"""#!/usr/bin/env bash
echo "emulator $* [lock_held=$([ -d "$AURI_EMULATOR_LOCK" ] && echo yes || echo no) owner=$(cat "$AURI_EMULATOR_LOCK/owner" 2>/dev/null)]" >> "$FAKE_LOG"
echo $$ > "$FAKE_STATE/emulator.pid"
touch "$FAKE_STATE/emulator-up"
while [ ! -f "$FAKE_STATE/killed" ]; do sleep 0.1; done
exit 0
"""

FAKE_AVDMANAGER = r"""#!/usr/bin/env bash
echo "avdmanager $*" >> "$FAKE_LOG"
name=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do [ "${args[i]}" = "-n" ] && name="${args[i + 1]}"; done
case "$1 $2" in
  "create avd")
    mkdir -p "$ANDROID_AVD_HOME/$name.avd"
    printf 'hw.ramSize=1024\nfoo=bar\n' > "$ANDROID_AVD_HOME/$name.avd/config.ini"
    touch "$ANDROID_AVD_HOME/$name.ini" ;;
  "delete avd")
    echo "config-at-delete: $(tr '\n' ' ' < "$ANDROID_AVD_HOME/$name.avd/config.ini")" >> "$FAKE_LOG"
    rm -rf "$ANDROID_AVD_HOME/$name.avd" "$ANDROID_AVD_HOME/$name.ini" ;;
esac
exit 0
"""

# -x adb：别人的 adb 服务在不在（FAKE_ADB_RUNNING=1）；-f 模式：有没有模拟器进程（FAKE_EMULATOR_PROC=1）
FAKE_PGREP = r"""#!/usr/bin/env bash
if [ "$1" = "-x" ]; then [ "${FAKE_ADB_RUNNING:-0}" = "1" ]; else [ "${FAKE_EMULATOR_PROC:-0}" = "1" ]; fi
"""


class Env:
    def __init__(self, root: pathlib.Path):
        self.root = root
        self.bin = root / "bin"
        self.state = root / "state"
        self.lock = root / "emulator.lock"
        self.avd_home = root / "avd"
        self.out = root / "out"
        self.log = root / "fake.log"
        self.bin.mkdir()
        self.state.mkdir()
        for name, body in (("adb", FAKE_ADB), ("emulator", FAKE_EMULATOR), ("avdmanager", FAKE_AVDMANAGER), ("pgrep", FAKE_PGREP)):
            (self.bin / name).write_text(body)
            (self.bin / name).chmod(0o755)
        self.log.write_text("")
        (root / "syncplatform-test.apk").write_bytes(b"not a real apk")
        self.syncplatform_log = root / "syncplatform.log"
        self.syncplatform_log.write_text(instrumentation_log(all_pass(ci.expected_tests([SYNCPLATFORM_SRC]))))

    def env(self, **extra) -> dict:
        env = {
            **os.environ,
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "JAVA_HOME": str(self.root),
            "AURI_SKIP_BUILD": "1",
            "AURI_SYNCPLATFORM_TEST_APK": str(self.root / "syncplatform-test.apk"),
            "AURI_EMULATOR_LOCK": str(self.lock),
            "AURI_AVD_NAME": "auri-test-avd",
            "AURI_DEVICE_OUT": str(self.out),
            "AURI_LOCK_OWNER": "ticket/test-owner",
            "AURI_LOCK_POLL_SECONDS": "1",
            "ANDROID_AVD_HOME": str(self.avd_home),
            "FAKE_LOG": str(self.log),
            "FAKE_STATE": str(self.state),
            "FAKE_SYNCPLATFORM_LOG": str(self.syncplatform_log),
        }
        env.pop("ANDROID_SERIAL", None)
        env.update({k: str(v) for k, v in extra.items()})
        return env

    def run(self, **extra) -> subprocess.CompletedProcess:
        return subprocess.run(["bash", str(SCRIPT)], env=self.env(**extra), capture_output=True, text=True, timeout=120)

    def calls(self) -> list:
        return self.log.read_text().splitlines()

    def emulator_alive(self) -> bool:
        pid_file = self.state / "emulator.pid"
        if not pid_file.exists():
            return False
        try:
            os.kill(int(pid_file.read_text()), 0)
            return True
        except ProcessLookupError:
            return False

    def assert_clean(self):
        assert not self.lock.exists(), "锁必须释放"
        assert not (self.avd_home / "auri-test-avd.avd").exists() and not (self.avd_home / "auri-test-avd.ini").exists(), "AVD 与磁盘镜像必须删掉"
        assert not self.emulator_alive(), "模拟器进程必须退出"
        calls = self.calls()
        assert any(c.startswith("adb emu kill") for c in calls), "必须先 adb emu kill"
        assert any(c.startswith("avdmanager delete avd -n auri-test-avd") for c in calls), "必须 avdmanager delete"


@pytest.fixture
def env(tmp_path):
    return Env(tmp_path)


def test_the_script_has_valid_bash_syntax():
    assert subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True).returncode == 0


def test_success_runs_the_emulator_headless_under_the_lock_and_leaves_nothing_behind(env):
    result = env.run()

    assert result.returncode == 0, result.stdout + result.stderr
    env.assert_clean()
    calls = env.calls()
    launch = next(c for c in calls if c.startswith("emulator "))
    assert "-no-window" in launch and "-no-audio" in launch and "-avd auri-test-avd" in launch
    assert "lock_held=yes owner=ticket/test-owner" in launch, "起模拟器时必须已经持有锁，owner 写的是分支名"
    kill_at = next(i for i, c in enumerate(calls) if c.startswith("adb emu kill"))
    delete_at = next(i for i, c in enumerate(calls) if c.startswith("avdmanager delete"))
    assert kill_at < delete_at, "先杀模拟器，再删 AVD"
    config = next(c for c in calls if c.startswith("config-at-delete:"))
    assert "hw.ramSize=2048" in config and "hw.ramSize=1024" not in config, "内存要改成 2048 而不是追加一行（共享机器）"
    assert "disk.dataPartition.size=4G" in config and "foo=bar" in config, "data 分区 4G，其余配置原样保留"
    assert "adb kill-server" in " ".join(calls), "adb 服务是本脚本带起来的，用完要停"
    assert (env.out / "device.txt").read_text().count("E2") == 1


def test_it_only_installs_and_tests_on_the_emulator_never_on_a_phone(env):
    result = env.run(FAKE_PHONE="R5CT1234ABC")

    assert result.returncode == 0, result.stdout + result.stderr
    touching = [c for c in env.calls() if c.startswith(("adb install", "adb shell am instrument", "adb shell getprop"))]
    assert touching and all("serial=emulator-5554" in c for c in touching), touching
    assert "kill-server" not in " ".join(env.calls()), "还连着别的设备时不停 adb 服务"


def test_it_leaves_an_adb_server_that_was_already_running_alone(env):
    result = env.run(FAKE_ADB_RUNNING="1")

    assert result.returncode == 0, result.stdout + result.stderr
    assert "kill-server" not in " ".join(env.calls())


def test_a_run_where_a_test_did_not_really_run_fails_but_still_cleans_up(env):
    names = ci.expected_tests([SYNCPLATFORM_SRC])
    entries = all_pass(names)
    del entries[sorted(names)[0]]
    env.syncplatform_log.write_text(instrumentation_log(entries))

    result = env.run()

    assert result.returncode != 0
    assert "没有这个测试的记录" in result.stdout
    env.assert_clean()


def test_ignored_and_assumption_failed_tests_do_not_count_as_passing(env):
    names = sorted(ci.expected_tests([SYNCPLATFORM_SRC]))
    env.syncplatform_log.write_text(instrumentation_log({**all_pass(names), names[0]: [1, -4]}))

    result = env.run()

    assert result.returncode != 0
    assert "假设失败" in result.stdout
    env.assert_clean()


def test_a_device_that_is_not_an_emulator_is_refused_and_still_cleaned_up(env):
    result = env.run(FAKE_QEMU="0")

    assert result.returncode != 0
    assert "不是模拟器" in result.stderr
    assert not any(c.startswith("adb install") for c in env.calls())
    env.assert_clean()


def test_it_waits_while_someone_else_holds_the_lock_and_starts_no_second_emulator(env):
    env.lock.mkdir()
    (env.lock / "owner").write_text("ticket/someone-else\n")
    proc = subprocess.Popen(["bash", str(SCRIPT)], env=env.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        time.sleep(3.5)
        assert proc.poll() is None, "锁被占着，脚本必须还在等"
        assert not any(c.startswith("emulator ") for c in env.calls()), "别人占着锁时不能起模拟器"
        assert (env.lock / "owner").read_text().strip() == "ticket/someone-else", "不能动别人的锁"
        (env.lock / "owner").unlink()
        env.lock.rmdir()  # 对方释放
        out, err = proc.communicate(timeout=60)
    finally:
        if proc.poll() is None:
            proc.kill()
    assert proc.returncode == 0, out + err
    assert "ticket/someone-else 占用" in out
    env.assert_clean()


def test_a_lock_older_than_three_hours_with_no_emulator_process_is_cleared(env):
    env.lock.mkdir()
    (env.lock / "owner").write_text("ticket/crashed-long-ago\n")
    four_hours_ago = time.time() - 4 * 3600
    os.utime(env.lock, (four_hours_ago, four_hours_ago))

    result = env.run()

    assert result.returncode == 0, result.stdout + result.stderr
    assert "视为残留" in result.stdout
    env.assert_clean()


def test_an_old_lock_is_respected_while_an_emulator_process_still_exists(env):
    env.lock.mkdir()
    (env.lock / "owner").write_text("ticket/still-running\n")
    four_hours_ago = time.time() - 4 * 3600
    os.utime(env.lock, (four_hours_ago, four_hours_ago))
    proc = subprocess.Popen(["bash", str(SCRIPT)], env=env.env(FAKE_EMULATOR_PROC="1"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        time.sleep(3.5)
        assert proc.poll() is None, "还有模拟器进程在跑，锁再老也不能清"
        assert env.lock.exists() and not any(c.startswith("emulator ") for c in env.calls())
    finally:
        proc.kill()
        proc.communicate()


@pytest.mark.parametrize("sig", [signal.SIGINT, signal.SIGTERM, signal.SIGHUP])
def test_interrupting_the_script_mid_run_still_kills_the_emulator_and_releases_the_lock(env, sig):
    proc = subprocess.Popen(
        ["bash", str(SCRIPT)],
        env=env.env(FAKE_INSTRUMENT_SLEEP="60"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    deadline = time.time() + 30
    while time.time() < deadline and not any(c.startswith("adb shell am instrument") for c in env.calls()):
        time.sleep(0.2)
    assert any(c.startswith("adb shell am instrument") for c in env.calls()), "测试还没跑到仪器测试那一步"

    os.killpg(proc.pid, sig)  # 等同于终端里 Ctrl-C / 关终端 / 工作进程被收掉：整个进程组一起收到
    proc.communicate(timeout=60)

    assert proc.returncode != 0
    env.assert_clean()
