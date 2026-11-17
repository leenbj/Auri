package app.auri.probe.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @Test
    fun redactsMacAddressesAndSensitiveDeviceFieldsWhilePreservingProbeSignals() {
        val rawLine = """{"kind":"route_observed","mono_ns":123456,"route_type":"bluetooth_hfp","address":"AA:BB:CC:DD:EE:FF","product_name":"Ethan's FreeClip 2","device_name":"Pixel 9 Pro","verification_state":"verified"}"""

        val redacted = LogRedactor.redactLine(rawLine)

        assertFalse(redacted.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(redacted.contains("Ethan's FreeClip 2"))
        assertFalse(redacted.contains("Pixel 9 Pro"))
        assertTrue(redacted.contains(""""kind":"route_observed""""))
        assertTrue(redacted.contains(""""mono_ns":123456"""))
        assertTrue(redacted.contains(""""route_type":"bluetooth_hfp""""))
        assertTrue(redacted.contains(""""verification_state":"verified""""))
        assertTrue(redacted.contains(""""address":"<REDACTED>""""))
        assertTrue(redacted.contains(""""product_name":"<REDACTED>""""))
        assertTrue(redacted.contains(""""device_name":"<REDACTED>""""))
    }

    @Test
    fun redactsInlineMacAddressesInsideErrorOrDescriptorStrings() {
        val rawLine = """{"kind":"media_button","descriptor":"bt-dev-11:22:33:44:55:66","error":"failed on aa-bb-cc-dd-ee-ff"}"""

        val redacted = LogRedactor.redactLine(rawLine)

        assertFalse(redacted.contains("11:22:33:44:55:66"))
        assertFalse(redacted.contains("aa-bb-cc-dd-ee-ff"))
        assertTrue(redacted.contains("<REDACTED_MAC>"))
    }

    @Test
    fun redactsTranscriptOrAudioFieldsIfAccidentallyPresent() {
        val rawLine = """{"kind":"unexpected","transcript":"secret dictation","text":"hello","audio_base64":"AAAA"}"""

        val redacted = LogRedactor.redactLine(rawLine)

        assertFalse(redacted.contains("secret dictation"))
        assertFalse(redacted.contains("hello"))
        assertFalse(redacted.contains("AAAA"))
        assertTrue(redacted.contains(""""transcript":"<REDACTED>""""))
        assertTrue(redacted.contains(""""text":"<REDACTED>""""))
        assertTrue(redacted.contains(""""audio_base64":"<REDACTED>""""))
    }
}
