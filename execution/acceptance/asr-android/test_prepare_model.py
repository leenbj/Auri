"""prepare_model.py 的测试：模型包按清单里固定的哈希才会被接受，解包不会写出缓存目录之外。

用的是现场造的小 tar.bz2 与清单，下载走本机的 http.server：不联网、不碰真实的 163 MB 模型包。
真实模型包的校验在首次准备时由同一份代码完成（README 记录了结果）。
"""
import errno
import functools
import hashlib
import http.server
import io
import json
import os
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


def test_a_failed_refresh_keeps_the_previously_prepared_model_and_samples(tmp_path, serve):
    """T-023 的精神：新文件有问题时，原来校验过的好模型必须原样保留，而不是先删后装。"""
    www, base = serve
    good = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(good)
    cache = tmp_path / "cache"
    model_dir = prepare_model.prepare(write_manifest(tmp_path, make_manifest(good, f"{base}/pkg.tar.bz2")), cache)

    broken = build_archive({**GOOD_MEMBERS, f"{PREFIX}/model.int8.onnx": MODEL + b"x", f"{PREFIX}/test_wavs/zh.wav": b"NEW-WAV"})
    (www / "pkg2.tar.bz2").write_bytes(broken)
    with pytest.raises(SystemExit):
        prepare_model.prepare(write_manifest(tmp_path, make_manifest(broken, f"{base}/pkg2.tar.bz2")), cache)

    assert (model_dir / "model.int8.onnx").read_bytes() == MODEL
    assert (model_dir / "tokens.txt").read_bytes() == TOKENS
    assert (cache / "samples" / "zh.wav").read_bytes() == WAV, "失败的那次刷新不能动已有的示例音频"
    assert [p.name for p in cache.iterdir() if p.name.startswith("extract-")] == [], "暂存目录要清理干净"


def test_an_existing_symlink_in_the_samples_directory_is_replaced_not_followed(tmp_path, serve):
    """缓存里事先放好的符号链接不能把解出的文件引到别处去写（覆盖链接指向的文件）。"""
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive)
    cache = tmp_path / "cache"
    (cache / "samples").mkdir(parents=True)
    outside = tmp_path / "outside.wav"
    outside.write_bytes(b"ORIGINAL")
    (cache / "samples" / "zh.wav").symlink_to(outside)

    prepare_model.prepare(write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2")), cache)

    assert outside.read_bytes() == b"ORIGINAL"
    assert not (cache / "samples" / "zh.wav").is_symlink()
    assert (cache / "samples" / "zh.wav").read_bytes() == WAV


def test_download_messages_never_contain_url_credentials(tmp_path, capsys):
    archive = build_archive(GOOD_MEMBERS)
    secret_url = "http://user:s3cr3t-pass@127.0.0.1:9/p.tar.bz2?token=TOK3N-VALUE"
    manifest = write_manifest(tmp_path, make_manifest(archive, secret_url))

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(manifest, tmp_path / "cache", retries=0)

    shown = capsys.readouterr()
    everything = str(refused.value) + shown.out + shown.err
    assert "s3cr3t-pass" not in everything and "TOK3N-VALUE" not in everything
    assert "127.0.0.1" in everything, "主机与路径仍要给出，方便排查"


def test_a_download_larger_than_the_manifest_declares_is_refused_before_the_body_is_fetched(tmp_path, serve):
    """压缩包大小清单里写死了；服务器声明的长度更大就不该把它整个下完再检查。"""
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive + b"\0" * 4096)
    manifest = write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2"))
    cache = tmp_path / "cache"

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(manifest, cache, retries=0)

    assert "下载失败" in str(refused.value), str(refused.value)
    assert not (cache / "downloads" / "pkg.tar.bz2").exists()


def test_a_stream_without_declared_length_is_cut_off_at_the_manifest_size(tmp_path):
    """声明长度拦不住的流式响应：下载过程中 .part 一超过清单里的大小就掐断，不能先填满磁盘再检查。"""

    class Endless(http.server.BaseHTTPRequestHandler):
        sent = 0

        def do_GET(self):
            self.send_response(200)
            self.send_header("Connection", "close")  # 不写 Content-Length：curl 事先不知道有多大
            self.end_headers()
            chunk = b"\0" * 65536
            try:
                while Endless.sent < 64 * 1024 * 1024:  # 兜底：限制失效时测试也能在几秒内结束
                    self.wfile.write(chunk)
                    Endless.sent += len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Endless)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        archive = build_archive(GOOD_MEMBERS)
        manifest = write_manifest(tmp_path, make_manifest(archive, f"http://127.0.0.1:{server.server_address[1]}/pkg.tar.bz2"))
        cache = tmp_path / "cache"

        with pytest.raises(SystemExit) as refused:
            prepare_model.prepare(manifest, cache, retries=0)
    finally:
        server.shutdown()

    assert "下载失败" in str(refused.value), str(refused.value)
    assert Endless.sent < 16 * 1024 * 1024, f"应在清单大小（{len(archive)} 字节）附近就掐断，实际已发送 {Endless.sent} 字节"
    assert not list((cache / "downloads").glob("*")), "超限的残片不能留在缓存里被续传"


