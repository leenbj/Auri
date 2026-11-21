"""校验 Android 采集 → 转录样本输出是否满足合同 1.2（Ticket 16）。

沿用 Ticket 14 的 execution/acceptance/asr-android/contract_check.py 的做法（真实 contracts/schemas/*.json、注册方式与
tests/contract/conftest.py 相同、报错不回显来自输出文件的值），但检查的是另一组文件：Kotlin 测试（:capture 的 ContractOutputTest）
把一次“录音 → 封存 → 转录 → Revision”的产物写成每个场景一个目录，本工具逐个目录检查三层：

  1. 每个文件按真实 schema 校验：
       recording-result.json → adapter.schema.json#/$defs/recording_result
       route-evidence.json   → adapter.schema.json#/$defs/route_change_event
       capabilities.json     → adapter.schema.json#/$defs/asr_capabilities
       task-handle.json      → adapter.schema.json#/$defs/asr_task_handle
       capture-block.json    → revision.schema.json#/$defs/capture   （从本地 Record 组装，Ticket 21 提交时用）
       revision-content.json → revision.schema.json#/$defs/content   （从本地 Revision 组装）
  2. 合同的语义规则（复用 tests/contract/semantic_rules.py 的参考实现，只读）：
       SR-02/03/04（时间段升序不重叠、不越界、时长之和相符）、SR-01/05/06（正文哈希、执行位置、1 MiB）。
  3. 文件之间必须互相一致（单个 schema 表达不了）：
       - 路由证据必须是 verified，且属于这个 Capture Session；音频块只能来自这个证据的路由纪元（没有“来自别的路由”的音频）；
       - capture 段与录音结果是同一次录音（状态、结束原因、两个时长、语音片段、输入类型、耳机名）；
       - Revision 写明的引擎与模型就是转录能力声明里的那个（Revision 必须写明用了哪个模型）。

期望写成 <种类>[:<录音状态>]：
  with-text   有文字：必须有 capture-block.json 与 revision-content.json，且录音里有音频块与语音片段；
  no-speech   路由已确认但没有语音：必须没有 capture-block.json 与 revision-content.json（不编造文字，也不向网关提交）。
  后缀 :complete / :partial 钉死录音状态。
命令行：
  python capture_contract_check.py <输出根目录> <场景名>=<期望> [...]
  python capture_contract_check.py <输出根目录> --preset jvm      # 预设清单，严格模式：多出来的场景目录也算问题
有任何问题就打印并以 1 退出。
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
BASE_URI = "https://contracts.auri.invalid/1.2/"

FILE_SCHEMAS = {
    "recording-result.json": "adapter.schema.json#/$defs/recording_result",
    "route-evidence.json": "adapter.schema.json#/$defs/route_change_event",
    "capabilities.json": "adapter.schema.json#/$defs/asr_capabilities",
    "task-handle.json": "adapter.schema.json#/$defs/asr_task_handle",
    "capture-block.json": "revision.schema.json#/$defs/capture",
    "revision-content.json": "revision.schema.json#/$defs/content",
}
REQUIRED_FILES = ("recording-result.json", "route-evidence.json", "capabilities.json", "task-handle.json")
TEXT_FILES = ("capture-block.json", "revision-content.json")

# :capture 的 ContractOutputTest 写出的场景与各自的期望
EXPECTED_JVM_SCENARIOS = {
    "complete-with-text": "with-text:complete",
    "complete-no-speech": "no-speech:complete",
    "partial-route-lost": "with-text:partial",
}
PRESETS = {"jvm": EXPECTED_JVM_SCENARIOS}

KINDS = ("with-text", "no-speech")
STATUSES = ("complete", "partial")

text_sha256 = semantic_rules.text_sha256

_SCHEMAS = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(SCHEMA_DIR.glob("*.schema.json"))]
_REGISTRY = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in _SCHEMAS)


def _flatten(errors):
    for error in errors:
        yield error
        yield from _flatten(error.context or [])


def _describe(error) -> str:
    """校验器的输出会被贴进报告：凡是来自实例的东西（值、多余字段的名字）一律不说，只说路径、违反的约束和合同里的要求。"""
    if error.validator == "required":
        return error.message  # 只含 schema 里写死的字段名
    if error.validator in ("additionalProperties", "unevaluatedProperties"):
        return f"违反 {error.validator} 约束：出现了合同未声明的字段（字段名来自输出文件，不回显）"
    return f"违反 {error.validator} 约束（要求 {repr(error.validator_value)[:200]}）"


def schema_problems(name: str, schema_ref: str, instance) -> list[str]:
    validator = Draft202012Validator({"$ref": BASE_URI + schema_ref}, registry=_REGISTRY)
    found = []
    for error in _flatten(validator.iter_errors(instance)):
        pointer = "".join(f"/{part}" for part in error.absolute_path) or "/"
        found.append(f"{name}: schema {schema_ref} 不通过 {pointer}：{_describe(error)}")
    return found


def _load(dir_: pathlib.Path, name: str, problems: list[str], required: bool):
    path = dir_ / name
    if not path.is_file():
        if required:
            problems.append(f"{name}: 文件不存在")
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeDecodeError) as e:
        # 只说异常类型（和 JSON 的行列号）：UnicodeDecodeError 的消息里有出错的那个字节，不回显文件内容
        where = f"，第 {e.lineno} 行第 {e.colno} 列" if isinstance(e, json.JSONDecodeError) else ""
        problems.append(f"{name}: 不是有效的 UTF-8 JSON（{type(e).__name__}{where}）")
        return None
    if not isinstance(value, dict):
        problems.append(f"{name}: 顶层必须是 JSON 对象")  # 不回显内容：它可能就是正文
        return None
    return value


def _same(problems: list[str], where: str, left, right, what: str) -> None:
    if left != right:
        problems.append(f"{where}: {what}不一致（值不回显）")


def _consistency(docs: dict, kind: str) -> list[str]:
    """第 3 层：文件之间互相一致。只对已经通过 schema 的文件做（缺的、坏的在第 1 层已经报过）。"""
    problems: list[str] = []
    result, evidence, caps = docs["recording-result.json"], docs["route-evidence.json"], docs["capabilities.json"]
    block, content = docs["capture-block.json"], docs["revision-content.json"]

    if result is not None and evidence is not None:
        _same(problems, "route-evidence.json", evidence.get("capture_session_id"), result.get("capture_session_id"), "capture_session_id 与录音结果的")
        if evidence.get("verification_state") != "verified":
            problems.append("route-evidence.json: 产生 Record 的路由证据必须是 verified（没确认的路由不会有 Record）")
        for number, chunk in enumerate(result.get("chunks", []), start=1):
            if chunk.get("route_epoch") != evidence.get("epoch"):
                problems.append(f"recording-result.json: 第 {number} 个音频块的 route_epoch 不是路由证据的 epoch（音频只能来自已确认的那条路由）")

    if kind == "with-text":
        if result is not None and (not result.get("chunks") or not result.get("verified_ranges")):
            problems.append("recording-result.json: 有文字的场景必须有音频块与保留的语音片段")
        if block is None:
            problems.append("capture-block.json: 有文字的场景必须有 capture 段")
        if content is None:
            problems.append("revision-content.json: 有文字的场景必须有 Revision 内容")
    else:
        for name in TEXT_FILES:
            if docs[name] is not None:
                problems.append(f"{name}: 没有语音的 Record 不得有这个文件（不编造文字，也不提交到网关）")

    if block is not None and result is not None:
        for field, source in (("status", "status"), ("end_reason", "end_reason"), ("capture_duration_ms", "capture_duration_ms"), ("speech_duration_ms", "speech_duration_ms")):
            _same(problems, "capture-block.json", block.get(field), result.get(source), f"{field} 与录音结果的")
        _same(problems, "capture-block.json", block.get("speech_segments"), result.get("verified_ranges"), "speech_segments 与录音结果 verified_ranges 的")
    if block is not None and evidence is not None:
        observed = evidence.get("observed_input") or {}
        _same(problems, "capture-block.json", block.get("input_route_type"), observed.get("route_type"), "input_route_type 与路由证据的")
        _same(problems, "capture-block.json", block.get("headset_display_name"), observed.get("display_name"), "headset_display_name 与路由证据的")
    if content is not None and caps is not None:
        for field in ("engine", "model_id", "model_version", "execution_location"):
            _same(problems, "revision-content.json", content.get(field), caps.get(field), f"{field} 与转录能力声明的")
    return problems


def check_scenario(dir_: pathlib.Path, expectation: str) -> list[str]:
    kind, _, status = expectation.partition(":")
    if kind not in KINDS or status not in ("", *STATUSES):
        return [f"未知的期望 {expectation!r}（种类 {list(KINDS)}，可加 :complete 或 :partial）"]
    problems: list[str] = []
    docs = {name: _load(dir_, name, problems, required=name in REQUIRED_FILES) for name in FILE_SCHEMAS}

    for name, schema_ref in FILE_SCHEMAS.items():
        if docs[name] is not None:
            problems += schema_problems(name, schema_ref, docs[name])
    if problems:
        return problems  # 文件缺失/损坏或 schema 没过：语义与一致性检查没有意义（也会因字段缺失而崩）

    result, caps, block, content = docs["recording-result.json"], docs["capabilities.json"], docs["capture-block.json"], docs["revision-content.json"]
    if result is not None:
        problems += [f"recording-result.json: {rule} 违反" for rule in semantic_rules.violations(FILE_SCHEMAS["recording-result.json"], result)]
        if status and result.get("status") != status:
            problems.append(f"recording-result.json: 录音状态与期望的 {status} 不符（实际值不回显）")
    if caps is not None:
        problems += [f"capabilities.json: {rule} 违反：execution_location 必须是 on_device" for rule in semantic_rules.violations(FILE_SCHEMAS["capabilities.json"], caps)]
    if block is not None and content is not None:
        submission = {"content": content, "capture": block}
        problems += [f"revision-content.json/capture-block.json: {rule} 违反" for rule in semantic_rules.violations("revision.schema.json#/$defs/submission", submission)]
    elif content is not None and isinstance(content.get("text"), str):
        problems += _text_rules(content)

    return problems + _consistency(docs, kind)


def _text_rules(content: dict) -> list[str]:
    found = []
    if text_sha256(content["text"]) != content.get("text_sha256"):
        found.append("revision-content.json: SR-01 text_sha256 与正文按 UTF-8 原始字节算出的哈希不符")
    if len(content["text"].encode("utf-8")) > semantic_rules.MAX_TEXT_BYTES:
        found.append("revision-content.json: SR-06 正文按 UTF-8 编码后超过 1 MiB")
    if content.get("execution_location") != "on_device":
        found.append("revision-content.json: SR-05 execution_location 必须是 on_device")
    return found


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
            f"目录 {d.name} 不在预期清单里：新场景必须登记到 capture_contract_check.py，否则它的输出没人校验"
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
