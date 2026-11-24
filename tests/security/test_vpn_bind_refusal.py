"""Ticket 22 第四轮 F9：网关不得自动或显式绑定 VPN / 隧道接口与 Tailscale ULA 地址（ADR-0018）。

回归测试通过注入网卡列表运行，不依赖本机真实网卡或代理状态。
"""
from __future__ import annotations

import dataclasses
import pathlib
import subprocess
import sys

import pytest

_INTEGRATION_DIR = pathlib.Path(__file__).resolve().parents[1] / "integration"
_GATEWAY_SRC = pathlib.Path(__file__).resolve().parents[2] / "services" / "gateway"
for _p in (str(_INTEGRATION_DIR), str(_GATEWAY_SRC)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from auri_gateway import network  # noqa: E402
from harness import REPO_ROOT, find_free_port, subprocess_env  # noqa: E402


@dataclasses.dataclass(frozen=True)
class FakeIP:
    ip: str | tuple[str, int, int]


@dataclasses.dataclass(frozen=True)
class FakeAdapter:
    name: str
    nice_name: str
    ips: tuple[FakeIP, ...]


def _sample_adapters() -> list[FakeAdapter]:
    return [
        FakeAdapter("lo0", "lo0", (FakeIP("127.0.0.1"), FakeIP(("::1", 0, 0)))),
        FakeAdapter("en0", "Wi-Fi", (FakeIP("192.168.1.20"), FakeIP(("fd12:3456:789a::1", 0, 0)))),
        FakeAdapter("utun3", "utun3", (FakeIP("100.64.1.2"), FakeIP(("fd7a:115c:a1e0::1", 0, 0)))),
        FakeAdapter("utun12", "utun12", (FakeIP("198.18.0.1"), FakeIP(("fd00:abcd::1", 0, 0)))),
        FakeAdapter("wg0", "WireGuard", (FakeIP("10.8.0.2"), FakeIP(("fd22:3344::2", 0, 0)))),
        FakeAdapter("tun0", "tun0", (FakeIP("172.16.99.5"),)),
        FakeAdapter("tailscale0", "Tailscale", (FakeIP(("fd7a:115c:a1e0::99", 0, 0)),)),
    ]


def test_default_discovery_skips_tunnel_and_vpn_interfaces_even_with_ula_or_rfc1918_ips() -> None:
    adapters = _sample_adapters()

    detected = network.detect_lan_ips(adapters=adapters)
    resolved = network.resolve_bind_hosts(None, adapters=adapters)

    assert detected == ["192.168.1.20", "fd12:3456:789a::1"]
    assert resolved == ["127.0.0.1", "192.168.1.20", "fd12:3456:789a::1"]


def test_tailscale_ula_is_never_classified_as_lan_and_explicit_bind_is_refused() -> None:
    assert not network.is_lan_ip("fd7a:115c:a1e0::1")
    assert not network.is_allowed_bind_ip("fd7a:115c:a1e0::1")

    with pytest.raises(ValueError, match="Refusing bind host"):
        network.resolve_bind_hosts(["fd7a:115c:a1e0::1"], adapters=[])


@pytest.mark.parametrize(
    ("vpn_ip", "expected_iface"),
    [
        pytest.param("fd00:abcd::1", "utun12", id="utun12"),
        pytest.param("10.8.0.2", "wg0", id="wg0"),
        pytest.param("172.16.99.5", "tun0", id="tun0"),
    ],
)
def test_explicit_bind_refuses_ip_assigned_to_tunnel_or_vpn_interface(
    vpn_ip: str, expected_iface: str
) -> None:
    adapters = _sample_adapters()

    with pytest.raises(ValueError, match=f"tunnel/VPN.*{expected_iface}|{expected_iface}.*tunnel/VPN"):
        network.resolve_bind_hosts([vpn_ip], adapters=adapters)


def test_explicit_bind_still_allows_real_lan_and_loopback_ips() -> None:
    adapters = _sample_adapters()

    assert network.resolve_bind_hosts(["192.168.1.20"], adapters=adapters) == ["192.168.1.20"]
    assert network.resolve_bind_hosts(["fd12:3456:789a::1"], adapters=adapters) == ["fd12:3456:789a::1"]
    assert network.resolve_bind_hosts(["127.0.0.1"], adapters=adapters) == ["127.0.0.1"]


def test_cli_serve_refuses_explicit_tailscale_ula_bind(tmp_path: pathlib.Path) -> None:
    res = subprocess.run(
        [
            sys.executable,
            "-m",
            "auri_gateway",
            "serve",
            "--data-dir",
            str(tmp_path / "tailscale-ula"),
            "--host",
            "fd7a:115c:a1e0::1",
            "--port",
            str(find_free_port()),
        ],
        cwd=str(REPO_ROOT),
        env=subprocess_env(),
        capture_output=True,
        text=True,
        timeout=5.0,
        check=False,
    )

    assert res.returncode != 0
    assert "Refusing bind host" in res.stderr
