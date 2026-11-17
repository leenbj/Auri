"""校验 Android 转录测试写出的合同 JSON（Ticket 14 第 6 项）。

三层检查，任何一层不过都是失败，没有“跳过”：
  1. 每个文件按真实的 contracts/schemas/*.json 校验（注册方式与 tests/contract/conftest.py 相同）；
  2. 合同的语义规则：SR-01（正文哈希按原始 UTF-8 字节）与 SR-05（执行位置只能是 on_device），
     直接复用 tests/contract/semantic_rules.py 的参考实现（只读，不修改合同测试）；
  3. 转录状态机轨迹按 contracts/fixtures/state-machines/transcription.machine.json 逐步重放，
     并核对终态与文件（修订内容、错误码）互相一致。

期望写成 <终态> 或 <终态>:<错误码>（后者钉死具体错误码，例如 WAITING_MODEL:MODEL_INTEGRITY_FAILED）。
命令行：
  python contract_check.py <输出根目录> <场景名>=<期望> [...]
  python contract_check.py <输出根目录> --preset jvm|device|runs     # 预设清单，严格模式：多出来的场景目录也算问题
有任何问题就打印并以 1 退出；这是 Android 模拟器/真机输出的校验入口（见 README）。
"""
import json
import pathlib
import sys

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "tests" / "contract"))
import semantic_rules  # noqa: E402

SCHEMA_DIR = REPO_ROOT / "contracts" / "schemas"
MACHINE_FILE = REPO_ROOT / "contracts" / "fixtures" / "state-machines" / "transcription.machine.json"
BASE_URI = "https://contracts.auri.invalid/1.1/"

# 文件名 → 它必须满足的 schema
FILE_SCHEMAS = {
    "capabilities.json": "adapter.schema.json#/$defs/asr_capabilities",
    "task-handle.json": "adapter.schema.json#/$defs/asr_task_handle",
    "revision-content.json": "revision.schema.json#/$defs/content",
    "error.json": "error.schema.json",
}
REQUIRED_FILES = ("capabilities.json", "task-handle.json", "trace.json")

# JVM 单元测试（ContractOutputTest）导出的场景与各自的期望（终态[:错误码]）
EXPECTED_JVM_SCENARIOS = {
    "succeeded": "SUCCEEDED",
    "no-speech-empty-audio": "NO_SPEECH:ASR_NO_SPEECH",
    "no-speech-blank-text": "NO_SPEECH:ASR_NO_SPEECH",
    "waiting-model-not-installed": "WAITING_MODEL:MODEL_NOT_INSTALLED",
    "waiting-model-integrity-failed": "WAITING_MODEL:MODEL_INTEGRITY_FAILED",
    "failed-duration-limit": "FAILED:ASR_RESOURCE_LIMIT",
    "failed-engine-exception": "FAILED",
}

# 设备（模拟器/真机）仪器测试写在 files/contract-out/ 下的场景；名字与 app/src/androidTest 里的 outDir 对应
EXPECTED_DEVICE_SCENARIOS = {
    **{f"real-{lang}": "SUCCEEDED" for lang in ("zh", "en", "ja", "ko", "yue")},
    "real-silence-hallucination": "SUCCEEDED",  # 已知限制：引擎对纯静音也会出字，见 README
    "real-zh-16k-direct": "SUCCEEDED",
    "real-zh-48k": "SUCCEEDED",
    "real-zh-44k1": "SUCCEEDED",
    "refused-corrupted": "WAITING_MODEL:MODEL_INTEGRITY_FAILED",
    "refused-truncated": "WAITING_MODEL:MODEL_INTEGRITY_FAILED",
    "refused-not-installed": "WAITING_MODEL:MODEL_NOT_INSTALLED",
    "refused-then-ok-1": "WAITING_MODEL:MODEL_NOT_INSTALLED",
    "refused-then-ok-2": "SUCCEEDED",
    "airplane-zh": "SUCCEEDED",
}

