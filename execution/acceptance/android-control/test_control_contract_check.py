"""合同校验器自身的测试：它必须真的能拦住不合规的输出。

control_contract_check.py 检查 :capture 的 ControlContractOutputTest 写出的 JSON，也给产品负责人校验从手机取回的真实档案。
如果它对什么都说“通过”，test_control_contract_output.py 的通过就毫无意义，所以这里用手造的坏数据证明它会失败，
每一条语义规则至少有一个会让它失败的例子。好数据的基线取自合同自带的有效样本
（contracts/fixtures/messages/valid/headset-profile.untested-candidate.json），改成 Android 向导会产出的样子，不是自己编的。
"""
import copy
import json
import pathlib
import uuid

import pytest

import control_contract_check as cc

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
VALID = REPO_ROOT / "contracts" / "fixtures" / "messages" / "valid"
SECRET = "SECRET-PROFILE-MARKER"
ENVIRONMENTS = ("emulator", "unconfirmed", "unit-test")


def fixture_instance(name: str) -> dict:
    return json.loads((VALID / name).read_text(encoding="utf-8"))["instance"]


def evidence_id(step: str, name: str, env: str, attempt: int = 1, run: str = "cal-test") -> str:
    return f"calibration:{run}:{step}:attempt-{attempt}:{name}:{env}"


def untested_profile(model_hint: str | None = "FreeClip 2") -> dict:
    """一轮没有任何观察的向导产出：五项能力全是未测试。"""
    profile = fixture_instance("headset-profile.untested-candidate.json")
    profile.update(platform="android", manufacturer_hint=None, model_hint=model_hint, profile_id=cc.profile_id_for(model_hint))
    profile["command_mapping"] = {"play": "START", "pause": "STOP", "toggle": "TOGGLE", "next": "IGNORE", "previous": "IGNORE"}
    assert all(profile["capabilities"][n] == cc.UNTESTED for n in cc.CAPABILITIES)
    return profile


def e2_profile(env: str) -> dict:
    """非真机环境下完整的一轮：四项能力带 E2 证据但状态仍是未测试，第五项没测。"""
    profile = untested_profile()
    ids = {
        "foreground_start_stop": [evidence_id("gesture-while-recording", "stop-by-gesture", env), evidence_id("restart-after-stop", "start-by-gesture-after-stop", env)],
        "stop_while_bluetooth_mic_active": [evidence_id("gesture-while-recording", "stop-by-gesture", env)],
        "continue_recording_when_locked": [evidence_id("screen-locked", "recording-survived-lock", env)],
        "restart_after_stop_when_locked": [evidence_id("screen-locked", "start-by-gesture-while-locked", env)],
    }
    for name, found in ids.items():
        profile["capabilities"][name] = {"status": "NOT_TESTED", "evidence_level": "E2", "evidence_ids": found}
    return profile


