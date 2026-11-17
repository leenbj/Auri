"""install_on_device.sh 的测试：用一个假的 adb 记录它对设备做了什么，不需要真设备或模拟器。

要证明的是脚本自己的逻辑：清单里的名字不能被当成命令交给设备上的 shell；
新模型先完整复制到暂存目录，成功之后才换掉旧的（复制中途失败不能毁掉设备上原来能用的模型）。
"""
import json
import os
import pathlib
import re
import stat
import subprocess

SCRIPT = pathlib.Path(__file__).resolve().parent / "install_on_device.sh"
SHA = "a" * 64

FAKE_ADB = """#!/usr/bin/env bash
echo "ARGS: $*" >> "$ADB_LOG"
case "$*" in
  *run-as*" sh"*) echo "STDIN-BEGIN" >> "$ADB_LOG"; cat >> "$ADB_LOG"; echo "STDIN-END" >> "$ADB_LOG" ;;
esac
exit 0
"""


def manifest(model_id: str = "test-model", model_version: str = "v1") -> dict:
    return {
        "manifest_version": 1,
        "model_id": model_id,
        "model_version": model_version,
        "archive": {"url": "https://example.invalid/m.tar.bz2", "size_bytes": 1, "sha256": SHA},
        "files": [{"role": "model", "path": "weights/model.onnx", "size_bytes": 1, "sha256": SHA}],
    }


# 真正执行设备脚本的假 adb：“设备”是本地目录。/data/local/tmp 映射到 $FAKE_TMP；
# run-as 在 $FAKE_APP（应用私有目录）里用 sh 执行标准输入里的命令，$FAKE_SHIM 排在 PATH 最前面，可用来让某条命令失败。
FAKE_ADB_EXECUTING = r"""#!/usr/bin/env bash
map() { printf '%s\n' "${1/\/data\/local\/tmp/$FAKE_TMP}"; }
case "$1" in
  get-state) echo device ;;
  push) dest="$(map "$3")"; mkdir -p "$dest"; cp -R "$2"/. "$dest"/ ;;
  shell)
    shift
    case "$1" in
      pm) exit 0 ;;
      rm) shift 2; for p in "$@"; do rm -rf "$(map "$p")"; done ;;
      run-as)
        shift 2
        cd "$FAKE_APP"
        if [ "$1" = sh ]; then
          sed "s#/data/local/tmp#$FAKE_TMP#g" | PATH="$FAKE_SHIM:$PATH" sh
        else
          "$@"
        fi ;;
    esac ;;
esac
"""

# 让“把暂存目录换进正式位置”这一步失败，模拟换到一半被打断
FAILING_STAGE_MV = """#!/bin/sh
case "$1" in *.install-*) echo "mv: 模拟失败" >&2; exit 1 ;; esac
exec /bin/mv "$@"
"""


def run_script(tmp_path: pathlib.Path, manifest_value: dict, *, cache_dirs=("test-model", "v1"), fake_adb=FAKE_ADB, extra_env=None):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    adb = bin_dir / "adb"
    adb.write_text(fake_adb, encoding="utf-8")
    adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
    cache = tmp_path / "cache"
    model_dir = cache.joinpath("models", *cache_dirs)
    model_dir.mkdir(parents=True)
    (model_dir / "weights").mkdir()
    (model_dir / "weights" / "model.onnx").write_bytes(b"x")
    (cache / "samples").mkdir()
    (cache / "samples" / "zh.wav").write_bytes(b"RIFF")
    path = tmp_path / "m.manifest.json"
    path.write_text(json.dumps(manifest_value), encoding="utf-8")
    log = tmp_path / "adb.log"
    env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "AURI_CACHE_DIR": str(cache), "ADB_LOG": str(log), **(extra_env or {})}
    result = subprocess.run(["bash", str(SCRIPT), str(path)], env=env, capture_output=True, text=True, timeout=60)
    return result, (log.read_text(encoding="utf-8") if log.exists() else "")


def run_on_fake_device(tmp_path: pathlib.Path, app: pathlib.Path, *, fail_stage_mv: bool = False):
    """真的执行设备脚本；app 是“设备”上应用私有目录的本地替身（里面的 files/ 可以事先摆好旧状态）。"""
    shim = tmp_path / "shim"
    shim.mkdir(parents=True)
    if fail_stage_mv:
        (shim / "mv").write_text(FAILING_STAGE_MV, encoding="utf-8")
        (shim / "mv").chmod(0o755)
    device_tmp = tmp_path / "devtmp"
    device_tmp.mkdir()
    (app / "files").mkdir(parents=True, exist_ok=True)
    env = {"FAKE_APP": str(app), "FAKE_TMP": str(device_tmp), "FAKE_SHIM": str(shim)}
    result, _ = run_script(tmp_path, manifest(), fake_adb=FAKE_ADB_EXECUTING, extra_env=env)
    return result


def write_app_file(app: pathlib.Path, relative: str, text: str) -> None:
    path = app / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def leftovers(app: pathlib.Path) -> list:
    return sorted(str(p.relative_to(app)) for p in (app / "files").rglob("*") if ".install-" in p.name or p.name.endswith(".old"))


def device_script(log: str) -> str:
    """交给设备上 sh 的那段命令（标准输入）。"""
    return log.split("STDIN-BEGIN\n", 1)[1].split("STDIN-END", 1)[0]


