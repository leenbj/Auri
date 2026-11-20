package app.auri.sync.gateway

import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.Revision
import app.auri.capture.library.RevisionDraft
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.RepoContracts
import app.auri.capture.testing.Samples
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.testing.FixtureRecords
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket 21 第 7 项的前提：手机发给网关的请求体必须是合同允许的 Revision submission（revision.schema.json#/$defs/submission），
 * 否则网关会以 REQUEST_INVALID 永久拒绝，用户的文字就永远到不了。期望值取自合同样本文件（仓库 contracts/ 里的原始文件，不是拷贝）：
 * 样本说“有效”的，本地数据组出来的请求体必须与它逐字段相同；样本说“无效”而手机端又表达得出来的，构建器必须拒绝组装。
 * 真正的网关验证（网关自己的 schema 与 SR-01…06 校验）在 realgateway 测试里。
 */
class SubmissionBuilderTest {
    private val builder = SubmissionBuilder(appVersion = "0.1.0-fixture")

    private fun fixture(dir: String, name: String): JsonObject =
        RepoContracts.json("fixtures/messages/$dir/revision.submission.$name.json").jsonObject.getValue("instance").jsonObject

    private fun bodyOf(submission: RevisionSubmission): JsonObject =
        Json.parseToJsonElement(submission.body.toString(Charsets.UTF_8)).jsonObject

    private fun JsonObject.with(vararg changes: Pair<String, JsonElement>) = JsonObject(this + changes)

    /**
     * 手机本地没有的两样东西，请求体里固定成合同允许的“空”：ASR 的逐段文字、Destination 选择（Ticket 22 才有）。
     * 样本里别的平台（iOS）的录音，在这里按“这是 Android 客户端发的”改成 android——其余字段一个都不动。
     */
    private fun expectedFrom(instance: JsonObject): JsonObject {
        val capture = instance.getValue("capture").jsonObject.with("platform" to JsonPrimitive("android"))
        val content = instance.getValue("content").jsonObject.with("segments" to JsonArray(emptyList()))
        val publication = instance.getValue("publication").jsonObject.with("destination_ids" to JsonArray(emptyList()))
        return instance.with("capture" to capture, "content" to content, "publication" to publication)
    }

    @Test
    fun `只入库样本：本地数据组出来的请求体与合同样本逐字段相同`() {
        val instance = fixture("valid", "no-destination")
        val (entry, revision) = localDataFrom(instance)

        val submission = builder.build(entry, revision)

        assertNotNull("合同说有效的数据必须能组出请求", submission)
        assertEquals(expectedFrom(instance), bodyOf(submission!!))
        assertEquals("bea727a1-0f5b-5e71-b05e-61de556ac06b", submission.recordId)
        assertEquals(1, submission.revision)
        // 期望的哈希是样本文件里写死的字面值，不是用被测代码算出来的
        assertEquals("d0d776b5f83facd57fa2b9ecd4e39984745e516a4c4de0495cdb71acc28f56ec", submission.textSha256)
    }

