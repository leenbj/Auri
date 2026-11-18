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
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parents[3]
ANDROID = REPO / "apps" / "android"
GRADLE_BASE = ["./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process"]
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
STATUS_TESTS = (":sync:test", "--tests", "*SyncStatusTest*")
LOOP_TESTS = (":sync:test", "--tests", "*SyncLoopTest*")

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
        (Edit(ENGINE, "revisions.any { it.revision == revision && it.textSha256 == textSha256 }", "revisions.any { it.revision == revision }"),),
        ENGINE_TESTS, SYNC_TEST[0],
        ("网关上同一版本号是另一份正文",),
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
        (Edit(ENGINE, "        pairing.update { current -> if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null }\n", ""),),
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
        (Edit(LOOP, "!initial -> wake.trySend(Unit)", "!initial -> Unit"),),
        LOOP_TESTS, SYNC_TEST[0],
        ("网络恢复就跑一轮",),
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
        (Edit(LOOP, "log.event(\"loop.$name.failed ${failure::class.simpleName}\")", "throw failure"),),
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