def test_a_model_id_with_shell_syntax_is_refused_before_anything_reaches_the_device(tmp_path):
    hostile = "test-model$(touch pwned)"

    result, log = run_script(tmp_path, manifest(model_id=hostile), cache_dirs=("test-model", "v1"))

    assert result.returncode != 0
    assert "格式不对" in result.stderr, result.stderr
    assert log == "", "清单不合格时不能对设备做任何事：" + log


def test_a_model_version_that_climbs_out_of_the_models_directory_is_refused(tmp_path):
    # 缓存里确实有这个目录（cache/escape）：拦住它的必须是格式校验，而不是“目录不存在”
    result, log = run_script(tmp_path, manifest(model_version="../../escape"), cache_dirs=("test-model", "../../escape"))

    assert result.returncode != 0
    assert "格式不对" in result.stderr, result.stderr
    assert log == ""


def test_a_model_version_named_like_a_backup_directory_is_refused(tmp_path):
    # 设备上换位时旧目录会改名 <版本>.old 再删：叫 v1.old 的另一个版本会被当成 v1 的备份一起删掉
    result, log = run_script(tmp_path, manifest(model_version="v1.old"), cache_dirs=("test-model", "v1.old"))

    assert result.returncode != 0
    assert "格式不对" in result.stderr, result.stderr
    assert log == ""


def test_the_new_model_is_staged_completely_before_the_old_one_is_replaced(tmp_path):
    result, log = run_script(tmp_path, manifest())

    assert result.returncode == 0, result.stderr
    script = device_script(log)
    lines = [line.strip() for line in script.splitlines() if line.strip()]
    stage_copy = next(i for i, line in enumerate(lines) if line.startswith("cp -R") and "/.install-" in line and "/models" in line)
    swap = next(i for i, line in enumerate(lines) if line.startswith("mv") and "files/models/test-model/v1" in line and "/.install-" in line)
    assert stage_copy < swap, "复制必须先于替换：\n" + script
    first_destructive = next((i for i, line in enumerate(lines) if "rm -rf files/models" in line or "rm -rf files/samples" in line), len(lines))
    assert first_destructive > stage_copy, "不能在新模型复制完成之前就删旧模型：\n" + script


def test_nested_model_paths_are_copied_recursively(tmp_path):
    """清单里的路径可以带子目录（weights/model.onnx）；cp 不递归会让安装静默缺文件。"""
    _, log = run_script(tmp_path, manifest())

    assert "cp -R" in device_script(log)


def test_each_run_uses_its_own_staging_directories(tmp_path):
    """/data/local/tmp 下的固定路径会让两次同时运行互相覆盖；暂存目录名要带进程号。"""
    _, log = run_script(tmp_path, manifest())

    targets = [line.split()[-1] for line in log.splitlines() if line.startswith("ARGS: push")]
    assert len(targets) == 2
    assert all(re.fullmatch(r"/data/local/tmp/auri-(model|samples)-\d+", t) for t in targets), targets


def test_a_fresh_install_puts_model_and_samples_in_place_and_leaves_no_staging_behind(tmp_path):
    app = tmp_path / "app"

    result = run_on_fake_device(tmp_path, app)

    assert result.returncode == 0, result.stderr
    assert (app / "files/models/test-model/v1/weights/model.onnx").read_bytes() == b"x"
    assert (app / "files/samples/zh.wav").read_bytes() == b"RIFF"
    assert leftovers(app) == []


def test_reinstalling_replaces_the_previous_model_and_samples_completely(tmp_path):
    app = tmp_path / "app"
    write_app_file(app, "files/models/test-model/v1/weights/model.onnx", "OLD-MODEL")
    write_app_file(app, "files/models/test-model/v1/stale-extra.bin", "OLD-EXTRA")
    write_app_file(app, "files/samples/old.wav", "OLD-WAV")

    result = run_on_fake_device(tmp_path, app)

    assert result.returncode == 0, result.stderr
    assert (app / "files/models/test-model/v1/weights/model.onnx").read_bytes() == b"x"
    assert not (app / "files/models/test-model/v1/stale-extra.bin").exists(), "整个目录换新，旧版多出来的文件不留"
    assert sorted(p.name for p in (app / "files/samples").iterdir()) == ["zh.wav"]
    assert leftovers(app) == []


def test_an_interrupted_swap_keeps_the_only_copy_of_the_old_model_and_a_retry_finishes_the_job(tmp_path):
    """上次换到一半被打断：正式目录不在，旧模型只剩 .old 这一份。再运行时（哪怕这次又失败）都不能先把它删了。"""
    app = tmp_path / "app"
    write_app_file(app, "files/models/test-model/v1.old/weights/model.onnx", "ONLY-COPY")

    failed = run_on_fake_device(tmp_path / "attempt1", app, fail_stage_mv=True)

    assert failed.returncode != 0, "把暂存目录换进正式位置失败，脚本必须以非零退出"
    assert (app / "files/models/test-model/v1.old/weights/model.onnx").read_text() == "ONLY-COPY", "旧模型仅剩的一份被删了"

    retried = run_on_fake_device(tmp_path / "attempt2", app)

    assert retried.returncode == 0, retried.stderr
    assert (app / "files/models/test-model/v1/weights/model.onnx").read_bytes() == b"x"
    assert leftovers(app) == [], "换成功之后 .old 才可以清掉"
