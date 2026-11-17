"""LAN/loopback address helpers and runtime endpoint tracking for Auri Gateway."""
from __future__ import annotations

import ipaddress
import json
import os
import pathlib
import socket
import uuid
from typing import Any

import ifaddr

MDNS_SERVICE_TYPE = "_auri-gateway._tcp"
DEFAULT_TLS_PORT = 8443

_LAN_IPV4_NETWORKS = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("169.254.0.0/16"),
)
_LAN_IPV6_NETWORKS = (
    ipaddress.ip_network("fc00::/7"),
)


def is_loopback_ip(ip_str: str) -> bool:
    try:
        return ipaddress.ip_address(ip_str).is_loopback
    except ValueError:
        return False


def is_lan_ip(ip_str: str) -> bool:
    try:
        ip_obj = ipaddress.ip_address(ip_str)
    except ValueError:
        return False
    if ip_obj.version == 4:
        return any(ip_obj in net for net in _LAN_IPV4_NETWORKS)
    return any(ip_obj in net for net in _LAN_IPV6_NETWORKS)


def is_allowed_bind_ip(ip_str: str) -> bool:
    """Only loopback (127.0.0.0/8, ::1) and private LAN (RFC1918, 169.254/16, fc00::/7) are allowed."""
    try:
        ip_obj = ipaddress.ip_address(ip_str)
    except ValueError:
        return False
    if ip_obj.is_unspecified or ip_obj.is_multicast:
        return False
    return is_loopback_ip(ip_str) or is_lan_ip(ip_str)


def resolve_bind_hosts(requested_hosts: list[str] | None) -> list[str]:
    """Validate explicit --host values or discover default loopback + LAN addresses."""
    if not requested_hosts:
        hosts: list[str] = ["127.0.0.1"]
        for lan_ip in detect_lan_ips():
            if lan_ip not in hosts:
                hosts.append(lan_ip)
        return hosts

    resolved: list[str] = []
    seen: set[str] = set()
    for raw in requested_hosts:
        host = raw.strip()
        if not host:
            raise ValueError("Bind host must not be empty.")
        if host.lower() == "localhost":
            canonical = "127.0.0.1"
        else:
            try:
                ip_obj = ipaddress.ip_address(host)
            except ValueError as exc:
                raise ValueError(
                    f"Refusing non-IP bind host {host!r}: provide an explicit loopback or LAN IP."
                ) from exc
            canonical = str(ip_obj)
            if not is_allowed_bind_ip(canonical):
                raise ValueError(
                    f"Refusing bind host {canonical!r}: Gateway only listens on loopback and private LAN addresses."
                )
        if canonical not in seen:
            seen.add(canonical)
            resolved.append(canonical)
    return resolved


def bind_tcp_sockets(hosts: list[str], port: int) -> tuple[list[socket.socket], int]:
    """Bind non-blocking listening TCP sockets on each allowed host."""
    sockets: list[socket.socket] = []
    actual_port = port
    try:
        for host in hosts:
            ip_obj = ipaddress.ip_address(host)
            family = socket.AF_INET6 if ip_obj.version == 6 else socket.AF_INET
            sock = socket.socket(family, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if family == socket.AF_INET6:
                sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
            sock.bind((host, actual_port))
            if actual_port == 0:
                actual_port = int(sock.getsockname()[1])
            sock.listen(128)
            sock.setblocking(False)
            sockets.append(sock)
    except Exception:
        for s in sockets:
            try:
                s.close()
            except OSError:
                pass
        raise
    return sockets, actual_port


def detect_lan_ips() -> list[str]:
    """Return local private LAN IPv4/ULA IPv6 addresses (excluding loopback, VPN/CGNAT, and public IPs)."""
    found: list[str] = []
    seen: set[str] = set()
    for adapter in ifaddr.get_adapters():
        for ip_info in adapter.ips:
            raw = ip_info.ip[0] if isinstance(ip_info.ip, tuple) else ip_info.ip
            if not is_lan_ip(raw):
                continue
            canonical = str(ipaddress.ip_address(raw))
            if canonical not in seen:
                seen.add(canonical)
                found.append(canonical)
    return found


def runtime_state_path(data_dir: pathlib.Path) -> pathlib.Path:
    return data_dir / "runtime.json"


def write_runtime_state(data_dir: pathlib.Path, *, port: int, hosts: list[str]) -> None:
    path = runtime_state_path(data_dir)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    payload = json.dumps({"pid": os.getpid(), "port": port, "hosts": hosts}).encode("utf-8")
    tmp_path.write_bytes(payload)
    os.replace(tmp_path, path)


def clear_runtime_state(data_dir: pathlib.Path) -> None:
    path = runtime_state_path(data_dir)
    try:
        if not path.exists():
            return
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("pid") == os.getpid():
            path.unlink(missing_ok=True)
    except (OSError, ValueError):
        pass


def read_runtime_state(data_dir: pathlib.Path) -> tuple[int, list[str]] | None:
    path = runtime_state_path(data_dir)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        pid = int(data["pid"])
        os.kill(pid, 0)
        port = int(data["port"])
        hosts = [str(h) for h in data["hosts"] if str(h)]
        return port, hosts
    except (OSError, ValueError, KeyError, TypeError):
        return None


def build_pairing_addresses(
    data_dir: pathlib.Path,
    *,
    port_override: int | None = None,
    host_overrides: list[str] | None = None,
) -> list[dict[str, Any]]:
    """Build 1..8 {host, port} entries for pairing.schema.json#/$defs/payload."""
    runtime = read_runtime_state(data_dir)
    port = port_override if port_override is not None else (runtime[0] if runtime else DEFAULT_TLS_PORT)

    candidates: list[str] = []
    if host_overrides:
        candidates.extend(host_overrides)
    elif runtime:
        runtime_hosts = runtime[1]
        lan_only = [h for h in runtime_hosts if not is_loopback_ip(h)]
        if lan_only:
            hostname = socket.gethostname()
            if hostname:
                local_name = hostname if hostname.endswith(".local") else f"{hostname}.local"
                candidates.append(local_name)
            candidates.extend(lan_only)
        else:
            candidates.extend(runtime_hosts)
    else:
        lan_ips = detect_lan_ips()
        if lan_ips:
            hostname = socket.gethostname()
            if hostname:
                local_name = hostname if hostname.endswith(".local") else f"{hostname}.local"
                candidates.append(local_name)
            candidates.extend(lan_ips)
        else:
            candidates.append("127.0.0.1")

    addresses: list[dict[str, Any]] = []
    seen: set[tuple[str, int]] = set()
    for host in candidates:
        key = (host, port)
        if key not in seen:
            seen.add(key)
            addresses.append({"host": host, "port": port})
        if len(addresses) >= 8:
            break

    if not addresses:
        addresses.append({"host": "127.0.0.1", "port": port})
    return addresses
