"""real_gateway_host.py 的测试：宿主机一侧的辅助脚本本身必须可靠，否则模拟器验收的结论没有意义。

这里起的是真实的网关进程（仓库 .venv 的解释器，services/gateway），不是 mock。验证：
- 交接文件里的载荷地址是模拟器能用的 10.0.2.2:端口，载荷里的指纹等于网关证书文件里公钥的指纹（测试自己算的）；
- 收到 SIGTERM 之后网关进程也一起停了，端口不再有人听；启动它的父进程没了也一样（不留孤儿网关）；
- 核对器会变红：没有设备、没有这一版、正文不对、哈希不对、多了一台设备、多了一版，各自都要被抓到；全部符合才通过。
"""
from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import pathlib
import signal
import socket
import ssl
import subprocess
import sys
import time

import pytest

import real_gateway_host as host

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "real_gateway_host.py"
FIXTURE = host.REPO_ROOT / "contracts" / "fixtures" / "messages" / "valid" / "revision.submission.no-destination.json"


def wait_for(path: pathlib.Path, timeout: float = 60.0) -> None:
    deadline = time.monotonic() + timeout
    while not path.is_file():
        assert time.monotonic() < deadline, f"等了 {timeout} 秒也没有 {path}"
        time.sleep(0.1)


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def wait_until(condition, timeout: float, what: str) -> None:
    deadline = time.monotonic() + timeout
    while not condition():
        assert time.monotonic() < deadline, f"{timeout} 秒内没有做到：{what}"
        time.sleep(0.1)


def spki_sha256(cert_pem: pathlib.Path) -> str:
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    certificate = x509.load_pem_x509_certificate(cert_pem.read_bytes())
    der = certificate.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(der).hexdigest()


class Running:
    """一个正在跑的 `real_gateway_host.py start`。"""

    def __init__(self, tmp: pathlib.Path) -> None:
        self.dir = tmp / "gateway"
        self.handoff_path = tmp / "handoff.json"
        self.process = subprocess.Popen(
            [sys.executable, str(SCRIPT), "start", "--dir", str(self.dir), "--handoff", str(self.handoff_path)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            wait_for(self.handoff_path)
        except AssertionError:
            self.stop()
            raise
        self.handoff = json.loads(self.handoff_path.read_text(encoding="utf-8"))
        self.port = int(self.handoff["port"])

    def stop(self) -> int:
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.process.stdout.close()
        self.process.stderr.close()
        return self.process.returncode

    # ---- 在回环上扮演一台设备（模拟器里的 App 走 10.0.2.2，宿主机上走 127.0.0.1；同一个网关） ----

    def request(self, method: str, path: str, body: dict | None = None, token: str | None = None) -> tuple[int, dict]:
        context = ssl.create_default_context(cafile=str(self.dir / "tls" / "gateway.cert.pem"))
        context.check_hostname = False
        connection = http.client.HTTPSConnection("127.0.0.1", self.port, timeout=10, context=context)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        try:
            connection.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read() or b"{}")
        finally:
            connection.close()

    def pair(self, code: str, name: str = "pytest device") -> str:
        status, body = self.request("POST", "/v1/pairing/redeem", {
            "schema_version": "1.2", "pairing_code": code,
            "device": {"platform": "android", "app_version": "0.0.0-test", "os_build": "test", "display_name": name},
        })
        assert status == 200, body
        return body["device_token"]

    def put_fixture(self, token: str) -> dict:
        instance = json.loads(FIXTURE.read_text(encoding="utf-8"))["instance"]
        instance["capture"]["platform"] = "android"
        instance["publication"]["destination_ids"] = []
        status, body = self.request("PUT", f"/v1/records/{instance['record_id']}/revisions/{instance['revision']}", instance, token)
        assert status == 200, body
        return instance

    def verify(self, record_id: str, text: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "verify", "--dir", str(self.dir), "--record-id", record_id, "--text", text],
            capture_output=True, text=True, check=False, timeout=60,
        )


@pytest.fixture()
def running(tmp_path):
    instance = Running(tmp_path)
    try:
        yield instance
    finally:
        instance.stop()


def test_handoff_gives_the_emulator_a_usable_payload_and_the_fingerprint_is_the_certificates(running):
    payload = json.loads(running.handoff["payload"])
    assert payload["addresses"] == [{"host": "10.0.2.2", "port": running.port}]
    assert base64.b64decode(running.handoff["payload_b64"]).decode("utf-8") == running.handoff["payload"]
    assert payload["public_key_sha256"] == running.handoff["public_key_sha256"]
    assert payload["public_key_sha256"] == spki_sha256(running.dir / "tls" / "gateway.cert.pem")
    assert host.serving(running.dir, running.port), "网关必须真的在经 TLS 提供服务"
    assert not (running.dir / "tls" / "gateway.cert.pem").read_text().startswith("fake")


