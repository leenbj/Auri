"""校验 Android 耳机控制这条线的产出是否满足合同 1.1（Ticket 17）。

沿用 Ticket 16 的 execution/acceptance/android-capture/capture_contract_check.py 的做法（读它，不复制、不改它）：
真实 contracts/schemas/*.json、注册方式与 tests/contract/conftest.py 相同、报错不回显来自输出文件的值。
检查两类文件：

  headset-profile.json                  → headset-profile.schema.json
  normalized-control-event-<名字>.json   → adapter.schema.json#/$defs/normalized_control_event

每类三层：
  1. 按真实 schema 校验；
  2. 合同 schema 表达不了、但产品规则要求的语义（从规格推导，不是照抄输出）：
     档案——Android 的媒体会话看不到原始手势（raw_gesture_visible 必须是 false，不猜左右耳）；命令映射里没有 volume_change
     （那是 iOS 的，ADR-0014），next / previous 只能忽略；厂商与固件未知必须是 null（不猜）；profile_id 由型号推出；
     start_after_process_termination 永远未测试（进程被杀后没有任何组件能收到按键）；
     兼容结论只能来自真机 + 真实耳机（E3，证据 id 的环境标签是 real-device）；E2 证据只能说明“跑过”，状态保持 NOT_TESTED，
     证据 id 的环境标签必须是那次校准真实所处的环境（单测 / 模拟器 / 未确认），不得冒充 real-device；
     事件——Android 媒体键的来源只能是 headset_remote 或 unknown_remote（不是屏幕按钮也不是音量手势）；事件标识各不相同、
     同一个耳机档案映射出的事件 profile_id / profile_version 一致（平台事件的取值范围合同 schema 已经按来源约束，不重复检查）；
  3. 对 Kotlin 测试造的那四个模拟按键，按规格逐个核对结果（来源、平台事件、命令、序号、时间戳取舍、原始标识）。

期望写成：
  profile:untested[:<环境>]   一轮没有任何观察：五项能力全是 NOT_TESTED，没有证据等级也没有证据 id；
  profile:e2-only[:<环境>]    非真机环境下跑完整的一轮：四个场景覆盖的能力是 NOT_TESTED 且带 E2 证据，第五项仍是未测试；
  events                      四个模拟按键归一化后的控制事件。
  <环境> 取 real-device / emulator / unconfirmed / unit-test，写了就钉死证据 id 里的环境标签。

命令行：
  python control_contract_check.py <输出根目录> --preset jvm                  # 预设清单，严格模式：多出来的场景目录也算问题
  python control_contract_check.py <输出根目录> <场景名>=<期望> [...]
  python control_contract_check.py --profile <headset-profile.json>           # 校验从手机取回的真实档案（产品负责人用）
有任何问题就打印并以 1 退出。
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]


def _load_module(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Ticket 16 的检查器：真实 schema 的注册与“不回显值”的报错写法都用它的
_base = _load_module("capture_contract_check", HERE.parent / "android-capture" / "capture_contract_check.py")
schema_problems = _base.schema_problems

PROFILE_FILE = "headset-profile.json"
PROFILE_SCHEMA = "headset-profile.schema.json"
EVENT_SCHEMA = "adapter.schema.json#/$defs/normalized_control_event"
EVENT_FILE = re.compile(r"^normalized-control-event-([a-z0-9-]+)\.json$")

CAPABILITIES = (
    "foreground_start_stop",
    "stop_while_bluetooth_mic_active",
    "continue_recording_when_locked",
    "restart_after_stop_when_locked",
    "start_after_process_termination",
)
SCENARIO_CAPABILITIES = CAPABILITIES[:4]  # 四个校准场景能覆盖的四项；第五项测不到
UNTESTED = {"status": "NOT_TESTED", "evidence_level": None, "evidence_ids": []}
NO_CONCLUSION = ("NOT_TESTED", "CALIBRATION_REQUIRED")
ENVIRONMENTS = ("real-device", "emulator", "unconfirmed", "unit-test")
STEP_KEYS = ("idle-events", "gesture-while-recording", "restart-after-stop", "screen-locked")
EVIDENCE_ID = re.compile(
    r"^calibration:[A-Za-z0-9._-]+:(?:" + "|".join(STEP_KEYS) + r"):attempt-\d+:[a-z-]+:(" + "|".join(ENVIRONMENTS) + r")$"
)
MEDIA_SOURCES = {"headset_remote", "unknown_remote"}

# Kotlin 的 ControlContractOutputTest 造的四个模拟按键，按规格应当得到的结果：
#   名字: (来源, 平台事件, 命令, 序号, 时间戳 ns, 原始标识)
# 来源：系统证明来自外接设备才是 headset_remote，没说清就是 unknown_remote；停止键映射成 pause 且只能停止；
# 时间戳：平台给了可信的手势时刻就用它，否则用接收时刻；下一首永远忽略；没有原始标识就是 null。
EXPECTED_EVENTS = {
    "headset-hook-external": ("headset_remote", "toggle", "TOGGLE", 0, 1_200_000_000, "key:79:a:0:t:1200"),
    "play-unknown-source": ("unknown_remote", "play", "START", 1, 5_000_000_000, None),
    "stop-key-is-pause": ("headset_remote", "pause", "STOP", 2, 9_000_000_000, "key:86:a:0:t:9000"),
    "next-is-ignored": ("headset_remote", "next", "IGNORE", 3, 20_000_000_000, "key:87:a:0:t:20000"),
}

# ControlContractOutputTest 写出的场景与各自的期望
EXPECTED_JVM_SCENARIOS = {
    "no-observations-real-device": "profile:untested:real-device",
    "no-observations-emulator": "profile:untested:emulator",
    "no-observations-unconfirmed": "profile:untested:unconfirmed",
    "no-observations-unit-test": "profile:untested:unit-test",
    "e2-full-run-unit-test": "profile:e2-only:unit-test",
    "e2-full-run-emulator": "profile:e2-only:emulator",
    "e2-full-run-unconfirmed": "profile:e2-only:unconfirmed",
    "events": "events",
}
PRESETS = {"jvm": EXPECTED_JVM_SCENARIOS}


def profile_id_for(model_hint: str | None) -> str:
    """与 Kotlin 的 profileIdFor 同一个规则：android- 加上型号里小写字母与数字的部分，什么都不剩就是 android-headset。"""
    slug = re.sub(r"[^a-z0-9]+", "-", (model_hint or "").lower()).strip("-")
    return "android-headset" if not slug else ("android-" + slug)[:100].rstrip("-")


def _load(path: pathlib.Path, label: str, problems: list[str]):
    if not path.is_file():
        problems.append(f"{label}: 文件不存在")
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeDecodeError) as e:
        # 只说异常类型（和 JSON 的行列号）：UnicodeDecodeError 的消息里有出错的那个字节，不回显文件内容
        where = f"，第 {e.lineno} 行第 {e.colno} 列" if isinstance(e, json.JSONDecodeError) else ""
        problems.append(f"{label}: 不是有效的 UTF-8 JSON（{type(e).__name__}{where}）")
        return None
    if not isinstance(value, dict):
        problems.append(f"{label}: 顶层必须是 JSON 对象")
        return None
    return value


def _evidence_label(evidence_id: str) -> str | None:
    match = EVIDENCE_ID.match(evidence_id)
    return match.group(1) if match else None


def profile_problems(profile: dict, *, environment: str | None = None, expect: str | None = None) -> list[str]:
    """第 2 层：档案的语义。profile 已经通过 schema（字段齐全、类型正确）。值一律不回显。"""
    problems: list[str] = []
    if profile["platform"] != "android":
        problems.append("platform 必须是 android（这是 Android 校准向导的产出）")
    if profile["raw_gesture_visible"] is not False:
        problems.append("raw_gesture_visible 必须是 false：Android 的媒体会话只给平台事件，看不到原始手势，不假设存在双击、不猜左右耳（FR-HS-001）")
    mapping = profile["command_mapping"]
    if "volume_change" in mapping:
        problems.append("command_mapping 里不该有 volume_change：那是 iOS 录音中停止的信号（ADR-0014），Android 没有")
    for kind in ("next", "previous"):
        if kind in mapping and mapping[kind] != "IGNORE":
            problems.append(f"command_mapping.{kind} 只能是 IGNORE：下一首 / 上一首永远不触发录音")
    for field in ("manufacturer_hint", "firmware_constraint"):
        if profile[field] is not None:
            problems.append(f"{field} 必须是 null：校准向导不知道厂商与固件，未知不得猜测（值不回显）")
    if profile["profile_id"] != profile_id_for(profile["model_hint"]):
        problems.append("profile_id 与 model_hint 推出的标识不一致（应为 android- 加上型号里的小写字母与数字，没有型号则是 android-headset；值不回显）")

    capabilities = profile["capabilities"]
    if capabilities["start_after_process_termination"] != UNTESTED:
        problems.append("capabilities.start_after_process_termination 必须保持未测试（NOT_TESTED、无证据等级、无证据 id）：进程被杀后没有任何组件能收到按键，校准测不到")
    for name in CAPABILITIES:
        problems += _capability_problems(name, capabilities[name], environment)

    if expect == "untested":
        for name in CAPABILITIES:
            if capabilities[name] != UNTESTED:
                problems.append(f"capabilities.{name}: 一轮没有任何观察的校准，这一项必须是未测试（NOT_TESTED、无证据等级、无证据 id）")
    elif expect == "e2-only":
        for name in SCENARIO_CAPABILITIES:
            cap = capabilities[name]
            if cap["status"] != "NOT_TESTED" or cap["evidence_level"] != "E2" or not cap["evidence_ids"]:
                problems.append(f"capabilities.{name}: 非真机环境下跑完整的一轮，这一项必须是 NOT_TESTED 且带 E2 证据（E2 只能说明跑过，不是兼容结论）")
    return problems


def _capability_problems(name: str, cap: dict, environment: str | None) -> list[str]:
    problems: list[str] = []
    where = f"capabilities.{name}"
    status, level, ids = cap["status"], cap["evidence_level"], cap["evidence_ids"]
    labels = [_evidence_label(i) for i in ids]
    if any(label is None for label in labels):
        problems.append(f"{where}: 证据 id 的格式不对（应为 calibration:<运行>:<步骤>:attempt-N:<检查>:<环境>；值不回显）")
    labels = [label for label in labels if label is not None]

    if status in NO_CONCLUSION:
        if level not in (None, "E2"):
            problems.append(f"{where}: 没有兼容结论的能力，证据等级只能是空或 E2（E2 只能说明跑过）")
        if (level is None) != (not ids):
            problems.append(f"{where}: 证据等级与证据 id 必须同时有或同时没有")
        if "real-device" in labels:
            problems.append(f"{where}: E2 证据的 id 不得声称环境是 real-device")
    else:
        if level != "E3":
            problems.append(f"{where}: 兼容结论只能来自真机 + 真实耳机的 E3 证据")
        if any(label != "real-device" for label in labels):
            problems.append(f"{where}: 兼容结论的证据 id 必须全部来自 real-device 环境")

    if environment is not None:
        if environment != "real-device" and status not in NO_CONCLUSION:
            problems.append(f"{where}: 在 {environment} 环境下不可能有兼容结论")
        if any(label != environment for label in labels):
            problems.append(f"{where}: 证据 id 的环境标签与这轮校准所处的环境（{environment}）不一致")
    return problems


def check_profile_file(path: pathlib.Path, label: str, *, environment: str | None = None, expect: str | None = None) -> list[str]:
    problems: list[str] = []
    profile = _load(path, label, problems)
    if profile is None:
        return problems
    problems += schema_problems(label, PROFILE_SCHEMA, profile)
    if problems:
        return problems  # schema 没过：语义检查没有意义（也会因字段缺失而崩）
    return [f"{label}: {p}" for p in profile_problems(profile, environment=environment, expect=expect)]


def event_problems(name: str, event: dict, expected: tuple | None) -> list[str]:
    """第 2、3 层：单个事件的语义；expected 是 EXPECTED_EVENTS 里的那一条（没有就只查通用规则）。值一律不回显。"""
    label = f"normalized-control-event-{name}.json"
    problems: list[str] = []
    if event["source"] not in MEDIA_SOURCES:
        problems.append(f"{label}: Android 媒体键的来源只能是 headset_remote 或 unknown_remote（不是屏幕按钮，也不是音量手势）")
    if expected is None:
        return problems
    source, platform_event, command, sequence, monotonic_ns, raw_event_id = expected
    for field, want in (
        ("source", source), ("platform_event", platform_event), ("command", command),
        ("sequence", sequence), ("monotonic_ns", monotonic_ns), ("raw_event_id", raw_event_id),
    ):
        if event[field] != want:
            problems.append(f"{label}: {field} 与按规格应得的结果不一致（值不回显）")
    return problems


def check_events_dir(dir_: pathlib.Path) -> list[str]:
    problems: list[str] = []
    files = sorted(p for p in dir_.iterdir() if p.is_file())
    names = {}
    for path in files:
        match = EVENT_FILE.match(path.name)
        if match is None:
            problems.append(f"{path.name}: 不在预期里的文件（事件文件必须叫 normalized-control-event-<名字>.json）")
        else:
            names[match.group(1)] = path
    for name in EXPECTED_EVENTS:
        if name not in names:
            problems.append(f"normalized-control-event-{name}.json: 文件不存在")
    for name in names:
        if name not in EXPECTED_EVENTS:
            problems.append(f"normalized-control-event-{name}.json: 不在预期清单里：新的事件必须登记到 control_contract_check.py，否则它没人按规格核对")

    events = {}
    for name, path in names.items():
        event = _load(path, path.name, problems)
        if event is None:
            continue
        found = schema_problems(path.name, EVENT_SCHEMA, event)
        problems += found
        if not found:
            events[name] = event
    for name, event in events.items():
        problems += event_problems(name, event, EXPECTED_EVENTS.get(name))
    ids = [e["event_id"] for e in events.values()]
    if len(set(ids)) != len(ids):
        problems.append("事件标识（event_id）有重复")
    profiles = {(e["profile_id"], e["profile_version"]) for e in events.values()}
    if len(profiles) > 1:
        problems.append("同一个耳机档案映射出的事件，profile_id / profile_version 必须一致")
    return problems


def check_scenario(dir_: pathlib.Path, expectation: str) -> list[str]:
    kind, _, rest = expectation.partition(":")
    environment = rest.partition(":")[2] or None
    flavor = rest.partition(":")[0]
    if kind == "events" and not rest:
        return check_events_dir(dir_)
    if kind == "profile" and flavor in ("untested", "e2-only") and (environment is None or environment in ENVIRONMENTS):
        return check_profile_file(dir_ / PROFILE_FILE, PROFILE_FILE, environment=environment, expect=flavor)
    return [f"未知的期望 {expectation!r}（profile:untested[:环境] / profile:e2-only[:环境] / events；环境取 {list(ENVIRONMENTS)}）"]


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
            f"目录 {d.name} 不在预期清单里：新场景必须登记到 control_contract_check.py，否则它的输出没人校验"
            for d in sorted(root.iterdir()) if d.is_dir() and d.name not in expected
        ]
    return problems


def _owner_profile(path: pathlib.Path) -> int:
    problems = check_profile_file(path, "headset-profile.json")
    for p in problems:
        print(p)
    if problems:
        print(f"1 份档案，{len(problems)} 个问题")
        return 1
    profile = json.loads(path.read_text(encoding="utf-8"))
    print("档案通过合同校验与语义检查。校准结论（只列状态与证据等级，不列证据 id）：")
    for name in CAPABILITIES:
        cap = profile["capabilities"][name]
        print(f"  {name}: {cap['status']}（证据等级 {cap['evidence_level'] or '无'}）")
    print("1 份档案，0 个问题")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "--profile":
        return _owner_profile(pathlib.Path(argv[2]))
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
