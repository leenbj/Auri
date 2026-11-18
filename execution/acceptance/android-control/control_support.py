"""android-control 验收测试共用：仓库路径、Ticket 16 的检查器与 Gradle 调用（读它们，不复制）。"""
import importlib.util
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
ANDROID_DIR = REPO_ROOT / "apps" / "android"
CAPTURE_ACCEPTANCE = HERE.parent / "android-capture"


def _load(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Ticket 16 的 Gradle 调用（带 JAVA_HOME 检查，缺了就让测试失败而不是跳过）与仪器测试日志的核对 / 造假日志工具
_support = _load("capture_support", CAPTURE_ACCEPTANCE / "capture_support.py")
instrumentation_checker = _load("check_capture_instrumentation", CAPTURE_ACCEPTANCE / "check_capture_instrumentation.py")
gradle = _support.gradle
instrumentation_log = _support.instrumentation_log
all_pass = _support.all_pass