    @Test
    fun `耳机断开被截断的样本：status 为 partial，同样组得出来`() {
        val instance = fixture("valid", "partial-route-lost")
        val (entry, revision) = localDataFrom(instance)

        val submission = builder.build(entry, revision)

        assertNotNull(submission)
        assertEquals(expectedFrom(instance), bodyOf(submission!!))
        assertEquals("partial", bodyOf(submission).getValue("capture").jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `合同里所有有效的 submission 样本，本地数据都组得出请求`() {
        val files = RepoContracts.contractsDir.resolve("fixtures/messages/valid").listFiles { f -> f.name.startsWith("revision.submission.") }!!.sortedBy { it.name }
        assertTrue("样本目录里应当有 submission 样本", files.size >= 4)
        for (file in files) {
            val instance = RepoContracts.json("fixtures/messages/valid/${file.name}").jsonObject.getValue("instance").jsonObject
            val (entry, revision) = localDataFrom(instance)
            val submission = builder.build(entry, revision)
            assertNotNull("${file.name}：合同说有效，却组不出请求", submission)
            assertEquals("${file.name} 的请求体与样本不同", expectedFrom(instance), bodyOf(submission!!))
        }
    }

    @Test
    fun `合同判为无效而手机端表达得出来的样本，构建器一个都不组`() {
        // 这些样本的每一处“错”，手机本地数据都能原样表达出来（别的样本，比如 revision 为 0 或云端执行，在本地类型上就构造不出来）
        val refused = listOf(
            "schema-invalid" to "asr-without-model",
            "schema-invalid" to "empty-text",
            "schema-invalid" to "no-speech-segments",
            "schema-invalid" to "truncated-marked-complete",
            "schema-invalid" to "uppercase-uuid",
            "semantic-invalid" to "overlapping-speech-segments",
            "semantic-invalid" to "segment-beyond-capture",
            "semantic-invalid" to "speech-duration-mismatch",
        )
        for ((dir, name) in refused) {
            val (entry, revision) = localDataFrom(fixture(dir, name))
            assertNull("$dir/$name：合同判为无效，构建器却组出了请求", builder.build(entry, revision))
        }
    }

    @Test
    fun `输入路由不是蓝牙：不组请求，不管别的字段多么完整`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        for (route in ObservedInputRouteType.entries.filter { !it.isBluetooth }) {
            assertNull("$route 不是蓝牙，不能送出去", builder.build(entry.copy(inputRouteType = route), revision))
        }
        for (route in ObservedInputRouteType.entries.filter { it.isBluetooth }) {
            assertNotNull("$route 是蓝牙，应当能组", builder.build(entry.copy(inputRouteType = route), revision))
        }
    }

    @Test
    fun `完成状态必须与结束原因一致，两个方向都核对`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        for (reason in EndReason.entries) {
            val consistent = if (reason.truncated) CaptureStatus.PARTIAL else CaptureStatus.COMPLETE
            val inconsistent = if (reason.truncated) CaptureStatus.COMPLETE else CaptureStatus.PARTIAL
            assertNotNull("$reason + $consistent 应当能组", builder.build(entry.copy(endReason = reason, status = consistent), revision))
            assertNull("$reason + $inconsistent 不一致，合同判无效", builder.build(entry.copy(endReason = reason, status = inconsistent), revision))
        }
    }

    @Test
    fun `正文原样进请求体，哈希对的是原始 UTF-8 字节：不规范化、不去空白、引号与换行照常转义`() {
        val (entry, _) = localDataFrom(fixture("valid", "no-destination"))
        // 末尾空格、制表符、换行、引号、反斜杠、组合字符（NFD 的 é）、emoji（代理对）、不间断空格——任何“顺手整理”都会改变哈希
        val text = "  先说\t“引号”，再说\\反斜杠\ncafe\u0301 \uD83D\uDE00\u00A0结尾 "
        val revision = Samples.draft(entry.recordId, text).toRevision(1)

        val submission = builder.build(entry, revision)!!

        val content = bodyOf(submission).getValue("content").jsonObject
        assertEquals(text, content.getValue("text").jsonPrimitive.content)
        assertEquals(SyncSamples.sha256Hex(text), content.getValue("text_sha256").jsonPrimitive.content)
        assertEquals(submission.textSha256, content.getValue("text_sha256").jsonPrimitive.content)
    }

    @Test
    fun `正文超过 1 MiB（按 UTF-8 字节计）不组，刚好 1 MiB 组`() {
        val (entry, _) = localDataFrom(fixture("valid", "no-destination"))
        val limit = 1_048_576
        val exactly = "a".repeat(limit)
        val tooLong = "a".repeat(limit - 1) + "好" // 字符数是 limit，字节数是 limit + 2
        assertNotNull(builder.build(entry, Samples.draft(entry.recordId, exactly).toRevision(1)))
        assertNull(builder.build(entry, Samples.draft(entry.recordId, tooLong).toRevision(1)))
        assertNull(builder.build(entry, Samples.draft(entry.recordId, "a".repeat(limit + 1)).toRevision(1)))
    }

