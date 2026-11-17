"""消息合同：schema 自身合法；有效样本通过；无效样本因为它声明的那个原因被拒绝。"""
import pytest
from jsonschema import Draft202012Validator

import semantic_rules
from conftest import FIXTURE_DIR, SCHEMAS, flatten_errors, json_pointer, load_json, validator_for

MESSAGE_DIR = FIXTURE_DIR / "messages"
EXPECT_BY_DIR = {"valid": "valid", "schema-invalid": "schema_invalid", "semantic-invalid": "semantic_invalid"}

# 每个消息合同都必须同时有有效样本和无效样本。
MESSAGE_SCHEMAS = [
    "error.schema.json",
    "headset-profile.schema.json",
    "revision.schema.json#/$defs/submission",
    "revision.schema.json#/$defs/stored",
    "revision.schema.json#/$defs/record_deleted",
    "pairing.schema.json#/$defs/payload",
    "pairing.schema.json#/$defs/redeem_request",
    "pairing.schema.json#/$defs/redeem_response",
    "destination.schema.json#/$defs/create_request",
    "destination.schema.json#/$defs/resource",
    "destination.schema.json#/$defs/credential_response",
    "delivery.schema.json#/$defs/claim_request",
    "delivery.schema.json#/$defs/claim_response",
    "delivery.schema.json#/$defs/renew_request",
    "delivery.schema.json#/$defs/renew_response",
    "delivery.schema.json#/$defs/ack_request",
    "delivery.schema.json#/$defs/ack_response",
    "delivery.schema.json#/$defs/nack_request",
    "delivery.schema.json#/$defs/nack_response",
    "record_query.schema.json#/$defs/list_request",
    "record_query.schema.json#/$defs/list_response",
    "record_query.schema.json#/$defs/get_request",
    "record_query.schema.json#/$defs/get_response",
    "record_query.schema.json#/$defs/search_request",
    "record_query.schema.json#/$defs/search_response",
    "gateway.schema.json#/$defs/health_response",
    "gateway.schema.json#/$defs/capabilities_response",
    "gateway.schema.json#/$defs/operation_response",
    "gateway.schema.json#/$defs/record_status_response",
    "adapter.schema.json#/$defs/normalized_control_event",
    "adapter.schema.json#/$defs/capture_eligibility",
    "adapter.schema.json#/$defs/prepared_input",
    "adapter.schema.json#/$defs/capture_handle",
    "adapter.schema.json#/$defs/route_change_event",
    "adapter.schema.json#/$defs/recording_result",
    "adapter.schema.json#/$defs/asr_capabilities",
    "adapter.schema.json#/$defs/asr_task_handle",
]


def fixtures(kind):
    return sorted((MESSAGE_DIR / kind).glob("*.json"))


def ids(paths):
    return [path.stem for path in paths]


@pytest.mark.parametrize("name", sorted(SCHEMAS))
def test_schema_is_a_valid_json_schema(name):
    Draft202012Validator.check_schema(SCHEMAS[name])


def test_fixture_directories_exist_and_are_not_empty():
    for kind in EXPECT_BY_DIR:
        assert fixtures(kind), f"{kind} 目录下没有样本"


@pytest.mark.parametrize("kind", sorted(EXPECT_BY_DIR))
def test_fixture_wrapper_matches_its_directory(kind):
    for path in fixtures(kind):
        fixture = load_json(path)
        assert fixture["expect"] == EXPECT_BY_DIR[kind], path.name
        assert fixture["schema"] in MESSAGE_SCHEMAS, f"{path.name} 引用了未登记的合同"
        if kind != "valid":
            assert fixture["rejected_because"].strip(), f"{path.name} 没有写明拒绝原因"


@pytest.mark.parametrize("schema_ref", MESSAGE_SCHEMAS)
def test_every_message_schema_has_valid_and_invalid_fixtures(schema_ref):
    def count(kind):
        return sum(1 for path in fixtures(kind) if load_json(path)["schema"] == schema_ref)

    assert count("valid") >= 1, f"{schema_ref} 没有有效样本"
    assert count("schema-invalid") >= 1, f"{schema_ref} 没有无效样本"


@pytest.mark.parametrize("path", fixtures("valid"), ids=ids(fixtures("valid")))
def test_valid_fixture_passes_schema_and_semantic_rules(path):
    fixture = load_json(path)
    errors = list(validator_for(fixture["schema"]).iter_errors(fixture["instance"]))
    assert not errors, [f"{json_pointer(e)}: {e.message}" for e in errors]
    assert semantic_rules.violations(fixture["schema"], fixture["instance"]) == []


@pytest.mark.parametrize("path", fixtures("schema-invalid"), ids=ids(fixtures("schema-invalid")))
def test_schema_invalid_fixture_is_rejected_for_its_stated_reason(path):
    fixture = load_json(path)
    errors = list(flatten_errors(validator_for(fixture["schema"]).iter_errors(fixture["instance"])))
    assert errors, "本应被 schema 拒绝的样本通过了校验"
    found = {(json_pointer(e), e.validator) for e in errors}
    expected = (fixture["expect_error"]["path"], fixture["expect_error"]["keyword"])
    assert expected in found, f"被拒绝了，但不是因为声明的原因；实际错误：{sorted(found)}"


@pytest.mark.parametrize("path", fixtures("semantic-invalid"), ids=ids(fixtures("semantic-invalid")))
def test_semantic_invalid_fixture_passes_schema_but_breaks_its_rule(path):
    fixture = load_json(path)
    errors = list(validator_for(fixture["schema"]).iter_errors(fixture["instance"]))
    assert not errors, "这类样本应当通过 schema、只违反语义规则"
    assert semantic_rules.violations(fixture["schema"], fixture["instance"]) == [fixture["rule"]]


def test_hash_vectors_match_utf8_sha256():
    vectors = load_json(FIXTURE_DIR / "hash-vectors.json")["vectors"]
    assert len(vectors) >= 5
    for vector in vectors:
        assert vector["text"].encode("utf-8").hex() == vector["utf8_hex"], vector["name"]
        assert semantic_rules.text_sha256(vector["text"]) == vector["text_sha256"], vector["name"]


def test_hash_does_not_normalize_unicode():
    by_name = {v["name"]: v for v in load_json(FIXTURE_DIR / "hash-vectors.json")["vectors"]}
    assert by_name["nfc"]["text"] != by_name["nfd"]["text"]
    assert by_name["nfc"]["text_sha256"] != by_name["nfd"]["text_sha256"]
