"""校验 Android 目的地与发送流程写出的请求是否满足合同 1.1（Ticket 22）。

沿用 Ticket 16 的 execution/acceptance/android-capture/capture_contract_check.py 的做法（真实 contracts/schemas/*.json、注册方式与
tests/contract/conftest.py 相同、报错不回显来自输出文件的值），但检查的是另一组文件：Kotlin 测试（:sync 的 DestinationContractOutputTest）
用真实的视图模型、服务与同步引擎，把**手机实际发给网关的请求**按场景写成目录，本工具逐个目录检查三层：

  1. 每个文件按真实 schema 校验：
       create-request.json → destination.schema.json#/$defs/create_request   （创建 Destination 的请求体）
       submission.json     → revision.schema.json#/$defs/submission           （PUT 入库的请求体，含 publication）
  2. 合同的语义规则（复用 tests/contract/semantic_rules.py 的参考实现，只读）：submission 的 SR-01/05/06 等。
  3. 场景自己的断言（单个 schema 表达不了）：
       create:<模式>        创建请求里的 send_mode 恰好是 <模式>（auto_send / preview_confirm）；没有 submission.json。
       submission:auto      自动发送：content.review_state 是 not_reviewed（未审核的初稿），publication.destination_ids 就是场景记下的目的地。
       submission:preview   预览确认：用户确认之后才有 submission.json；destination_ids 含用户确认的那个目的地；初稿仍是 not_reviewed。
       submission:action_request  标成“请求执行”的内容：publication.intent 是 action_request（只是数据上的标签）。
       held                 预览确认且用户没确认：**没有** submission.json，网关上 PUT 与 Delivery 都是 0。
     scenario.json（Kotlin 写的网关侧观察：put 次数、delivery 数、涉及的目的地编号）必须与 submission.json 互相一致。

命令行：
  python destination_contract_check.py <输出根目录> <场景名>=<期望> [...]
  python destination_contract_check.py <输出根目录> --preset jvm      # 预设清单，严格模式：多出来的场景目录也算问题
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
BASE_URI = "https://contracts.auri.invalid/1.1/"

FILE_SCHEMAS = {
    "create-request.json": "destination.schema.json#/$defs/create_request",
    "submission.json": "revision.schema.json#/$defs/submission",
}
SUBMISSION_SCHEMA = FILE_SCHEMAS["submission.json"]
SEND_MODES = ("auto_send", "preview_confirm")

# :sync 的 DestinationContractOutputTest 写出的场景与各自的期望
EXPECTED_JVM_SCENARIOS = {
    "create-auto-send": "create:auto_send",
    "create-preview-confirm": "create:preview_confirm",
    "auto-send-unreviewed": "submission:auto",
    "preview-held-before-confirm": "held",
    "preview-after-confirm": "submission:preview",
    "action-request-tag": "submission:action_request",
}
PRESETS = {"jvm": EXPECTED_JVM_SCENARIOS}

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


def _scenario_facts(scenario, problems: list[str]):
    """scenario.json 必须有的几样网关侧观察，类型不对就报（不回显值）。"""
    if scenario is None:
        return None
    ok = (
        isinstance(scenario.get("puts"), int)
        and isinstance(scenario.get("deliveries"), int)
        and isinstance(scenario.get("destination_ids"), list)
        and all(isinstance(i, str) for i in scenario["destination_ids"])
    )
    if not ok:
        problems.append("scenario.json: 必须有整数 puts、deliveries 与字符串数组 destination_ids")
        return None
    return scenario


def _expect_create(mode: str, create, submission, problems: list[str]) -> None:
    if create is None:
        problems.append("create-request.json: 创建场景必须有创建请求")
        return
    if create.get("send_mode") != mode:
        problems.append(f"create-request.json: send_mode 不是用户选的 {mode}（实际值不回显）")
    if submission is not None:
        problems.append("submission.json: 创建 Destination 的场景不应当有入库请求")


def _expect_submission(kind: str, submission, scenario, problems: list[str]) -> None:
    if submission is None:
        problems.append("submission.json: 这个场景必须有入库请求")
        return
    content, publication = submission["content"], submission["publication"]
    if kind in ("auto", "preview") and content.get("review_state") != "not_reviewed":
        problems.append("submission.json: 自动发送的、以及预览确认后发出的转录初稿必须是 not_reviewed（实际值不回显）")
    if kind == "action_request" and publication.get("intent") != "action_request":
        problems.append("submission.json: 标成请求执行的内容，publication.intent 必须是 action_request（实际值不回显）")
    if kind in ("auto", "preview") and publication.get("intent") != "context":
        problems.append("submission.json: 没有改过标签的内容，publication.intent 必须是默认的 context（实际值不回显）")
    ids = publication.get("destination_ids", [])
    if not ids:
        problems.append("submission.json: 这个场景的入库请求必须带着至少一个目的地（publication.destination_ids 为空就没有 Delivery）")
    if scenario is not None:
        if sorted(ids) != sorted(scenario["destination_ids"]):
            problems.append("submission.json 与 scenario.json: publication.destination_ids 与场景记下的目的地不一致（值不回显）")
        if scenario["puts"] != 1:
            problems.append("scenario.json: 这个场景网关应当恰好收到一次 PUT（重复确认、重试都不会多发）")
        if scenario["deliveries"] != len(ids):
            problems.append("scenario.json: 网关上的 Delivery 数必须等于入库请求里的目的地数")


def _expect_held(submission, scenario, problems: list[str]) -> None:
    if submission is not None:
        problems.append("submission.json: 预览确认且用户没确认：不得有入库请求（确认之前网关上不能有这条文字）")
    if scenario is None:
        problems.append("scenario.json: 必须有网关侧观察，才能证明确认之前一个 Delivery 也没有")
    elif scenario["puts"] != 0 or scenario["deliveries"] != 0:
        problems.append("scenario.json: 确认之前网关上的 PUT 与 Delivery 必须都是 0")


def check_scenario(dir_: pathlib.Path, expectation: str) -> list[str]:
    kind, _, detail = expectation.partition(":")
    valid = (kind == "create" and detail in SEND_MODES) or (kind == "submission" and detail in ("auto", "preview", "action_request")) or (kind == "held" and detail == "")
    if not valid:
        return [f"未知的期望 {expectation!r}（create:<模式>、submission:auto|preview|action_request、held）"]
    problems: list[str] = []
    docs = {name: _load(dir_, name, problems, required=False) for name in FILE_SCHEMAS}
    scenario = _scenario_facts(_load(dir_, "scenario.json", problems, required=kind != "create"), problems)

    for name, schema_ref in FILE_SCHEMAS.items():
        if docs[name] is not None:
            problems += schema_problems(name, schema_ref, docs[name])
    if problems:
        return problems  # 文件缺失/损坏或 schema 没过：语义与场景断言没有意义（也会因字段缺失而崩）

    submission = docs["submission.json"]
    if submission is not None:
        problems += [f"submission.json: {rule} 违反" for rule in semantic_rules.violations(SUBMISSION_SCHEMA, submission)]

    if kind == "create":
        _expect_create(detail, docs["create-request.json"], submission, problems)
    elif kind == "submission":
        _expect_submission(detail, submission, scenario, problems)
    else:
        _expect_held(submission, scenario, problems)
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
            f"目录 {d.name} 不在预期清单里：新场景必须登记到 destination_contract_check.py，否则它的输出没人校验"
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
