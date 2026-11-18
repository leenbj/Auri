#!/usr/bin/env python3
"""Ticket 21 变异检查：对每条“必须守住的规则”故意破坏一处生产代码，确认对应的测试会变红，然后一定还原。

为什么要有它：只写“通过的测试”不能证明测试能拦住错误（ticket 16 的评审教训）。这里把“我破坏了什么、哪些测试必须因此失败”
写成一张表，任何人都能重跑；测试表本身也有离线检查（test_mutation_table.py：每条变异的原文在源码里恰好出现一次，防止表悄悄过期）。

用法（先 source ~/.config/auri/android-env.sh；在仓库根目录执行）：
    .venv/bin/python execution/acceptance/android-sync/mutation_check.py --list
    .venv/bin/python execution/acceptance/android-sync/mutation_check.py [--only ID ...] [--report PATH]
退出码：0 = 每条变异都被预期的测试拦住；1 = 有变异没被拦住（测试有漏洞）；2 = 变异本身无效（找不到原文、编译不过没跑出测试等）。
"""
from __future__ import annotations

import argparse
import dataclasses
import pathlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parents[3]
ANDROID = REPO / "apps" / "android"
GRADLE_BASE = ["./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process"]


@dataclasses.dataclass(frozen=True)
class Edit:
    path: str  # 相对仓库根目录
    old: str
    new: str


@dataclasses.dataclass(frozen=True)
class Mutation:
    id: str
    rule: str  # 被破坏的规则（一句话）
    edits: tuple[Edit, ...]
    gradle: tuple[str, ...]  # 传给 gradlew 的任务与参数
    results_dir: str  # 相对 apps/android，JUnit XML 所在目录
    must_fail: tuple[str, ...]  # 每一项都必须是某个失败用例名的子串


SPKI_TM = "apps/android/sync/src/main/kotlin/app/auri/sync/tls/SpkiPinTrustManager.kt"
HTTPS = "apps/android/sync/src/main/kotlin/app/auri/sync/http/PinnedHttpsClient.kt"
SYNC_TEST = ("sync/build/test-results/test",)
PIN_TESTS = (":sync:test", "--tests", "*PinnedHttpsClientTest*", "--tests", "*SpkiPinTrustManagerTest*")

MUTATIONS: tuple[Mutation, ...] = (
    Mutation(
        "pin-accepts-any-key",
        "指纹不符必须拒绝（把比对改成永远通过 = 信任一切）",
        (Edit(SPKI_TM, "if (leaf == null || !pin.matches(leaf)) {", "if (leaf == null || false) {"),),
        PIN_TESTS, SYNC_TEST[0],
        ("叶证书指纹不符就拒绝", "指纹不符：拒绝连接，而且服务器一个请求都没收到"),
    ),
    Mutation(
        "pin-checks-last-certificate",
        "只认叶证书（改成看链里最后一张）",
        (Edit(SPKI_TM, "chain?.firstOrNull()", "chain?.lastOrNull()"),),
        PIN_TESTS, SYNC_TEST[0],
        ("只认叶证书",),
    ),
    Mutation(
        "pin-accepts-empty-chain",
        "没有证书的对端必须拒绝",
        (Edit(SPKI_TM, "if (leaf == null || !pin.matches(leaf)) {", "if (leaf != null && !pin.matches(leaf)) {"),),
        PIN_TESTS, SYNC_TEST[0],
        ("只认叶证书",),
    ),
    Mutation(
        "mismatch-reported-as-unreachable",
        "指纹不符要如实报成 KeyMismatch（让用户重新配对），不能混成“网关不可达”",
        (Edit(SPKI_TM, "            mismatchObserved = true\n            throw GatewayKeyMismatchException()", "            throw GatewayKeyMismatchException()"),),
        PIN_TESTS, SYNC_TEST[0],
        ("指纹不符：拒绝连接", "叶证书指纹不符就拒绝"),
    ),
    Mutation(
        "network-error-reported-as-mismatch",
        "网络故障（对方不说 TLS、端口没人听）不能误报成公钥不符",
        (Edit(HTTPS, "                if (tls.trustManager.mismatchObserved) throw TransportFailure.KeyMismatch()\n                throw TransportFailure.Unreachable(e)\n            } catch (e: RuntimeException) {",
              "                throw TransportFailure.KeyMismatch()\n            } catch (e: RuntimeException) {"),),
        PIN_TESTS, SYNC_TEST[0],
        ("对方不说 TLS", "端口没人监听"),
    ),
    Mutation(
        "send-failure-reported-as-unreachable",
        "请求发出后的故障必须报成“结果不确定”（Interrupted），否则同步引擎会误以为没送到而直接重发",
        (Edit(HTTPS, "socket.getInputStream().buffered()\n            } catch (e: IOException) {\n                throw TransportFailure.Interrupted(e)",
              "socket.getInputStream().buffered()\n            } catch (e: IOException) {\n                throw TransportFailure.Unreachable(e)"),
         Edit(HTTPS, "                readResponse(input, socket)\n            } catch (e: IOException) {\n                throw TransportFailure.Interrupted(e)",
              "                readResponse(input, socket)\n            } catch (e: IOException) {\n                throw TransportFailure.Unreachable(e)")),
        PIN_TESTS, SYNC_TEST[0],
        ("请求已经发出但连接被断开", "请求已经发出但响应迟迟不来", "响应被截断"),
    ),
    Mutation(
        "tls-context-shared-between-connections",
        "每次连接都是全新的 TLS 上下文：共用上下文会复用会话，复用的会话不会再经过指纹核对",
        (Edit("apps/android/sync/src/main/kotlin/app/auri/sync/tls/SpkiPinTrustManager.kt",
              "    val socketFactory: SSLSocketFactory =\n        SSLContext.getInstance(\"TLS\").apply { init(null, arrayOf(trustManager), SecureRandom()) }.socketFactory\n}",
              "    val socketFactory: SSLSocketFactory = synchronized(Companion) {\n        cached ?: SSLContext.getInstance(\"TLS\").apply { init(null, arrayOf(trustManager), SecureRandom()) }.socketFactory.also { cached = it }\n    }\n\n    private companion object {\n        var cached: SSLSocketFactory? = null\n    }\n}"),),
        # 只跑这一个测试：共用上下文还会连带搞乱别的测试（缓存里是别的用例的指纹），单独跑才能证明是它本身能识别出“会话被复用”
        (":sync:test", "--tests", "*PinnedHttpsClientTest.没有会话复用*"), SYNC_TEST[0],
        ("没有会话复用",),
    ),
)

