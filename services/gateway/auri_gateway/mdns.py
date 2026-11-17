"""mDNS (_auri-gateway._tcp) service advertisement for Auri Gateway (ADR-0018)."""
from __future__ import annotations

import ipaddress
import socket
import threading
from dataclasses import dataclass
from typing import Iterable

from zeroconf import InterfaceChoice, ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf

from .network import MDNS_SERVICE_TYPE, is_loopback_ip


@dataclass(frozen=True)
class DiscoveredGateway:
    gateway_id: str | None
    addresses: list[str]
    port: int
    service_name: str


class MdnsAdvertiser:
    """Advertise the Gateway HTTPS endpoint on the local network via mDNS/DNS-SD."""

    def __init__(self, *, gateway_id: str, hosts: Iterable[str], port: int) -> None:
        self.gateway_id = gateway_id
        self.port = port
        host_list = list(hosts)
        lan_hosts = [h for h in host_list if not is_loopback_ip(h)]
        self.advertised_hosts = lan_hosts if lan_hosts else host_list
        self._zeroconf: Zeroconf | None = None
        self._info: ServiceInfo | None = None

    def start(self) -> None:
        packed_addrs = [ipaddress.ip_address(h).packed for h in self.advertised_hosts]
        raw_hostname = socket.gethostname() or "auri-gateway"
        safe_host = raw_hostname.split(".")[0] or "auri-gateway"
        service_type = f"{MDNS_SERVICE_TYPE}.local."
        instance_name = f"auri-gateway-{self.gateway_id[:8]}.{service_type}"
        server_name = f"{safe_host}-{self.gateway_id[:8]}.local."

        self._info = ServiceInfo(
            service_type,
            instance_name,
            addresses=packed_addrs,
            port=self.port,
            properties={
                "gateway_id": self.gateway_id,
                "schema_version": "1.1",
            },
            server=server_name,
        )
        self._zeroconf = Zeroconf(interfaces=InterfaceChoice.All)
        self._zeroconf.register_service(self._info)

    def stop(self) -> None:
        if self._zeroconf is not None:
            try:
                if self._info is not None:
                    self._zeroconf.unregister_service(self._info)
            finally:
                self._zeroconf.close()
                self._zeroconf = None
                self._info = None


def discover_gateways(
    *,
    gateway_id: str | None = None,
    service_type: str = MDNS_SERVICE_TYPE,
    timeout_seconds: float = 3.0,
) -> list[DiscoveredGateway]:
    """Browse the local network via mDNS for advertised Auri Gateways."""
    fq_type = service_type if service_type.endswith(".local.") else f"{service_type}.local."
    results: list[DiscoveredGateway] = []
    lock = threading.Lock()
    done = threading.Event()

    class _Listener(ServiceListener):
        def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
            info = zc.get_service_info(type_, name, timeout=1500)
            if info is None:
                return
            props = info.properties or {}
            gw_raw = props.get(b"gateway_id")
            found_gw_id = gw_raw.decode("utf-8", errors="replace") if gw_raw else None
            addrs = info.parsed_addresses()
            if not addrs:
                return
            entry = DiscoveredGateway(
                gateway_id=found_gw_id,
                addresses=addrs,
                port=int(info.port),
                service_name=name,
            )
            with lock:
                results.append(entry)
            if gateway_id is not None and found_gw_id == gateway_id:
                done.set()

        def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
            pass

        def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
            self.add_service(zc, type_, name)

    zc = Zeroconf(interfaces=InterfaceChoice.All)
    try:
        _browser = ServiceBrowser(zc, fq_type, _Listener())
        done.wait(timeout_seconds)
    finally:
        zc.close()

    with lock:
        if gateway_id is not None:
            return [r for r in results if r.gateway_id == gateway_id]
        return list(results)
