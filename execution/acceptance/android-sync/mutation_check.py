#!/usr/bin/env python3
"""Ticket 21 变异检查：对每条“必须守住的规则”故意破坏一处生产代码，确认对应的测试会变红，然后一定还原。

为什么要有它：只写“通过的测试”不能证明测试能拦住错误（ticket 16 的评审教训）。这里把“我破坏了什么、哪些测试必须因此失败”
写成一张表，任何人都能重跑；测试表本身也有离线检查（test_mutation_table.py：每条变异的原文在源码里恰好出现一次，防止表悄悄过期）。

用法（先 source ~/.config/auri/android-env.sh；在仓库根目录执行）：
    .venv/bin/python execution/acceptance/android-sync/mutation_check.py --list
    .venv/bin/python execution/acceptance/android-sync/mutation_check.py [--only ID ...] [--report PATH]
退出码：0 = 每条变异都被预期的测试拦住；1 = 有变异没被拦住（测试有漏洞）；2 = 变异本身无效（找不到原文、编译不过没跑出测试等）。

安全：默认先把工作树同步一份到临时快照目录（SNAPSHOT_DIR），变异只改快照里的源码，真实工作树一个字节都不会动。
所以进程被杀、被超时终止都不会把“故意写坏的代码”留在源码里（早先直接改工作树的做法在后台任务被收掉时就真的留下过一处变异），
也不用停下手里的开发等变异跑完。--in-place 只给快照里的子进程用。
"""
from __future__ import annotations

import argparse
import dataclasses
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parents[3]
ANDROID = REPO / "apps" / "android"
# 与别的验收脚本一样认 AURI_GRADLE_ARGS（内存紧的机器上加 -Dorg.gradle.workers.max=2）；不设就用下面的默认值
GRADLE_BASE = ["./gradlew", *shlex.split(os.environ.get("AURI_GRADLE_ARGS") or "--no-daemon -Pkotlin.compiler.execution.strategy=in-process")]
SNAPSHOT_DIR = pathlib.Path(tempfile.gettempdir()) / "auri-ticket21-mutation-snapshot"
SCRIPT_RELATIVE = pathlib.Path(__file__).resolve().relative_to(REPO)
# 不同步：版本库内部、虚拟环境、构建产物（留在快照里让 Gradle 增量构建）、别的工具的私有目录
SNAPSHOT_EXCLUDES = (".git", ".venv", ".gitnexus", ".serena", "build", ".gradle", ".kotlin", ".cxx", "__pycache__", ".pytest_cache")


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
ENGINE = "apps/android/sync/src/main/kotlin/app/auri/sync/engine/SyncEngine.kt"
RETRY = "apps/android/sync/src/main/kotlin/app/auri/sync/engine/RetryPolicy.kt"
MACHINE = "apps/android/sync/src/main/kotlin/app/auri/sync/machine/SyncMachine.kt"
FILE_STORE = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/FilePairingStore.kt"
ENGINE_TESTS = (":sync:test", "--tests", "*SyncEngineTest*", "--tests", "*SyncScenarioReplayTest*")
MACHINE_TESTS = (":sync:test", "--tests", "*SyncMachineTableTest*", "--tests", "*SyncScenarioReplayTest*")
PIN_TESTS = (":sync:test", "--tests", "*PinnedHttpsClientTest*", "--tests", "*SpkiPinTrustManagerTest*")
STATUS = "apps/android/sync/src/main/kotlin/app/auri/sync/status/SyncStatus.kt"
LOOP = "apps/android/sync/src/main/kotlin/app/auri/sync/loop/SyncLoop.kt"
PRODUCER = "apps/android/sync/src/main/kotlin/app/auri/sync/loop/RestartingProducer.kt"
STATUS_TESTS = (":sync:test", "--tests", "*SyncStatusTest*")
LOOP_TESTS = (":sync:test", "--tests", "*SyncLoopTest*")
ENGINE_AND_LOOP_TESTS = (":sync:test", "--tests", "*SyncEngineTest*", "--tests", "*SyncScenarioReplayTest*", "--tests", "*SyncLoopTest*")

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

GATEWAY_CLIENT = "apps/android/sync/src/main/kotlin/app/auri/sync/gateway/GatewayClient.kt"
WIRE = "apps/android/sync/src/main/kotlin/app/auri/sync/gateway/Wire.kt"
TOKEN = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/DeviceToken.kt"
HTTP = "apps/android/sync/src/main/kotlin/app/auri/sync/http/Http.kt"
CLIENT_TESTS = (":sync:test", "--tests", "*GatewayClientTest*")