# 调试入口 TranscribeFileActivity 写在 files/runs/ 下的场景：仪器测试的 activity-zh，
# 加上 run_device_acceptance.sh 的三次冷启动基准 bench-1..3（脚本里 COLD_RUNS=3，两处要一起改）
EXPECTED_DEVICE_RUNS = {"activity-zh": "SUCCEEDED", **{f"bench-{i}": "SUCCEEDED" for i in (1, 2, 3)}}

PRESETS = {
    "jvm": EXPECTED_JVM_SCENARIOS,
    "device": EXPECTED_DEVICE_SCENARIOS,
    "runs": EXPECTED_DEVICE_RUNS,
}

# 终态 → (是否必须有修订内容, 允许的错误码；None 表示可以没有 error.json)
# FAILED 允许没有错误文件：合同 1.1 没有“引擎内部失败”的错误码，不硬套一个（README 的合同缺口）。
STATE_RULES = {
    "SUCCEEDED": (True, set()),
    "NO_SPEECH": (False, {"ASR_NO_SPEECH"}),
    "WAITING_MODEL": (False, {"MODEL_NOT_INSTALLED", "MODEL_INTEGRITY_FAILED"}),
    "FAILED": (False, {"ASR_RESOURCE_LIMIT", None}),
}

text_sha256 = semantic_rules.text_sha256

_SCHEMAS = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(SCHEMA_DIR.glob("*.schema.json"))]
_REGISTRY = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in _SCHEMAS)
_MACHINE = json.loads(MACHINE_FILE.read_text(encoding="utf-8"))


def _flatten(errors):
    for error in errors:
        yield error
        yield from _flatten(error.context or [])


def schema_problems(name: str, schema_ref: str, instance) -> list[str]:
    validator = Draft202012Validator({"$ref": BASE_URI + schema_ref}, registry=_REGISTRY)
    found = []
    for error in _flatten(validator.iter_errors(instance)):
        pointer = "".join(f"/{part}" for part in error.absolute_path) or "/"
        found.append(f"{name}: schema {schema_ref} 不通过 {pointer}：{error.message}")
    return found


def _load(dir_: pathlib.Path, name: str, problems: list[str], required: bool):
    path = dir_ / name
    if not path.is_file():
        if required:
            problems.append(f"{name}: 文件不存在")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeDecodeError) as e:
        problems.append(f"{name}: 不是有效的 UTF-8 JSON（{e}）")
        return None


def _replay(trace: dict) -> tuple[list[str], str | None]:
    """按合同转移表重放轨迹，返回问题与到达的终态。"""
    problems = []
    if trace.get("machine") != _MACHINE["machine"]:
        problems.append(f"trace.json: machine 是 {trace.get('machine')!r}，应为 {_MACHINE['machine']!r}")
    state = trace.get("initial_state")
    if state != _MACHINE["initial_state"]:
        problems.append(f"trace.json: initial_state 是 {state!r}，合同要求 {_MACHINE['initial_state']!r}")
    table = {(t["from"], t["event"]): t for t in _MACHINE["transitions"]}
    for number, step in enumerate(trace.get("steps", []), start=1):
        event = step.get("event")
        transition = table.get((state, event))
        if transition is None or transition["outcome"] != "transition":
            problems.append(f"trace.json: 第 {number} 步，状态 {state} 不接受事件 {event}")
            return problems, None
        if step.get("state_after") != transition["to"]:
            problems.append(
                f"trace.json: 第 {number} 步，事件 {event} 应到达 {transition['to']}，轨迹写的是 {step.get('state_after')}"
            )
            return problems, None
        state = transition["to"]
    return problems, state


