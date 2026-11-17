"""JSON Schema 表达不了、必须由实现另行校验的规则（参考实现）。

规则编号与 contracts/README.md 中的清单一致。三端和网关各自实现时，
以 contracts/fixtures/messages/semantic-invalid/ 下的样本为准。
"""
import hashlib

MAX_TEXT_BYTES = 1024 * 1024


def text_sha256(text: str) -> str:
    """正文按 UTF-8 编码后的原始字节直接算 SHA-256，不做任何字符规范化。"""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _check_text(text: str, declared_hash: str) -> list[str]:
    violations = []
    if text_sha256(text) != declared_hash:
        violations.append("SR-01")
    if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
        violations.append("SR-06")
    return violations


def _check_ranges(ranges: list[dict], capture_duration_ms: int, speech_duration_ms: int) -> list[str]:
    """SR-02/03/04：保留的时间段升序不重叠、不超出采集时长、时长之和等于语音时长。"""
    violations = []
    ordered = all(r["start_ms"] < r["end_ms"] for r in ranges) and all(
        earlier["end_ms"] <= later["start_ms"] for earlier, later in zip(ranges, ranges[1:])
    )
    if not ordered:
        violations.append("SR-02")
    if any(r["end_ms"] > capture_duration_ms for r in ranges):
        violations.append("SR-03")
    if sum(r["end_ms"] - r["start_ms"] for r in ranges) != speech_duration_ms:
        violations.append("SR-04")
    return violations


def _check_submission(instance: dict) -> list[str]:
    violations = _check_text(instance["content"]["text"], instance["content"]["text_sha256"])
    capture = instance["capture"]
    violations.extend(
        _check_ranges(capture["speech_segments"], capture["capture_duration_ms"], capture["speech_duration_ms"])
    )
    if instance["content"]["execution_location"] != "on_device":
        violations.append("SR-05")
    return violations


def _check_recording_result(instance: dict) -> list[str]:
    return _check_ranges(
        instance["verified_ranges"], instance["capture_duration_ms"], instance["speech_duration_ms"]
    )


def _check_asr_capabilities(instance: dict) -> list[str]:
    return [] if instance["execution_location"] == "on_device" else ["SR-05"]


def _check_claim_response(instance: dict) -> list[str]:
    violations = []
    for delivery in instance["deliveries"]:
        violations.extend(_check_text(delivery["text"], delivery["text_sha256"]))
    return violations


def _check_get_response(instance: dict) -> list[str]:
    text = instance["text"]
    if instance["is_partial"]:
        return ["SR-06"] if len(text.encode("utf-8")) > MAX_TEXT_BYTES else []
    return _check_text(text, instance["text_sha256"])


_CHECKS = {
    "revision.schema.json#/$defs/submission": _check_submission,
    "delivery.schema.json#/$defs/claim_response": _check_claim_response,
    "record_query.schema.json#/$defs/get_response": _check_get_response,
    "adapter.schema.json#/$defs/recording_result": _check_recording_result,
    "adapter.schema.json#/$defs/asr_capabilities": _check_asr_capabilities,
}


def violations(schema_ref: str, instance: dict) -> list[str]:
    """返回被违反的规则编号；只对已通过 schema 校验的数据调用。"""
    check = _CHECKS.get(schema_ref)
    return sorted(set(check(instance))) if check else []
