"""核对仪器测试日志（`adb shell am instrument -w -r` 的原始输出）：该跑的测试是不是都跑了、都通过了。

只看最后那行 `OK (N tests)` 不够：被 @Ignore 掉的测试不计入 N，Assume 失败（等于跳过）却算“通过”，
删掉一个测试也只是 N 少一。这里以 androidTest 源码里的每个 @Test 为准，逐个要求日志里有它的“通过”记录，
没有记录、被忽略、假设失败、失败、出错、只开始没结束（进程崩了）都是问题，没有“跳过”。

用法：python check_instrumentation.py <instrumentation.log> <androidTest 源码目录>
有问题就逐条打印并以 1 退出；参数不对退出 2。
shortcut: 靠正则读 Kotlin 源码（每个文件一个顶层类；@Test 与 fun 之间只许有空白与别的注解）。读不懂的写法不会被悄悄丢掉：
@Test 的个数对不上解析出的方法数就抛 ValueError；日志里出现源码里没有的测试也算问题。
测试里还钉着真实源码目录的几个关键测试，读不到时会失败而不是悄悄放行。
"""
import pathlib
import re
import sys

_PACKAGE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
_CLASS = re.compile(r"^class\s+(\w+)", re.MULTILINE)
_COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)
# 注解可以带参数，参数里的字符串可以含括号：@Ignore("等设备")、@Throws(Exception::class)
_ANNOTATION = r'@\w+(?:\((?:[^()"]|"(?:[^"\\]|\\.)*")*\))?'
_TEST_FUN = re.compile(rf"@Test\b(?:\s*{_ANNOTATION})*\s*fun\s+(\w+)")
_TEST_MARK = re.compile(r"@Test\b")

# am instrument -r 的状态码（android.test.InstrumentationTestRunner / AndroidJUnitRunner）
_BAD = {-1: "出错", -2: "失败", -3: "被忽略", -4: "假设失败（等于跳过）"}


def expected_tests(src_dir) -> set:
    """源码里的每个 @Test，形如 `app.auri.android.EnginePinTest#方法名`。"""
    names = set()
    for path in sorted(pathlib.Path(src_dir).rglob("*.kt")):
        text = _COMMENTS.sub(" ", path.read_text(encoding="utf-8"))
        methods = _TEST_FUN.findall(text)
        marks = len(_TEST_MARK.findall(text))
        if marks != len(methods):
            raise ValueError(f"{path.name} 里有 {marks} 个 @Test，只读出 {len(methods)} 个方法名：写法超出了本脚本能读的范围，不能悄悄漏掉")
        if not methods:
            continue
        package, cls = _PACKAGE.search(text), _CLASS.search(text)
        if package is None or cls is None:
            raise ValueError(f"{path.name} 里有 @Test，但读不出包名或顶层类名")
        names.update(f"{package.group(1)}.{cls.group(1)}#{method}" for method in methods)
    return names


def parse_log(log: str) -> dict:
    """{类#方法: [状态码, ...]}，按出现顺序；1 是开始，0 是通过。"""
    seen: dict = {}
    current: dict = {}
    for line in log.replace("\r", "").splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            current["class"] = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            current["test"] = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            if "class" in current and "test" in current:
                seen.setdefault(f"{current['class']}#{current['test']}", []).append(int(line.split(": ", 1)[1]))
            current = {}
    return seen


def check(log: str, src_dir) -> list:
    expected = expected_tests(src_dir)
    if not expected:
        return [f"{src_dir} 里没有找到任何 @Test：目录不对或测试被清空，无法核对"]
    seen = parse_log(log)
    problems = []
    for name in sorted(expected):
        codes = seen.get(name)
        if codes is None:
            problems.append(f"{name}: 日志里没有这个测试的记录（被过滤、被删除，或进程没跑到它）")
            continue
        bad = next((c for c in codes if c in _BAD), None)
        if bad is not None:
            problems.append(f"{name}: {_BAD[bad]}")
        elif 0 not in codes:
            problems.append(f"{name}: 只有开始记录，没有结束记录（进程中途崩溃？）")
    for name in sorted(set(seen) - expected):
        detail = next((_BAD[c] for c in seen[name] if c in _BAD), "有记录")
        problems.append(f"{name}: 日志里有，源码里找不到这个测试（{detail}）：读源码漏了它，或设备上装的是旧版测试 APK")
    return problems


def main(argv: list) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    log = pathlib.Path(argv[1]).read_text(encoding="utf-8", errors="replace")
    problems = check(log, argv[2])
    for problem in problems:
        print(problem)
    print(f"{len(expected_tests(argv[2]))} 个测试，{len(problems)} 个问题")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
