# Android silence filtering and duration limit

All device and emulator checks below are **NOT_RUN**: deferred, emulator disabled
to save memory; no phone or headset was attached. JVM tests are E2 evidence and
cannot establish Bluetooth routing, VAD quality, background execution or timing
on a real device.

## Product-owner procedure (real Android phone and headset, E3)

1. Record phone model, Android version, headset model and firmware, app commit,
   microphone and notification permissions, and whether the screen is locked.
   Pair the headset, enable its microphone, and open Auri. Do not accept a phone
   microphone as a substitute for the selected headset input.
2. Start from the screen and confirm the displayed actual input is Bluetooth.
   Speak a sentence, pause for several minutes, then speak another and stop.
   Confirm that the pause never stops the session, both sentences transcribe,
   and the Record reports the elapsed duration and the original gap. Repeat
   with soft speech, distant speech, background noise and digital silence.
3. Start again, stay silent, then stop. Confirm one Record labelled `无语音`, no
   Revision, no transcription job, and no retained audio. Try starting and
   stopping with the calibrated headset gesture as well as the screen control.
4. Set a short supported duration limit. Verify the warning appears one minute
   before the deadline, including the existing vibration/notification feedback;
   keep recording until it stops, and confirm the saved Record has end reason
   `duration_limit` and no new session starts. Repeat with a long real recording
   and confirm all retained speech is transcribed in order.
5. Repeat the long-pause and limit checks with the screen locked. During separate
   sessions disconnect the headset, revoke microphone permission, and trigger a
   system interruption; confirm the retained audio is sealed as partial with the
   corresponding reason and there is no automatic restart or phone-mic audio.
6. Record observations and failures without copying personal audio, transcript
   text, device identifiers or credentials into the repository.

The device-only instrumented suites are also **NOT_RUN** (deferred, emulator
disabled to save memory). With a suitable device attached, take the shared Gradle
lock and then run:

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :app:connectedDebugAndroidTest :platform:connectedDebugAndroidTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```