def check_scenario(dir_: pathlib.Path, expectation: str) -> list[str]:
    expected_state, _, code_text = expectation.partition(":")
    expected_code = code_text or None
    if expected_state not in STATE_RULES:
        return [f"未知的期望终态 {expected_state!r}（已知：{sorted(STATE_RULES)}）"]
    problems: list[str] = []
    docs = {name: _load(dir_, name, problems, required=name in REQUIRED_FILES) for name in (*REQUIRED_FILES, "revision-content.json", "error.json")}

    for name, schema_ref in FILE_SCHEMAS.items():
        if docs.get(name) is not None:
            problems += schema_problems(name, schema_ref, docs[name])

    content, caps, handle, error = docs["revision-content.json"], docs["capabilities.json"], docs["task-handle.json"], docs["error.json"]
    if content is not None and isinstance(content.get("text"), str):
        if text_sha256(content["text"]) != content.get("text_sha256"):
            problems.append("revision-content.json: SR-01 text_sha256 与正文按 UTF-8 原始字节算出的哈希不符")
        if content.get("execution_location") != "on_device":
            problems.append("revision-content.json: SR-05 execution_location 必须是 on_device")
    if caps is not None and isinstance(caps.get("execution_location"), str):
        problems += [f"capabilities.json: {rule} execution_location 必须是 on_device" for rule in semantic_rules.violations(FILE_SCHEMAS["capabilities.json"], caps)]

    final_state = None
    if docs["trace.json"] is not None:
        replay, final_state = _replay(docs["trace.json"])
        problems += replay
        if final_state is not None and final_state != expected_state:
            problems.append(f"trace.json: 终态是 {final_state}，期望 {expected_state}")

    state = final_state or expected_state
    needs_revision, allowed_codes = STATE_RULES[state]
    if needs_revision and content is None:
        problems.append(f"终态 {state} 必须有 revision-content.json")
    if not needs_revision and content is not None:
        problems.append(f"终态 {state} 不得有 revision-content.json（没有产出文本就不该有修订）")
    if state == "SUCCEEDED" and error is not None:
        problems.append("终态 SUCCEEDED 不应有 error.json")
    if state != "SUCCEEDED":
        code = None if error is None else error.get("code")
        if code not in allowed_codes:
            problems.append(f"终态 {state} 的错误码 {code!r} 不在允许的 {sorted(c for c in allowed_codes if c)} 里")
        if expected_code is not None and code != expected_code:
            reason = "但没有 error.json" if error is None else f"实际是 {code!r}"
            problems.append(f"终态 {state} 期望错误码 {expected_code}，{reason}")
    if error is not None and handle is not None and error.get("operation_id") != handle.get("job_id"):
        problems.append("error.json: operation_id 必须等于 task-handle.json 的 job_id")
    return problems


def check_tree(root: pathlib.Path, expected: dict[str, str], *, strict: bool = False) -> list[str]:
    problems: list[str] = []
    for name, expectation in expected.items():
        dir_ = root / name
        if not dir_.is_dir():
            problems.append(f"场景目录 {name} 不存在（{root}）")
            continue
        problems += [f"[{name}] {p}" for p in check_scenario(dir_, expectation)]
    if strict and root.is_dir():
        problems += [
            f"目录 {d.name} 不在预期清单里：新场景必须登记到 contract_check.py，否则它的输出没人校验"
            for d in sorted(root.iterdir()) if d.is_dir() and d.name not in expected
        ]
    return problems


def main(argv: list[str]) -> int:
    if len(argv) == 4 and argv[2] == "--preset":
        if argv[3] not in PRESETS:
            print(f"未知的预设 {argv[3]!r}，可选：{sorted(PRESETS)}", file=sys.stderr)
            return 2
        expected, strict = PRESETS[argv[3]], True
    elif len(argv) >= 3 and all("=" in a for a in argv[2:]):
        expected, strict = dict(a.split("=", 1) for a in argv[2:]), False
    else:
        print(__doc__, file=sys.stderr)
        return 2
    problems = check_tree(pathlib.Path(argv[1]), expected, strict=strict)
    for p in problems:
        print(p)
    print(f"{len(expected)} 个场景，{len(problems)} 个问题")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
