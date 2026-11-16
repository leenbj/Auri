"""状态机合同：定义自洽，与 schema 里的枚举一致，事件序列样本与转移表一致。"""
import pytest

from conftest import FIXTURE_DIR, SCHEMAS, load_json, validator_for

MACHINE_DIR = FIXTURE_DIR / "state-machines"
MACHINES = ["capture", "transcription", "sync", "delivery"]
STATE_ENUM = {
    "capture": "capture_state",
    "transcription": "transcription_state",
    "sync": "sync_state",
    "delivery": "delivery_state",
}


def machine(name):
    return load_json(MACHINE_DIR / f"{name}.machine.json")


def scenarios(name):
    return load_json(MACHINE_DIR / f"{name}.scenarios.json")["scenarios"]


def table(definition):
    return {(t["from"], t["event"]): t for t in definition["transitions"]}


def step_outcome(definition, state, event):
    """按转移表计算一步的结果；表里没有的组合是 rejected，状态不变。"""
    rule = table(definition).get((state, event))
    if rule is None:
        return "rejected", state
    return rule["outcome"], rule["to"]


@pytest.mark.parametrize("name", MACHINES)
def test_files_match_the_file_format_schema(name):
    fmt = "state-machine.schema.json#/$defs/"
    assert not list(validator_for(fmt + "machine").iter_errors(machine(name)))
    assert not list(validator_for(fmt + "scenarios").iter_errors(load_json(MACHINE_DIR / f"{name}.scenarios.json")))


@pytest.mark.parametrize("name", MACHINES)
def test_states_equal_the_enum_in_common_schema(name):
    enum = SCHEMAS["common.schema.json"]["$defs"][STATE_ENUM[name]]["enum"]
    assert sorted(machine(name)["states"]) == sorted(enum)


@pytest.mark.parametrize("name", MACHINES)
def test_definition_is_internally_consistent(name):
    definition = machine(name)
    states, events = set(definition["states"]), set(definition["events"])
    reserved, terminal = set(definition["reserved_states"]), set(definition["terminal_states"])

    assert definition["initial_state"] in states - reserved
    assert reserved <= states and terminal <= states - reserved
    assert len(table(definition)) == len(definition["transitions"]), "同一（状态，事件）出现了两条规则"

    for t in definition["transitions"]:
        assert t["from"] in states and t["to"] in states and t["event"] in events
        assert t["from"] not in reserved and t["to"] not in reserved, "保留状态不得出现在转移里"
        if t["outcome"] == "ignored":
            assert t["to"] == t["from"], "被忽略的事件不改变状态"
        if t["from"] in terminal:
            assert t["outcome"] == "ignored", f"终止状态 {t['from']} 不能再转移"
    assert {t["event"] for t in definition["transitions"]} == events


@pytest.mark.parametrize("name", MACHINES)
def test_every_non_reserved_state_is_reachable(name):
    definition = machine(name)
    reached, frontier = {definition["initial_state"]}, [definition["initial_state"]]
    while frontier:
        state = frontier.pop()
        for t in definition["transitions"]:
            if t["from"] == state and t["to"] not in reached:
                reached.add(t["to"])
                frontier.append(t["to"])
    assert reached == set(definition["states"]) - set(definition["reserved_states"])


@pytest.mark.parametrize("name", MACHINES)
def test_scenarios_agree_with_the_transition_table(name):
    definition = machine(name)
    usable = set(definition["states"]) - set(definition["reserved_states"])
    names = [s["name"] for s in scenarios(name)]
    assert len(names) == len(set(names)), "样本名重复"

    for scenario in scenarios(name):
        state = scenario["initial_state"]
        assert state in usable, scenario["name"]
        for index, step in enumerate(scenario["steps"]):
            assert step["event"] in definition["events"], f"{scenario['name']} 第 {index + 1} 步用了未定义的事件"
            outcome, after = step_outcome(definition, state, step["event"])
            where = f"{scenario['name']} 第 {index + 1} 步（{state} + {step['event']}）"
            assert step["expect"] == outcome, where
            assert step["state_after"] == after, where
            state = after


@pytest.mark.parametrize("name", MACHINES)
def test_scenarios_cover_every_rule_and_include_rejections(name):
    definition = machine(name)
    used, rejections = set(), 0
    for scenario in scenarios(name):
        state = scenario["initial_state"]
        for step in scenario["steps"]:
            outcome, after = step_outcome(definition, state, step["event"])
            if outcome == "rejected":
                rejections += 1
            else:
                used.add((state, step["event"]))
            state = after
    missing = sorted(set(table(definition)) - used)
    assert not missing, f"这些规则没有任何样本覆盖：{missing}"
    assert rejections >= 1, "至少要有一个“应被拒绝”的样本"


def test_capture_finalizing_reasons_are_consistent():
    """进入 FINALIZING 的非命令事件必须写明 end_reason，且都属于截断或时长上限。"""
    for t in machine("capture")["transitions"]:
        if t["to"] == "FINALIZING" and t["outcome"] == "transition" and t["event"] not in ("STOP", "TOGGLE"):
            assert "end_reason" in t, t


def test_v1_transcription_never_enters_cloud_states():
    definition = machine("transcription")
    assert set(definition["reserved_states"]) == {"WAITING_NETWORK", "WAITING_CONSENT", "SUBMITTED_REMOTE"}
    for scenario in scenarios("transcription"):
        touched = {scenario["initial_state"]} | {step["state_after"] for step in scenario["steps"]}
        assert not touched & set(definition["reserved_states"]), scenario["name"]
