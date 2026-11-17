package app.auri.asr

import app.auri.asr.manifest.ModelManifest
import java.io.File
import java.security.MessageDigest

/** 单元测试用的“模型”：真实的清单、真实的文件、真实的哈希，只是内容是几 KB 的假数据。 */
class TestModel(val dir: File, val manifest: ModelManifest, val modelBytes: ByteArray, val tokensBytes: ByteArray) {
    fun modelFile() = File(dir, "model.int8.onnx")
    fun tokensFile() = File(dir, "tokens.txt")

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** 在 [dir] 里写出两个文件并返回与之完全吻合的清单。 */
        fun install(dir: File, languages: String = """["zh","en"]""", maxDurationMs: String = "30000"): TestModel {
            dir.mkdirs()
            val model = ByteArray(4096) { (it * 31 + 7).toByte() }
            val tokens = "a 0\nb 1\n".toByteArray()
            File(dir, "model.int8.onnx").writeBytes(model)
            File(dir, "tokens.txt").writeBytes(tokens)
            val json = """
                {
                  "manifest_version": 1,
                  "model_id": "test-model",
                  "model_version": "test-1",
                  "family": "sense_voice",
                  "languages": $languages,
                  "sample_rate_hz": 16000,
                  "limits": { "max_audio_duration_ms": $maxDurationMs },
                  "engine": { "name": "sherpa-onnx", "version_range": "==2.0.1" },
                  "license": {
                    "name": "test-license",
                    "source_url": "https://example.invalid/license",
                    "redistribution": "unconfirmed",
                    "note": "测试用"
                  },
                  "archive": {
                    "url": "https://example.invalid/model.tar.bz2",
                    "size_bytes": 12345,
                    "sha256": "${"b".repeat(64)}"
                  },
                  "files": [
                    { "role": "model", "path": "model.int8.onnx", "size_bytes": ${model.size}, "sha256": "${sha256Hex(model)}" },
                    { "role": "tokens", "path": "tokens.txt", "size_bytes": ${tokens.size}, "sha256": "${sha256Hex(tokens)}" }
                  ]
                }
            """.trimIndent()
            return TestModel(dir, ModelManifest.parse(json), model, tokens)
        }
    }
}