# 后面各个切片的变异在对应测试写好之后追加到这里（见 README 的“变异检查”一节）。


def apply_edits(mutation: Mutation) -> dict[pathlib.Path, str]:
    """应用变异并返回原文（用来还原）。原文必须恰好出现一次，否则这条变异是无效的。"""
    originals: dict[pathlib.Path, str] = {}
    try:
        for edit in mutation.edits:
            path = REPO / edit.path
            current = path.read_text(encoding="utf-8")
            originals.setdefault(path, current)
            if current.count(edit.old) != 1:
                raise ValueError(f"{mutation.id}: 在 {edit.path} 里找到 {current.count(edit.old)} 处原文（必须恰好 1 处）")
            path.write_text(current.replace(edit.old, edit.new), encoding="utf-8")
    except BaseException:
        restore(originals)
        raise
    return originals


def restore(originals: dict[pathlib.Path, str]) -> None:
    for path, text in originals.items():
        path.write_text(text, encoding="utf-8")
        if path.read_text(encoding="utf-8") != text:  # 还原失败 = 源码被留在了破坏状态，必须大声报错
            raise RuntimeError(f"无法还原 {path}")


def failed_tests(results_dir: pathlib.Path) -> tuple[int, list[str]]:
    total, failed = 0, []
    for xml in sorted(results_dir.glob("TEST-*.xml")):
        for case in ET.parse(xml).getroot().iter("testcase"):
            total += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{case.get('classname', '').rsplit('.', 1)[-1]}.{case.get('name')}")
    return total, failed


def run_mutation(mutation: Mutation) -> tuple[str, str]:
    """返回 (状态, 说明)。状态：CAUGHT / SURVIVED / INVALID。"""
    results = ANDROID / mutation.results_dir
    originals = apply_edits(mutation)
    try:
        subprocess.run(["rm", "-rf", str(results)], check=True)
        proc = subprocess.run([*GRADLE_BASE, *mutation.gradle], cwd=ANDROID, capture_output=True, text=True, timeout=1800)
        total, failed = failed_tests(results) if results.exists() else (0, [])
    finally:
        restore(originals)
    if total == 0:
        return "INVALID", f"没有跑出任何测试（多半是变异后编译不过）：\n{proc.stdout[-1500:]}\n{proc.stderr[-500:]}"
    if proc.returncode == 0 or not failed:
        return "SURVIVED", f"变异后 {total} 个测试全部通过：测试拦不住这个破坏"
    missing = [needle for needle in mutation.must_fail if not any(needle in name for name in failed)]
    if missing:
        return "SURVIVED", f"有测试失败，但预期会失败的这些没有失败：{missing}；实际失败：{failed}"
    return "CAUGHT", f"{len(failed)}/{total} 个测试变红：{failed}"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="只列出变异")
    parser.add_argument("--only", nargs="*", default=[], help="只跑这些 id")
    parser.add_argument("--report", type=pathlib.Path, help="把结果写成 markdown")
    args = parser.parse_args(argv)
    selected = [m for m in MUTATIONS if not args.only or m.id in args.only]
    unknown = set(args.only) - {m.id for m in MUTATIONS}
    if unknown:
        print(f"未知的变异 id：{sorted(unknown)}", file=sys.stderr)
        return 2
    if args.list:
        for m in selected:
            print(f"{m.id}\t{m.rule}")
        return 0
    rows, worst = [], 0
    for m in selected:
        started = time.monotonic()
        status, detail = run_mutation(m)
        worst = max(worst, {"CAUGHT": 0, "SURVIVED": 1, "INVALID": 2}[status])
        rows.append((m, status, detail, time.monotonic() - started))
        print(f"[{status}] {m.id} ({rows[-1][3]:.0f}s) — {m.rule}\n    {detail}", flush=True)
    if args.report:
        lines = ["# 变异检查结果（ticket 21）", "", "| 变异 | 被破坏的规则 | 结果 | 变红的测试 |", "|---|---|---|---|"]
        for m, status, detail, _ in rows:
            lines.append(f"| `{m.id}` | {m.rule} | {status} | {detail.replace('|', '/').replace(chr(10), ' ')[:400]} |")
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