def e3_profile() -> dict:
    """真机 + 真实耳机的校准：有结论，证据等级 E3，证据 id 的环境是 real-device。"""
    profile = untested_profile()
    caps = profile["capabilities"]
    caps["foreground_start_stop"] = {"status": "SUPPORTED_FOREGROUND", "evidence_level": "E3", "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "real-device"), evidence_id("restart-after-stop", "start-by-gesture-after-stop", "real-device")]}
    caps["stop_while_bluetooth_mic_active"] = {"status": "SUPPORTED_FOREGROUND", "evidence_level": "E3", "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "real-device")]}
    caps["continue_recording_when_locked"] = {"status": "SUPPORTED_RECORDING_IN_BACKGROUND", "evidence_level": "E3", "evidence_ids": [evidence_id("screen-locked", "recording-survived-lock", "real-device")]}
    caps["restart_after_stop_when_locked"] = {"status": "UNSUPPORTED", "evidence_level": "E3", "evidence_ids": [evidence_id("screen-locked", "owner-reported-no-effect", "real-device")]}
    return profile


def write(path: pathlib.Path, value) -> pathlib.Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def check(profile: dict, tmp_path, **kwargs) -> list[str]:
    return cc.check_profile_file(write(tmp_path / "headset-profile.json", profile), "headset-profile.json", **kwargs)


# ---------- 档案：好数据 ----------


@pytest.mark.parametrize("model_hint", [None, "FreeClip 2"])
@pytest.mark.parametrize("env", ["real-device", *ENVIRONMENTS])
def test_a_run_with_no_observations_is_valid_only_as_all_untested(tmp_path, env, model_hint):
    assert check(untested_profile(model_hint), tmp_path, environment=env, expect="untested") == []


@pytest.mark.parametrize("env", ENVIRONMENTS)
def test_a_full_run_outside_a_real_device_is_valid_as_e2_only(tmp_path, env):
    assert check(e2_profile(env), tmp_path, environment=env, expect="e2-only") == []


def test_a_real_device_run_may_carry_e3_conclusions(tmp_path):
    assert check(e3_profile(), tmp_path, environment="real-device") == []


# ---------- 档案：每条语义规则都有一个会让它失败的例子 ----------


def _set(path: tuple, value):
    def change(profile):
        node = profile
        for key in path[:-1]:
            node = node[key]
        node[path[-1]] = value

    return change


def _drop_mapping(key):
    return lambda p: p["command_mapping"].pop(key)


BAD_PROFILES = [
    ("platform 不是 android", _set(("platform",), "ios"), "platform 必须是 android"),
    ("声称看得到原始手势", _set(("raw_gesture_visible",), True), "raw_gesture_visible"),
    ("映射里出现 iOS 才有的 volume_change", _set(("command_mapping", "volume_change"), "STOP"), "volume_change"),
    ("下一首映射成开始", _set(("command_mapping", "next"), "START"), "command_mapping.next"),
    ("上一首映射成切换", _set(("command_mapping", "previous"), "TOGGLE"), "command_mapping.previous"),
    ("猜了厂商", _set(("manufacturer_hint",), "HUAWEI"), "manufacturer_hint"),
    ("猜了固件", _set(("firmware_constraint",), "1.2.3"), "firmware_constraint"),
    ("profile_id 与型号推出的不一致", _set(("profile_id",), "android-something-else"), "profile_id"),
    (
        "进程被杀后启动被写成支持（测不到的事不得有结论）",
        _set(("capabilities", "start_after_process_termination"), {"status": "SUPPORTED_BACKGROUND_RESTART", "evidence_level": "E3", "evidence_ids": [evidence_id("screen-locked", "start-by-gesture-while-locked", "real-device")]}),
        "start_after_process_termination",
    ),
    (
        "进程被杀后启动带了 E2 证据",
        _set(("capabilities", "start_after_process_termination"), {"status": "NOT_TESTED", "evidence_level": "E2", "evidence_ids": [evidence_id("screen-locked", "x", "emulator")]}),
        "start_after_process_termination",
    ),
    (
        "未测试却写了 E3",
        _set(("capabilities", "foreground_start_stop"), {"status": "NOT_TESTED", "evidence_level": "E3", "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "real-device")]}),
        "只能是空或 E2",
    ),
    (
        "有证据等级却没有证据 id",
        _set(("capabilities", "foreground_start_stop"), {"status": "NOT_TESTED", "evidence_level": "E2", "evidence_ids": []}),
        "同时有或同时没有",
    ),
    (
        "有证据 id 却没有证据等级",
        _set(("capabilities", "foreground_start_stop"), {"status": "NOT_TESTED", "evidence_level": None, "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "emulator")]}),
        "同时有或同时没有",
    ),
    (
        "E2 证据冒充真机",
        _set(("capabilities", "foreground_start_stop"), {"status": "NOT_TESTED", "evidence_level": "E2", "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "real-device")]}),
        "不得声称环境是 real-device",
    ),
    (
        "证据 id 的格式不对",
        _set(("capabilities", "foreground_start_stop"), {"status": "NOT_TESTED", "evidence_level": "E2", "evidence_ids": ["随便写的"]}),
        "证据 id 的格式不对",
    ),
]


@pytest.mark.parametrize(("what", "change", "keyword"), BAD_PROFILES, ids=[b[0] for b in BAD_PROFILES])
def test_each_semantic_rule_rejects_its_violation(tmp_path, what, change, keyword):
    profile = untested_profile()
    change(profile)

    problems = check(profile, tmp_path)

    assert any(keyword in p for p in problems), (what, problems)


def test_a_conclusion_backed_only_by_e2_evidence_is_rejected_by_the_contract_schema(tmp_path):
    profile = untested_profile()
    profile["capabilities"]["foreground_start_stop"] = {"status": "SUPPORTED_FOREGROUND", "evidence_level": "E2", "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "emulator")]}

    problems = check(profile, tmp_path)

    assert any("schema" in p and "headset-profile.schema.json" in p for p in problems), problems


def test_a_conclusion_claiming_e4_is_rejected_because_this_wizard_can_only_produce_e3(tmp_path):
    """合同允许结论是 E3 或 E4；向导最高只给 E3，所以档案里出现 E4 只可能是手改的。"""
    profile = e3_profile()
    profile["capabilities"]["foreground_start_stop"]["evidence_level"] = "E4"

    problems = check(profile, tmp_path)

    assert not any("schema" in p for p in problems), problems  # 合同本身放行，只有向导专属规则能拦住
    assert any("只能来自真机 + 真实耳机的 E3 证据" in p for p in problems), problems


def test_a_conclusion_whose_evidence_is_not_from_a_real_device_is_rejected(tmp_path):
    profile = e3_profile()
    profile["capabilities"]["foreground_start_stop"]["evidence_ids"] = [evidence_id("gesture-while-recording", "stop-by-gesture", "emulator")]

    problems = check(profile, tmp_path)

    assert any("必须全部来自 real-device" in p for p in problems), problems


@pytest.mark.parametrize("env", ENVIRONMENTS)
def test_no_conclusion_is_possible_when_the_run_was_not_on_a_real_device(tmp_path, env):
    problems = check(e3_profile(), tmp_path, environment=env)

    assert any("不可能有兼容结论" in p for p in problems), problems


def test_evidence_ids_must_name_the_environment_the_run_really_had(tmp_path):
    problems = check(e2_profile("unit-test"), tmp_path, environment="emulator")

    assert any("环境标签" in p for p in problems), problems


# ---------- 清单第 5 项的反面：没有任何观察的校准，每一项都必须是未测试 ----------


@pytest.mark.parametrize("name", cc.CAPABILITIES)
def test_a_run_with_no_observations_cannot_have_any_capability_other_than_untested(tmp_path, name):
    """单个能力被改成“支持（E3）”：合同 schema 本身通过，只有“这一轮没观察、必须全未测试”的期望能拦住它。"""
    profile = untested_profile()
    profile["capabilities"][name] = {"status": "SUPPORTED_FOREGROUND", "evidence_level": "E3", "evidence_ids": [evidence_id("gesture-while-recording", "stop-by-gesture", "real-device")]}

    problems = check(profile, tmp_path, environment="real-device", expect="untested")

    assert any(f"capabilities.{name}" in p and "未测试" in p for p in problems), problems


def test_a_run_with_no_observations_cannot_carry_even_e2_evidence(tmp_path):
    problems = check(e2_profile("emulator"), tmp_path, environment="emulator", expect="untested")

    assert any("必须是未测试" in p for p in problems), problems


def test_a_full_run_outside_a_real_device_cannot_be_missing_its_e2_evidence(tmp_path):
    profile = e2_profile("emulator")
    profile["capabilities"]["continue_recording_when_locked"] = copy.deepcopy(cc.UNTESTED)

    problems = check(profile, tmp_path, environment="emulator", expect="e2-only")

    assert any("continue_recording_when_locked" in p and "E2" in p for p in problems), problems


# ---------- 文件层：缺失、损坏、多余字段，且不回显文件里的值 ----------


def test_a_missing_profile_file_is_reported(tmp_path):
    assert cc.check_profile_file(tmp_path / "headset-profile.json", "headset-profile.json") == ["headset-profile.json: 文件不存在"]


def test_a_file_that_is_not_json_is_reported_without_echoing_it(tmp_path):
    path = tmp_path / "headset-profile.json"
    path.write_text(SECRET + " {", encoding="utf-8")

    problems = cc.check_profile_file(path, "headset-profile.json")

    assert problems and not any(SECRET in p for p in problems)


def test_a_top_level_array_is_reported(tmp_path):
    path = write(tmp_path / "headset-profile.json", [SECRET])

    problems = cc.check_profile_file(path, "headset-profile.json")

    assert problems and not any(SECRET in p for p in problems)


def test_a_field_the_contract_does_not_declare_is_reported_without_echoing_its_name_or_value(tmp_path):
    profile = untested_profile()
    profile[SECRET] = SECRET

    problems = check(profile, tmp_path)

    assert any("additionalProperties" in p for p in problems), problems
    assert not any(SECRET in p for p in problems)


def test_semantic_problems_never_echo_the_values_from_the_file(tmp_path):
    profile = untested_profile()
    profile["manufacturer_hint"] = SECRET
    profile["firmware_constraint"] = SECRET
    profile["model_hint"] = SECRET
    profile["capabilities"]["foreground_start_stop"] = {"status": "NOT_TESTED", "evidence_level": "E2", "evidence_ids": [SECRET]}

    problems = check(profile, tmp_path)

    assert len(problems) >= 3
    assert not any(SECRET in p for p in problems)


@pytest.mark.parametrize(
    ("model_hint", "expected"),
    [
        (None, "android-headset"),
        ("", "android-headset"),
        ("   ", "android-headset"),
        ("FreeClip 2", "android-freeclip-2"),
        ("  HUAWEI FreeClip!  ", "android-huawei-freeclip"),
        ("华为耳机", "android-headset"),
        ("华为 FreeClip", "android-freeclip"),
        ("x" * 200, "android-" + "x" * 92),
    ],
)
def test_the_profile_id_rule_matches_the_kotlin_one(model_hint, expected):
    assert cc.profile_id_for(model_hint) == expected


# ---------- 控制事件 ----------


def event_for(name: str) -> dict:
    source, platform_event, command, sequence, monotonic_ns, raw_event_id = cc.EXPECTED_EVENTS[name]
    return {
        "event_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"auri-test/{name}")),
        "source": source,
        "platform_event": platform_event,
        "command": command,
        "monotonic_ns": monotonic_ns,
        "sequence": sequence,
        "profile_id": "generic-media-control",
        "profile_version": 1,
        "raw_event_id": raw_event_id,
    }


def make_events(root: pathlib.Path) -> pathlib.Path:
    dir_ = root / "events"
    for name in cc.EXPECTED_EVENTS:
        write(dir_ / f"normalized-control-event-{name}.json", event_for(name))
    return dir_


def edit_event(dir_: pathlib.Path, name: str, change) -> None:
    path = dir_ / f"normalized-control-event-{name}.json"
    value = json.loads(path.read_text(encoding="utf-8"))
    change(value)
    write(path, value)


def test_the_events_the_spec_calls_for_are_valid(tmp_path):
    assert cc.check_events_dir(make_events(tmp_path)) == []


BAD_EVENTS = [
    ("停止键被映射成了开始", "stop-key-is-pause", lambda e: e.update(command="START"), "command"),
    ("来源无法验证的键被说成耳机", "play-unknown-source", lambda e: e.update(source="headset_remote"), "source"),
    ("外接设备的键被说成来源未知", "headset-hook-external", lambda e: e.update(source="unknown_remote"), "source"),
    ("耳机挂机键没有归到 toggle", "headset-hook-external", lambda e: e.update(platform_event="play"), "platform_event"),
    ("下一首没有被忽略", "next-is-ignored", lambda e: e.update(command="START"), "command"),
    ("序号不对", "stop-key-is-pause", lambda e: e.update(sequence=7), "sequence"),
    ("时间戳用了接收时刻而不是手势时刻", "headset-hook-external", lambda e: e.update(monotonic_ns=1_210_000_000), "monotonic_ns"),
    ("没有原始标识却编了一个", "play-unknown-source", lambda e: e.update(raw_event_id="made-up"), "raw_event_id"),
    ("原始标识被改了", "stop-key-is-pause", lambda e: e.update(raw_event_id="key:86:other"), "raw_event_id"),
    ("来源变成了屏幕按钮", "next-is-ignored", lambda e: e.update(source="screen_control", platform_event="screen_button", profile_id=None, profile_version=None), "来源只能是"),
    ("来源变成了音量手势", "next-is-ignored", lambda e: e.update(source="volume_gesture", platform_event="volume_change"), "来源只能是"),
]


@pytest.mark.parametrize(("what", "name", "change", "keyword"), BAD_EVENTS, ids=[b[0] for b in BAD_EVENTS])
def test_each_event_rule_rejects_its_violation(tmp_path, what, name, change, keyword):
    dir_ = make_events(tmp_path)
    edit_event(dir_, name, change)

    problems = cc.check_events_dir(dir_)

    assert any(keyword in p for p in problems), (what, problems)


def test_a_duplicate_event_id_is_reported(tmp_path):
    dir_ = make_events(tmp_path)
    first = event_for("headset-hook-external")["event_id"]
    edit_event(dir_, "play-unknown-source", lambda e: e.update(event_id=first))

    assert any("event_id" in p for p in cc.check_events_dir(dir_))


def test_events_from_different_profiles_are_reported(tmp_path):
    dir_ = make_events(tmp_path)
    edit_event(dir_, "next-is-ignored", lambda e: e.update(profile_id="android-freeclip-2"))

    assert any("profile_id" in p for p in cc.check_events_dir(dir_))


def test_a_missing_event_file_is_reported(tmp_path):
    dir_ = make_events(tmp_path)
    (dir_ / "normalized-control-event-next-is-ignored.json").unlink()

    assert any("next-is-ignored" in p and "文件不存在" in p for p in cc.check_events_dir(dir_))


def test_an_event_nobody_registered_is_reported(tmp_path):
    dir_ = make_events(tmp_path)
    write(dir_ / "normalized-control-event-brand-new.json", event_for("next-is-ignored") | {"event_id": str(uuid.uuid4()), "sequence": 9})

    assert any("brand-new" in p and "不在预期清单里" in p for p in cc.check_events_dir(dir_))


def test_a_stray_file_in_the_events_directory_is_reported(tmp_path):
    dir_ = make_events(tmp_path)
    (dir_ / "notes.txt").write_text("x", encoding="utf-8")

    assert any("notes.txt" in p for p in cc.check_events_dir(dir_))


def test_an_event_the_schema_rejects_is_reported_without_echoing_its_values(tmp_path):
    dir_ = make_events(tmp_path)
    edit_event(dir_, "next-is-ignored", lambda e: e.update(extra_field=SECRET, raw_event_id=SECRET))

    problems = cc.check_events_dir(dir_)

    assert any("additionalProperties" in p for p in problems), problems
    assert not any(SECRET in p for p in problems)


def test_a_wrong_value_in_an_event_is_reported_without_echoing_it(tmp_path):
    dir_ = make_events(tmp_path)
    edit_event(dir_, "stop-key-is-pause", lambda e: e.update(raw_event_id=SECRET))

    problems = cc.check_events_dir(dir_)

    assert any("raw_event_id" in p for p in problems)
    assert not any(SECRET in p for p in problems)


# ---------- 目录树与命令行 ----------


def make_tree(root: pathlib.Path) -> pathlib.Path:
    root.mkdir(parents=True, exist_ok=True)
    make_events(root)
    for name, expectation in cc.EXPECTED_JVM_SCENARIOS.items():
        if expectation == "events":
            continue
        _, flavor, env = expectation.split(":")
        write(root / name / "headset-profile.json", untested_profile(None) if flavor == "untested" else e2_profile(env))
    return root


def test_the_preset_tree_with_valid_files_has_no_problems(tmp_path):
    root = make_tree(tmp_path / "control-jvm")

    assert cc.check_tree(root, cc.EXPECTED_JVM_SCENARIOS, strict=True) == []


def test_strict_mode_reports_a_scenario_directory_nobody_registered(tmp_path):
    root = make_tree(tmp_path / "control-jvm")
    (root / "surprise").mkdir()

    assert any("surprise" in p and "不在预期清单里" in p for p in cc.check_tree(root, cc.EXPECTED_JVM_SCENARIOS, strict=True))


def test_a_missing_scenario_directory_is_reported(tmp_path):
    root = make_tree(tmp_path / "control-jvm")
    import shutil

    shutil.rmtree(root / "no-observations-emulator")

    assert any("no-observations-emulator" in p and "不存在" in p for p in cc.check_tree(root, cc.EXPECTED_JVM_SCENARIOS, strict=True))


def test_an_unknown_expectation_is_reported(tmp_path):
    root = make_tree(tmp_path / "control-jvm")

    assert any("未知的期望" in p for p in cc.check_tree(root, {"events": "events:extra"}))
    assert any("未知的期望" in p for p in cc.check_tree(root, {"events": "profile:untested:somewhere"}))


def test_the_command_line_accepts_a_good_tree_and_rejects_a_bad_one(tmp_path, capsys):
    root = make_tree(tmp_path / "control-jvm")
    assert cc.main(["x", str(root), "--preset", "jvm"]) == 0

    edit_event(root / "events", "stop-key-is-pause", lambda e: e.update(command="START"))
    assert cc.main(["x", str(root), "--preset", "jvm"]) == 1
    assert "个问题" in capsys.readouterr().out


def test_the_command_line_rejects_an_unknown_preset(tmp_path):
    assert cc.main(["x", str(tmp_path), "--preset", "nope"]) == 2


def test_the_owner_mode_validates_a_real_profile_and_lists_only_statuses(tmp_path, capsys):
    path = write(tmp_path / "headset-profile.json", e3_profile())

    assert cc.main(["x", "--profile", str(path)]) == 0
    out = capsys.readouterr().out
    assert "SUPPORTED_FOREGROUND" in out and "UNSUPPORTED" in out
    assert "calibration:" not in out  # 证据 id 不列


def test_the_owner_mode_rejects_a_profile_that_claims_an_e2_conclusion(tmp_path, capsys):
    profile = e3_profile()
    profile["capabilities"]["foreground_start_stop"]["evidence_level"] = "E2"
    path = write(tmp_path / "headset-profile.json", profile)

    assert cc.main(["x", "--profile", str(path)]) == 1
    assert "个问题" in capsys.readouterr().out


def test_the_owner_mode_rejects_a_profile_that_claims_to_know_the_raw_gesture(tmp_path):
    profile = e3_profile()
    profile["raw_gesture_visible"] = True
    path = write(tmp_path / "headset-profile.json", profile)

    assert cc.main(["x", "--profile", str(path)]) == 1
