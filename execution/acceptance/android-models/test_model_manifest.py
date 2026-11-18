"""Offline checks for the model metadata shipped with the Android app."""

import ipaddress
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[3]
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def test_shipped_manifests_have_pinned_models_and_visible_license_metadata():
    manifests = sorted((ROOT / "apps/android/manifests").glob("*.manifest.json"))
    assert manifests
    for path in manifests:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        assert manifest["manifest_version"] == 1
        assert manifest["model_id"] and manifest["model_version"]
        assert manifest["languages"] and len(set(manifest["languages"])) == len(manifest["languages"])
        assert re.fullmatch(r"==[0-9]+(?:\.[0-9]+)+", manifest["engine"]["version_range"])
        assert manifest["license"]["name"]
        assert urlsplit(manifest["license"]["source_url"]).scheme == "https"
        archive = manifest["archive"]
        url = urlsplit(archive["url"])
        assert url.scheme == "https" and url.hostname and not url.username and not url.password
        assert url.hostname.lower() not in {"localhost", "local", "internal"}
        assert not url.hostname.lower().endswith((".localhost", ".local", ".internal"))
        try:
            address = ipaddress.ip_address(url.hostname)
        except ValueError:
            address = None
        assert address is None or address.is_global
        assert archive["size_bytes"] > 0 and SHA256.fullmatch(archive["sha256"])
        assert {item["role"] for item in manifest["files"]} >= {"model", "tokens"}
        for item in manifest["files"]:
            assert item["size_bytes"] > 0 and SHA256.fullmatch(item["sha256"])
            assert not Path(item["path"]).is_absolute() and ".." not in Path(item["path"]).parts


def test_model_weights_and_download_archives_are_not_tracked():
    tracked = subprocess.run(
        ["git", "ls-files", "--", "*.onnx", "*.tar.bz2"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    assert not tracked.stdout.strip()
