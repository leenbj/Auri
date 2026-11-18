package app.auri.platform.system

import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.IdGenerator
import java.time.Instant
import java.util.UUID

object SystemClock : Clock {
    override fun monotonicNs(): Long = System.nanoTime()

    override fun now(): Instant = Instant.now()
}

object RandomIds : IdGenerator {
    override fun newUuid(): String = UUID.randomUUID().toString()
}
