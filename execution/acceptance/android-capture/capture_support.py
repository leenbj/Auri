"""android-capture 验收测试共用：仓库路径与带环境检查的 Gradle 调用（做法同 asr-android/asr_android_support.py）。"""
import os
import pathlib
import shlex
import subprocess

import pytest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
ANDROID_DIR = REPO_ROOT / "apps" / "android"


def gradle(*args: str, timeout: int = 900) -> subprocess.CompletedProcess:
    """在 apps/android 下运行真实的 Gradle（离线）。缺少前置条件时让测试失败而不是跳过。"""
    if not os.environ.get("JAVA_HOME"):
        pytest.fail("JAVA_HOME 未设置：先执行 `source ~/.config/auri/android-env.sh`")
    # 额外的 Gradle 参数，例如慢网络下与首次构建一致的 `-I tools/mirror-init.gradle`
    extra = shlex.split(os.environ.get("AURI_GRADLE_ARGS", ""))
    return subprocess.run(
        ["./gradlew", "--offline", "--console=plain", *extra, *args],
        cwd=ANDROID_DIR,
        env=dict(os.environ),
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def instrumentation_log(entries: dict) -> str:
    """造一份 `am instrument -r` 的原始输出：{"包.类#方法": [状态码, ...]}，1 是开始、0 是通过，负数见 check_instrumentation.py。"""
    lines = []
    for full, codes in entries.items():
        cls, test = full.split("#", 1)
        for code in codes:
            lines += [f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: test={test}", f"INSTRUMENTATION_STATUS_CODE: {code}"]
    return "\n".join(lines) + "\n"


def all_pass(names) -> dict:
    return {name: [1, 0] for name in names}
