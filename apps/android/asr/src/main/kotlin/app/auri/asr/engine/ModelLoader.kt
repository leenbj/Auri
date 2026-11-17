package app.auri.asr.engine

import app.auri.asr.manifest.ModelManifest
import app.auri.asr.manifest.ModelVerifier
import java.io.File

/** 先校验、后建引擎。校验抛出的 [app.auri.asr.manifest.ModelRefusedException] 不会被吞掉，工厂此时一次都没被调用。 */
object ModelLoader {
    fun load(
        manifest: ModelManifest,
        modelDir: File,
        factory: AsrEngineFactory,
        verifier: ModelVerifier = ModelVerifier(),
    ): AsrEngine = factory.create(verifier.verify(manifest, modelDir))
}
