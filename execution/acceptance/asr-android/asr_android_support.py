"""acceptance 测试共用：仓库路径与带环境检查的 Gradle 调用。"""
import os
import pathlib
import shlex
import subprocess

import pytest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
ANDROID_DIR = REPO_ROOT / "apps" / "android"


def gradle(*args: str, cache_dir: pathlib.Path | None = None, timeout: int = 900) -> subprocess.CompletedProcess:
    """在 apps/android 下运行真实的 Gradle（离线）。缺少前置条件时让测试失败而不是跳过。"""
    if not os.environ.get("JAVA_HOME"):
        pytest.fail("JAVA_HOME 未设置：先执行 `source ~/.config/auri/android-env.sh`")
    env = dict(os.environ)
    if cache_dir is not None:
        env["AURI_CACHE_DIR"] = str(cache_dir)
    # 额外的 Gradle 参数，例如慢网络下与首次构建一致的 `-I tools/mirror-init.gradle`
    # （Gradle 的模块缓存按仓库区分，离线运行必须用与联网构建相同的仓库配置）。
    extra = shlex.split(os.environ.get("AURI_GRADLE_ARGS", ""))
    return subprocess.run(
        ["./gradlew", "--offline", "--console=plain", *extra, *args],
        cwd=ANDROID_DIR,
        env=env,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