def test_sigterm_stops_the_gateway_too_and_leaves_nothing_listening(running):
    assert port_open(running.port)

    assert running.stop() == 0

    wait_until(lambda: not port_open(running.port), 10, "网关停下后端口不再有人听")
    leftovers = subprocess.run(["pgrep", "-f", str(running.dir)], capture_output=True, text=True).stdout.split()
    assert not leftovers, f"还有进程在用这个数据目录：{leftovers}"


def test_gateway_is_stopped_when_the_launching_script_disappears(tmp_path):
    handoff = tmp_path / "handoff.json"
    pid_file = tmp_path / "helper.pid"
    parent = (
        "import subprocess, sys, pathlib, os\n"
        f"p = subprocess.Popen([sys.executable, {str(SCRIPT)!r}, 'start', '--dir', {str(tmp_path / 'gateway')!r}, '--handoff', {str(handoff)!r}],"
        " stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)\n"
        f"pathlib.Path({str(pid_file)!r}).write_text(str(p.pid))\n"
        # 等网关就绪（交接文件出现）后，父进程不打招呼就消失：模拟启动脚本被 SIGKILL
        f"import time\n"
        f"while not pathlib.Path({str(handoff)!r}).is_file(): time.sleep(0.1)\n"
        "os._exit(0)\n"
    )
    subprocess.run([sys.executable, "-c", parent], check=True, timeout=90)
    port = int(json.loads(handoff.read_text())["port"])
    helper_pid = int(pid_file.read_text())

    def helper_gone() -> bool:
        try:
            os.kill(helper_pid, 0)
        except ProcessLookupError:
            return True
        return False

    try:
        wait_until(helper_gone, 15, "启动它的脚本没了之后，辅助脚本自己退出")
        wait_until(lambda: not port_open(port), 15, "辅助脚本退出后网关也停了")
    finally:
        if not helper_gone():
            os.kill(helper_pid, signal.SIGKILL)


def test_verify_is_red_until_a_device_really_stored_exactly_this_text(running):
    instance = json.loads(FIXTURE.read_text(encoding="utf-8"))["instance"]
    record_id, text = instance["record_id"], instance["content"]["text"]

    empty = running.verify(record_id, text)
    assert empty.returncode != 0
    assert "恰好登记 1 台设备" in empty.stderr and "恰好 1 条" in empty.stderr and "恰好 1 版" in empty.stderr

    code = json.loads(running.handoff["payload"])["pairing_code"]
    token = running.pair(code)
    paired_only = running.verify(record_id, text)
    assert paired_only.returncode != 0 and "恰好 1 版" in paired_only.stderr

    running.put_fixture(token)
    assert running.verify(record_id, text).returncode == 0
    running.put_fixture(token)  # 重复提交是幂等的：仍然通过
    ok = running.verify(record_id, text)
    assert ok.returncode == 0, ok.stderr
    assert "核对通过" in ok.stdout

    wrong_text = running.verify(record_id, text + "！")
    assert wrong_text.returncode != 0
    assert "正文与设备发的不一致" in wrong_text.stderr and "哈希与设备发的不一致" in wrong_text.stderr
    other_record = running.verify("00000000-0000-4000-8000-000000000000", text)
    assert other_record.returncode != 0 and "恰好 1 条" in other_record.stderr


def test_verify_catches_a_second_device(running):
    instance = json.loads(FIXTURE.read_text(encoding="utf-8"))["instance"]
    record_id, text = instance["record_id"], instance["content"]["text"]
    token = running.pair(json.loads(running.handoff["payload"])["pairing_code"])
    running.put_fixture(token)
    assert running.verify(record_id, text).returncode == 0

    second = host.cli("pair", "--data-dir", str(running.dir), "--host", "127.0.0.1", "--port", str(running.port), "--json")
    running.pair(json.loads(second.stdout)["pairing_code"], name="the second device")

    two_devices = running.verify(record_id, text)
    assert two_devices.returncode != 0 and "恰好登记 1 台设备" in two_devices.stderr


def test_verify_catches_a_revoked_device(running):
    instance = json.loads(FIXTURE.read_text(encoding="utf-8"))["instance"]
    record_id, text = instance["record_id"], instance["content"]["text"]
    token = running.pair(json.loads(running.handoff["payload"])["pairing_code"])
    running.put_fixture(token)
    device_id = host.cli("list-devices", "--data-dir", str(running.dir), "--json").stdout
    revoked = host.cli("revoke-device", "--data-dir", str(running.dir), "--device-id", json.loads(device_id)["devices"][0]["device_id"])
    assert revoked.returncode == 0, revoked.stderr

    result = running.verify(record_id, text)

    assert result.returncode != 0 and "吊销" in result.stderr
