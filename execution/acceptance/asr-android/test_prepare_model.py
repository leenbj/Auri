"""prepare_model.py 的测试：模型包按清单里固定的哈希才会被接受，解包不会写出缓存目录之外。

用的是现场造的小 tar.bz2 与清单，下载走本机的 http.server：不联网、不碰真实的 163 MB 模型包。
真实模型包的校验在首次准备时由同一份代码完成（README 记录了结果）。
"""
import functools
import hashlib
import http.server
import io
import json
import pathlib
import tarfile
import threading

import pytest

import prepare_model

PREFIX = "pkg-2024"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


MODEL = bytes(range(256)) * 8
TOKENS = b"a 0\nb 1\n"
WAV = b"RIFF....WAVEfmt fake wav"


def build_archive(members: dict[str, bytes], extra_tar_members=()) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:bz2") as tar:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        for info, payload in extra_tar_members:
            tar.addfile(info, payload)
    return buffer.getvalue()


GOOD_MEMBERS = {
    f"{PREFIX}/model.int8.onnx": MODEL,
    f"{PREFIX}/tokens.txt": TOKENS,
    f"{PREFIX}/test_wavs/zh.wav": WAV,
    f"{PREFIX}/README.md": b"not wanted",
}


def make_manifest(archive: bytes, url: str, model_sha: str | None = None) -> dict:
    return {
        "manifest_version": 1,
        "model_id": "test-model",
        "model_version": "v1",
        "archive": {"url": url, "size_bytes": len(archive), "sha256": sha(archive)},
        "files": [
            {"role": "model", "path": "model.int8.onnx", "size_bytes": len(MODEL), "sha256": model_sha or sha(MODEL)},
            {"role": "tokens", "path": "tokens.txt", "size_bytes": len(TOKENS), "sha256": sha(TOKENS)},
        ],
    }


@pytest.fixture()
def serve(tmp_path):
    """把 tmp_path/www 里的文件用本机 HTTP 服务器提供出来，返回基础 URL。"""
    www = tmp_path / "www"
    www.mkdir()
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(www))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield www, f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def write_manifest(tmp_path: pathlib.Path, manifest: dict) -> pathlib.Path:
    path = tmp_path / "m.manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def test_downloads_verifies_and_extracts_only_what_the_manifest_lists(tmp_path, serve):
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive)
    manifest = write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2"))
    cache = tmp_path / "cache"

    model_dir = prepare_model.prepare(manifest, cache)

    assert model_dir == cache / "models" / "test-model" / "v1"
    assert (model_dir / "model.int8.onnx").read_bytes() == MODEL
    assert (model_dir / "tokens.txt").read_bytes() == TOKENS
    assert (cache / "samples" / "zh.wav").read_bytes() == WAV
    assert not (model_dir / "README.md").exists(), "清单没列的文件不解出来"


def test_refuses_an_archive_whose_hash_differs_and_removes_it(tmp_path, serve):
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    tampered = bytearray(archive)
    tampered[len(tampered) // 2] ^= 1
    (www / "pkg.tar.bz2").write_bytes(bytes(tampered))
    manifest = write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2"))
    cache = tmp_path / "cache"

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(manifest, cache)

    assert "SHA-256" in str(refused.value)
    assert not (cache / "downloads" / "pkg.tar.bz2").exists(), "不合格的包不能留在缓存里被下次直接采信"
    assert not (cache / "models").exists()


def test_refuses_when_an_extracted_file_does_not_match_the_manifest(tmp_path, serve):
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive)
    manifest = write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2", model_sha="0" * 64))
    cache = tmp_path / "cache"

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(manifest, cache)

    assert "model.int8.onnx" in str(refused.value)
    assert not (cache / "models" / "test-model" / "v1" / "model.int8.onnx").exists(), "校验不过的文件不能留在模型目录里"


def test_a_cached_archive_is_reused_without_any_download(tmp_path):
    archive = build_archive(GOOD_MEMBERS)
    manifest = write_manifest(tmp_path, make_manifest(archive, "http://127.0.0.1:9/never-reachable.tar.bz2"))
    cache = tmp_path / "cache"
    (cache / "downloads").mkdir(parents=True)
    (cache / "downloads" / "never-reachable.tar.bz2").write_bytes(archive)

    model_dir = prepare_model.prepare(manifest, cache)

    assert (model_dir / "tokens.txt").read_bytes() == TOKENS


def test_unreachable_url_fails_loudly_instead_of_continuing(tmp_path):
    archive = build_archive(GOOD_MEMBERS)
    manifest = write_manifest(tmp_path, make_manifest(archive, "http://127.0.0.1:9/nothing.tar.bz2"))

    with pytest.raises(SystemExit):
        prepare_model.prepare(manifest, tmp_path / "cache", retries=0)


def test_archive_members_cannot_write_outside_the_cache(tmp_path, serve):
    """成员名带 .. 或是符号链接：要么被忽略要么被拒绝，缓存之外不能出现任何文件。"""
    www, base = serve
    evil = tarfile.TarInfo(f"{PREFIX}/../../escaped.txt")
    evil.size = 4
    link = tarfile.TarInfo(f"{PREFIX}/test_wavs/link.wav")
    link.type = tarfile.SYMTYPE
    link.linkname = "/etc/passwd"
    archive = build_archive(GOOD_MEMBERS, extra_tar_members=[(evil, io.BytesIO(b"evil")), (link, io.BytesIO(b""))])
    (www / "pkg.tar.bz2").write_bytes(archive)
    manifest = write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2"))
    cache = tmp_path / "work" / "cache"

    prepare_model.prepare(manifest, cache)

    assert not (tmp_path / "escaped.txt").exists()
    assert not (tmp_path / "work" / "escaped.txt").exists()
    assert not (cache / "samples" / "link.wav").exists(), "符号链接不是常规文件，不解出"
    assert sorted(p.name for p in (cache / "samples").iterdir()) == ["zh.wav"]


def test_rejects_a_manifest_that_is_missing_required_fields(tmp_path):
    path = write_manifest(tmp_path, {"manifest_version": 1})

    with pytest.raises(SystemExit):
        prepare_model.prepare(path, tmp_path / "cache")


def test_rejects_unsafe_file_paths_in_the_manifest(tmp_path):
    archive = build_archive(GOOD_MEMBERS)
    manifest = make_manifest(archive, "http://127.0.0.1:9/x.tar.bz2")
    manifest["files"][0]["path"] = "../outside.onnx"
    path = write_manifest(tmp_path, manifest)

    with pytest.raises(SystemExit):
        prepare_model.prepare(path, tmp_path / "cache")


def test_the_real_manifest_in_the_repo_has_what_the_script_needs():
    manifest = json.loads(
        (prepare_model.REPO_ROOT / "apps/android/manifests/sensevoice-small-int8-2024-07-17.manifest.json").read_text(encoding="utf-8")
    )

    assert manifest["archive"]["url"].startswith("https://")
    assert {f["path"] for f in manifest["files"]} == {"model.int8.onnx", "tokens.txt"}
