"""核对 Ticket 16 的仪器测试日志（`adb shell am instrument -w -r` 的原始输出）：该跑的测试是不是都跑了、都通过了。

沿用 Ticket 14 的 execution/acceptance/asr-android/check_instrumentation.py（读它的日志解析与源码里 @Test 的提取，不复制），
只补一件它做不到的事：继承来的测试。`:platform` 的 SqliteRecordLibraryContractTest 自己没有 @Test，它继承
`:capture` testFixtures 里 RecordLibraryContract 的 11 个契约测试（名字是带反引号的 Kotlin 方法名，日志里以子类名报出）。
只看最后那行 `OK (N tests)` 不够：被 @Ignore 的不计入 N，Assume 失败（等于跳过）却算“通过”，删掉一个测试也只是 N 少一。
所以这里以源码为准，逐个要求日志里有它的“通过”记录；没有记录、被忽略、假设失败、失败、出错、只开始没结束（进程崩了）
都是问题，没有“跳过”；日志里出现源码里没有的测试也算问题（设备上装的可能是旧版测试 APK）。

用法：python check_capture_instrumentation.py <instrumentation.log> <androidTest 源码目录> [<目录> ...]
有问题就逐条打印并以 1 退出；参数不对退出 2。
shortcut: 继承只认 `class X : RecordLibraryContract()` 这一种写法（目前唯一的契约基类）；契约基类里 @Test 的个数与读出的方法名个数
对不上就抛 ValueError，不悄悄少数。有第二个契约基类时再泛化。
"""
import importlib.util
import pathlib
import re
import sys

_HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = _HERE.parents[2]

_spec = importlib.util.spec_from_file_location("asr_check_instrumentation", _HERE.parent / "asr-android" / "check_instrumentation.py")
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)

CONTRACT_SOURCE = REPO_ROOT / "apps" / "android" / "capture" / "src" / "testFixtures" / "kotlin" / "app" / "auri" / "capture" / "testing" / "RecordLibraryContract.kt"
_BACKTICK_TEST = re.compile(r"@Test\b\s*fun\s+`([^`]+)`")
_SUBCLASS = re.compile(r"^class\s+(\w+)\s*:\s*RecordLibraryContract\(\)", re.MULTILINE)
_PACKAGE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)


def contract_test_names(source: pathlib.Path = CONTRACT_SOURCE) -> list:
    text = source.read_text(encoding="utf-8")
    names = _BACKTICK_TEST.findall(text)
    marks = len(re.findall(r"@Test\b", text))
    if marks != len(names) or not names:
        raise ValueError(f"{source.name} 里有 {marks} 个 @Test，只读出 {len(names)} 个方法名：写法超出了本脚本能读的范围，不能悄悄漏掉")
    return names


def expected_tests(src_dirs) -> set:
    """源码里的每个 @Test（含继承来的契约测试），形如 `app.auri.platform.library.SqliteRecordLibraryTest#方法名`。"""
    names: set = set()
    contract = None
    for src_dir in src_dirs:
        names |= _base.expected_tests(src_dir)
        for path in sorted(pathlib.Path(src_dir).rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            for match in _SUBCLASS.finditer(text):
                package = _PACKAGE.search(text)
                if package is None:
                    raise ValueError(f"{path.name} 继承了 RecordLibraryContract，但读不出包名")
                contract = contract if contract is not None else contract_test_names()
                names.update(f"{package.group(1)}.{match.group(1)}#{name}" for name in contract)
    return names


def check(log: str, src_dirs) -> list:
    expected = expected_tests(src_dirs)
    if not expected:
        return [f"{', '.join(map(str, src_dirs))} 里没有找到任何 @Test：目录不对或测试被清空，无法核对"]
    seen = _base.parse_log(log)
    problems = []
    for name in sorted(expected):
        codes = seen.get(name)
        if codes is None:
            problems.append(f"{name}: 日志里没有这个测试的记录（被过滤、被删除，或进程没跑到它）")
            continue
        bad = next((c for c in codes if c in _base._BAD), None)
        if bad is not None:
            problems.append(f"{name}: {_base._BAD[bad]}")
        elif 0 not in codes:
            problems.append(f"{name}: 只有开始记录，没有结束记录（进程中途崩溃？）")
    for name in sorted(set(seen) - expected):
        detail = next((_base._BAD[c] for c in seen[name] if c in _base._BAD), "有记录")
        problems.append(f"{name}: 日志里有，源码里找不到这个测试（{detail}）：读源码漏了它，或设备上装的是旧版测试 APK")
    return problems


def main(argv: list) -> int:
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    log = pathlib.Path(argv[1]).read_text(encoding="utf-8", errors="replace")
    src_dirs = argv[2:]
    problems = check(log, src_dirs)
    for problem in problems:
        print(problem)
    print(f"{len(expected_tests(src_dirs))} 个测试，{len(problems)} 个问题")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
