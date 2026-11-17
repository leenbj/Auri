#!/usr/bin/env python3
"""准备清单固定的本地转录模型：下载（可续传）→ 校验 → 只解出清单里列的文件与示例音频。

用法：python3 prepare_model.py [清单文件] [--cache-dir 目录]
  默认清单   apps/android/manifests/sensevoice-small-int8-2024-07-17.manifest.json
  默认缓存   ${AURI_CACHE_DIR:-~/.cache/auri}
  产出       <缓存>/models/<model_id>/<model_version>/（清单里列的文件）与 <缓存>/samples/*.wav（包里的示例音频）

信任锚是清单里固定的 SHA-256，不是 URL：压缩包、解出的每个文件都按清单的大小与哈希校验，
不符就删掉并以非零状态退出。模型权重与示例音频只进缓存目录，不进仓库。
只依赖标准库与 curl（下载与续传、重试交给 curl）；Python 3.8 起可用。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
from urllib.parse import urlparse

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DEFAULT_MANIFEST = REPO_ROOT / "apps" / "android" / "manifests" / "sensevoice-small-int8-2024-07-17.manifest.json"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_MODEL_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,99}$")
_MODEL_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,199}$")


def die(message: str) -> "NoReturn":  # noqa: F821
    raise SystemExit(f"prepare_model: {message}")


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _safe_relative(path: str) -> bool:
    parts = pathlib.PurePosixPath(path).parts
    return bool(parts) and not path.startswith("/") and "\\" not in path and all(p not in ("", ".", "..") for p in parts)


def load_manifest(path: pathlib.Path) -> dict:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        archive = manifest["archive"]
        files = manifest["files"]
        if not (_MODEL_ID.match(manifest["model_id"]) and _MODEL_VERSION.match(manifest["model_version"])):
            die("model_id 或 model_version 的格式不对")
        if not (isinstance(archive["url"], str) and int(archive["size_bytes"]) > 0 and _SHA256.match(archive["sha256"])):
            die("archive 的 url、size_bytes、sha256 不完整")
        if not files:
            die("files 不能为空")
        for entry in files:
            if not (_safe_relative(entry["path"]) and int(entry["size_bytes"]) > 0 and _SHA256.match(entry["sha256"])):
                die(f"files 里有不合法的条目：{entry.get('path')!r}")
        if not pathlib.PurePosixPath(urlparse(archive["url"]).path).name:
            die("archive.url 没有文件名")
    except (OSError, ValueError, KeyError, TypeError) as e:
        die(f"清单不合法（{path}）：{e!r}")
    return manifest


def _verify_archive(path: pathlib.Path, archive: dict) -> bool:
    return path.is_file() and path.stat().st_size == int(archive["size_bytes"]) and sha256_of(path) == archive["sha256"]


def _download(url: str, archive_path: pathlib.Path, retries: int) -> None:
    part = archive_path.with_name(archive_path.name + ".part")
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    command = ["curl", "-L", "--fail", "--silent", "--show-error", "-C", "-", "--retry", str(retries), "--retry-delay", "2", "-o", str(part), url]
    print(f"下载 {url}", flush=True)
    if subprocess.run(command).returncode != 0:
        die(f"下载失败：{url}（已下载的部分保留在 {part}，重跑会续传）")
    part.replace(archive_path)


def _extract(archive_path: pathlib.Path, manifest: dict, cache_dir: pathlib.Path) -> pathlib.Path:
    model_dir = cache_dir / "models" / manifest["model_id"] / manifest["model_version"]
    samples_dir = cache_dir / "samples"
    shutil.rmtree(model_dir, ignore_errors=True)
    model_dir.mkdir(parents=True)
    samples_dir.mkdir(parents=True, exist_ok=True)
    wanted = {entry["path"]: model_dir / entry["path"] for entry in manifest["files"]}
    found = set()

    # 不用 tar.extract：每个成员都按名字自己决定写到哪里，名字里带 .. 的、不是常规文件的（符号链接等）一律不解
    with tarfile.open(archive_path, "r:*") as tar:
        for member in tar:
            parts = pathlib.PurePosixPath(member.name).parts
            if not member.isreg() or member.name.startswith("/") or ".." in parts or len(parts) < 2:
                continue
            relative = "/".join(parts[1:])  # 去掉包的顶层目录
            if relative in wanted:
                destination = wanted[relative]
                found.add(relative)
            elif len(parts) == 3 and parts[1] == "test_wavs" and parts[2].endswith(".wav"):
                destination = samples_dir / parts[2]
            else:
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, destination.open("wb") as target:
                shutil.copyfileobj(source, target, 1 << 20)

    missing = sorted(set(wanted) - found)
    if missing:
        shutil.rmtree(model_dir, ignore_errors=True)
        die(f"压缩包里没有清单要求的文件：{missing}")
    for entry in manifest["files"]:
        file = wanted[entry["path"]]
        if file.stat().st_size != int(entry["size_bytes"]) or sha256_of(file) != entry["sha256"]:
            shutil.rmtree(model_dir, ignore_errors=True)
            die(f"解出的文件 {entry['path']} 与清单的大小或 SHA-256 不符，已删除")
    return model_dir


def prepare(manifest_path: pathlib.Path, cache_dir: pathlib.Path, retries: int = 5) -> pathlib.Path:
    manifest = load_manifest(pathlib.Path(manifest_path))
    archive = manifest["archive"]
    archive_path = pathlib.Path(cache_dir) / "downloads" / pathlib.PurePosixPath(urlparse(archive["url"]).path).name

    if archive_path.exists() and not _verify_archive(archive_path, archive):
        print(f"缓存里的 {archive_path.name} 与清单不符，删除后重新下载", flush=True)
        archive_path.unlink()
    if not archive_path.exists():
        _download(archive["url"], archive_path, retries)
        if not _verify_archive(archive_path, archive):
            actual = sha256_of(archive_path)
            archive_path.unlink()
            die(f"压缩包大小或 SHA-256 与清单不符（清单 {archive['sha256']}，实际 {actual}），已删除")
    return _extract(archive_path, manifest, pathlib.Path(cache_dir))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("manifest", nargs="?", type=pathlib.Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--cache-dir", type=pathlib.Path, default=pathlib.Path(os.environ.get("AURI_CACHE_DIR", pathlib.Path.home() / ".cache" / "auri")))
    args = parser.parse_args(argv)
    model_dir = prepare(args.manifest, args.cache_dir)
    print(f"模型目录：{model_dir}")
    print(f"示例音频：{args.cache_dir / 'samples'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