def test_curl_error_output_that_echoes_the_url_is_scrubbed_of_credentials(tmp_path, monkeypatch, capfd):
    """curl 遇到范围语法等错误会把完整 URL（含口令与查询串）原样打到它自己的 stderr：只脱敏脚本自己的输出不够。
    用一个假 curl 稳定地复现“把 URL 原样回显”，并用 capfd 抓子进程写到文件描述符上的输出。"""
    fake_bin = tmp_path / "fakebin"
    fake_bin.mkdir()
    fake_curl = fake_bin / "curl"
    fake_curl.write_text('#!/bin/sh\nfor last; do :; done\necho "curl: (3) bad range in URL position 9:" >&2\necho "$last" >&2\nexit 3\n')
    fake_curl.chmod(0o755)
    monkeypatch.setenv("PATH", f"{fake_bin}{os.pathsep}{os.environ['PATH']}")
    archive = build_archive(GOOD_MEMBERS)
    manifest = write_manifest(tmp_path, make_manifest(archive, "http://user:s3cr3t-pass@127.0.0.1:9/p.tar.bz2?token=TOK3N-VALUE"))

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(manifest, tmp_path / "cache", retries=0)

    seen = capfd.readouterr()
    everything = str(refused.value) + seen.out + seen.err
    assert "s3cr3t-pass" not in everything and "TOK3N-VALUE" not in everything, everything
    assert "下载失败" in everything
    assert "127.0.0.1" in everything, "主机与路径仍要给出，方便排查"


class Ranged(http.server.BaseHTTPRequestHandler):
    """支持 Range 的服务器：文件总长 total，续传时只发剩下的部分。"""

    total = 0

    def do_GET(self):
        start = int(self.headers.get("Range", "bytes=0-").split("=")[1].split("-")[0])
        body = b"\0" * (self.total - start)
        self.send_response(206 if start else 200)
        if start:
            self.send_header("Content-Range", f"bytes {start}-{self.total - 1}/{self.total}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def test_a_resumed_download_may_only_fetch_what_is_left_under_the_manifest_size(tmp_path):
    """-C - 续传时 --max-filesize 数的是这一次要下的字节数：不扣掉已有的残片，反复中断就能把总量撑过清单大小。"""
    archive = build_archive(GOOD_MEMBERS)
    assert len(archive) > 200
    already = len(archive) - 10  # 残片只差 10 字节就满了
    cache = tmp_path / "cache"
    (cache / "downloads").mkdir(parents=True)
    (cache / "downloads" / "pkg.tar.bz2.part").write_bytes(b"\0" * already)
    handler = type("Resumable", (Ranged,), {"total": already + 100})  # 续传还要发 100 字节：超出剩余的 10，但不超过整包大小
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        manifest = write_manifest(tmp_path, make_manifest(archive, f"http://127.0.0.1:{server.server_address[1]}/pkg.tar.bz2"))

        with pytest.raises(SystemExit) as refused:
            prepare_model.prepare(manifest, cache, retries=0)
    finally:
        server.shutdown()

    assert "下载失败" in str(refused.value), str(refused.value)
    assert not list((cache / "downloads").glob("*")), "超限的残片不能留着被下次续传"


def test_a_leftover_part_file_larger_than_the_manifest_size_is_discarded_and_the_download_restarts(tmp_path, serve):
    """比整个压缩包还大的残片不可能是这个包的前半截：删掉从头下，而不是带着它去续传（服务器不支持 Range 就卡死在那里）。"""
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive)
    cache = tmp_path / "cache"
    (cache / "downloads").mkdir(parents=True)
    (cache / "downloads" / "pkg.tar.bz2.part").write_bytes(b"\0" * (len(archive) + 50))

    model_dir = prepare_model.prepare(write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2")), cache)

    assert (model_dir / "tokens.txt").read_bytes() == TOKENS


def test_a_part_file_that_is_a_symlink_is_refused_instead_of_written_through(tmp_path, serve):
    """.part 本身是符号链接时，curl 会以追加方式写到链接指向的文件：必须先拒绝，别的文件不能被动。"""
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive)
    cache = tmp_path / "cache"
    (cache / "downloads").mkdir(parents=True)
    outside = tmp_path / "outside.bin"
    outside.write_bytes(b"KEEP")
    (cache / "downloads" / "pkg.tar.bz2.part").symlink_to(outside)

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2")), cache, retries=0)

    assert "符号链接" in str(refused.value), str(refused.value)
    assert outside.read_bytes() == b"KEEP"


