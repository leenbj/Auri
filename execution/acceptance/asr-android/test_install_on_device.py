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


def run_script(tmp_path: pathlib.Path, manifest_value: dict, *, cache_dirs=("test-model", "v1")):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    adb = bin_dir / "adb"
    adb.write_text(FAKE_ADB, encoding="utf-8")
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
    env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "AURI_CACHE_DIR": str(cache), "ADB_LOG": str(log)}
    result = subprocess.run(["bash", str(SCRIPT), str(path)], env=env, capture_output=True, text=True, timeout=60)
    return result, (log.read_text(encoding="utf-8") if log.exists() else "")


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
