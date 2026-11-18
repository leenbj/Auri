#!/usr/bin/env python3
"""Ticket 21 模拟器验收的宿主机一侧：起真实网关、交出配对载荷、事后回头查网关数据库。

模拟器用 10.0.2.2 访问宿主机的回环地址，所以网关只听 127.0.0.1（不开放到局域网），配对载荷里写的地址是 10.0.2.2:端口。
网关是 services/gateway 的真实进程（自签 TLS、临时数据目录、随机端口、不广播 mDNS），不是 mock。

  real_gateway_host.py start  --dir DIR --handoff FILE [--advertise-host 10.0.2.2]
      起网关；就绪后把 {"port", "payload", "payload_b64", "public_key_sha256"} 写进 FILE，然后一直守着网关，
      收到 SIGTERM/SIGINT 或者父进程没了就停掉网关并退出（不会留下孤儿网关进程）。
  real_gateway_host.py verify --dir DIR --record-id UUID --text TEXT
      只读打开网关数据库核对：恰好一台设备（公钥不符的那次配对没有消耗配对码）、这条 Record 恰好一条、这一版恰好一条且正文与哈希一致、
      来源设备就是那台设备。有任何不符退出码非零，并逐条说明。

只用标准库；网关用运行本脚本的同一个解释器（仓库的 .venv）启动。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
import os
import pathlib
import signal
import socket
import sqlite3
import ssl
import subprocess
import sys
import time

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
GATEWAY_SRC = REPO_ROOT / "services" / "gateway"
START_TIMEOUT_SECONDS = 30.0


def gateway_env() -> dict[str, str]:
    env = os.environ.copy()
    existing = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = f"{GATEWAY_SRC}:{existing}" if existing else str(GATEWAY_SRC)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUNBUFFERED"] = "1"
    return env


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def cli(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "auri_gateway", *args],
        cwd=REPO_ROOT, env=gateway_env(), capture_output=True, text=True, check=False, timeout=120,
    )


def serving(data_dir: pathlib.Path, port: int) -> bool:
    """证书文件已写好，并且网关经 TLS 答了 /health。只信网关自己的那一张自签证书（不是“不校验”）。"""
    cert = data_dir / "tls" / "gateway.cert.pem"
    if not cert.is_file():
        return False
    try:
        context = ssl.create_default_context(cafile=str(cert))
        context.check_hostname = False  # 自签证书按公钥认，不按主机名；证书本身仍然必须是这一张
        connection = http.client.HTTPSConnection("127.0.0.1", port, timeout=2, context=context)
        try:
            connection.request("GET", "/health")
            return connection.getresponse().status == 200
        finally:
            connection.close()
    except (OSError, ssl.SSLError, http.client.HTTPException):
        return False


def stop(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def command_start(args: argparse.Namespace) -> int:
    data_dir = pathlib.Path(args.dir)
    data_dir.mkdir(parents=True, exist_ok=True)
    port = free_port()
    log_path = data_dir.parent / f"{data_dir.name}.gateway.log"
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            [sys.executable, "-m", "auri_gateway", "serve", "--data-dir", str(data_dir), "--host", "127.0.0.1", "--port", str(port), "--no-mdns"],
            cwd=REPO_ROOT, env=gateway_env(), stdout=log, stderr=subprocess.STDOUT,
        )
    terminated = []
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, lambda *_: terminated.append(signum))
    try:
        deadline = time.monotonic() + START_TIMEOUT_SECONDS
        while not serving(data_dir, port):
            if process.poll() is not None:
                print(f"网关启动时退出了（退出码 {process.returncode}）：{log_path.read_text(errors='replace')[:600]}", file=sys.stderr)
                return 1
            if terminated:
                return 1
            if time.monotonic() > deadline:
                print(f"网关 {START_TIMEOUT_SECONDS:.0f} 秒内没有开始提供 HTTPS 服务：{log_path.read_text(errors='replace')[:600]}", file=sys.stderr)
                return 1
            time.sleep(0.1)
        issued = cli("pair", "--data-dir", str(data_dir), "--host", args.advertise_host, "--port", str(port), "--ttl-seconds", "600", "--json")
        if issued.returncode != 0:
            print(f"生成配对载荷失败：{issued.stderr[:400]}", file=sys.stderr)
            return 1
        payload_text = issued.stdout.strip()
        payload = json.loads(payload_text)
        handoff = {
            "port": port,
            "payload": payload_text,
            "payload_b64": base64.b64encode(payload_text.encode("utf-8")).decode("ascii"),
            "public_key_sha256": payload["public_key_sha256"],
        }
        pathlib.Path(args.handoff).write_text(json.dumps(handoff), encoding="utf-8")
        parent = os.getppid()
        # 守着网关：被通知、网关自己退出、或者启动我的那个脚本没了（父进程变了），都停下
        while not terminated and process.poll() is None and os.getppid() == parent:
            time.sleep(0.5)
        return 0
    finally:
        stop(process)


def command_verify(args: argparse.Namespace) -> int:
    db = pathlib.Path(args.dir) / "gateway.db"
    problems: list[str] = []
    if not db.is_file():
        print(f"没有网关数据库 {db}", file=sys.stderr)
        return 1
    conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        devices = conn.execute("SELECT device_id, revoked_at FROM devices").fetchall()
        records = conn.execute("SELECT origin_device_id FROM records WHERE record_id = ?", (args.record_id,)).fetchall()
        revisions = conn.execute(
            "SELECT revision, text, text_sha256, origin_device_id FROM revisions WHERE record_id = ?", (args.record_id,)
        ).fetchall()
        all_revisions = conn.execute("SELECT COUNT(*) FROM revisions").fetchone()[0]
    finally:
        conn.close()
    if len(devices) != 1:
        problems.append(f"网关上应当恰好登记 1 台设备（公钥不符的那次配对不能消耗配对码），实际 {len(devices)} 台")
    elif devices[0][1] is not None:
        problems.append("这台设备不应当是吊销状态")
    if len(records) != 1:
        problems.append(f"这条 Record 应当恰好 1 条，实际 {len(records)} 条")
    if len(revisions) != 1:
        problems.append(f"这条 Record 应当恰好 1 版（重复提交是幂等的），实际 {len(revisions)} 版")
    else:
        number, text, sha, origin = revisions[0]
        expected_sha = hashlib.sha256(args.text.encode("utf-8")).hexdigest()
        if number != 1:
            problems.append(f"版本号应为 1，实际 {number}")
        if text != args.text:
            problems.append("网关存的正文与设备发的不一致")
        if sha != expected_sha:
            problems.append("网关存的正文哈希与设备发的不一致")
        if devices and origin != devices[0][0]:
            problems.append("这一版的来源设备不是那台已配对的设备")
    if all_revisions != 1:
        problems.append(f"网关里总共应当只有 1 版，实际 {all_revisions} 版")
    if problems:
        for problem in problems:
            print(f"网关数据库核对失败：{problem}", file=sys.stderr)
        return 1
    print("网关数据库核对通过：1 台设备、1 条 Record、1 版，正文与哈希一致，来源设备正确")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("start")
    start.add_argument("--dir", required=True)
    start.add_argument("--handoff", required=True)
    start.add_argument("--advertise-host", default="10.0.2.2")
    verify = sub.add_parser("verify")
    verify.add_argument("--dir", required=True)
    verify.add_argument("--record-id", required=True)
    verify.add_argument("--text", required=True)
    args = parser.parse_args(argv)
    return command_start(args) if args.command == "start" else command_verify(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
