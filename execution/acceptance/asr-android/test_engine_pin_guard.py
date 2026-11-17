"""Ticket 14 第 1 项：引擎版本固定，构建时校验 AAR 的 SHA-256。

这些测试运行真实的 Gradle 构建，不替换任何东西：
  * 把篡改过、被截断的 AAR 放进一个临时缓存目录，构建必须失败，并且不能产出可被后续任务使用的 AAR；
  * 缓存里是固定的那个 AAR 时构建通过，产出与固定哈希一致；
  * 每个 Android 模块的构建图都包含这道校验（不是一个没人调用的独立任务）。

前置条件见 README.md：先 `source ~/.config/auri/android-env.sh`，并让真实 AAR 已在
`${AURI_CACHE_DIR:-~/.cache/auri}/engine/` 里（首次运行 `./gradlew fetchSherpaAar`）。
缺少前置条件时测试失败而不是跳过：跳过会让“通过”变成假话。
"""
import hashlib
import os
import pathlib
import shutil

import pytest

from asr_android_support import ANDROID_DIR, gradle

PIN_FILE = ANDROID_DIR / "engine" / "sherpa-onnx.properties"
VERIFIED_DIR = ANDROID_DIR / "build" / "pinned-engine"


def read_pin() -> dict:
    pin = {}
    for line in PIN_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            pin[key.strip()] = value.strip()
    return pin


PIN = read_pin()
AAR_NAME = PIN["aar.file"]
AAR_SHA256 = PIN["aar.sha256"]


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def real_cache_dir() -> pathlib.Path:
    return pathlib.Path(os.environ.get("AURI_CACHE_DIR", pathlib.Path.home() / ".cache" / "auri"))


@pytest.fixture(scope="session")
def real_aar() -> pathlib.Path:
    path = real_cache_dir() / "engine" / AAR_NAME
    if not path.is_file():
        pytest.fail(f"缺少真实 AAR：{path}。先运行 `cd apps/android && ./gradlew fetchSherpaAar`。")
    assert sha256_of(path) == AAR_SHA256, "缓存里的 AAR 本身就与固定哈希不符，请删除后重新获取"
    return path


@pytest.fixture()
def fake_cache(tmp_path) -> pathlib.Path:
    (tmp_path / "engine").mkdir()
    return tmp_path


@pytest.fixture(autouse=True)
def no_stale_verified_copy():
    """上一次通过的构建会留下已校验的副本；每个测试都从没有副本开始，避免掩盖失败。"""
    shutil.rmtree(VERIFIED_DIR, ignore_errors=True)
    yield


def test_pin_declares_a_sha256_and_a_pinned_https_source():
    assert len(AAR_SHA256) == 64 and set(AAR_SHA256) <= set("0123456789abcdef")
    assert PIN["aar.url"].startswith("https://github.com/k2-fsa/sherpa-onnx/releases/download/v")
    assert PIN["aar.url"].endswith("/" + AAR_NAME)
    assert PIN["version"] in AAR_NAME
    assert int(PIN["aar.size_bytes"]) > 0


def test_build_refuses_an_aar_with_one_flipped_byte(real_aar, fake_cache):
    tampered = bytearray(real_aar.read_bytes())
    tampered[len(tampered) // 2] ^= 0x01
    (fake_cache / "engine" / AAR_NAME).write_bytes(tampered)

    result = gradle("verifySherpaAar", cache_dir=fake_cache)

    assert result.returncode != 0
    assert AAR_SHA256 in result.stderr + result.stdout, "错误信息要写出期望的哈希"
    assert hashlib.sha256(bytes(tampered)).hexdigest() in result.stderr + result.stdout, "错误信息要写出实际的哈希"
    assert not (VERIFIED_DIR / AAR_NAME).exists(), "校验失败后不得留下可被后续任务使用的 AAR"


def test_build_refuses_a_truncated_aar(real_aar, fake_cache):
    (fake_cache / "engine" / AAR_NAME).write_bytes(real_aar.read_bytes()[:-1024])

    result = gradle("verifySherpaAar", cache_dir=fake_cache)

    assert result.returncode != 0
    assert "SHA-256" in result.stderr + result.stdout
    assert not (VERIFIED_DIR / AAR_NAME).exists()


def test_build_accepts_exactly_the_pinned_aar(real_aar, fake_cache):
    shutil.copyfile(real_aar, fake_cache / "engine" / AAR_NAME)

    result = gradle("verifySherpaAar", cache_dir=fake_cache)

    assert result.returncode == 0, result.stderr + result.stdout
    assert sha256_of(VERIFIED_DIR / AAR_NAME) == AAR_SHA256


def test_missing_aar_is_not_silently_replaced_when_offline(fake_cache):
    result = gradle("verifySherpaAar", cache_dir=fake_cache)

    assert result.returncode != 0
    assert "fetchSherpaAar" in result.stderr + result.stdout, "错误信息要告诉用户怎么获取"
    assert not (VERIFIED_DIR / AAR_NAME).exists()


@pytest.mark.parametrize("module", [":asr", ":app"])
def test_every_module_build_runs_the_aar_check_first(module, real_aar):
    result = gradle(f"{module}:assembleDebug", "--dry-run", cache_dir=real_aar.parents[1])

    assert result.returncode == 0, result.stderr + result.stdout
    order = [line.split()[0] for line in result.stdout.splitlines() if line.startswith(":")]
    assert ":verifySherpaAar" in order
    assert order.index(":verifySherpaAar") < order.index(f"{module}:preBuild")