@pytest.mark.parametrize("declares_length", [True, False])
def test_a_server_that_cannot_resume_gets_the_partial_file_dropped_so_a_rerun_converges(tmp_path, declares_length):
    """服务器忽略 Range、整份回 200 时，续传永远不会成功：残片要丢掉，否则每次重跑都发同一个续传请求、再失败，要人手动去删。
    声明了长度时 curl 先撞上 --max-filesize（63），没声明长度时是“不支持续传”（33）：两条路都要收敛。"""
    archive = build_archive(GOOD_MEMBERS)

    class IgnoresRange(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            if declares_length:
                self.send_header("Content-Length", str(len(archive)))
            self.end_headers()
            self.wfile.write(archive)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), IgnoresRange)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    cache = tmp_path / "cache"
    part = cache / "downloads" / "pkg.tar.bz2.part"
    part.parent.mkdir(parents=True)
    part.write_bytes(archive[:10])
    try:
        manifest = write_manifest(tmp_path, make_manifest(archive, f"http://127.0.0.1:{server.server_address[1]}/pkg.tar.bz2"))

        with pytest.raises(SystemExit) as first:
            prepare_model.prepare(manifest, cache, retries=0)
        assert "断点续传" in str(first.value), str(first.value)
        assert not part.exists(), "残片留着，下次还是同样的续传请求，永远卡在这里"

        model_dir = prepare_model.prepare(manifest, cache, retries=0)  # 重跑：没有残片，从头下载
        assert (model_dir / "tokens.txt").read_bytes() == TOKENS
    finally:
        server.shutdown()


@pytest.mark.parametrize("link_name", ["downloads", "models", "models/test-model", "samples"])
def test_a_symlinked_cache_subdirectory_is_refused_instead_of_followed(tmp_path, serve, link_name):
    """缓存里的 downloads、models、samples 若是符号链接，往里写就等于写到缓存之外：拒绝并说明，不跟随。"""
    www, base = serve
    archive = build_archive(GOOD_MEMBERS)
    (www / "pkg.tar.bz2").write_bytes(archive)
    cache = tmp_path / "cache"
    outside = tmp_path / "outside"
    outside.mkdir()
    link = cache / link_name
    link.parent.mkdir(parents=True, exist_ok=True)
    link.symlink_to(outside, target_is_directory=True)

    with pytest.raises(SystemExit) as refused:
        prepare_model.prepare(write_manifest(tmp_path, make_manifest(archive, f"{base}/pkg.tar.bz2")), cache)

    assert "符号链接" in str(refused.value), str(refused.value)
    assert list(outside.iterdir()) == [], "不能往符号链接指向的目录里写任何东西"


def test_replacing_a_directory_never_deletes_the_only_remaining_copy_of_the_old_one(tmp_path, monkeypatch):
    """上次换到一半被打断时，旧目录只剩 .old 这一份：重试若先删 .old 再换入，换入再失败旧模型就彻底没了。"""
    destination = tmp_path / "models" / "v1"
    only_copy = tmp_path / "models" / "v1.old"
    only_copy.mkdir(parents=True)
    (only_copy / "weights").write_text("ONLY-COPY")
    staged = tmp_path / "staged"
    staged.mkdir()
    (staged / "weights").write_text("NEW")
    real_replace = pathlib.Path.replace

    def failing_replace(self, target):
        if self == staged:
            raise OSError(errno.EXDEV, "跨文件系统，换入失败")
        return real_replace(self, target)

    monkeypatch.setattr(pathlib.Path, "replace", failing_replace)

    with pytest.raises(OSError):
        prepare_model._replace_dir(staged, destination)

    assert (destination / "weights").read_text() == "ONLY-COPY", "换入失败后旧目录必须原样回到原位"


def test_a_failed_swap_puts_the_previous_directory_back(tmp_path, monkeypatch):
    destination = tmp_path / "models" / "v1"
    destination.mkdir(parents=True)
    (destination / "weights").write_text("OLD")
    staged = tmp_path / "staged"
    staged.mkdir()
    (staged / "weights").write_text("NEW")
    real_replace = pathlib.Path.replace

    def failing_replace(self, target):
        if self == staged:
            raise OSError(errno.EXDEV, "跨文件系统，换入失败")
        return real_replace(self, target)

    monkeypatch.setattr(pathlib.Path, "replace", failing_replace)

    with pytest.raises(OSError):
        prepare_model._replace_dir(staged, destination)

    assert (destination / "weights").read_text() == "OLD"
    assert not (tmp_path / "models" / "v1.old").exists(), "放回去之后不留 .old"


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


def test_a_model_version_that_looks_like_the_backup_of_another_version_is_rejected(tmp_path):
    """换位时旧目录先改名成 <版本>.old：另一个版本若恰好叫 v1.old，准备 v1 时就会把它当垃圾删掉。"""
    archive = build_archive(GOOD_MEMBERS)
    manifest = make_manifest(archive, "http://127.0.0.1:9/x.tar.bz2")
    manifest["model_version"] = "v1.old"

    with pytest.raises(SystemExit) as refused:
        prepare_model.load_manifest(write_manifest(tmp_path, manifest))

    assert ".old" in str(refused.value), str(refused.value)


def test_the_real_manifest_in_the_repo_has_what_the_script_needs():
    manifest = json.loads(
        (prepare_model.REPO_ROOT / "apps/android/manifests/sensevoice-small-int8-2024-07-17.manifest.json").read_text(encoding="utf-8")
    )

    assert manifest["archive"]["url"].startswith("https://")
    assert {f["path"] for f in manifest["files"]} == {"model.int8.onnx", "tokens.txt"}
