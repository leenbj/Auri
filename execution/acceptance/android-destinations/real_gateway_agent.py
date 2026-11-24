"""一个 Agent 一侧的单次命令：拿 Destination 凭证，经 MCP 向真实网关领取或确认投递（Ticket 22 的真网关测试用）。

走的路和真实的 Agent 宿主（Hermes、Claude Code）经 packages/mcp-stdio-bridge 走的一样：固定公钥（SPKI）的 TLS、Bearer 凭证、官方 MCP SDK 客户端。
区别只是这里是一次性的命令：JVM 测试（:sync:realGatewayTest 里的 RealGatewayDestinationsTest）起它、读 stdout 的一行 JSON、做断言。
凭证只从环境变量读（命令行参数会被 `ps` 看到），任何输出里都没有它。

环境：
  AURI_GATEWAY_URL=https://127.0.0.1:<端口>/mcp
  AURI_GATEWAY_SPKI_SHA256=<64 位小写十六进制>
  AURI_DESTINATION_CREDENTIAL=<凭证>

命令（输出都是一行 JSON）：
  claim [租约秒数]               delivery_claim → {"is_error": bool, "payload": 工具返回的结构化内容}
  ack <delivery_id> <lease_token> [说明]   delivery_ack → 同上
  probe                          凭证在门口还被不被接受（不领取任何东西）→ {"status": HTTP 状态, "code": 错误码或 null}
                                 401 + GATEWAY_AUTH_REVOKED = 凭证已被轮换或撤销
出错（连不上、指纹不符、协议错）：stderr 打异常类名，退出码 2，stdout 没有内容。
"""
from __future__ import annotations

import json
import logging
import os
import pathlib
import re
import sys

import anyio
import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "packages" / "mcp-stdio-bridge"))
from bridge import PinnedTransport  # noqa: E402  与 Agent 宿主用的是同一个固定公钥的传输层


def _settings() -> tuple[str, str, str]:
    url = os.environ["AURI_GATEWAY_URL"]
    pin = os.environ["AURI_GATEWAY_SPKI_SHA256"]
    credential = os.environ["AURI_DESTINATION_CREDENTIAL"]
    if not url.startswith("https://") or not url.endswith("/mcp"):
        raise ValueError("AURI_GATEWAY_URL 必须是 https 的 /mcp 端点")
    if not re.fullmatch(r"[0-9a-f]{64}", pin):
        raise ValueError("AURI_GATEWAY_SPKI_SHA256 必须是 64 位小写十六进制")
    if not credential or "\r" in credential or "\n" in credential:
        raise ValueError("没有凭证")
    return url, pin, credential


async def _call_tool(name: str, arguments: dict) -> dict:
    url, pin, credential = _settings()
    async with httpx2.AsyncClient(
        transport=PinnedTransport(pin),
        headers={"Authorization": f"Bearer {credential}"},
        timeout=20.0,
        follow_redirects=False,
        trust_env=False,
    ) as http:
        async with Client(streamable_http_client(url, http_client=http), read_timeout_seconds=10) as client:
            result = await client.call_tool(name, arguments)
    payload = result.structured_content
    if payload is None:
        payload = json.loads(result.content[0].text)
    return {"is_error": bool(result.is_error), "payload": payload}


def _probe() -> dict:
    url, pin, credential = _settings()
    http = PinnedTransport(pin).http
    try:
        response = http.post(
            url,
            json={"jsonrpc": "2.0", "id": 1, "method": "initialize"},
            headers={"Authorization": f"Bearer {credential}"},
        )
        code = None
        if response.status_code == 401:
            code = response.json().get("code")
        return {"status": response.status_code, "code": code}
    finally:
        http.close()


def main(argv: list[str]) -> dict:
    command = argv[0] if argv else ""
    if command == "claim":
        arguments = {"lease_seconds": int(argv[1])} if len(argv) > 1 else {}
        return anyio.run(_call_tool, "delivery_claim", arguments)
    if command == "ack":
        arguments = {"delivery_id": argv[1], "lease_token": argv[2]}
        if len(argv) > 3:
            arguments["note"] = argv[3]
        return anyio.run(_call_tool, "delivery_ack", arguments)
    if command == "probe":
        return _probe()
    raise SystemExit("用法：real_gateway_agent.py claim [秒数] | ack <delivery_id> <lease_token> [说明] | probe")


if __name__ == "__main__":
    logging.disable(logging.CRITICAL)
    try:
        print(json.dumps(main(sys.argv[1:]), ensure_ascii=False))
    except SystemExit:
        raise
    except BaseException as failure:  # SDK 把失败包在异常组里；只说类名，不回显消息（消息里可能带地址或头）
        while isinstance(failure, BaseExceptionGroup) and len(failure.exceptions) == 1:
            failure = failure.exceptions[0]
        print(f"agent failed: {type(failure).__name__}", file=sys.stderr)
        sys.exit(2)
