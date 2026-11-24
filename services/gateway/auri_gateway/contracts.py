"""JSON Schema and semantic-rule (SR-01..SR-06) validation against contracts/."""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
from functools import lru_cache
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

BASE_URI = "https://contracts.auri.invalid/1.2/"
MAX_TEXT_BYTES = 1024 * 1024
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def _default_schema_dir() -> pathlib.Path:
    env_dir = os.environ.get("AURI_CONTRACTS_SCHEMA_DIR")
    if env_dir:
        return pathlib.Path(env_dir)
    return pathlib.Path(__file__).resolve().parents[3] / "contracts" / "schemas"


@lru_cache(maxsize=1)
def _schema_registry() -> Registry:
    schema_dir = _default_schema_dir()
    resources = []
    for path in sorted(schema_dir.glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        resources.append((schema["$id"], Resource.from_contents(schema)))
    return Registry().with_resources(resources)


@lru_cache(maxsize=16)
def validator_for(schema_ref: str) -> Draft202012Validator:
    """Return a cached Draft 2020-12 validator for a frozen contract schema ref."""
    return Draft202012Validator({"$ref": BASE_URI + schema_ref}, registry=_schema_registry())


def inline_schema(schema_ref: str) -> dict[str, Any]:
    """Return a frozen contract definition with every `$ref` resolved in place.

    MCP clients cannot fetch `https://contracts.auri.invalid/...`, so tool schemas must be
    self-contained.
    """
    resolved = _schema_registry().resolver(BASE_URI).lookup(schema_ref)
    return _inline_refs(resolved.contents, resolved.resolver)


def _inline_refs(node: Any, resolver: Any) -> Any:
    if isinstance(node, list):
        return [_inline_refs(item, resolver) for item in node]
    if not isinstance(node, dict):
        return node
    siblings = {key: _inline_refs(value, resolver) for key, value in node.items() if key != "$ref"}
    if "$ref" not in node:
        return siblings
    target = resolver.lookup(node["$ref"])
    return {**_inline_refs(target.contents, target.resolver), **siblings}


def validate_schema(schema_ref: str, instance: Any) -> list[str]:
    """Validate an instance against schema_ref; return redacted field-level error strings."""
    errors = sorted(
        validator_for(schema_ref).iter_errors(instance),
        key=lambda err: (list(err.absolute_path), err.validator),
    )
    formatted: list[str] = []
    for err in errors[:5]:
        pointer = "/" + "/".join(str(p) for p in err.absolute_path) if err.absolute_path else "/"
        formatted.append(f"{pointer} ({err.validator})")
    return formatted


def text_sha256(text: str) -> str:
    """SR-01: SHA-256 of raw UTF-8 bytes with no Unicode normalization or trimming."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def check_revision_semantic_rules(submission: dict[str, Any]) -> list[str]:
    """Enforce contracts semantic rules SR-01 through SR-06 on a schema-valid submission."""
    violations: list[str] = []
    content = submission["content"]
    capture = submission["capture"]

    # SR-01: hash must match raw UTF-8 bytes.
    if text_sha256(content["text"]) != content["text_sha256"]:
        violations.append("SR-01")

    # SR-02 / SR-03 / SR-04: speech_segments ordering, bounds, and duration sum.
    segments = capture["speech_segments"]
    ordered = all(seg["start_ms"] < seg["end_ms"] for seg in segments) and all(
        prev["end_ms"] <= curr["start_ms"] for prev, curr in zip(segments, segments[1:])
    )
    if not ordered:
        violations.append("SR-02")
    if any(seg["end_ms"] > capture["capture_duration_ms"] for seg in segments):
        violations.append("SR-03")
    if sum(seg["end_ms"] - seg["start_ms"] for seg in segments) != capture["speech_duration_ms"]:
        violations.append("SR-04")

    # SR-05: V1 allows on_device execution_location only (ADR-0019).
    if content["execution_location"] != "on_device":
        violations.append("SR-05")

    # SR-06: text encoded as UTF-8 must not exceed 1 MiB.
    if len(content["text"].encode("utf-8")) > MAX_TEXT_BYTES:
        violations.append("SR-06")

    return violations