MUTATIONS += (
    Mutation(
        "put-accepts-answer-about-another-revision",
        "提交的响应必须是对这一条的回答（record_id / revision / text_sha256 都要对上），否则不能当成已入库",
        (Edit(GATEWAY_CLIENT, "if (stored.recordId != submission.recordId || stored.revision != submission.revision || stored.textSha256 != submission.textSha256) {", "if (false) {"),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("回答的不是我们提交的那一条",),
    ),
    Mutation(
        "status-accepts-answer-about-another-record",
        "状态查询的回答必须是我们问的那条 Record",
        (Edit(GATEWAY_CLIENT, "if (status.recordId != recordId) throw", "if (false) throw"),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("回答的不是我们问的那条",),
    ),
    Mutation(
        "retryable-inferred-from-status-only",
        "网关在错误体里明说的 retryable 优先于按状态码的推断",
        (Edit(GATEWAY_CLIENT, "retryable = retryable ?: (reply.status >= 500 || reply.status == 429 || reply.status == 408),", "retryable = (reply.status >= 500 || reply.status == 429 || reply.status == 408),"),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("明说的 retryable 优先",),
    ),
    Mutation(
        "response-schema-version-unchecked",
        "响应的 schema_version 必须是 1.1",
        (Edit(WIRE, 'if (string("schema_version") != Wire.SCHEMA_VERSION) throw Unreadable("schema_version")', "Unit"),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("令牌太短、含空白或版本不是 1_1", "读不懂"),
    ),
    Mutation(
        "device-token-format-unchecked",
        "令牌必须是 32..512 个可见 ASCII（不含空白与换行），否则不存",
        (Edit(TOKEN, r'private val FORMAT = Regex("^[\\x21-\\x7e]{32,512}$")', r'private val FORMAT = Regex("^.*$", RegexOption.DOT_MATCHES_ALL)'),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("令牌太短、含空白或版本不是 1_1",),
    ),
    Mutation(
        "device-token-printed-by-toString",
        "令牌的字符串形式不能含内容（日志里不小心带上也不泄露）",
        (Edit(TOKEN, 'override fun toString() = "DeviceToken(redacted)"', 'override fun toString() = "DeviceToken($value)"'),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("合同样本响应解析出网关与设备标识",),
    ),
    Mutation(
        "request-toString-prints-headers",
        "请求的字符串形式不能带请求头（里面有令牌）",
        (Edit(HTTP, 'override fun toString() = "$method $path"', 'override fun toString() = "$method $path $headers"'),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("路径、令牌、内容类型与正文逐字节正确",),
    ),
    Mutation(
        "health-accepts-anything",
        "存活检查必须是合同里的 {status: ok}",
        (Edit(GATEWAY_CLIENT, 'if (it.string("status") != "ok") throw Unreadable("status")', "Unit"),),
        CLIENT_TESTS, "sync/build/test-results/test",
        ("回的不是合同里的存活响应",),
    ),
)

SECRET_BOX = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/SecretBox.kt"
STORE = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/FilePairingStore.kt"
PAIRING = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/PairingService.kt"
DISCOVERY = "apps/android/sync/src/main/kotlin/app/auri/sync/discovery/GatewayDiscovery.kt"
STORE_TESTS = (":sync:test", "--tests", "*AesGcmSecretBoxTest*", "--tests", "*FilePairingStoreTest*")
PAIRING_TESTS = (":sync:test", "--tests", "*GatewayLocatorTest*", "--tests", "*PairingServiceTest*")

MUTATIONS += (
    Mutation(
        "secretbox-reuses-a-fixed-iv",
        "每次封装必须用新的随机 IV（GCM 重用 IV 会泄露密钥流与认证密钥）",
        (Edit(SECRET_BOX, "cipher.init(Cipher.ENCRYPT_MODE, key())", "cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, ByteArray(IV_BYTES)))"),),
        STORE_TESTS, "sync/build/test-results/test",
        ("同一段明文每次封装的结果都不同",),
    ),
    Mutation(
        "secretbox-ignores-purpose",
        "密文绑定用途（附加认证数据）：别的用途的密文不能冒充配对记录",
        (Edit(SECRET_BOX, "cipher.init(Cipher.ENCRYPT_MODE, key())\n        cipher.updateAAD(aad)", "cipher.init(Cipher.ENCRYPT_MODE, key())"),
         Edit(SECRET_BOX, "cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))\n            cipher.updateAAD(aad)", "cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))")),
        STORE_TESTS, "sync/build/test-results/test",
        ("换密钥或换用途都打不开",),
    ),
    Mutation(
        "store-writes-token-in-plaintext",
        "设备令牌不能以明文落盘（封装与打开同时改成直通，其余行为不变，只有“磁盘上找不到令牌”能发现）",
        (Edit(STORE, "val sealed = box.seal(encode(gateway))", "val sealed = encode(gateway)"),
         Edit(STORE, "box.open(file.readBytes())", "file.readBytes()")),
        STORE_TESTS, "sync/build/test-results/test",
        ("磁盘上的文件里找不到令牌",),
    ),
    Mutation(
        "store-treats-unreadable-record-as-not-paired",
        "读不出来的配对记录是“丢失”（要求重新配对），不能静默当成“从没配对过”",
        (Edit(STORE, "?: PairingState.Lost", "?: PairingState.NotPaired"),),
        STORE_TESTS, "sync/build/test-results/test",
        ("文件被改动、截断、清空或是乱码是丢失", "换了密钥"),
    ),
    Mutation(
        "walk-stops-at-first-mismatch",
        "某个地址上是冒充者时要继续找（真网关可能在别的地址）",
        (Edit(DISCOVERY, "Step.Mismatch -> mismatched = true", "Step.Mismatch -> return null"),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("不信它，继续找", "配对码不发给它，继续试下一个"),
    ),
    Mutation(
        "walk-forgets-that-a-mismatch-was-seen",
        "全是不符的公钥时结论必须是 KeyMismatch，不能降级成“不可达”",
        (Edit(DISCOVERY, "return if (mismatched) Walk.Mismatched else Walk.NothingReachable", "return Walk.NothingReachable"),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("所有地址都是不符的公钥", "只有冒充者"),
    ),
    Mutation(
        "walk-retries-addresses-already-tried",
        "mDNS 给出的地址里已经试过的不重复尝试",
        (Edit(DISCOVERY, "if (!tried.add(address)) continue", "tried.add(address)"),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("已经试过的不重复尝试",),
    ),
    Mutation(
        "walk-swallows-cancellation",
        "取消必须向上抛，不能被“发现失败当作没找到”吞掉",
        (Edit(DISCOVERY, "} catch (cancelled: CancellationException) {\n        throw cancelled\n    } catch (_: Exception) {", "} catch (_: Exception) {"),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("取消不能被吞掉",),
    ),
    Mutation(
        "pairing-retries-after-possibly-sent",
        "请求可能已经送到（配对码可能已被消耗）就绝不再发：换地址重发会白白烧掉配对码",
        (Edit(PAIRING, "is TransportFailure.Interrupted -> Step.Done(Attempt.Ended(PairingOutcome.Uncertain))", "is TransportFailure.Interrupted -> Step.Unreachable"),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("请求可能已经送到却没拿到回答",),
    ),
    Mutation(
        "pairing-trusts-any-gateway-id",
        "网关回答的 gateway_id 必须是二维码里的那一个",
        (Edit(PAIRING, "if (response.gatewayId != payload.gatewayId) return PairingOutcome.WrongGateway", ""),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("gateway_id 不是二维码里的",),
    ),
    Mutation(
        "pairing-save-can-be-cancelled",
        "令牌只出现一次：网关签发之后，即使调用方被取消也必须存下来",
        (Edit(PAIRING, "withContext(NonCancellable) { store.save(gateway) }", "store.save(gateway)"),),
        PAIRING_TESTS, "sync/build/test-results/test",
        ("即使调用方被取消，令牌也要存下来",),
    ),
    # ---- 切片 5：同步状态机与引擎 ----
    Mutation(
        "engine-send-started-not-recorded-uncertain",
        "先落盘后发送：SEND_STARTED 必须在请求发出之前就把任务记成“结果不确定”",
        (Edit(ENGINE, "current.copy(attempts = current.attempts + 1, uncertain = true, retryAt = null, blockedReason = null)", "current.copy(attempts = current.attempts + 1, uncertain = current.uncertain, retryAt = null, blockedReason = null)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("进程在发送途中被杀", "发送中被取消", "请求入了库但响应丢了"),
    ),
    Mutation(
        "engine-resends-without-asking",
        "结果不确定时先问网关，不盲目重发",
        (Edit(ENGINE, "if (wasUncertain) {", "if (false) {"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("绝不盲目重发", "不重发，保持不确定"),
    ),
    Mutation(
        "engine-confirms-without-comparing-hash",
        "只有网关上同一版本的正文哈希相同才能确认入库",
        (Edit(ENGINE, "            textSha256 -> Standing.SAME_STORED\n            else -> Standing.CONFLICTING", "            else -> Standing.SAME_STORED"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("网关上同一版本号是另一份正文",),
    ),
    Mutation(
        "engine-resends-on-conflicting-hash",
        "网关上同一版本号是另一份正文：不再提交，直接停下（F10）",
        (Edit(ENGINE, "Standing.CONFLICTING -> return blocked(key, BlockedReason.VERSION_CONFLICT)", "Standing.CONFLICTING -> Unit"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("网关上同一版本号是另一份正文",),
    ),
    Mutation(
        "engine-confirms-missing-revision",
        "网关没有这一版就是没入库，必须重新提交，不能当成已入库（F10 的对照）",
        (Edit(ENGINE, "Standing.MISSING -> Unit // 网关没有这一版：往下重新提交", "Standing.MISSING -> return stored(key)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("网关上有这条 Record 的别的版本、唯独没有这一版", "网关里查不到这条 Record（404）等于没入库"),
    ),
    Mutation(
        "engine-confirms-deleted-record",
        "网关说 Record 已删除：哪怕那一版哈希相同也不能确认入库，也不再提交（F9）",
        (Edit(ENGINE, "        deleted -> Standing.DELETED\n", ""),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("网关说这条 Record 已被删除：哪怕那一版的哈希相同", "网关说这条 Record 已被删除、而且没有这一版"),
    ),
    Mutation(
        "engine-accepts-stale-put-result",
        "PUT 在途中用户换了配对：旧配对得到的结果（哪怕是已入库）必须作废（F6）",
        (Edit(ENGINE, "        if (!stillPaired(gateway)) return stale(key)\n        return when (put) {", "        return when (put) {"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("旧网关回的已入库不能算新网关的送达", "这一轮缓存的旧网关地址不能拿给新网关的指纹用"),
    ),
    Mutation(
        "engine-accepts-stale-status-answer",
        "问结果的途中用户换了配对：旧网关的回答必须作废（F6）",
        (Edit(ENGINE, "            if (!stillPaired(gateway)) return stale(key)\n            when (answer) {", "            when (answer) {"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("问结果的途中改配了另一个网关",),
    ),
    Mutation(
        "engine-blocks-on-stale-connection-verdict",
        "旧配对的公钥不符/令牌被拒结论到得太晚：配对记录已经不是同一份，就作废而不是停下任务（F6）",
        (Edit(ENGINE, "if (!pairing.update { current -> if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null }) return stale(key)", "pairing.update { current -> if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null }"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("旧公钥的探测这时才报公钥不符",),
    ),
    Mutation(
        "engine-reuses-location-across-pairings",
        "一轮里缓存的网关地址只对同一份配对有效（F6）",
        (Edit(ENGINE, "if (cached != null && forPairing?.isSamePairing(gateway) == true) return cached", "if (cached != null) return cached"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("这一轮缓存的旧网关地址不能拿给新网关的指纹用",),
    ),
    Mutation(
        "engine-offline-failure-schedules-retry",
        "已经报告没网时，请求被中断要按等待网关处理，不排重试倒计时（F12）",
        (Edit(ENGINE, "        if (offline) {\n            deliver(key, SyncEvent.GATEWAY_UNREACHABLE)", "        if (false) {\n            deliver(key, SyncEvent.GATEWAY_UNREACHABLE)"),),
        ENGINE_AND_LOOP_TESTS, SYNC_TEST[0],
        ("发送途中网络没了、请求随之被中断", "发送途中断了网、请求随之被中断"),
    ),
    Mutation(
        "engine-never-clears-offline",
        "网络回来之后要清除“没网”：之后的失败按正常重试倒计时算（F12）",
        (Edit(ENGINE, "        offline = false\n        for (job in jobs.all()) {\n            if (job.state == SyncState.RETRY_SCHEDULED)", "        for (job in jobs.all()) {\n            if (job.state == SyncState.RETRY_SCHEDULED)"),),
        ENGINE_AND_LOOP_TESTS, SYNC_TEST[0],
        ("网络回来之后：失败又按正常的重试倒计时算",),
    ),
    Mutation(
        "engine-network-change-keeps-old-retry",
        "网络变了：等待重试的任务不再干等旧的重试时间（F11）",
        (Edit(ENGINE, "if (job.state == SyncState.RETRY_SCHEDULED) deliver(job.key, SyncEvent.RETRY_DUE) { it.copy(attempts = 0) }", "if (job.state == SyncState.RETRY_SCHEDULED) Unit"),),
        ENGINE_AND_LOOP_TESTS, SYNC_TEST[0],
        ("默认网络换了一个：等待重试的任务不再干等旧的重试时间", "网络变了之后再失败：退避从头算"),
    ),
    Mutation(
        "engine-network-change-keeps-old-attempts",
        "网络变了：持久化的重试次数清零，退避从头算（F11）",
        (Edit(ENGINE, "deliver(job.key, SyncEvent.RETRY_DUE) { it.copy(attempts = 0) }", "deliver(job.key, SyncEvent.RETRY_DUE) { it }"),),
        ENGINE_AND_LOOP_TESTS, SYNC_TEST[0],
        ("网络变了之后再失败：退避从头算",),
    ),
    Mutation(
        "loop-ignores-network-change-for-scheduled-jobs",
        "循环在网络回来/换了一个时必须通知引擎重新武装等待重试的任务（F11/F12）",
        (Edit(LOOP, "                    containNetworkFailure { engine.networkChanged() }\n", ""),),
        ENGINE_AND_LOOP_TESTS, SYNC_TEST[0],
        ("默认网络换了一个：等待重试的任务不再干等旧的重试时间", "发送途中断了网、请求随之被中断"),
    ),
    Mutation(
        "loop-observer-ends-after-first-failure",
        "触发来源暂时出故障后必须重新订阅，不能就此失聪（F4）",
        (Edit(PRODUCER, "    while (true) {\n        val startedAt = clock.now()", "    repeat(1) {\n        val startedAt = clock.now()"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("任务表的变化信号暂时出了故障", "网络信号暂时出了故障"),
    ),
    Mutation(
        "loop-observer-restarts-densely",
        "重新订阅要按有界退避，不能每秒一次地空转（F4）",
        (Edit(PRODUCER, "        delay(retry.delayMs(failures))\n", "        delay(1_000)\n"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("任务表的变化信号一直出故障",),
    ),
    Mutation(
        "loop-observer-backoff-never-resets",
        "上一次订阅活得够久再出故障，重新订阅的退避从头算（F4）",
        (Edit(PRODUCER, "STABLE_MS) failures = 0", "STABLE_MS) failures += 0"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("上一次订阅活了一阵",),
    ),
    Mutation(
        "engine-confirms-foreign-origin",
        "网关说 Record 是别的设备录的：不能确认入库，也不再提交（F9，ADR-0020）",
        (Edit(ENGINE, "        originDeviceId != deviceId -> Standing.FOREIGN\n", ""),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("网关说这条 Record 是别的设备录的",),
    ),
    Mutation(
        "engine-resends-when-status-rejected",
        "问结果时网关回了错误（非“查无此 Record”）：不重发，稍后再问",
        (Edit(ENGINE, "if (!(answer.status == 404 && answer.code == ErrorCode.REQUEST_INVALID)) return failed(key, gateway, answer, wasUncertain)", "if (false) return failed(key, gateway, answer, wasUncertain)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("问网关结果时网关自己出错或没回答",),
    ),
    Mutation(
        "engine-resends-when-status-unanswered",
        "问结果时没有得到回答（断线、读不懂）：不重发，稍后再问",
        (Edit(ENGINE, "else -> return failed(key, gateway, answer, wasUncertain)", "else -> Unit"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("问网关结果时网关自己出错或没回答",),
    ),
    Mutation(
        "engine-retries-version-conflict",
        "永久拒绝（版本冲突）不重试：停在需要用户处理",
        (Edit(ENGINE, "result.code == ErrorCode.RECORD_VERSION_CONFLICT -> blocked(key, BlockedReason.VERSION_CONFLICT)", "result.code == ErrorCode.RECORD_VERSION_CONFLICT -> retryLater(key)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("永久性拒绝不会被重试", "用户处理单条任务"),
    ),
    Mutation(
        "engine-retries-unknown-rejection",
        "没有认出来的 4xx 拒绝也不能无限重试",
        (Edit(ENGINE, "else -> blocked(key, BlockedReason.REQUEST_REJECTED)", "else -> retryLater(key)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("永久性拒绝不会被重试",),
    ),
    Mutation(
        "machine-lets-blocked-retry",
        "BLOCKED 只有用户处理才能离开（转移表里不能有别的出口）",
        (Edit(MACHINE, "go(BLOCKED, USER_RESOLVED, PENDING),", "go(BLOCKED, USER_RESOLVED, PENDING),\n        go(BLOCKED, RETRY_DUE, PENDING),"),),
        MACHINE_TESTS, SYNC_TEST[0],
        ("每一条转移规则都和合同逐条一致", "BLOCKED 只有用户处理才能离开", "合同里的每个场景都被回放通过"),
    ),
    Mutation(
        "machine-wrong-target",
        "转移表的目标状态必须与合同一致",
        (Edit(MACHINE, "go(SENDING, STORED_CONFIRMED, STORED),", "go(SENDING, STORED_CONFIRMED, PENDING),"),),
        MACHINE_TESTS, SYNC_TEST[0],
        ("每一条转移规则都和合同逐条一致", "合同里的每个场景都被回放通过"),
    ),
    Mutation(
        "engine-ignores-connection-gate",
        "连接要用户处理（公钥不符、令牌被拒）期间一个带令牌的请求都不能发",
        (Edit(ENGINE, "?.gateway?.takeIf { it.needsUser == null }", "?.gateway"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("令牌被撤销：整个连接停下", "没有任何继续的路"),
    ),
    Mutation(
        "engine-does-not-persist-connection-gate",
        "公钥不符、令牌被拒要记进配对记录",
        (Edit(ENGINE, "        if (!pairing.update { current -> if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null }) return stale(key)\n", ""),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("令牌被撤销：整个连接停下", "没有任何继续的路"),
    ),
    Mutation(
        "engine-new-pairing-overwritten-by-old-observation",
        "晚到的旧观察不能覆盖用户刚重新配对写入的新记录（打“要用户处理”标记时）",
        (Edit(ENGINE, "if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null", "current.copy(needsUser = needs)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("发送途中用户重新配对了",),
    ),
    Mutation(
        "engine-address-update-overwrites-new-pairing",
        "晚到的旧观察不能覆盖新的配对记录（记最近连上的地址时）",
        (Edit(ENGINE, "if (current.isSamePairing(gateway) && current.lastGoodAddress != address) current.copy(lastGoodAddress = address) else null", "if (current.lastGoodAddress != address) current.copy(lastGoodAddress = address) else null"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("找网关的途中用户重新配对了",),
    ),
    Mutation(
        "engine-address-not-remembered",
        "连上的地址要记住，下次先连它",
        (Edit(ENGINE, "            pairing.update { current -> if (current.isSamePairing(gateway) && current.lastGoodAddress != address) current.copy(lastGoodAddress = address) else null }\n", ""),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("成功的地址被记住",),
    ),
    Mutation(
        "engine-recovers-live-sends",
        "正在发送的任务不能被当成“进程被杀留下的中断”误回收",
        (Edit(ENGINE, "if (job.state == SyncState.SENDING && job.key !in inFlight) {", "if (job.state == SyncState.SENDING) {"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("正在发送的任务不会被当成",),
    ),
    Mutation(
        "engine-send-skips-machine-for-rejection",
        "状态机拒绝的开始发送也要留下记录（即使连接要用户处理）",
        (Edit(ENGINE, "        if (SyncMachine.resolve(job.state, SyncEvent.SEND_STARTED)?.outcome != Outcome.TRANSITION) {\n            deliver(key, SyncEvent.SEND_STARTED)\n            return SendResult.SKIPPED\n        }\n", ""),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("合同里的每个场景都被回放通过",),
    ),
    Mutation(
        "engine-backoff-does-not-grow",
        "重试的退避时间随失败次数增长",
        (Edit(ENGINE, "plusMillis(retry.delayMs(it.attempts))) }", "plusMillis(retry.delayMs(1))) }"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("退避时间逐次翻倍",),
    ),
    Mutation(
        "retry-policy-does-not-double",
        "退避时间翻倍增长",
        (Edit(RETRY, "delay *= 2", "delay += baseMs"),),
        (":sync:test", "--tests", "*RetryPolicyTest*"), SYNC_TEST[0],
        ("退避时间逐次翻倍并且有上限",),
    ),
    Mutation(
        "retry-policy-overflows",
        "次数再大退避时间也不溢出、不超过上限",
        (Edit(RETRY, "if (delay >= maxMs) return maxMs", "if (false) return maxMs"),),
        (":sync:test", "--tests", "*RetryPolicyTest*"), SYNC_TEST[0],
        ("次数再大也不会溢出",),
    ),
    Mutation(
        "engine-retries-too-early",
        "重试计时器响了但还没到点：不提前重试",
        (Edit(ENGINE, "        if (job.state == SyncState.RETRY_SCHEDULED && at != null && at.isAfter(clock.now())) return\n", ""),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("旧计时器响了", "退避时间逐次翻倍"),
    ),
    Mutation(
        "engine-contained-failure-leaves-sending",
        "适配器出故障时任务不能卡在“发送中”",
        (Edit(ENGINE, "            return contain(key)\n", "            return SendResult.SKIPPED\n"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("读本地 Record 库出错",),
    ),
    Mutation(
        "engine-treats-cancellation-as-failure",
        "取消不是故障：不能被收住、不能记成“收住的故障”",
        (Edit(ENGINE, "        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {\n            log.event(\"send.contained", "        } catch (failure: Exception) {\n            log.event(\"send.contained"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("发送中被取消",),
    ),
    Mutation(
        "engine-waiting-marks-uncertain",
        "没连上网关（请求没有发出）不能把任务记成“结果不确定”",
        (Edit(ENGINE, "deliver(key, SyncEvent.GATEWAY_UNREACHABLE) { it.copy(uncertain = wasUncertain) }", "deliver(key, SyncEvent.GATEWAY_UNREACHABLE) { it }"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("没有发生过不确定的失败时",),
    ),
    Mutation(
        "engine-mismatch-marks-uncertain",
        "公钥不符（请求没有发出）不能把任务记成“结果不确定”",
        (Edit(ENGINE, "deliver(key, event) { it.copy(uncertain = wasUncertain, blockedReason = reason) }", "deliver(key, event) { it.copy(blockedReason = reason) }"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("PUT 发出时才发现公钥不符",),
    ),
    Mutation(
        "engine-ignores-mismatch-while-probing",
        "等待网关时遇到换了公钥的网关：必须走到“要用户重新配对”，不能永远等待",
        (Edit(ENGINE, "is Located.Reachable, Located.KeyMismatch -> waiting.forEach { deliver(it.key, SyncEvent.GATEWAY_REACHABLE) }\n            Located.Unreachable -> Unit", "is Located.Reachable -> waiting.forEach { deliver(it.key, SyncEvent.GATEWAY_REACHABLE) }\n            Located.Unreachable, Located.KeyMismatch -> Unit"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("等待网关的任务遇到换了公钥的网关",),
    ),
    Mutation(
        "engine-does-not-park-pending-jobs",
        "找不到网关就停下，排队中的任务全部改为等待网关",
        (Edit(ENGINE, "parkPending()\n                        return", "return"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("连不上网关：任务进入等待网关",),
    ),
    Mutation(
        "engine-local-data-missing-retried",
        "本机没有可发的内容时停下等用户，不无限重试",
        (Edit(ENGINE, "?: return blocked(key, BlockedReason.LOCAL_DATA_MISSING)", "?: return retryLater(key)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("本机已经没有这条 Revision",),
    ),
    Mutation(
        "engine-repair-unblocks-everything",
        "重新配对只解除因连接问题停下的任务，版本冲突等要用户另行处理",
        (Edit(ENGINE, "if (job.state == SyncState.BLOCKED && job.blockedReason in CONNECTION_REASONS) userResolved(job.key)", "if (job.state == SyncState.BLOCKED) userResolved(job.key)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("没重新配对时，resolveAfterRepair 什么也不做",),
    ),
    Mutation(
        "engine-resolves-without-repair",
        "连接仍然要用户处理时，resolveAfterRepair 什么也不做",
        (Edit(ENGINE, "        if (usableGateway() == null) return\n        for (job in jobs.all()) {\n            if (job.state == SyncState.BLOCKED && job.blockedReason in CONNECTION_REASONS)", "        for (job in jobs.all()) {\n            if (job.state == SyncState.BLOCKED && job.blockedReason in CONNECTION_REASONS)"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("没重新配对时，resolveAfterRepair 什么也不做",),
    ),
    Mutation(
        "file-store-update-without-lock",
        "配对记录的读改写与 save 互斥：重新配对写入的新记录不能被夹在中间的旧观察覆盖",
        (Edit(FILE_STORE, "override suspend fun update(transform: (PairedGateway) -> PairedGateway?): Boolean = mutex.withLock {", "override suspend fun update(transform: (PairedGateway) -> PairedGateway?): Boolean = run {"),),
        (":sync:test", "--tests", "*FilePairingStoreTest*"), SYNC_TEST[0],
        ("update 持锁期间 save 必须等着",),
    ),
    # ---- 同步状态汇总（SyncStatus）与同步循环（SyncLoop） ----
    Mutation(
        "status-merges-waiting-into-retrying",
        "三种情形各有各的标题：等网关不能和稍后重试混成一种提示",
        (Edit(STATUS, "counts.waitingForGateway > 0 -> SyncHeadline.WAITING_FOR_GATEWAY", "counts.waitingForGateway > 0 -> SyncHeadline.RETRYING_LATER"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("三种情形互不相同", "真引擎：连不上网关"),
    ),
    Mutation(
        "status-hides-blocked-behind-retrying",
        "需要用户处理优先于稍后重试：真正的问题不能被“稍后会好”盖住",
        (Edit(STATUS, "causes.isNotEmpty() -> SyncHeadline.NEEDS_USER", "causes.isNotEmpty() && counts.retryingLater == 0 -> SyncHeadline.NEEDS_USER"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("同时有几种任务时按",),
    ),
    Mutation(
        "status-ignores-connection-level-mismatch",
        "公钥不符是连接层面的问题：即使没有任务，也要告诉用户重新配对",
        (Edit(STATUS, "NeedsUser.KEY_MISMATCH -> causes += UserCause.KEY_MISMATCH", "NeedsUser.KEY_MISMATCH -> Unit"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("连接层面的问题",),
    ),
    Mutation(
        "status-treats-lost-pairing-as-calm",
        "配对记录读不出来要提示重新配对，不能当成平静的状态",
        (Edit(STATUS, "PairingState.Lost -> causes += UserCause.PAIRING_LOST", "PairingState.Lost -> Unit"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("配对记录读不出来",),
    ),
    Mutation(
        "status-next-retry-is-the-latest",
        "下一次重试时间取最早的那一个",
        (Edit(STATUS, ".mapNotNull { it.retryAt }.minOrNull()", ".mapNotNull { it.retryAt }.maxOrNull()"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("下一次重试的时间是等待重试的任务里最早的那个",),
    ),
    Mutation(
        "status-drops-blocked-without-reason",
        "没记下原因的停止也是“需要用户处理”，不能漏掉",
        (Edit(STATUS, "jobs.filter { it.state == SyncState.BLOCKED }.forEach { causes += (it.blockedReason ?: BlockedReason.REQUEST_REJECTED).toCause() }",
              "jobs.filter { it.state == SyncState.BLOCKED && it.blockedReason != null }.forEach { causes += it.blockedReason!!.toCause() }"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("没有记下原因的停止按",),
    ),
    Mutation(
        "status-hides-not-paired",
        "没配对时只说没配对（文字留在本机），不能显示成“正在发送”",
        (Edit(STATUS, "pairing == PairingState.NotPaired -> SyncHeadline.NOT_PAIRED", "false -> SyncHeadline.NOT_PAIRED"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("没配对：只说没配对",),
    ),
    Mutation(
        "status-miscounts-stored",
        "各类任务的条数要如实统计",
        (Edit(STATUS, "stored = count(SyncState.STORED),", "stored = count(SyncState.PENDING),"),),
        STATUS_TESTS, SYNC_TEST[0],
        ("各类任务的条数分别统计",),
    ),
    Mutation(
        "loop-no-startup-pass",
        "启动时必须先跑一轮：上次被杀时卡在发送中的任务不是排队中的，没有别的触发能发现它",
        (Edit(LOOP, "        wake.trySend(Unit)\n        producer(\"network\") { watchNetwork() }", "        producer(\"network\") { watchNetwork() }"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("启动时接回上次被杀时卡在发送中的任务",),
    ),
    Mutation(
        "loop-network-regained-does-not-wake",
        "网络恢复要立刻跑一轮，不能干等下一次探测",
        (Edit(LOOP, "containNetworkFailure { engine.networkChanged() }\n                    wake.trySend(Unit)", "containNetworkFailure { engine.networkChanged() }\n                    Unit"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("网络恢复就跑一轮",),
    ),
    Mutation(
        "loop-network-change-deduplicated",
        "默认网络换了一个而信号一直是“可用”（手机常开移动数据，Wi-Fi 回来只是换回去）也要立刻跑一轮：去重会把它吞掉，补传要干等最长一刻钟的探测退避",
        (
            Edit(LOOP, "network.available.collect { available ->", "network.available.distinctUntilChanged().collect { available ->"),
            Edit(LOOP, "import kotlinx.coroutines.flow.combine\n", "import kotlinx.coroutines.flow.combine\nimport kotlinx.coroutines.flow.distinctUntilChanged\n"),
        ),
        LOOP_TESTS, SYNC_TEST[0],
        ("默认网络换了一个",),
    ),
    Mutation(
        "loop-network-event-keeps-backoff",
        "网络恢复或换了网络时探测的退避要从头开始：刚连上的那一刻网关未必已经应答，下一次探测不能还按之前涨到的间隔",
        (Edit(LOOP, "            online = available\n            probeAttempt = 0\n", "            online = available\n"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("网络恢复或默认网络换了一个",),
    ),
    Mutation(
        "loop-network-event-does-not-rearm",
        "网络事件要让已经睡着的旧探测计时器重新计时，否则它还按旧的间隔响",
        (Edit(LOOP, "            probeAttempt = 0\n            rearm.value += 1\n            when {", "            probeAttempt = 0\n            when {"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("网络恢复或默认网络换了一个",),
    ),
    Mutation(
        "loop-offline-keeps-probing",
        "断网期间不探测、不重试",
        (
            Edit(LOOP, "if (online && list.any { it.state == SyncState.WAITING_GATEWAY }) {", "if (list.any { it.state == SyncState.WAITING_GATEWAY }) {"),
            Edit(LOOP, "if (online) wake.trySend(Unit)", "wake.trySend(Unit)"),
        ),
        LOOP_TESTS, SYNC_TEST[0],
        ("断网期间不再碰网关",),
    ),
    Mutation(
        "loop-probe-ignores-backoff",
        "探测网关的间隔按退避逐次拉长，不能一直 5 秒一次",
        (Edit(LOOP, "delay(retry.delayMs(probeAttempt + 1))", "delay(retry.delayMs(1))"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("按退避去探",),
    ),
    Mutation(
        "loop-probe-attempt-never-grows",
        "探测次数要累计，退避才会增长",
        (Edit(LOOP, "probeAttempt += 1", "probeAttempt += 0"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("按退避去探",),
    ),
    Mutation(
        "loop-nudge-keeps-backoff",
        "催一下（刚配对、回到前台）要让退避从头开始",
        (Edit(LOOP, "fun nudge() {\n        probeAttempt = 0\n", "fun nudge() {\n"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("nudge 立刻跑一轮",),
    ),
    Mutation(
        "loop-nudge-does-not-rearm",
        "催一下要重新计时：已经睡着的旧计时器不能还按旧的退避去响",
        (Edit(LOOP, "        probeAttempt = 0\n        rearm.value += 1\n        wake.trySend(Unit)", "        probeAttempt = 0\n        wake.trySend(Unit)"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("nudge 立刻跑一轮",),
    ),
    Mutation(
        "loop-pass-inside-collectlatest",
        "一轮同步绝不能跑在会被任务表变化取消的协程里（取消发到一半的请求会留下“结果不确定”的任务）",
        (Edit(LOOP, ".collectLatest { list -> sleepThenWake(list) }", ".collectLatest { list -> sleepThenWake(list); engine.runOnce() }"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("别的任务正占着这一轮时重试计时器响了",),
    ),
    Mutation(
        "loop-wake-cancels-running-pass",
        "新的触发不能取消正在跑的一轮（取消发到一半的请求会留下“结果不确定”的任务）：一轮在跑时来的触发只合并成之后的一轮",
        (
            Edit(LOOP, "        for (signal in wake) {\n            log.event(\"loop.pass\")\n            engine.runOnce()\n        }\n", "        wake.receiveAsFlow().collectLatest {\n            log.event(\"loop.pass\")\n            engine.runOnce()\n        }\n"),
            Edit(LOOP, "import kotlinx.coroutines.flow.onEach\n", "import kotlinx.coroutines.flow.onEach\nimport kotlinx.coroutines.flow.receiveAsFlow\n"),
        ),
        LOOP_TESTS, SYNC_TEST[0],
        ("正在发送的请求不会被任务表的变化打断",),
    ),
    Mutation(
        "loop-retry-timer-fires-early",
        "重试要等到引擎排好的时间，不能提前",
        (Edit(LOOP, "delay(Duration.between(clock.now(), due).toMillis().coerceAtLeast(0))", "delay(0)"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("到了重试时间才重发", "一直失败时重试间隔逐次翻倍"),
    ),
    Mutation(
        "loop-new-job-does-not-wake",
        "有新任务（刚发布的 Revision）要马上发，不能等别的触发",
        (Edit(LOOP, "keys.any { it !in before }", "false"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("运行中有新任务",),
    ),
    Mutation(
        "loop-blocked-jobs-get-timers",
        "被永久拒绝的任务不产生任何计时与唤醒：没有“再试几次”的路",
        (Edit(LOOP, "if (online && list.any { it.state == SyncState.WAITING_GATEWAY }) {", "if (online && list.any { it.state == SyncState.WAITING_GATEWAY || it.state == SyncState.BLOCKED }) {"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("被永久拒绝的任务无论过多久",),
    ),
    Mutation(
        "loop-source-failure-kills-loop",
        "一个触发来源出故障不能拖垮循环的其余部分",
        (Edit(PRODUCER, "log.event(\"loop.$name.failed ${failure::class.simpleName}\")", "throw failure"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("网络信号自己出了故障", "任务表的变化信号出了故障"),
    ),
    Mutation(
        "loop-wakes-not-conflated",
        "一轮在跑时来的触发要合并成之后的一轮，不能排成长队",
        (Edit(LOOP, "Channel<Unit>(Channel.CONFLATED)", "Channel<Unit>(Channel.UNLIMITED)"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("一堆催促合并成一次",),
    ),
)

SUBMISSION = "apps/android/sync/src/main/kotlin/app/auri/sync/gateway/SubmissionBuilder.kt"
SOURCE = "apps/android/sync/src/main/kotlin/app/auri/sync/engine/RecordLibrarySubmissionSource.kt"
SUBMISSION_TESTS = (":sync:test", "--tests", "*SubmissionBuilderTest*")
FIXTURE_REFUSAL = "合同判为无效而手机端表达得出来的样本"

# 切片 6：提交体构建器（本地 Record/Revision → 合同允许的请求体）
MUTATIONS += (
    Mutation(
        "submission-accepts-non-bluetooth",
        "输入路由不是蓝牙的录音绝不送出去",
        (Edit(SUBMISSION, "            entry.inputRouteType.isBluetooth &&\n", "            true &&\n"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("输入路由不是蓝牙",),
    ),
    Mutation(
        "submission-ignores-status-end-reason",
        "complete/partial 必须与结束原因一致（被截断的不能标成完成）",
        (Edit(SUBMISSION, "entry.status == (if (entry.endReason.truncated) CaptureStatus.PARTIAL else CaptureStatus.COMPLETE)", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("完成状态必须与结束原因一致", FIXTURE_REFUSAL),
    ),
    Mutation(
        "submission-allows-no-speech-segments",
        "有 Revision 就至少有一个语音片段",
        (Edit(SUBMISSION, "        return segments.isNotEmpty() &&\n", "        return true &&\n"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        (FIXTURE_REFUSAL,),
    ),
    Mutation(
        "submission-skips-sr02-order",
        "SR-02：语音片段不得重叠、必须按时间排好",
        (Edit(SUBMISSION, "segments.zipWithNext().all { (previous, next) -> previous.endMs <= next.startMs }", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        (FIXTURE_REFUSAL,),
    ),
    Mutation(
        "submission-skips-sr03-bounds",
        "SR-03：语音片段不得超出录音时长",
        (Edit(SUBMISSION, "segments.all { it.endMs <= entry.captureDurationMs }", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        (FIXTURE_REFUSAL,),
    ),
    Mutation(
        "submission-skips-sr04-duration-sum",
        "SR-04：语音总时长等于各片段时长之和",
        (Edit(SUBMISSION, "segments.sumOf { it.endMs - it.startMs } == entry.speechDurationMs", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        (FIXTURE_REFUSAL,),
    ),
    Mutation(
        "submission-counts-text-by-chars",
        "SR-06：正文上限按 UTF-8 字节计，不是按字符数",
        (Edit(SUBMISSION, "revision.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES", "revision.text.length <= MAX_TEXT_BYTES"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("正文超过 1 MiB",),
    ),
    Mutation(
        "submission-asr-without-model",
        "转录来源的 Revision 必须写明引擎与模型",
        (Edit(SUBMISSION, "(revision.source != RevisionSource.ASR || (revision.engine.isNotNullOrEmpty() && revision.modelId.isNotNullOrEmpty() && revision.modelVersion.isNotNullOrEmpty()))", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("来源是转录就必须写明引擎与模型", FIXTURE_REFUSAL),
    ),
    Mutation(
        "submission-trims-text",
        "正文原样发送：改动一个字符（哪怕是空白）哈希就对不上",
        (Edit(SUBMISSION, '                put("text", revision.text)\n', '                put("text", revision.text.trim())\n'),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("正文原样进请求体",),
    ),
    Mutation(
        "submission-guesses-firmware",
        "系统不暴露的耳机固件版本必须是 null，不得猜",
        (Edit(SUBMISSION, 'put("headset_firmware", JsonNull)', 'put("headset_firmware", "unknown")'),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("耳机名按字符数",),
    ),
    Mutation(
        "submission-name-length-in-utf16",
        "耳机名上限按字符（码点）计，不是 UTF-16 单元",
        (Edit(SUBMISSION, "it.characterCount() <= MAX_HEADSET_NAME", "it.length <= MAX_HEADSET_NAME"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("耳机名按字符数",),
    ),
    Mutation(
        "submission-confidence-unbounded",
        "置信度必须在 0 到 1 之间",
        (Edit(SUBMISSION, "(confidence.isFinite() && confidence in 0.0..1.0)", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("语言、置信度和各字段的长度上限",),
    ),
    Mutation(
        "submission-language-unchecked",
        "语言标签必须符合合同的格式",
        (Edit(SUBMISSION, "            LANGUAGE.matches(revision.language) &&\n", "            true &&\n"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("语言、置信度和各字段的长度上限",),
    ),
    Mutation(
        "submission-uuid-unchecked",
        "record_id 必须是小写 UUID",
        (Edit(SUBMISSION, "            Wire.UUID.matches(entry.recordId) &&\n", "            true &&\n"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        (FIXTURE_REFUSAL,),
    ),
    Mutation(
        "source-ignores-revision-number",
        "任务的版本号必须对上：要发第 2 版不能发成别的版本",
        (Edit(SOURCE, "it.number == key.revision", "true"),),
        SUBMISSION_TESTS, SYNC_TEST[0],
        ("从本地 Record 库读",),
    ),
)

OBSERVABLE = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/ObservablePairingStore.kt"
QR = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/QrDecoder.kt"
OBSERVABLE_TESTS = (":sync:test", "--tests", "*ObservablePairingStoreTest*")
QR_TESTS = (":sync:test", "--tests", "*QrDecoderTest*")
OBSERVABLE_SAVE = "delegate.save(gateway)\n            current.value = delegate.load()"

# 切片 7：配对状态的可观察副本、二维码解码
MUTATIONS += (
    Mutation(
        "observable-guesses-not-paired-initially",
        "没读过之前不发任何状态：真实状态可能是已配对，不猜“未配对”给界面",
        (Edit(OBSERVABLE, "MutableStateFlow<PairingState?>(null)", "MutableStateFlow<PairingState?>(PairingState.NotPaired)"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("还没读过之前没有任何状态",),
    ),
    Mutation(
        "observable-refresh-not-published",
        "启动时的 refresh 读到什么就要发出什么",
        (Edit(OBSERVABLE, "mutex.withLock { current.value = delegate.load() }", "mutex.withLock { delegate.load() }"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("还没读过之前没有任何状态",),
    ),
    Mutation(
        "observable-save-not-published",
        "配对完成（保存）后订阅者要立刻看到",
        (Edit(OBSERVABLE, OBSERVABLE_SAVE, "delegate.save(gateway)"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("保存、更新、清除之后订阅者各看到一次新状态",),
    ),
    Mutation(
        "observable-update-not-published",
        "引擎打上“要用户处理”（更新）后订阅者要立刻看到",
        (Edit(OBSERVABLE, "if (written) current.value = delegate.load()", "if (written) Unit"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("保存、更新、清除之后订阅者各看到一次新状态",),
    ),
    Mutation(
        "observable-clear-not-published",
        "清除配对后订阅者要立刻看到",
        (Edit(OBSERVABLE, "delegate.clear()\n            current.value = delegate.load()", "delegate.clear()"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("保存、更新、清除之后订阅者各看到一次新状态",),
    ),
    Mutation(
        "observable-publishes-written-not-read-back",
        "发出的是读回来的记录，不是写进去的那份（盘上读不回来就不能显示已配对）",
        (Edit(OBSERVABLE, OBSERVABLE_SAVE, "delegate.save(gateway)\n            current.value = PairingState.Paired(gateway)"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("读回来的记录",),
    ),
    Mutation(
        "observable-publishes-before-persisting",
        "存盘失败时不能已经发出“已配对”",
        (Edit(OBSERVABLE, OBSERVABLE_SAVE, "current.value = PairingState.Paired(gateway)\n            delegate.save(gateway)"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("底层存不下",),
    ),
    Mutation(
        "observable-load-not-published",
        "读到的状态也要发出，底层被别处改过时订阅者跟上",
        (Edit(OBSERVABLE, "delegate.load().also { current.value = it }", "delegate.load()"),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("读一次也会发出",),
    ),
    Mutation(
        "observable-no-lock",
        "读写排队：旧读数不能盖掉后来写入的新状态",
        (Edit(
            OBSERVABLE, "private val mutex = Mutex()",
            "private val mutex = object { suspend fun <T> withLock(block: suspend () -> T): T = block() }",
        ),),
        OBSERVABLE_TESTS, SYNC_TEST[0],
        ("同时进行的读写排队",),
    ),
    Mutation(
        "qr-reads-other-formats",
        "只认二维码：别的条码不读",
        (Edit(
            QR, "QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text",
            "com.google.zxing.MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), emptyMap<DecodeHintType, Any>()).text",
        ),),
        QR_TESTS, SYNC_TEST[0],
        ("只认二维码",),
    ),
    Mutation(
        "qr-ignores-row-stride",
        "CameraX 的行距可能大于宽度，必须按行距读",
        (Edit(QR, "System.arraycopy(luminance, y * rowStride, pixels, y * width, width)", "System.arraycopy(luminance, y * width, pixels, y * width, width)"),),
        QR_TESTS, SYNC_TEST[0],
        ("行距大于宽度",),
    ),
    Mutation(
        "qr-no-inverted-attempt",
        "深色背景上的浅色二维码（网关终端的 --invert 输出）也要能读",
        (Edit(QR, "(read(source) ?: read(source.invert()))?.let { return it }", "read(source)?.let { return it }"),),
        QR_TESTS, SYNC_TEST[0],
        ("深底浅码",),
    ),
    Mutation(
        "qr-no-rotation",
        "手机竖着拿时相机帧转了 90 度：四个方向都要读",
        (Edit(QR, "repeat(4) {", "repeat(1) {"),),
        QR_TESTS, SYNC_TEST[0],
        ("四个方向都能读",),
    ),
    Mutation(
        "qr-no-size-guard",
        "尺寸与数据对不上时返回 null，不抛越界异常（取景回调里抛异常会停掉相机管线）",
        (Edit(QR, "        if (luminance.size.toLong() < rowStride.toLong() * (height - 1) + width) return null\n", ""),),
        QR_TESTS, SYNC_TEST[0],
        ("尺寸与数据对不上",),
    ),
    Mutation(
        "qr-throws-on-unreadable",
        "读不出来是 null，不是异常",
        (Edit(QR, "} catch (_: ReaderException) {\n        null\n    }", "} catch (e: ReaderException) {\n        throw e\n    }"),),
        QR_TESTS, SYNC_TEST[0],
        ("纯白、纯黑、噪声",),
    ),
)

FRAME_READER = "apps/android/sync/src/main/kotlin/app/auri/sync/pairing/QrFrameReader.kt"
FRAME_TESTS = (":sync:test", "--tests", "*QrFrameReaderTest*")
DISCOVERY = "apps/android/syncplatform/src/main/kotlin/app/auri/syncplatform/discovery/NsdGatewayDiscovery.kt"
DEVICE_INFO = "apps/android/syncplatform/src/main/kotlin/app/auri/syncplatform/DeviceInfoProvider.kt"
SYNCPLATFORM_TEST = "syncplatform/build/test-results/testDebugUnitTest"
ADVERT_TESTS = (":syncplatform:testDebugUnitTest", "--tests", "*GatewayAdvertisementTest*")
DEVICE_TESTS = (":syncplatform:testDebugUnitTest", "--tests", "*DeviceInfoTest*")

# 切片 8：取景帧的读取、mDNS 广告筛选、设备信息
MUTATIONS += (
    Mutation(
        "frame-reader-never-closes-frame",
        "每一帧都必须关闭：CameraX 要等这一帧关闭才给下一帧，漏一次预览就卡住",
        (Edit(FRAME_READER, "        } finally {\n            frame.close()\n        }", "        }"),),
        FRAME_TESTS, SYNC_TEST[0],
        ("读到二维码就把文字交出去，并关闭这一帧", "没有二维码的帧什么也不交出去，但照样关闭"),
    ),
    Mutation(
        "frame-reader-closes-frame-twice",
        "每一帧恰好关闭一次（重复关闭会让 CameraX 抛异常）",
        (Edit(FRAME_READER, "?.let(onText)", "?.let(onText).also { frame.close() }"),),
        FRAME_TESTS, SYNC_TEST[0],
        ("读到二维码就把文字交出去，并关闭这一帧",),
    ),
    Mutation(
        "frame-reader-lets-exceptions-escape",
        "一帧读失败不能让分析线程死掉，更不能影响下一帧",
        (Edit(
            FRAME_READER,
            "        } catch (_: Exception) {\n            // 这一帧读不出来，等下一帧\n        } finally {",
            "        } finally {",
        ),),
        FRAME_TESTS, SYNC_TEST[0],
        ("取灰度出异常也不外抛", "回调出异常也不外抛"),
    ),
    Mutation(
        "frame-reader-ignores-row-stride",
        "按这一帧自己的行距读，不能假设行距等于宽度",
        (Edit(FRAME_READER, "frame.width, frame.height, frame.rowStride", "frame.width, frame.height, frame.width"),),
        FRAME_TESTS, SYNC_TEST[0],
        ("按这一帧自己的行距读",),
    ),
    Mutation(
        "advert-accepts-other-gateways",
        "mDNS 里只认要找的那台网关（gateway_id 相同），同一局域网的别的网关不是候选",
        (Edit(DISCOVERY, '    if (attributes["gateway_id"] != gatewayId) return null\n', ""),),
        ADVERT_TESTS, SYNCPLATFORM_TEST,
        ("别的网关的广告不是候选", "没有 gateway_id 的广告不是候选"),
    ),
    Mutation(
        "advert-accepts-unsafe-host",
        "广告里的主机会被写进 Host 头：带空白或换行的不能当候选",
        (Edit(DISCOVERY, "!GatewayAddress.isValidHost(host) || ", ""),),
        ADVERT_TESTS, SYNCPLATFORM_TEST,
        ("主机里带空白或换行的广告不是候选",),
    ),
    Mutation(
        "advert-accepts-invalid-port",
        "端口不合法的广告不是候选",
        (Edit(DISCOVERY, " || !GatewayAddress.isValidPort(port)", ""),),
        ADVERT_TESTS, SYNCPLATFORM_TEST,
        ("端口不合法的广告不是候选",),
    ),
    Mutation(
        "device-info-app-version-not-truncated",
        "配对时报给网关的字段长度必须合乎合同（应用版本最长 64）",
        (Edit(DEVICE_INFO, "appVersion.take(64).ifEmpty", "appVersion.ifEmpty"),),
        DEVICE_TESTS, SYNCPLATFORM_TEST,
        ("过长的字段按合同上限截断",),
    ),
    Mutation(
        "device-info-os-build-not-truncated",
        "系统版本字段最长 100",
        (Edit(DEVICE_INFO, '(API $sdkInt)".take(100)', '(API $sdkInt)"'),),
        DEVICE_TESTS, SYNCPLATFORM_TEST,
        ("过长的字段按合同上限截断",),
    ),
    Mutation(
        "device-info-display-name-not-truncated",
        "设备显示名最长 100",
        (Edit(DEVICE_INFO, '.joinToString(" ").take(100)', '.joinToString(" ")'),),
        DEVICE_TESTS, SYNCPLATFORM_TEST,
        ("过长的字段按合同上限截断",),
    ),
    Mutation(
        "device-info-empty-version-not-replaced",
        "应用版本为空也要给出合格的值",
        (Edit(DEVICE_INFO, '.ifEmpty { "unknown" }', ""),),
        DEVICE_TESTS, SYNCPLATFORM_TEST,
        ("应用版本为空时也合格",),
    ),
    Mutation(
        "device-info-names-not-trimmed",
        "厂商与型号两端的空白要去掉",
        (Edit(DEVICE_INFO, "manufacturer?.trim(), model?.trim()", "manufacturer, model"),),
        DEVICE_TESTS, SYNCPLATFORM_TEST,
        ("厂商或型号缺失时仍给出合格的名字",),
    ),
)

APP_TEST = "app/build/test-results/testDebugUnitTest"
PLATFORM_TEST = "platform/build/test-results/testDebugUnitTest"
GUARD_TESTS = (":app:testDebugUnitTest", "--tests", "*NoNetworkCodeGuardTest*")
MANIFEST_TESTS = (":app:testDebugUnitTest", "--tests", "*NoNetworkManifestTest*")
RESUME_MANIFEST_TESTS = (":app:testDebugUnitTest", "--tests", "*ResumeManifestTest*")
BACKUP_TESTS = (":app:testDebugUnitTest", "--tests", "*BackupRulesTest*")
STORAGE_GUARD_TESTS = (":app:testDebugUnitTest", "--tests", "*StorageLocationGuardTest*")
NOBACKUP_TESTS = (":platform:testDebugUnitTest", "--tests", "*NoBackupStorageTest*")
PLATFORM_TEST = "platform/build/test-results/testDebugUnitTest"
NOBACKUP = "apps/android/platform/src/main/kotlin/app/auri/platform/storage/NoBackupStorage.kt"
EXTRACTION_RULES = "apps/android/app/src/main/res/xml/data_extraction_rules.xml"
BACKUP_RULES_FILE = "apps/android/app/src/main/res/xml/backup_rules.xml"
AURI_RUNTIME = "apps/android/app/src/main/kotlin/app/auri/android/AuriRuntime.kt"
SYNC_RUNTIME = "apps/android/syncplatform/src/main/kotlin/app/auri/syncplatform/SyncRuntime.kt"
RESUME_TESTS = (":sync:test", "--tests", "*ResumePlannerTest*", "--tests", "*ResumeRunnerTest*")
RESUME_PLANNER = "apps/android/sync/src/main/kotlin/app/auri/sync/resume/ResumePlanner.kt"
RESUME_RUNNER = "apps/android/sync/src/main/kotlin/app/auri/sync/resume/ResumeRunner.kt"
SYNCPLATFORM_MANIFEST = "apps/android/syncplatform/src/main/AndroidManifest.xml"
MIC_TESTS = (":platform:testDebugUnitTest", "--tests", "*NoPhoneMicPathTest*")
VM_TESTS = (":app:testDebugUnitTest", "--tests", "*SyncViewModelTest*")
TEXT_TESTS = (":app:testDebugUnitTest", "--tests", "*SyncScreenTextTest*")
NETSURFACE = "apps/android/app/src/test/kotlin/app/auri/android/netguard/NetworkSurface.kt"
# 唯一允许被变异的“测试源码”：网络卫士的规则表。它不是测试本身，而是被正对照测试检验的对象；
# 这些正对照（卫士认得出……）就是为“规则表被削弱”准备的，所以要用变异证明它们真的会变红。
SCANNER_SOURCES = frozenset({NETSURFACE})
APP_MANIFEST = "apps/android/app/src/main/AndroidManifest.xml"
APP_APPLICATION = "apps/android/app/src/main/kotlin/app/auri/android/AuriApplication.kt"
CAPTURE_TYPES = "apps/android/capture/src/main/kotlin/app/auri/capture/coordinator/CoordinatorTypes.kt"
SYNC_LOG = "apps/android/sync/src/main/kotlin/app/auri/sync/SyncLog.kt"
ANDROID_SYNC_LOG = "apps/android/syncplatform/src/main/kotlin/app/auri/syncplatform/AndroidSyncLog.kt"
VIEWMODEL = "apps/android/app/src/main/kotlin/app/auri/android/sync/SyncViewModel.kt"
ASR_AUDIO = "apps/android/asr/src/main/kotlin/app/auri/asr/audio/Audio.kt"
PLATFORM_CLOCK = "apps/android/platform/src/main/kotlin/app/auri/platform/system/SystemClockAndIds.kt"
MAIN_ACTIVITY = "apps/android/app/src/main/kotlin/app/auri/android/MainActivity.kt"
MODEL_ERROR = "apps/android/modelmgmt/src/main/kotlin/app/auri/modelmgmt/ModelError.kt"
SYNC_SCREEN = "apps/android/app/src/main/kotlin/app/auri/android/ui/SyncScreen.kt"
RELEASE_ENTRY = "apps/android/app/src/release/kotlin/app/auri/android/ui/ManualPairingEntry.kt"
RELEASE_TESTS = (":app:testDebugUnitTest", "--tests", "*ReleaseVariantTest*")
GUARD_REAL_TREE = "七个生产模块编译出的类里没有任何违反网络规矩的引用"
TRUST_ALL_SNIPPET = (
    "        javax.net.ssl.SSLContext.getInstance(\"TLS\").init(\n"
    "            null,\n"
    "            arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {\n"
    "                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit\n"
    "                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit\n"
    "                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()\n"
    "            }),\n"
    "            null,\n"
    "        )\n"
)
START_LINE = "        runtime.sync.start() // 配对记录读一次；没配对时什么也不会发生（Ticket 21）\n"

# 切片 9：网络卫士（真实生产树上的违规必须被它抓到；卫士自己的规则被削弱必须被正对照抓到）、清单、隐私卫士
MUTATIONS += (
    Mutation(
        "guard-hostname-verifier-in-pinned-client",
        "没有主机名校验的后门：PinnedHttpsClient 里放一个“全放行”的 HostnameVerifier 必须被卫士抓到",
        (Edit(
            HTTPS, "    override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {\n",
            "    private val permissive = javax.net.ssl.HostnameVerifier { _, _ -> true }\n\n    override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {\n",
        ),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "没有任何生产模块的类引用 HostnameVerifier 或 HttpsURLConnection", "源码里同样没有"),
    ),
    Mutation(
        "guard-trust-all-in-app",
        "应用壳里另起一套“信任所有证书”必须被卫士抓到（唯一的信任管理器是 SpkiPinTrustManager）",
        (Edit(APP_APPLICATION, START_LINE, START_LINE + TRUST_ALL_SNIPPET),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "整个应用里唯一的信任管理器实现是 SpkiPinTrustManager", "源码里同样没有"),
    ),
    Mutation(
        "guard-socket-in-capture-module",
        "录音/采集模块里出现套接字必须被卫士抓到（:capture 不许有任何网络类）",
        (Edit(CAPTURE_TYPES, "interface Clock {", "private val sneakySocket: Any = java.net.Socket()\n\ninterface Clock {"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE,),
    ),
    Mutation(
        "guard-cleartext-url-in-sync",
        "任何位置出现明文 http:// 地址必须被卫士抓到",
        (Edit(SYNC_LOG, "fun interface SyncLog {", 'const val SNEAKY_URL = "http://example.invalid/leak"\n\nfun interface SyncLog {'),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE,),
    ),
    Mutation(
        "guard-trust-class-outside-tls-package",
        "TrustManager 出现在 :sync 的 tls 包之外必须被卫士抓到（位置才是规矩）",
        (Edit(SYNC_LOG, "fun interface SyncLog {", "private val sneakyTrust: javax.net.ssl.TrustManager? = null\n\nfun interface SyncLog {"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有"),
    ),
    Mutation(
        "guard-rule-trust-allowed-in-app",
        "卫士自己被削弱（信任类放行到应用壳里的两个类）：正对照“信任所有证书”与“另建 SSLContext”必须因此变红",
        (Edit(
            NETSURFACE, "allowedIn = listOf(pinnedTls, spkiTrustManager),\n        ),\n        Rule(\n            id = \"no-bypass\"",
            "allowedIn = listOf(\n                pinnedTls, spkiTrustManager,\n"
            "                Transport(\"app\", \"app/auri/android/netguard/TrustAllExample\", \"x.kt\"),\n"
            "                Transport(\"app\", \"app/auri/android/netguard/SslContextExample\", \"x.kt\"),\n"
            "            ),\n        ),\n        Rule(\n            id = \"no-bypass\"",
        ),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出信任所有证书的实现", "卫士认得出另建 SSLContext 与信任管理器数组"),
    ),
    Mutation(
        "guard-rule-cleartext-removed",
        "卫士自己被削弱（不再查明文地址）：正对照“明文地址”必须因此变红",
        (Edit(NETSURFACE, 'private const val CLEARTEXT = "http://"', 'private const val CLEARTEXT = "http-never://"'),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出明文地址",),
    ),
    Mutation(
        "guard-rule-ignores-type-descriptors",
        "卫士自己被削弱（只看类常量，不看类型描述符）：数组与方法签名里的引用必须仍被认出",
        (Edit(NETSURFACE, ' || constant.contains("L$name;")', ""),),
        GUARD_TESTS, APP_TEST,
        ("前缀规则按包匹配",),
    ),
    Mutation(
        "guard-rule-ignores-module",
        "卫士自己被削弱（只看类路径、不看模块）：同一个类放到别的模块的同名包里必须仍被认出",
        (Edit(
            NETSURFACE, 'this.module == module && (classFile == "$classPath.class" || classFile.startsWith("$classPath\\$"))',
            'classFile == "$classPath.class" || classFile.startsWith("$classPath\\$")',
        ),),
        GUARD_TESTS, APP_TEST,
        ("同一个合规的类放到不该放的位置也被认出", "登记的是具体的类"),
    ),
    # 评审 F3：分块响应的长度算术。对端可能是写错或被攻破的网关，不能让它的一句“下一块有 7fffffff 字节”把手机撑爆。
    Mutation(
        "chunk-limit-sums-in-int",
        "分块的上限检查在 Int 里把已读字节与块大小相加：溢出成负数就放行，然后去分配 2 GB 的数组（F3）",
        (Edit(HTTPS, 'if (size > maxResponseBytes - out.size().toLong()) throw IOException("response too large")',
              'if (out.size() + size.toInt() > maxResponseBytes) throw IOException("response too large")'),),
        PIN_TESTS, SYNC_TEST[0],
        ("一个字节的块之后声称下一块有 7fffffff 字节", "格式正确但超出上限的块大小"),
    ),
    Mutation(
        "chunk-size-truncated-before-limit-check",
        "分块的块大小在和上限比较之前被截成 Int：超出 Int 的块大小截断后看起来很小（F3）",
        (Edit(HTTPS, 'if (size > maxResponseBytes - out.size().toLong()) throw IOException("response too large")',
              'if (size.toInt() > maxResponseBytes - out.size()) throw IOException("response too large")'),),
        PIN_TESTS, SYNC_TEST[0],
        ("格式正确但超出上限的块大小",),
    ),
    Mutation(
        "chunk-limit-ignores-earlier-chunks",
        "分块的上限只比单个块、不算前面已经读到的：很多个不超限的小块可以加起来撑爆内存（F3）",
        (Edit(HTTPS, 'if (size > maxResponseBytes - out.size().toLong()) throw IOException("response too large")',
              'if (size > maxResponseBytes.toLong()) throw IOException("response too large")'),),
        PIN_TESTS, SYNC_TEST[0],
        ("一个块比上限大、或者前面的块用掉了一部分额度", "很多个很小的块"),
    ),
    Mutation(
        "chunk-size-accepts-sign-and-prefix",
        "分块的块大小不再核对“只能是十六进制数字”：+5、-1 这类写法被 toLong 悄悄接受（F3）",
        (Edit(HTTPS, '            if (!CHUNK_SIZE.matches(digits)) throw IOException("bad chunk size")\n', ""),),
        PIN_TESTS, SYNC_TEST[0],
        ("写不成非负十六进制数的块大小",),
    ),
    # 评审 F8：一个总时限管住解析、连接、握手、写、读与取消（以前只有读响应受它约束）
    Mutation(
        "deadline-resolver-unbounded",
        "主机名解析不受总时限约束：解析器卡住时调用方陪着卡（F8）",
        (Edit(HTTPS, "lookup.get(((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(1), TimeUnit.MILLISECONDS)", "lookup.get()"),),
        PIN_TESTS, SYNC_TEST[0],
        ("主机名解析卡住",),
    ),
    Mutation(
        "deadline-watchdog-never-aborts",
        "总时限的看门狗到点不关套接字：阻塞在握手或写里的调用没有别的办法停下来（F8）",
        (Edit(HTTPS, "                    delay(timeouts.totalMs)\n                    connection.abort()\n", "                    delay(timeouts.totalMs)\n"),),
        PIN_TESTS, SYNC_TEST[0],
        ("握手卡住", "对端不收数据"),
    ),
    Mutation(
        "deadline-watchdog-closes-tls-before-tcp",
        "看门狗到点先关 TLS 层再关 TCP：TLS 层的关闭要抢被卡住的写占着的写锁，会陪着一起卡住（F8）",
        (Edit(HTTPS, "                    delay(timeouts.totalMs)\n                    connection.abort()\n", "                    delay(timeouts.totalMs)\n                    connection.close()\n"),),
        PIN_TESTS, SYNC_TEST[0],
        ("对端不收数据",),
    ),
    Mutation(
        "deadline-not-applied-to-reads",
        "读响应时每一步的超时不看剩余总时间、看门狗也不工作：对端一点一点滴数据可以拖过总时限（F8）",
        (Edit(HTTPS, "                    delay(timeouts.totalMs)\n                    connection.abort()\n", "                    delay(timeouts.totalMs)\n"),
         Edit(HTTPS, '            if (remainingMs <= 0) throw SocketTimeoutException("total deadline exceeded")\n            socket.soTimeout = minOf(timeouts.ioMs.toLong(), remainingMs).toInt().coerceAtLeast(1)\n',
              "            socket.soTimeout = timeouts.ioMs\n")),
        PIN_TESTS, SYNC_TEST[0],
        ("对端一点一点滴响应",),
    ),
    Mutation(
        "deadline-cancel-does-not-abort",
        "协程被取消时不关套接字：阻塞的读不会响应取消，调用要等读超时才返回（F8）",
        (Edit(HTTPS, "                    connection.abort()\n                    throw cancelled\n", "                    throw cancelled\n"),),
        PIN_TESTS, SYNC_TEST[0],
        ("协程被取消时立刻返回",),
    ),
    # 评审 F13：登记的是具体的类，不是整个包。下面三条把它放回“同一个包里都算”，或在 :sync 的 http 包里放一个没登记的类。
    Mutation(
        "guard-allowlist-widened-to-package",
        "卫士自己被削弱（登记从具体的类放宽成整个包）：放在 http 包里的未登记类、同包的兄弟类必须仍被认出（F13）",
        (Edit(
            NETSURFACE, 'this.module == module && (classFile == "$classPath.class" || classFile.startsWith("$classPath\\$"))',
            'this.module == module && classFile.startsWith(classPath.substringBeforeLast(\'/\') + "/")',
        ),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出未登记的类自己开套接字", "登记的是具体的类"),
    ),
    Mutation(
        "guard-raw-socket-next-to-the-pinned-client",
        "在 :sync 的 http 包里、PinnedHttpsClient 旁边写一个自己开套接字的类：以前整个包都放行，现在必须被抓到（F13）",
        (Edit(HTTP, "class HttpReply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {",
              "private val sneakySocket: Any = java.net.Socket()\n\nclass HttpReply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有", "引用了网络名字的生产类恰好都在登记的传输里"),
    ),
    Mutation(
        "guard-second-http-stack-in-sync",
        "在 :sync 里用 java.net.URL 另开一条 HTTP 路（只有 PinnedHttpsClient 与 :modelmgmt 的下载传输是登记的 HTTP 栈）（F1）",
        (Edit(HTTP, "interface HttpExchange {", 'private val sneakyUrl: Any = java.net.URL("https://example.invalid/")\n\ninterface HttpExchange {'),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有"),
    ),
    # 评审 F1：每个生产模块都要受网络规矩约束（以前 :modelmgmt 根本没被扫描，也没有 WebView、DownloadManager、查看网址意图的规则）
    Mutation(
        "guard-webview-in-asr-module",
        "转录模块里出现 WebView 必须被卫士抓到（F1）",
        (Edit(ASR_AUDIO, "class PcmAudio(val samples: ShortArray, val sampleRateHz: Int, val channels: Int) {",
              "private val sneakyWeb: Any = android.webkit.WebView::class.java\n\nclass PcmAudio(val samples: ShortArray, val sampleRateHz: Int, val channels: Int) {"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有"),
    ),
    Mutation(
        "guard-download-manager-in-platform",
        "平台模块里出现系统下载管理器必须被卫士抓到（F1）",
        (Edit(PLATFORM_CLOCK, "object SystemClock : Clock {",
              "private val sneakyDownloads: Any = android.app.DownloadManager::class.java\n\nobject SystemClock : Clock {"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有"),
    ),
    Mutation(
        "guard-second-view-intent-in-main-activity",
        "BrowserLink 之外又有一处发“查看网址”的意图必须被卫士抓到（F1）",
        (Edit(MAIN_ACTIVITY, "    private fun openLicense(url: String) = BrowserLink(this).open(url)",
              "    private fun sneakyView() = android.content.Intent(android.content.Intent.ACTION_VIEW)\n\n    private fun openLicense(url: String) = BrowserLink(this).open(url)"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有"),
    ),
    Mutation(
        "guard-raw-socket-in-modelmgmt-outside-the-transport",
        "下载模块里、下载传输之外的类开套接字必须被卫士抓到（以前 :modelmgmt 整个模块不在扫描范围）（F1）",
        (Edit(MODEL_ERROR, "enum class ModelErrorKind {", "private val sneakySocket: Any = java.net.Socket()\n\nenum class ModelErrorKind {"),),
        GUARD_TESTS, APP_TEST,
        (GUARD_REAL_TREE, "源码里同样没有"),
    ),
    Mutation(
        "guard-modelmgmt-not-scanned",
        "卫士自己被削弱（不再扫 :modelmgmt）：必须有测试证明下载模块在扫描范围里（F1）",
        (Edit(NETSURFACE, 'listOf("capture", "asr", "platform", "app", "modelmgmt", "sync", "syncplatform")', 'listOf("capture", "asr", "platform", "app", "sync", "syncplatform")'),),
        GUARD_TESTS, APP_TEST,
        ("下载模型的传输也在扫描范围里",),
    ),
    Mutation(
        "guard-rule-webview-dropped",
        "卫士自己被削弱（不再查 WebView）：正对照必须因此变红（F1）",
        (Edit(NETSURFACE, 'names = listOf("android/webkit/"),', 'names = listOf("android/webkit-never/"),'),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出 WebView",),
    ),
    Mutation(
        "guard-rule-download-manager-dropped",
        "卫士自己被削弱（不再查 DownloadManager）：正对照必须因此变红（F1）",
        (Edit(NETSURFACE, 'names = listOf("android/app/DownloadManager"),', 'names = listOf("android/app/DownloadManager-never"),'),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出 DownloadManager",),
    ),
    Mutation(
        "guard-rule-view-intent-dropped",
        "卫士自己被削弱（不再查“查看网址”的意图）：正对照必须因此变红（F1）",
        (Edit(NETSURFACE, 'names = listOf("android.intent.action.VIEW"),', 'names = listOf("android.intent.action.VIEW-never"),'),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出查看网址的意图",),
    ),
    Mutation(
        "guard-rule-http-stack-allowed-everywhere",
        "卫士自己被削弱（HTTP 栈对谁都放行）：别名后的 HttpURLConnection 正对照必须因此变红（F1）",
        (Edit(NETSURFACE, "allowedIn = downloadHttp,", "allowedIn = downloadHttp + listOf(Transport(\"app\", \"app/auri/android/netguard/AliasedHttpUrlConnectionExample\", \"x.kt\")),"),),
        GUARD_TESTS, APP_TEST,
        ("卫士认得出起了别名的 HttpURLConnection", "登记表没有多余项"),
    ),
    Mutation(
        "guard-source-layer-ignores-imports",
        "卫士自己被削弱（源码层不看 import 行）：别名导入与通配导入必须仍被认出（F1）",
        (Edit(NETSURFACE, "val code = SourceText.withoutCommentsAndStrings(text)\n",
              "val code = SourceText.withoutCommentsAndStrings(text.lines().filterNot { it.trimStart().startsWith(\"import \") }.joinToString(\"\\n\"))\n"),),
        GUARD_TESTS, APP_TEST,
        ("源码层认得出别名导入",),
    ),
    Mutation(
        "guard-source-layer-allows-the-whole-package",
        "卫士自己被削弱（源码层按包而不是按文件放行）：同包的别的文件必须仍被认出（F13）",
        (Edit(NETSURFACE, 'path.endsWith("/$module/src/main/kotlin/${it.source}")', 'path.contains("/$module/src/main/kotlin/${it.source.substringBeforeLast(\'/\')}/")'),),
        GUARD_TESTS, APP_TEST,
        ("源码层也按登记的文件放行",),
    ),
    Mutation(
        "guard-source-layer-counts-comments",
        "卫士自己被削弱（源码层把注释里的字也算数）：注释与字符串里的说明文字不能报错，否则文档里一提 Socket 就挂（F1）",
        (Edit(NETSURFACE, "val code = SourceText.withoutCommentsAndStrings(text)\n", "val code = text\n"),),
        GUARD_TESTS, APP_TEST,
        ("源码层认得出别名导入",),
    ),
    Mutation(
        "guard-allowlist-has-a-stale-entry",
        "登记表里多一项没人用的登记（悄悄放宽范围）：自检必须发现它没有用到那条规则里的任何名字（F13）",
        (Edit(NETSURFACE, "allowedIn = listOf(pinnedTls, spkiTrustManager),\n        ),\n        Rule(\n            id = \"no-bypass\"",
              "allowedIn = listOf(pinnedTls, spkiTrustManager, nsdDiscovery),\n        ),\n        Rule(\n            id = \"no-bypass\""),),
        GUARD_TESTS, APP_TEST,
        ("登记表没有多余项",),
    ),
    Mutation(
        "manifest-cleartext-not-explicit",
        "应用清单不再明确写 usesCleartextTraffic=false（只靠默认值）：清单测试必须发现（F1）",
        (Edit(APP_MANIFEST, '        android:theme="@android:style/Theme.Material.Light.NoActionBar"\n        android:usesCleartextTraffic="false">',
              '        android:theme="@android:style/Theme.Material.Light.NoActionBar">'),),
        MANIFEST_TESTS, APP_TEST,
        ("合并后的应用清单没有放开明文流量",),
    ),
    Mutation(
        "resume-needs-counts-blocked-jobs",
        "停下等用户处理的任务叫醒了也发不出去：不能因它们而叫醒（F7）",
        (Edit(RESUME_PLANNER, "it.state != SyncState.STORED && it.state != SyncState.BLOCKED", "it.state != SyncState.STORED"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("只剩停下等用户处理的任务", "决策表"),
    ),
    Mutation(
        "resume-needs-counts-stored-jobs",
        "任务都入库了就不该再留着叫醒（F7）",
        (Edit(RESUME_PLANNER, "it.state != SyncState.STORED && it.state != SyncState.BLOCKED", "it.state != SyncState.BLOCKED"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("任务都已入库", "决策表"),
    ),
    Mutation(
        "resume-needs-ignores-connection-block",
        "连接要用户处理（公钥不符、令牌被拒）时叫醒也发不出去（F7）",
        (Edit(RESUME_PLANNER, "pairing is PairingState.Paired && pairing.gateway.needsUser == null &&\n", "pairing is PairingState.Paired &&\n"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("没配对、配对记录丢了、连接要用户处理", "决策表"),
    ),
    Mutation(
        "resume-needs-when-not-paired",
        "没配对或配对记录丢了：不叫醒（F7）",
        (Edit(RESUME_PLANNER, "pairing is PairingState.Paired && pairing.gateway.needsUser == null &&\n", "(pairing !is PairingState.Paired || pairing.gateway.needsUser == null) &&\n"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("没配对、配对记录丢了、连接要用户处理", "决策表"),
    ),
    Mutation(
        "resume-planner-reschedules-every-change",
        "结论没变不重复打扰系统（F7）",
        (Edit(RESUME_PLANNER, ".distinctUntilChanged()", ""),),
        RESUME_TESTS, SYNC_TEST[0],
        ("结论没变就不重复打扰系统",),
    ),
    Mutation(
        "resume-planner-never-cancels",
        "没活了要取消叫醒（F7）",
        (Edit(RESUME_PLANNER, "if (needed) scheduler.ensureScheduled() else scheduler.cancel()", "if (needed) scheduler.ensureScheduled() else Unit"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("任务都已入库", "没有任何任务", "跟着变"),
    ),
    Mutation(
        "resume-planner-never-schedules",
        "有活要登记叫醒（F7）",
        (Edit(RESUME_PLANNER, "if (needed) scheduler.ensureScheduled() else scheduler.cancel()", "if (needed) Unit else scheduler.cancel()"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("已配对并且有任务没发完", "跟着变"),
    ),
    Mutation(
        "resume-planner-gives-up-after-scheduler-failure",
        "系统暂时拒绝登记：按退避重新来过，不丢掉这次请求（F7）",
        (
            Edit(RESUME_PLANNER, 'scope.restartingProducer("resume", log, clock, retry) {', 'scope.launch {'),
            Edit(RESUME_PLANNER, "import kotlinx.coroutines.Job\n", "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.launch\n"),
        ),
        RESUME_TESTS, SYNC_TEST[0],
        ("系统暂时拒绝登记",),
    ),
    Mutation(
        "resume-runner-no-time-limit",
        "被叫醒后的一轮要有时限，不能一直占着系统的执行时间（F7）",
        (Edit(RESUME_RUNNER, "withTimeoutOrNull(limitMs) {", "withTimeoutOrNull(Long.MAX_VALUE) {"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("这一轮卡住了",),
    ),
    Mutation(
        "resume-runner-skips-startup-wait",
        "被叫醒的那一轮要等启动做完（配对记录读出来）再跑（F7）",
        (Edit(RESUME_RUNNER, "                ready()\n                runPass()", "                runPass()"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("先等启动做完",),
    ),
    Mutation(
        "resume-runner-failure-means-done",
        "这一轮出了意外不能当作没活了（F7）",
        (Edit(RESUME_RUNNER, "            return true // 没得出结论：不能当作没活了", "            return false // 没得出结论：不能当作没活了"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("这一轮出了意外",),
    ),
    Mutation(
        "resume-runner-swallows-cancellation",
        "被系统叫停时取消要向上传，不再做收尾判断（F7）",
        (Edit(
            RESUME_RUNNER,
            "        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {\n            log.event(\"resume.failed ${failure::class.simpleName}\")\n            return true // 没得出结论",
            "        } catch (failure: Exception) {\n            log.event(\"resume.failed ${failure::class.simpleName}\")\n            return true // 没得出结论",
        ),),
        RESUME_TESTS, SYNC_TEST[0],
        ("被系统叫停",),
    ),
    Mutation(
        "resume-runner-swallows-cancellation-in-final-check",
        "收尾判断时被系统叫停：取消也要向上传（F7）",
        (Edit(
            RESUME_RUNNER,
            "            stillNeeded()\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (failure: Exception) {",
            "            stillNeeded()\n        } catch (failure: Exception) {",
        ),),
        RESUME_TESTS, SYNC_TEST[0],
        ("被系统叫停时正在做收尾判断",),
    ),
    Mutation(
        "resume-runner-always-asks-for-more",
        "没活了就不请系统再叫醒（F7）",
        (Edit(RESUME_RUNNER, "        return try {\n            stillNeeded()\n", "        return try {\n            stillNeeded() || true\n"),),
        RESUME_TESTS, SYNC_TEST[0],
        ("返回的是“还需要叫醒”的判断",),
    ),
    Mutation(
        "resume-runner-log-leaks-message",
        "叫醒那一轮出意外的日志只有异常类名（F7）",
        (Edit(RESUME_RUNNER, 'log.event("resume.failed ${failure::class.simpleName}")\n            return true // 没得出结论', 'log.event("resume.failed ${failure.message}")\n            return true // 没得出结论'),),
        RESUME_TESTS, SYNC_TEST[0],
        ("这一轮出了意外",),
    ),
    Mutation(
        "manifest-resume-no-boot-permission",
        "没有 RECEIVE_BOOT_COMPLETED，跨重启保留的叫醒在登记时被系统拒绝（F7）",
        (Edit(SYNCPLATFORM_MANIFEST, '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n', ""),),
        RESUME_MANIFEST_TESTS, APP_TEST,
        ("RECEIVE_BOOT_COMPLETED",),
    ),
    Mutation(
        "manifest-resume-service-exported",
        "叫醒服务不能对外导出（F7）",
        (Edit(SYNCPLATFORM_MANIFEST, 'android:exported="false"', 'android:exported="true"'),),
        RESUME_MANIFEST_TESTS, APP_TEST,
        ("叫醒服务只允许系统绑定",),
    ),
    Mutation(
        "manifest-resume-service-open-to-anyone",
        "叫醒服务必须要求 BIND_JOB_SERVICE，否则系统不会启动它、别的应用也能绑定（F7）",
        (Edit(SYNCPLATFORM_MANIFEST, '            android:permission="android.permission.BIND_JOB_SERVICE" />', '            />'),),
        RESUME_MANIFEST_TESTS, APP_TEST,
        ("叫醒服务只允许系统绑定",),
    ),
    Mutation(
        "manifest-allow-backup-true",
        "allowBackup 必须是 false（F2）",
        (Edit(APP_MANIFEST, 'android:allowBackup="false"', 'android:allowBackup="true"'),),
        BACKUP_TESTS, APP_TEST,
        ("合并后的清单：allowBackup 关闭",),
    ),
    Mutation(
        "manifest-drops-data-extraction-rules",
        "Android 12+ 的换机迁移要靠 dataExtractionRules 才关得上，只有 allowBackup=false 不够（F2）",
        (Edit(APP_MANIFEST, '        android:dataExtractionRules="@xml/data_extraction_rules"\n', ""),),
        BACKUP_TESTS, APP_TEST,
        ("合并后的清单：allowBackup 关闭",),
    ),
    Mutation(
        "manifest-drops-full-backup-content",
        "Android 11 及以下的备份规则必须成套声明（F2）",
        (Edit(APP_MANIFEST, '        android:fullBackupContent="@xml/backup_rules"\n', ""),),
        BACKUP_TESTS, APP_TEST,
        ("合并后的清单：allowBackup 关闭",),
    ),
    Mutation(
        "rules-transfer-leaves-database",
        "设备到设备迁移也要把数据库域整个排除（F2）",
        (Edit(
            EXTRACTION_RULES,
            '    <device-transfer>\n        <exclude domain="root" path="." />\n        <exclude domain="file" path="." />\n        <exclude domain="database" path="." />\n',
            '    <device-transfer>\n        <exclude domain="root" path="." />\n        <exclude domain="file" path="." />\n',
        ),),
        BACKUP_TESTS, APP_TEST,
        ("换机迁移规则",),
    ),
    Mutation(
        "rules-cloud-backup-includes-something",
        "规则里不许有 include（它会把排除的数据重新放进备份）（F2）",
        (Edit(EXTRACTION_RULES, "    <cloud-backup>\n", '    <cloud-backup>\n        <include domain="file" path="keep" />\n'),),
        BACKUP_TESTS, APP_TEST,
        ("换机迁移规则",),
    ),
    Mutation(
        "rules-legacy-backup-partial-exclusion",
        "旧版备份规则要把整个域排除，不能只排除一个子目录（F2）",
        (Edit(BACKUP_RULES_FILE, '<exclude domain="external" path="." />', '<exclude domain="external" path="cache" />'),),
        BACKUP_TESTS, APP_TEST,
        ("旧版备份规则",),
    ),
    Mutation(
        "pairing-file-back-in-files-dir",
        "配对记录（设备令牌）必须在 noBackupFilesDir（F2）",
        (Edit(SYNC_RUNTIME, "pairingFile = NoBackupStorage.place(application.noBackupFilesDir, application.filesDir, PAIRING_FILE),", "pairingFile = File(application.filesDir, PAIRING_FILE),"),),
        STORAGE_GUARD_TESTS, APP_TEST,
        ("每一处 filesDir", "三个敏感文件都经 NoBackupStorage"),
    ),
    Mutation(
        "recordings-back-in-files-dir",
        "录音必须在 noBackupFilesDir（F2）",
        (Edit(AURI_RUNTIME, 'recordingsDir: File = NoBackupStorage.place(application.noBackupFilesDir, application.filesDir, "recordings"),', 'recordingsDir: File = File(application.filesDir, "recordings"),'),),
        STORAGE_GUARD_TESTS, APP_TEST,
        ("每一处 filesDir", "三个敏感文件都经 NoBackupStorage"),
    ),
    Mutation(
        "headset-profile-back-in-files-dir",
        "耳机配置必须在 noBackupFilesDir（F2）",
        (Edit(AURI_RUNTIME, "FileProfileStore(NoBackupStorage.place(application.noBackupFilesDir, application.filesDir, PROFILE_FILE_NAME))", "FileProfileStore(File(application.filesDir, PROFILE_FILE_NAME))"),),
        STORAGE_GUARD_TESTS, APP_TEST,
        ("每一处 filesDir", "三个敏感文件都经 NoBackupStorage"),
    ),
    Mutation(
        "nobackup-place-ignores-legacy-data",
        "老位置的敏感文件要挪过来，不能丢（F2）",
        (Edit(NOBACKUP, "            legacy.renameTo(target)\n", "            legacy.exists()\n"),),
        NOBACKUP_TESTS, PLATFORM_TEST,
        ("旧位置有文件、新位置没有", "整个目录（录音）也能挪"),
    ),
    Mutation(
        "nobackup-place-overwrites-new-with-legacy",
        "新位置已经有了就不能被旧文件覆盖（F2）",
        (Edit(NOBACKUP, "if (!target.exists() && legacy.exists()) {", "if (legacy.exists()) {"),),
        NOBACKUP_TESTS, PLATFORM_TEST,
        ("新位置已经有了",),
    ),
    Mutation(
        "nobackup-place-returns-legacy-location",
        "敏感文件的位置必须在 noBackup 目录之下（F2）",
        (Edit(NOBACKUP, "val target = File(noBackupDir, name)", "val target = File(legacyDir, name)"),),
        NOBACKUP_TESTS, PLATFORM_TEST,
        ("敏感文件的位置在 noBackup 目录之下",),
    ),
    Mutation(
        "manifest-app-declares-internet",
        "应用主清单自己声明 INTERNET（而不是只来自 :syncplatform）必须被清单测试抓到",
        (Edit(APP_MANIFEST, '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n', '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-permission android:name="android.permission.INTERNET" />\n'),),
        MANIFEST_TESTS, APP_TEST,
        ("合并后的应用清单申请 INTERNET 恰好一次", "只有 modelmgmt 与 syncplatform 的清单声明 INTERNET"),
    ),
    Mutation(
        "manifest-allows-cleartext-traffic",
        "放开明文流量必须被清单测试抓到",
        (Edit(APP_MANIFEST, 'android:allowBackup="false"', 'android:allowBackup="false"\n        android:usesCleartextTraffic="true"'),),
        MANIFEST_TESTS, APP_TEST,
        ("合并后的应用清单没有放开明文流量",),
    ),
    Mutation(
        "privacy-guard-media-recorder-in-syncplatform",
        "同步层里出现 MediaRecorder（任何向系统要手机麦克风的路径）必须被隐私卫士抓到：卫士已扩到 :syncplatform",
        (Edit(ANDROID_SYNC_LOG, "object AndroidSyncLog : SyncLog {", "private val sneakyMic: Any = android.media.MediaRecorder::class.java\n\nobject AndroidSyncLog : SyncLog {"),),
        MIC_TESTS, PLATFORM_TEST,
        ("compiled production modules restrict AudioRecord", "production sources cannot hide inlined audio source constants"),
    ),
    Mutation(
        "privacy-guard-audio-record-in-sync",
        "纯 JVM 的 :sync 里出现 AudioRecord 的引用必须被隐私卫士抓到：卫士已扩到 :sync",
        (Edit(SYNC_LOG, "fun interface SyncLog {", 'const val SNEAKY_AUDIO = "android/media/AudioRecord"\n\nfun interface SyncLog {'),),
        MIC_TESTS, PLATFORM_TEST,
        ("compiled production modules restrict AudioRecord", "production sources cannot hide inlined audio source constants"),
    ),
)

# 切片 10：同步页的视图模型与文字
MUTATIONS += (
    Mutation(
        "sync-vm-pairing-not-atomic",
        "多个线程同时送来同一帧：配对必须恰好开始一次（原子地从 Idle 切到 Pairing）",
        (Edit(VIEWMODEL, "        if (!progress.compareAndSet(current, PairingProgress.Pairing)) return\n", "        progress.value = PairingProgress.Pairing\n"),),
        VM_TESTS, APP_TEST,
        ("多个线程同时送来同一帧",),
    ),
    Mutation(
        "sync-vm-repeated-frames-not-ignored",
        "配对进行中取景重复送来的帧必须被忽略",
        (Edit(VIEWMODEL, "        if (current is PairingProgress.Pairing) return // 取景一秒会来几十帧，配对进行中只认第一帧\n", ""),),
        VM_TESTS, APP_TEST,
        ("配对进行中取景重复送来的帧被忽略",),
    ),
    Mutation(
        "sync-vm-paired-qr-not-remembered",
        "配对成功之后同一张二维码在路上的帧不能触发第二次配对",
        (Edit(VIEWMODEL, "        if (text.trim() == lastPairedText) return\n", ""),),
        VM_TESTS, APP_TEST,
        ("配对成功之后同一张二维码在路上的帧",),
    ),
    Mutation(
        "sync-vm-failed-qr-marked-used",
        "配对没成功就没有用掉这张码：失败之后同一张二维码必须能重试",
        (Edit(VIEWMODEL, "            if (result == PairingProgress.Idle) lastPairedText = text.trim()\n", "            lastPairedText = text.trim()\n"),),
        VM_TESTS, APP_TEST,
        ("配对失败之后同一张二维码可以重试",),
    ),
    # 评审 F14：“粘贴配对载荷”原先写在 src/main、只靠运行时的 debuggable 标志藏起来；现在真实现只在 src/debug，release 是空实现。
    # 下面三条把它又放回会进 release 的地方，ReleaseVariantTest 读 release 版编译出的类，必须变红。
    Mutation(
        "release-manual-entry-text-leaks-into-main",
        "手动输入的文案又写回 src/main（两个变体都会编进去）：release 版的类里必须没有它",
        (Edit(SYNC_SCREEN, "        manualEntry()\n    }\n}\n", "        manualEntry()\n        Text(\"配对载荷\")\n    }\n}\n"),),
        RELEASE_TESTS, APP_TEST,
        ("release 版编译出的类里没有手动输入配对载荷的入口",),
    ),
    Mutation(
        "release-manual-entry-point-back-in-viewmodel",
        "视图模型里又有一个“手动输入”入口（再靠运行时标志管着）：名字在 release 与 debug 的类里都不能出现",
        (Edit(VIEWMODEL, "    fun onQrText(text: String) {\n        val current = progress.value\n",
              "    fun onManualPayload(text: String) = onQrText(text)\n\n    fun onQrText(text: String) {\n        val current = progress.value\n"),),
        RELEASE_TESTS, APP_TEST,
        ("release 版编译出的类里没有手动输入配对载荷的入口", "运行时开关与视图模型入口已经整个删掉"),
    ),
    Mutation(
        "release-stub-draws-the-debug-entry",
        "release 版的空实现被换成会画出手动输入的界面",
        (Edit(RELEASE_ENTRY, "enabled: Boolean) = Unit\n", "enabled: Boolean) { androidx.compose.material3.Text(\"用文字配对\") }\n"),),
        RELEASE_TESTS, APP_TEST,
        ("release 版编译出的类里没有手动输入配对载荷的入口",),
    ),
    Mutation(
        "sync-vm-unexpected-exception-not-contained",
        "配对流程抛了意料之外的异常：界面不能一直停在正在配对",
        (Edit(VIEWMODEL, "    } catch (_: Exception) {\n        PairingProgress.Failed(PairingProblem.UNKNOWN)", "    } catch (_: java.io.IOException) {\n        PairingProgress.Failed(PairingProblem.UNKNOWN)"),),
        VM_TESTS, APP_TEST,
        ("配对流程抛了意料之外的异常",),
    ),
    Mutation(
        "sync-vm-outcomes-confused",
        "配对的每一种结局界面都分得清：过期与找不到网关不能互相顶替",
        (Edit(
            VIEWMODEL,
            "            PairingOutcome.Expired -> PairingProgress.Failed(PairingProblem.EXPIRED)\n            PairingOutcome.NotFound -> PairingProgress.Failed(PairingProblem.NOT_FOUND)",
            "            PairingOutcome.Expired -> PairingProgress.Failed(PairingProblem.NOT_FOUND)\n            PairingOutcome.NotFound -> PairingProgress.Failed(PairingProblem.EXPIRED)",
        ),),
        VM_TESTS, APP_TEST,
        ("配对的每一种结局界面都分得清",),
    ),
    Mutation(
        "sync-vm-progress-waits-for-first-status",
        "配对的进展不用等第一份同步状态：读到配对记录之前也要看得到",
        (Edit(VIEWMODEL, ".onStart { emit(null) }", ""),),
        VM_TESTS, APP_TEST,
        ("合规的二维码开始配对", "不是配对载荷的二维码给出 INVALID_QR"),
    ),
    Mutation(
        "sync-vm-status-failure-escapes",
        "状态流（读本机任务表）出故障：异常不能冲出视图模型的作用域，否则进程崩溃（F4）",
        (Edit(VIEWMODEL, "            if (failure !is Exception || failure is CancellationException) return@retryWhen false\n", "            return@retryWhen false\n"),),
        VM_TESTS, APP_TEST,
        ("状态流读本机存储时出了故障",),
    ),
    Mutation(
        "sync-vm-storage-problem-never-set",
        "状态流出故障时界面必须知道本机存储暂时读不了（F4）",
        (Edit(VIEWMODEL, "            storageProblem.value = true\n", "            storageProblem.value = false\n"),),
        VM_TESTS, APP_TEST,
        ("状态流读本机存储时出了故障",),
    ),
    Mutation(
        "sync-vm-storage-problem-never-cleared",
        "读得出来了，存储故障状态必须消失（F4）",
        (Edit(VIEWMODEL, "            statusFailures = 0\n            storageProblem.value = false\n            status\n", "            statusFailures = 0\n            status\n"),),
        VM_TESTS, APP_TEST,
        ("状态流连续出故障",),
    ),
    Mutation(
        "sync-vm-status-retry-without-backoff",
        "状态流重新订阅要按有界退避，不能一秒一次地空转（F4）",
        (Edit(VIEWMODEL, "            delay(retry.delayMs(statusFailures))\n", "            delay(1_000)\n"),),
        VM_TESTS, APP_TEST,
        ("状态流连续出故障",),
    ),
    Mutation(
        "sync-vm-status-backoff-never-resets",
        "两次故障之间读到过状态：退避从头算（F4）",
        (Edit(VIEWMODEL, "            statusFailures = 0\n            storageProblem.value = false\n", "            storageProblem.value = false\n"),),
        VM_TESTS, APP_TEST,
        ("两次故障之间读到过状态",),
    ),
    Mutation(
        "sync-vm-status-failure-log-leaks-message",
        "状态流出故障的日志只有异常类名，异常消息（可能带路径、SQL）不进日志",
        (Edit(VIEWMODEL, 'log.event("sync.status.failed ${failure::class.simpleName}")', 'log.event("sync.status.failed ${failure.message}")'),),
        VM_TESTS, APP_TEST,
        ("状态流出故障只记一条不含内容的日志",),
    ),
    Mutation(
        "screen-text-storage-problem-asks-user-to-act",
        "本机存储暂时读不了不需要用户做什么：这句话要说明会自动重试，不能读成同步失败（F4）",
        (Edit(SYNC_SCREEN, "正在自动重试，不需要你做什么。", "请检查存储空间。"),),
        TEXT_TESTS, APP_TEST,
        ("本机存储暂时读不了",),
    ),
    Mutation(
        "sync-vm-dismiss-does-nothing",
        "关掉配对失败的提示后必须回到 Idle",
        (Edit(VIEWMODEL, "        if (current is PairingProgress.Failed) progress.compareAndSet(current, PairingProgress.Idle)\n", ""),),
        VM_TESTS, APP_TEST,
        ("不是配对载荷的二维码给出 INVALID_QR",),
    ),
    Mutation(
        "sync-vm-invalid-qr-silently-ignored",
        "不是配对载荷的二维码要给出 INVALID_QR，不能悄悄没反应",
        (Edit(VIEWMODEL, "                progress.compareAndSet(current, PairingProgress.Failed(PairingProblem.INVALID_QR))\n", ""),),
        VM_TESTS, APP_TEST,
        ("不是配对载荷的二维码给出 INVALID_QR",),
    ),
    Mutation(
        "screen-text-waiting-asks-user-to-act",
        "等网关不需要用户做什么：这句话不能变成让用户去处理",
        (Edit(SYNC_SCREEN, "连上之后会自动发送，不需要你做什么。\"", "连上之后会自动发送。请检查网关。\""),),
        TEXT_TESTS, APP_TEST,
        ("等网关与稍后重试明说不需要用户做什么",),
    ),
    Mutation(
        "screen-text-retry-same-as-waiting",
        "稍后重试与等网关是两种情形：文字不能混成一句",
        (Edit(
            SYNC_SCREEN, "SyncHeadline.RETRYING_LATER -> \"网关暂时没有成功处理，稍后会自动重试，不需要你做什么。\"",
            "SyncHeadline.RETRYING_LATER -> \"现在连不上网关（可能不在同一个网络，或网关没开机）。连上之后会自动发送，不需要你做什么。\"",
        ),),
        TEXT_TESTS, APP_TEST,
        ("六种情形的话两两不同",),
    ),
    Mutation(
        "screen-text-needs-user-says-nothing-to-do",
        "需要用户处理的那一种不能说成“不需要你做什么”",
        (Edit(SYNC_SCREEN, "\"需要你处理：下面的问题解决之前，相关文字不会再自动发送。\"", "\"需要你处理：不需要你做什么，相关文字不会再自动发送。\""),),
        TEXT_TESTS, APP_TEST,
        ("等网关与稍后重试明说不需要用户做什么",),
    ),
    Mutation(
        "screen-text-pairing-lost-no-repair-advice",
        "连接层面的原因要告诉用户重新配对",
        (Edit(SYNC_SCREEN, "本机保存的配对信息丢失（例如清除了安全存储）。请重新配对。", "本机保存的配对信息丢失（例如清除了安全存储）。请稍后再试。"),),
        TEXT_TESTS, APP_TEST,
        ("每个需要用户处理的原因都有自己的话",),
    ),
    Mutation(
        "screen-text-counts-order-changed",
        "计数那一行的顺序固定",
        (Edit(SYNC_SCREEN, "排队 ${counts.queued} · 发送中 ${counts.sending}", "发送中 ${counts.sending} · 排队 ${counts.queued}"),),
        TEXT_TESTS, APP_TEST,
        ("计数那一行六个数都在，顺序固定",),
    ),
)

# 评审 F5：升级后的回填。任务表建好之后，把升级前已经发布的 Revision 补成等待发送的任务（JVM 上用真的 SQLite 引擎验证）。
SQLITE_JOB_STORE = "apps/android/syncplatform/src/main/kotlin/app/auri/syncplatform/jobs/SqliteSyncJobStore.kt"
RECORD_LIBRARY = "apps/android/platform/src/main/kotlin/app/auri/platform/library/SqliteRecordLibrary.kt"
BACKFILL_TESTS = (":syncplatform:testDebugUnitTest", "--tests", "*SyncJobsBackfillTest*")
NOT_EXISTS = "WHERE NOT EXISTS (SELECT 1 FROM sync_jobs j WHERE j.record_id = r.record_id AND j.revision = r.number)"
BACKFILL_COLUMNS = "'${SyncState.PENDING.name}', 0, 0, NULL, NULL, NULL,"

MUTATIONS += (
    Mutation(
        "backfill-selects-nothing",
        "升级前已经发布的 Revision 必须被回填成任务，否则它们永远不会同步（F5）",
        (Edit(SQLITE_JOB_STORE, NOT_EXISTS, "WHERE 0"),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("升级时旧库里已经发布的每个 Revision", "进程死在建表之后、回填之前"),
    ),
    Mutation(
        "backfill-without-exists-check",
        "回填必须幂等：只补还没有任务的，重复打开不能撞主键或重复（F5）",
        (Edit(SQLITE_JOB_STORE, NOT_EXISTS, "WHERE 1"),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("再打开一次不会重复，也不会把已有任务的进度重置",),
    ),
    Mutation(
        "backfill-replaces-existing-jobs",
        "回填不能把已有任务（已入库、被拒收……）的进度重置成等待发送（F5）",
        (
            Edit(SQLITE_JOB_STORE, "INSERT INTO sync_jobs (\n                record_id, revision, state", "INSERT OR REPLACE INTO sync_jobs (\n                record_id, revision, state"),
            Edit(SQLITE_JOB_STORE, NOT_EXISTS, "WHERE 1"),
        ),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("再打开一次不会重复，也不会把已有任务的进度重置", "库里有 Revision 却没有任务时下一次打开补上"),
    ),
    Mutation(
        "backfill-only-first-revisions",
        "每一个 Revision 都要回填，不只是第一版（F5）",
        (Edit(SQLITE_JOB_STORE, NOT_EXISTS, NOT_EXISTS + " AND r.number = 1"),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("升级时旧库里已经发布的每个 Revision",),
    ),
    Mutation(
        "backfill-marks-jobs-stored",
        "回填出来的任务是等待发送，不能直接标成已入库（那样文字根本不会发出去，界面还显示一切正常）（F5）",
        (Edit(SQLITE_JOB_STORE, BACKFILL_COLUMNS, "'${SyncState.STORED.name}', 0, 0, NULL, NULL, NULL,"),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("升级时旧库里已经发布的每个 Revision",),
    ),
    Mutation(
        "backfill-marks-jobs-uncertain",
        "回填出来的任务与发布事务里入队的一个样子：没有“结果不确定”（F5）",
        (Edit(SQLITE_JOB_STORE, BACKFILL_COLUMNS, "'${SyncState.PENDING.name}', 0, 1, NULL, NULL, NULL,"),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("升级时旧库里已经发布的每个 Revision",),
    ),
    Mutation(
        "backfill-created-at-ignores-publication-time",
        "回填任务的时间取 Revision 自己的发布时间，先发布的先同步（F5）",
        (Edit(SQLITE_JOB_STORE, "r.created_at_s, r.created_at_nano, r.created_at_s, r.created_at_nano\n", "0, 0, 0, 0\n"),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("升级时旧库里已经发布的每个 Revision",),
    ),
    Mutation(
        "backfill-order-left-to-scan-order",
        "发布时间相同时的次序要由回填语句保证（先 Record 再版本号），不能靠碰巧的扫描次序（F5）",
        (Edit(SQLITE_JOB_STORE, "ORDER BY r.created_at_s, r.created_at_nano, r.record_id, r.number", ""),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("发布时间完全一样时次序也是确定的",),
    ),
    Mutation(
        "library-gains-deleted-column-backfill-unaware",
        "库里一旦有了“已删除”这类列，回填就必须据此排除；列变了而回填没跟上，测试要变红（F5）",
        (Edit(
            RECORD_LIBRARY, "                transcription TEXT NOT NULL,\n                failure_code TEXT\n",
            "                transcription TEXT NOT NULL,\n                failure_code TEXT,\n                deleted_at_s INTEGER\n",
        ),),
        BACKFILL_TESTS, SYNCPLATFORM_TEST,
        ("本机的库里还没有“已删除”与“来源设备”这两个概念",),
    ),
)

# 第 11 组：同样的破坏，换成对着真实的 Python 网关再跑一遍（:sync:realGatewayTest）。
# 前面的变异都是被“假网关 / 假传输”的单测抓住的；这一组证明真实网关那一层也守得住同样的规矩（不是只在假的世界里成立）。
REAL_GATEWAY_TEST = (":sync:realGatewayTest",)
REAL_GATEWAY_RESULTS = "sync/build/test-results/realGatewayTest"


def _on_real_gateway(base_id: str, new_id: str, rule: str, must_fail: tuple[str, ...]) -> Mutation:
    base = next(m for m in MUTATIONS if m.id == base_id)
    return Mutation(new_id, rule, base.edits, REAL_GATEWAY_TEST, REAL_GATEWAY_RESULTS, must_fail)


MUTATIONS += (
    _on_real_gateway(
        "pin-accepts-any-key", "real-gw-pin-accepts-any-key",
        "真网关：换了一台公钥不同的网关，连接必须被拒绝（比对改成永远通过就会连上冒充者）",
        ("换了一台网关",),
    ),
    _on_real_gateway(
        "engine-resends-without-asking", "real-gw-resends-without-asking",
        "真网关：响应丢了、网关其实已经入库——结果不确定时必须先问网关，不能盲目重发",
        ("响应在路上丢了",),
    ),
    _on_real_gateway(
        "engine-retries-version-conflict", "real-gw-retries-version-conflict",
        "真网关：同一版号不同正文，网关回版本冲突，永久拒绝不能重试",
        ("同一版号、正文不同",),
    ),
    Mutation(
        "real-gw-retries-not-origin-device",
        "真网关：别的设备改这条 Record，网关回 RECORD_NOT_ORIGIN_DEVICE 永久拒绝（ADR-0020），不能重试",
        (Edit(ENGINE, "result.code == ErrorCode.RECORD_NOT_ORIGIN_DEVICE -> blocked(key, BlockedReason.NOT_ORIGIN_DEVICE)", "result.code == ErrorCode.RECORD_NOT_ORIGIN_DEVICE -> retryLater(key)"),),
        REAL_GATEWAY_TEST, REAL_GATEWAY_RESULTS,
        ("别的设备改这条 Record",),
    ),
    _on_real_gateway(
        "store-writes-token-in-plaintext", "real-gw-token-in-plaintext",
        "真网关：真二维码配对之后，设备令牌不能以明文落盘",
        ("二维码配对：",),
    ),
    _on_real_gateway(
        "engine-ignores-connection-gate", "real-gw-ignores-connection-gate",
        "真网关：公钥不符、令牌被拒要用户处理期间，新任务也一个带令牌的请求都不能发",
        ("换了一台网关", "网关吊销了这部手机"),
    ),
    _on_real_gateway(
        "engine-does-not-persist-connection-gate", "real-gw-gate-not-persisted",
        "真网关：公钥不符、令牌被网关吊销要记进配对记录：之后新录的任务也不能再去敲网关",
        ("换了一台网关", "网关吊销了这部手机"),
    ),
    _on_real_gateway(
        "engine-does-not-park-pending-jobs", "real-gw-pending-not-parked",
        "真网关：网关关机时排队的任务全部改为等待网关，一条不丢",
        ("网关关机：",),
    ),
    _on_real_gateway(
        "engine-send-started-not-recorded-uncertain", "real-gw-send-started-not-recorded",
        "真网关：请求发出之前就要把任务记成结果不确定（先落盘后发送）",
        ("请求没送到网关",),
    ),
    _on_real_gateway(
        "engine-address-not-remembered", "real-gw-address-not-remembered",
        "真网关：网关换了地址、靠发现找到之后，新地址要记住，下次先连它而不是再去问发现",
        ("地址搬家：",),
    ),
    _on_real_gateway(
        "submission-trims-text", "real-gw-text-trimmed",
        "真网关：正文原样发送（含前后的空白与换行），网关里存的必须和手机上的逐字节一致",
        ("入库：引擎把一条 Revision 送到真实网关",),
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
        # 快照里没有 .venv（太大，不同步）：真网关测试要的 Python 解释器由真实工作树那一侧传进来
        python = ["-Pauri.python=" + os.environ["AURI_GATEWAY_PYTHON"]] if "AURI_GATEWAY_PYTHON" in os.environ else []
        proc = subprocess.run([*GRADLE_BASE, *python, *mutation.gradle], cwd=ANDROID, capture_output=True, text=True, timeout=1800)
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


def sync_snapshot(source: pathlib.Path, destination: pathlib.Path) -> None:
    """把 source 同步成 destination（逐字节校验；destination 里多出来的、被改过的都会被纠正，构建产物目录保留）。"""
    destination.mkdir(parents=True, exist_ok=True)
    command = ["rsync", "-a", "--checksum", "--delete", *(f"--exclude={name}" for name in SNAPSHOT_EXCLUDES), f"{source}/", f"{destination}/"]
    subprocess.run(command, check=True)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="只列出变异")
    parser.add_argument("--only", nargs="*", default=[], help="只跑这些 id")
    parser.add_argument("--match", help="只跑 id 匹配这个正则的变异，例如 '^(engine-|machine-)'")
    parser.add_argument("--report", type=pathlib.Path, help="把结果写成 markdown")
    parser.add_argument("--in-place", action="store_true", help="直接改本检出里的源码（只给快照里的子进程用；别在真实工作树里这样跑）")
    args = parser.parse_args(argv)
    selected = [m for m in MUTATIONS if (not args.only or m.id in args.only) and (not args.match or re.search(args.match, m.id))]
    unknown = set(args.only) - {m.id for m in MUTATIONS}
    if unknown:
        print(f"未知的变异 id：{sorted(unknown)}", file=sys.stderr)
        return 2
    if args.list:
        for m in selected:
            print(f"{m.id}\t{m.rule}")
        return 0
    if not args.in_place:
        sync_snapshot(REPO, SNAPSHOT_DIR)
        os.environ.setdefault("AURI_GATEWAY_PYTHON", str(REPO / ".venv" / "bin" / "python"))
        return subprocess.run([sys.executable, str(SNAPSHOT_DIR / SCRIPT_RELATIVE), "--in-place", *argv], check=False).returncode
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