    @Test
    fun `来源是转录就必须写明引擎与模型，用户修订可以不写`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        fun draftWith(source: RevisionSource, engine: String?, modelId: String?, modelVersion: String?) =
            RevisionDraft(entry.recordId, "文字", source, "zh-CN", ReviewState.NOT_REVIEWED, ExecutionLocation.ON_DEVICE, engine, modelId, modelVersion, null, revision.createdAt).toRevision(1)

        assertNull(builder.build(entry, draftWith(RevisionSource.ASR, null, "m", "1")))
        assertNull(builder.build(entry, draftWith(RevisionSource.ASR, "e", null, "1")))
        assertNull(builder.build(entry, draftWith(RevisionSource.ASR, "e", "m", null)))
        assertNull(builder.build(entry, draftWith(RevisionSource.ASR, "", "m", "1")))
        assertNotNull(builder.build(entry, draftWith(RevisionSource.ASR, "e", "m", "1")))
        assertNotNull(builder.build(entry, draftWith(RevisionSource.USER_EDIT, null, null, null)))
    }

    @Test
    fun `语言、置信度和各字段的长度上限都按合同核对`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        fun with(
            language: String = revision.language,
            confidence: Double? = revision.confidence,
            engine: String? = revision.engine,
            modelId: String? = revision.modelId,
            modelVersion: String? = revision.modelVersion,
        ) = RevisionDraft(entry.recordId, revision.text, revision.source, language, revision.reviewState, revision.executionLocation, engine, modelId, modelVersion, confidence, revision.createdAt).toRevision(1)

        for (good in listOf("zh", "zh-CN", "en", "yue", "zh-Hans-CN")) assertNotNull("语言 $good", builder.build(entry, with(language = good)))
        for (bad in listOf("", "Zh", "zhhh", "z", "zh_CN", "zh-", "zh-C", "中文", "zh cn")) assertNull("语言 \"$bad\" 不合规", builder.build(entry, with(language = bad)))

        for (good in listOf(0.0, 0.5, 1.0)) assertNotNull(builder.build(entry, with(confidence = good)))
        for (bad in listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) assertNull("置信度 $bad", builder.build(entry, with(confidence = bad)))
        assertEquals(0.5, bodyOf(builder.build(entry, with(confidence = 0.5))!!).getValue("content").jsonObject.getValue("confidence").jsonPrimitive.double, 0.0)

        assertNotNull(builder.build(entry, with(engine = "e".repeat(100), modelId = "m".repeat(200), modelVersion = "v".repeat(200))))
        assertNull(builder.build(entry, with(engine = "e".repeat(101))))
        assertNull(builder.build(entry, with(modelId = "m".repeat(201))))
        assertNull(builder.build(entry, with(modelVersion = "v".repeat(201))))
    }

    @Test
    fun `耳机名按字符数（不是 UTF-16 单元）限 200：太长就不报耳机名，其余照常提交`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        fun capture(name: String?) = bodyOf(builder.build(entry.copy(headsetDisplayName = name), revision)!!).getValue("capture").jsonObject

        assertEquals(JsonPrimitive("耳".repeat(200)), capture("耳".repeat(200)).getValue("headset_display_name"))
        // 200 个 emoji = 400 个 UTF-16 单元，合同按字符计，所以合法
        assertEquals(JsonPrimitive("\uD83D\uDE00".repeat(200)), capture("\uD83D\uDE00".repeat(200)).getValue("headset_display_name"))
        assertEquals(JsonNull, capture("耳".repeat(201)).getValue("headset_display_name"))
        assertEquals(JsonNull, capture(null).getValue("headset_display_name"))
        // 系统不暴露固件版本，合同要求是 null，不得猜
        assertEquals(JsonNull, capture("x").getValue("headset_firmware"))
    }

    @Test
    fun `时间戳按带时区的 ISO 8601 写出，带小数秒也合法`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        val pattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$")
        for (at in listOf(Instant.parse("2026-11-15T02:30:00Z"), Instant.parse("2026-11-15T02:30:00.5Z"), Instant.parse("2026-11-15T02:30:00.123456789Z"))) {
            val written = bodyOf(builder.build(entry.copy(startedAt = at), revision)!!).getValue("capture").jsonObject.getValue("started_at").jsonPrimitive.content
            assertTrue("$written 不符合合同的时间戳格式", pattern.matches(written))
            assertEquals(at, Instant.parse(written))
        }
    }

    @Test
    fun `应用版本不合规时构造就失败，不会静默发出被网关拒绝的请求`() {
        for (bad in listOf("", "v".repeat(65))) {
            try {
                SubmissionBuilder(bad)
                fail("app_version \"${bad.take(10)}…\" 应当被拒绝")
            } catch (expected: IllegalArgumentException) {
                // 对的
            }
        }
        SubmissionBuilder("v".repeat(64))
    }

    @Test
    fun `请求体里没有 tenant、agent 之类网关不信的自报身份，也没有合同之外的字段`() {
        val (entry, revision) = localDataFrom(fixture("valid", "no-destination"))
        val body = bodyOf(builder.build(entry, revision)!!)
        // 允许的字段集合来自合同 schema 的 required（这些 schema 都是 additionalProperties: false）
        val defs = RepoContracts.json("schemas/revision.schema.json").jsonObject.getValue("\$defs").jsonObject
        fun required(def: String) = defs.getValue(def).jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(required("submission"), body.keys)
        assertEquals(required("capture"), body.getValue("capture").jsonObject.keys)
        assertEquals(required("content"), body.getValue("content").jsonObject.keys)
        assertEquals(required("publication"), body.getValue("publication").jsonObject.keys)
        assertEquals("context", body.getValue("publication").jsonObject.getValue("intent").jsonPrimitive.content)
    }

    @Test
    fun `从本地 Record 库读：库里有就组，没有这条 Record 或没有这一版就是 null`() = runBlocking {
        val library = InMemoryRecordLibrary()
        val source = RecordLibrarySubmissionSource(library, builder)
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val published = library.publishRevision(Samples.draft(entry.recordId, "第一版"))

        val found = source.submissionFor(JobKey(entry.recordId, published.number))
        assertNotNull(found)
        assertEquals(builder.build(library.get(entry.recordId)!!, published)!!.textSha256, found!!.textSha256)

        assertNull("没有这条 Record", source.submissionFor(JobKey(Samples.uuid(99), 1)))
        assertNull("没有这一版", source.submissionFor(JobKey(entry.recordId, 2)))
        // 本地数据不合规的，同样是 null（引擎把它记成“本机数据读不到”，不会发出去）
        library.addRecord(Samples.entry(2).copy(inputRouteType = ObservedInputRouteType.BUILT_IN_MIC))
        val bad = library.publishRevision(Samples.draft(Samples.uuid(2), "手机麦克风录的"))
        assertNull(source.submissionFor(JobKey(Samples.uuid(2), bad.number)))
        Unit
    }

    // ---- Ticket 22：publication 由调用方给（意图 + 目标），不再写死成“只入库” ----

    /** 只把手机本地没有的两样东西（别的平台、ASR 逐段文字）改成合同允许的样子；publication 保持样本里写的。 */
    private fun expectedKeepingPublication(instance: JsonObject): JsonObject {
        val capture = instance.getValue("capture").jsonObject.with("platform" to JsonPrimitive("android"))
        val content = instance.getValue("content").jsonObject.with("segments" to JsonArray(emptyList()))
        return instance.with("capture" to capture, "content" to content)
    }

    private fun publicationOf(instance: JsonObject): Publication {
        val publication = instance.getValue("publication").jsonObject
        return Publication(
            Intent.fromWire(publication.getValue("intent").jsonPrimitive.content)!!,
            publication.getValue("destination_ids").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `带目标的样本：把样本里的 publication 交给构建器，请求体与合同样本逐字段相同（含自动发送初稿的 not_reviewed）`() {
        for (name in listOf("asr-first-revision", "ios-volume-gesture-stop", "partial-route-lost")) {
            val instance = fixture("valid", name)
            val (entry, revision) = localDataFrom(instance)

            val submission = builder.build(entry, revision, publicationOf(instance))

            assertNotNull("$name：合同说有效，却组不出请求", submission)
            assertEquals("$name 的请求体与样本不同", expectedKeepingPublication(instance), bodyOf(submission!!))
            assertEquals("$name：自动发送的初稿必须是未审核", "not_reviewed", bodyOf(submission).getValue("content").jsonObject.getValue("review_state").jsonPrimitive.content)
        }
    }

    @Test
    fun `意图标签只改 publication 的 intent：请求体其余部分一模一样（请求执行只是数据上的标签，不授予任何额外的东西）`() {
        val instance = fixture("valid", "asr-first-revision")
        val (entry, revision) = localDataFrom(instance)
        val ids = publicationOf(instance).destinationIds

        val memo = bodyOf(builder.build(entry, revision, Publication(Intent.CONTEXT, ids))!!)
        val action = bodyOf(builder.build(entry, revision, Publication(Intent.ACTION_REQUEST, ids))!!)

        assertEquals("context", memo.getValue("publication").jsonObject.getValue("intent").jsonPrimitive.content)
        assertEquals("action_request", action.getValue("publication").jsonObject.getValue("intent").jsonPrimitive.content)
        assertEquals(memo.getValue("publication").jsonObject.getValue("destination_ids"), action.getValue("publication").jsonObject.getValue("destination_ids"))
        assertEquals(JsonObject(memo - "publication"), JsonObject(action - "publication"))
    }

    @Test
    fun `不给 publication 就是只入库：忘了传只会少发，不会多发`() {
        val (entry, revision) = localDataFrom(fixture("valid", "asr-first-revision"))

        val body = bodyOf(builder.build(entry, revision)!!)

        assertEquals("context", body.getValue("publication").jsonObject.getValue("intent").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), body.getValue("publication").jsonObject.getValue("destination_ids"))
    }

    @Test
    fun `publication 本身守合同：最多 50 个目标、不许重复、必须是小写 UUID`() {
        val one = "65ea2953-505c-5c6f-9474-7cd5b8474104"
        fun ids(n: Int) = List(n) { "d0000000-0000-4000-8000-%012x".format(it) }

        Publication(Intent.CONTEXT, ids(Publication.MAX_DESTINATIONS))
        for (bad in listOf(ids(Publication.MAX_DESTINATIONS + 1), listOf(one, one), listOf(one.uppercase()), listOf("not-a-uuid"))) {
            assertTrue("应当被拒绝：${bad.size} 个", runCatching { Publication(Intent.CONTEXT, bad) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `发布时的 publication 每次发送都现问发送账：同一条文字，目标变了请求就变了`() = runBlocking {
        val library = InMemoryRecordLibrary()
        var targets = listOf("65ea2953-505c-5c6f-9474-7cd5b8474104")
        val source = RecordLibrarySubmissionSource(library, builder) { Publication(Intent.CONTEXT, targets) }
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val published = library.publishRevision(Samples.draft(entry.recordId, "第一版"))
        val key = JobKey(entry.recordId, published.number)

        val first = bodyOf(source.submissionFor(key)!!).getValue("publication").jsonObject.getValue("destination_ids")
        targets = emptyList() // 排队期间撤销了
        val second = bodyOf(source.submissionFor(key)!!).getValue("publication").jsonObject.getValue("destination_ids")

        assertEquals(JsonArray(listOf(JsonPrimitive("65ea2953-505c-5c6f-9474-7cd5b8474104"))), first)
        assertEquals(JsonArray(emptyList()), second)
    }

    // 把合同样本里的值还原成手机本地数据：与真实网关测试共用（testFixtures 里的 FixtureRecords）
    private fun localDataFrom(instance: JsonObject): Pair<RecordEntry, Revision> = FixtureRecords.localDataFrom(instance)
}
