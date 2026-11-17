#!/usr/bin/env python3
"""准备清单固定的本地转录模型：下载（可续传）→ 校验 → 只解出清单里列的文件与示例音频。

用法：python3 prepare_model.py [清单文件] [--cache-dir 目录]
  默认清单   apps/android/manifests/sensevoice-small-int8-2024-07-17.manifest.json
  默认缓存   ${AURI_CACHE_DIR:-~/.cache/auri}
  产出       <缓存>/models/<model_id>/<model_version>/（清单里列的文件）与 <缓存>/samples/*.wav（包里的示例音频）

信任锚是清单里固定的 SHA-256，不是 URL：压缩包、解出的每个文件都按清单的大小与哈希校验，
不符就以非零状态退出。解包先进暂存目录，全部校验通过才换进缓存，失败时原来的模型与示例音频原样保留。
模型权重与示例音频只进缓存目录，不进仓库。
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
import tempfile
from urllib.parse import urlparse, urlsplit

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DEFAULT_MANIFEST = REPO_ROOT / "apps" / "android" / "manifests" / "sensevoice-small-int8-2024-07-17.manifest.json"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_MODEL_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,99}$")
# 版本名不能以 .old 结尾：换位时旧目录先改名成 <版本>.old 再删（这里与 install_on_device.sh 都是），
# 另一个版本若恰好叫 v1.old，准备/安装 v1 时就会被当成垃圾一起删掉
_MODEL_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,199}(?<!\.old)$")


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
            die("model_id 或 model_version 的格式不对（model_version 不能以 .old 结尾：那是换位时备份目录的名字）")
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


def _display_url(url: str) -> str:
    """日志与错误信息里的 URL：去掉用户名、口令和查询参数，它们里面可能有凭证。"""
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc.rpartition('@')[2]}{parts.path}"


def _scrub(text: str, url: str) -> str:
    """curl 自己的错误输出有时会原样带上完整 URL（比如范围语法错误）：换成脱敏后的形式，再抹掉口令与查询串。"""
    parts = urlsplit(url)
    text = text.replace(url, _display_url(url))
    for secret in (parts.netloc.rpartition("@")[0], parts.query, parts.fragment):
        if secret:
            text = text.replace(secret, "***")
    return text


def _download(url: str, archive_path: pathlib.Path, retries: int, max_bytes: int) -> None:
    part = archive_path.with_name(archive_path.name + ".part")
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    _refuse_symlink(part)  # curl 以追加方式打开 .part：它若是链接，就会往链接指向的文件里写
    have = part.stat().st_size if part.exists() else 0
    if have > max_bytes:  # 比整个压缩包还大的残片不可能是它的前半截：从头下，别带着它去续传
        part.unlink()
        have = 0
    shown = _display_url(url)
    print(f"下载 {shown}", flush=True)
    if have < max_bytes:  # 恰好等于清单大小时残片已经是完整的一份，直接交给后面的哈希校验
        # --max-filesize 数的是“这一次要下载的字节数”：续传时要扣掉已有的残片，否则反复中断能把总量撑过清单大小。
        # 声明的长度超限就不下载；没声明长度的流式响应，curl 8.7.1 也会在超出时掐断
        # （test_a_stream_without_declared_length_is_cut_off_at_the_manifest_size 在本机实测）。
        # shortcut: 更老的 curl 若不对流式响应执行这个限制，就只剩下完之后的大小与 SHA-256 校验兜底。
        command = [
            "curl", "-L", "--fail", "--silent", "--show-error", "-C", "-", "--retry", str(retries), "--retry-delay", "2",
            "--max-filesize", str(max_bytes - have), "-o", str(part), url,
        ]
        # curl 的 stderr 要接过来脱敏再给人看：它的错误信息可能原样带着完整 URL（含口令与查询串）
        run = subprocess.run(command, stderr=subprocess.PIPE, text=True, errors="replace")
        if run.returncode == 33:  # curl: 服务器不支持 Range，续传不可能成功；残片留着，每次重跑都是同一个请求、同一个失败
            part.unlink(missing_ok=True)
            die(f"下载失败：{shown} 不支持断点续传，已丢掉 {have} 字节的残片，重跑会从头下载")
        if run.returncode == 63:  # curl: Maximum file size exceeded
            part.unlink(missing_ok=True)  # 超限的残片不能留着：下次 -C - 会接着它续传
            if have:  # 带着残片续传时收到超出“剩余额度”的内容：最常见的原因是服务器忽略 Range、整份重发
                die(
                    f"下载失败：续传 {shown} 时收到的内容超出了清单允许的剩余大小（已有 {have} 字节，整包上限 {max_bytes} 字节）："
                    "服务器多半不支持断点续传，已丢掉残片，重跑会从头下载；若重跑仍然超限，说明文件比清单声明的大"
                )
            die(f"下载失败：{shown} 的大小超过清单里的压缩包大小（{max_bytes} 字节），已中止")
        if run.returncode != 0:
            detail = _scrub(run.stderr, url).strip()
            die(f"下载失败：{shown}{'：' + detail if detail else ''}（已下载的部分保留在 {part}，重跑会续传）")
    part.replace(archive_path)


def _refuse_symlink(path: pathlib.Path) -> None:
    if path.is_symlink():
        die(f"缓存里的 {path} 是符号链接，不跟随它写文件：把它删掉，或用 --cache-dir 直接指向真实目录")


def _replace_dir(staged: pathlib.Path, destination: pathlib.Path) -> None:
    """用暂存好的目录换掉旧目录。旧目录先改名后删；换入失败就把它放回去。
    上次换到一半被打断时旧目录只剩 .old 这一份：重试先把它放回原位，绝不能先删 .old。"""
    destination.parent.mkdir(parents=True, exist_ok=True)
    old = destination.with_name(destination.name + ".old")
    if old.exists() and not destination.exists():
        old.replace(destination)
    shutil.rmtree(old, ignore_errors=True)
    if destination.is_symlink():
        destination.unlink()
    elif destination.exists():
        destination.replace(old)
    try:
        staged.replace(destination)
    except OSError:
        if old.exists() and not destination.exists():
            old.replace(destination)
        raise
    shutil.rmtree(old, ignore_errors=True)


def _extract(archive_path: pathlib.Path, manifest: dict, cache_dir: pathlib.Path) -> pathlib.Path:
    model_dir = cache_dir / "models" / manifest["model_id"] / manifest["model_version"]
    samples_dir = cache_dir / "samples"
    cache_dir.mkdir(parents=True, exist_ok=True)

    # 先全部解进全新的暂存目录（mkdtemp 新建，缓存里事先放好的符号链接引不走写入），逐个校验通过才换进缓存：
    # 校验失败时，缓存里原来校验过的模型与示例音频原样保留（T-023 的精神：新文件有问题不能丢掉旧的好文件）。
    stage = pathlib.Path(tempfile.mkdtemp(prefix="extract-", dir=cache_dir))
    try:
        staged_model, staged_samples = stage / "model", stage / "samples"
        staged_model.mkdir()
        staged_samples.mkdir()
        wanted = {entry["path"]: staged_model / entry["path"] for entry in manifest["files"]}
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
                    destination = staged_samples / parts[2]
                else:
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target, 1 << 20)

        missing = sorted(set(wanted) - found)
        if missing:
            die(f"压缩包里没有清单要求的文件：{missing}")
        for entry in manifest["files"]:
            file = wanted[entry["path"]]
            if file.stat().st_size != int(entry["size_bytes"]) or sha256_of(file) != entry["sha256"]:
                die(f"解出的文件 {entry['path']} 与清单的大小或 SHA-256 不符，未写入缓存")

        _replace_dir(staged_model, model_dir)
        samples_dir.mkdir(parents=True, exist_ok=True)
        for wav in sorted(staged_samples.iterdir()):
            wav.replace(samples_dir / wav.name)  # rename 替换的是目录项本身：目标若是符号链接，换掉链接而不是写进链接指向的文件
    finally:
        shutil.rmtree(stage, ignore_errors=True)
    return model_dir


def prepare(manifest_path: pathlib.Path, cache_dir: pathlib.Path, retries: int = 5) -> pathlib.Path:
    manifest = load_manifest(pathlib.Path(manifest_path))
    archive = manifest["archive"]
    for sub in ("downloads", "models", f"models/{manifest['model_id']}", "samples"):
        _refuse_symlink(pathlib.Path(cache_dir) / sub)  # 缓存根目录本身可以是链接（用户自己的选择），里面的子目录不行
    archive_path = pathlib.Path(cache_dir) / "downloads" / pathlib.PurePosixPath(urlparse(archive["url"]).path).name

    if archive_path.exists() and not _verify_archive(archive_path, archive):
        print(f"缓存里的 {archive_path.name} 与清单不符，删除后重新下载", flush=True)
        archive_path.unlink()
    if not archive_path.exists():
        _download(archive["url"], archive_path, retries, int(archive["size_bytes"]))
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
