# Android silence filtering and duration limit

All device and emulator checks below are **NOT_RUN**: deferred, emulator disabled
to save memory; no phone or headset was attached. JVM tests are E2 evidence and
cannot establish Bluetooth routing, VAD quality, background execution or timing
on a real device.

The initial VAD uses mean absolute 16-bit PCM energy of at least 300 over
10 ms windows, retaining 200 ms before and 300 ms after detected speech. The
reported speech duration includes that padding. The threshold is an uncalibrated
starting point: quiet, distant and noisy headset speech must be measured on real
hardware before making an accuracy claim. A setting of 1–120 whole minutes is
accepted; the default is 30. An active session keeps its original setting.

Verified frame offsets use the delivered PCM sample timeline from the verified
boundary. Capture duration also includes monotonic elapsed time, so missing
frame delivery cannot shorten the reported session; actual alignment under
device scheduling and dropped buffers still requires E3 validation.

After taking the shared Gradle lock, generate the four contract scenarios and
run the schema, semantic and mutation checks (JVM/Python, E2):

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :capture:test --tests app.auri.capture.contract.ContractOutputTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
cd ../..
.venv/bin/pytest execution/acceptance/android-silence/test_silence_contract_output.py
```

## Product-owner procedure (real Android phone and headset, E3)

1. Record phone model, Android version, headset model and firmware, app commit,
   microphone and notification permissions, and whether the screen is locked.
   Pair the headset, enable its microphone, and open Auri. Do not accept a phone
   microphone as a substitute for the selected headset input.
2. Start from the screen and confirm the displayed actual input is Bluetooth.
   Speak a sentence, pause for several minutes, then speak another and stop.
   Confirm that the pause never stops the session and both sentences transcribe.
   Inspect the stored duration and original gap using the metadata-only query
   below; the current home screen does not display individual segment offsets.
   Repeat with soft speech, distant speech, background noise and digital silence.
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

When the device provides `sqlite3`, inspect only the newest Record's timing and
state fields (no transcript text or identifiers):

```bash
adb shell run-as app.auri.android sqlite3 databases/auri.db 'SELECT status,end_reason,capture_duration_ms,speech_duration_ms,speech_segments,transcription,(SELECT COUNT(*) FROM revisions WHERE revisions.record_id=records.record_id) FROM records ORDER BY started_at_s DESC,started_at_nano DESC,rowid DESC LIMIT 1;'
```

If the phone does not provide `sqlite3`, leave that metadata inspection
`NOT_RUN` until an approved on-device inspector is available.

The device-only instrumented suites are also **NOT_RUN** (deferred, emulator
disabled to save memory). With a suitable device attached, take the shared Gradle
lock and then run:

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :app:connectedDebugAndroidTest :platform:connectedDebugAndroidTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```
