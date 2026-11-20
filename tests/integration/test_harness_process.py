"""The gateway harness must not orphan a process when startup fails."""

from __future__ import annotations

import pathlib
import os
import signal
import subprocess
import sys
import time

import pytest

import harness
from harness import GatewayProcess, REPO_ROOT, World


def test_bad_no_proxy_does_not_orphan_gateway(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("NO_PROXY", "fd7a:115c:a1e0::/48")
    monkeypatch.setenv("no_proxy", "fd7a:115c:a1e0::/48")
    gateway = GatewayProcess(tmp_path / "gateway-data")
    try:
        gateway.start()
        child = gateway._proc
        assert child is not None
        world = World(gateway)
        destination = world.destination()
        assert world.agent(destination).list_tools()
    finally:
        gateway.stop()
    assert child.poll() is not None


def test_unexpected_health_wait_failure_reaps_gateway(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    gateway = GatewayProcess(tmp_path / "gateway-data")
    child: subprocess.Popen[str] | None = None

    class HealthWaitFailure(BaseException):
        pass

    def fail_health_wait(*args: object, **kwargs: object) -> None:
        nonlocal child
        child = gateway._proc
        raise HealthWaitFailure()

    monkeypatch.setattr(harness.httpx, "get", fail_health_wait)
    try:
        with pytest.raises(HealthWaitFailure):
            gateway.start()
        assert child is not None
        assert child.poll() is not None
    finally:
        if child is not None and child.poll() is None:
            child.kill()
            child.wait(timeout=5)


def test_exit_handler_registration_failure_reaps_gateway(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    gateway = GatewayProcess(tmp_path / "gateway-data")
    child: subprocess.Popen[str] | None = None

    def fail_registration(*args: object, **kwargs: object) -> None:
        nonlocal child
        child = gateway._proc
        raise RuntimeError("cannot register cleanup")

    monkeypatch.setattr(harness.atexit, "register", fail_registration)
    try:
        with pytest.raises(RuntimeError, match="cannot register cleanup"):
            gateway.start()
        assert child is not None
        assert child.poll() is not None
    finally:
        if child is not None and child.poll() is None:
            child.kill()
            child.wait(timeout=5)


@pytest.mark.skipif(os.name != "posix", reason="requires POSIX process signals")
def test_gateway_exits_when_parent_is_killed(tmp_path: pathlib.Path) -> None:
    code = "\n".join(
        [
            "import pathlib, sys, time",
            f"sys.path.insert(0, {str(REPO_ROOT / 'tests' / 'integration')!r})",
            "from harness import GatewayProcess",
            f"gateway = GatewayProcess(pathlib.Path({str(tmp_path / 'gateway-data')!r}))",
            "gateway.start()",
            "print(gateway._proc.pid, flush=True)",
            "time.sleep(60)",
        ]
    )
    parent = subprocess.Popen(
        [sys.executable, "-u", "-c", code],
        cwd=REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    child_pid = None
    try:
        assert parent.stdout is not None
        child_pid = int(parent.stdout.readline())
        parent.kill()
        parent.wait(timeout=5)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                os.kill(child_pid, 0)
            except ProcessLookupError:
                break
            time.sleep(0.05)
        else:
            pytest.fail("gateway survived its parent")
    finally:
        if parent.poll() is None:
            parent.kill()
            parent.wait(timeout=5)
        if child_pid is not None:
            try:
                os.kill(child_pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
