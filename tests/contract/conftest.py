import json
import pathlib

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_DIR = REPO_ROOT / "contracts" / "schemas"
FIXTURE_DIR = REPO_ROOT / "contracts" / "fixtures"
BASE_URI = "https://contracts.auri.invalid/1.2/"


def load_json(path: pathlib.Path):
    return json.loads(path.read_text(encoding="utf-8"))


SCHEMAS = {path.name: load_json(path) for path in sorted(SCHEMA_DIR.glob("*.schema.json"))}
REGISTRY = Registry().with_resources(
    (schema["$id"], Resource.from_contents(schema)) for schema in SCHEMAS.values()
)


def validator_for(schema_ref: str) -> Draft202012Validator:
    """schema_ref 形如 'revision.schema.json#/$defs/submission' 或 'error.schema.json'。"""
    return Draft202012Validator({"$ref": BASE_URI + schema_ref}, registry=REGISTRY)


def flatten_errors(errors):
    """展开 oneOf/anyOf 的子错误，便于按路径和关键字查找。"""
    for error in errors:
        yield error
        yield from flatten_errors(error.context or [])


def json_pointer(error) -> str:
    return "".join(f"/{part}" for part in error.absolute_path)
