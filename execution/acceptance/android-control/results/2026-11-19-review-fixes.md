# Ticket 17 review fixes: verification

Branch: `ticket/17-headset-control`. The product-code checks below exercised `53d3302`, which merges `origin/integration/auri-v2` at `7f9a85e422d4` and keeps both model management and headset control accessible. All automated evidence is E2; no real phone or headset was available.

**Overall task status: incomplete.** The requested F1–F9 fixes and their tests are complete, but the literal requirement to rerun every historical mutation cannot be proven because several earlier one-off mutation definitions were not retained. Real-device checks are also NOT_RUN, as specified below.

| Finding | Test observed failing before the fix | Fix and passing evidence |
|---|---|---|
| F1 | `HomeFlowTest` manual mode-off case: 1/6 failed because the media source activated. | `CaptureCoordinator.kt`, `CoordinatorTypes.kt`, and `HomeViewModel.kt` separate manual capture from Control Mode. The real coordinator test verifies manual start/stop followed by a key does not start another segment; the mode-on control remains armed. App JVM suite: 110 passed, E2. |
| F2 | `HeadsetControlChainTest`: delayed START/TOGGLE and untrustworthy times failed, 2/13. | `HeadsetEventNormalizer.kt` rejects invalid or expired start/toggle times and preserves plausible event times; the coordinator also rejects expired queued commands. The chain verifies delayed START, TOGGLE and STOP. Platform JVM suite: 177 passed, E2. |
| F3 | `ControlViewModelTest`: 1/37 failed because the pending indicator cleared at ARMED before disable completed. | `CaptureCoordinator.kt` serializes stop and disable and rejects new starts while closing; `ControlViewModel.kt` waits for a newer DISARMED snapshot. Controllable fake and real-chain tests pass, E2. |
| F4 | `HeadsetControlChainTest`: 1/15 failed because a distinct cancellation inside 400 ms was dropped. | `HeadsetEventNormalizer.kt` compares key kind, input-device ID and platform time to deduplicate; STOP, PAUSE and a distinct TOGGLE cancel routing. Capture and platform JVM suites pass, E2. |
| F5 | `FeedbackNotificationsTest`: 1/1 failed with a previously audible channel; the injected gateway preserved the old behavior before the fix. | `AndroidFeedbackPlayer.kt` deletes the old channel, creates a new low-importance silent channel, mutes each notification through Android group-alert behavior, and sets only-alert-once. Fake-channel tests pass; API 35 emulator verified the old audible channel is removed and the posted notification has the mute behavior, E2. |
| F6 | `MediaKeysTest`: 1/9 failed because an external keyboard was called a headset; `CalibrationFindingsTest`: 1/60 failed because an unknown remote certified the headset. A separate red assertion exposed the missing unattributed-event reason. | Android system media keys remain UNKNOWN_REMOTE unless the selected Bluetooth headset can be attributed; `Observation.kt` and `CalibrationFindings.kt` do not credit unknown keys. The wizard and results page display the unattributed count/reason. Capture, platform and app JVM suites pass, E2. |
| F7 | `ScreenLockEvidenceTest`: 1/2 failed because screen-off without keyguard was called locked. | `AndroidScreenLockMonitor.kt` reports screen-off alone as unknown. The JVM test also verifies no locked-survival claim; the API 35 monitor test compares the actual keyguard state, E2. |
| F8 | `CalibrationFindingsTest`: 3/61 failed because a first locked start or a later locked stop was treated as a locked restart. | `CalibrationFindings.kt` requires the relevant headset stop while locked and its finalization before a successful locked start. The corrected negative tests and existing positive path pass, E2. |
| F9 | `NoSoundIntoBluetoothTest`: 1/2 failed to reject whitespace between `setSound` and `(`. | The guard accepts whitespace/newlines before the parenthesis and its negative controls cover both forms; the explicit `(null, null)` exception remains covered. Both tests pass, E2. |

JVM limits: F2 injects a fake media source through the real platform-key conversion, normalizer, adapter and coordinator; the JVM cannot exercise Android's actual `MediaSession` delivery and the emulator separately measures that delivery. F5's preconfigured audible channel is a JVM fake, followed by an Android-framework notification test on the emulator. F6's JVM input models a USB-like external device; it cannot prove a real hardware bus or identity, so the Android source conservatively attributes no system media key to a selected headset.

For F5, the native `Notification.Builder` in the installed API 35 SDK has no `setSilent` method; the per-instance mute is its `GROUP_ALERT_SUMMARY` flag on a child notification, together with `setOnlyAlertOnce`. The emulator verified the posted object's group behavior, its null sound, and its new silent channel after deleting a previously audible channel. It ran with `-no-audio`, so this is framework-state evidence, not an acoustic measurement on a Bluetooth headset.

The single Gradle command prefix was `source ~/.config/auri/android-env.sh && cd apps/android && ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`.

| Fresh suite | Exact task or command | Result |
|---|---|---|
| Capture JVM | `:capture:cleanTest :capture:test` | 330 passed, 0 failed, 0 skipped |
| Platform JVM | `:platform:cleanTestDebugUnitTest :platform:testDebugUnitTest` | 177 passed, 0 failed, 0 skipped |
| App JVM | `:app:cleanTestDebugUnitTest :app:testDebugUnitTest` | 110 passed, 0 failed, 0 skipped |
| ASR JVM | `:asr:cleanTestDebugUnitTest :asr:testDebugUnitTest` | 112 passed, 0 failed, 0 skipped |
| Model management JVM | `:modelmgmt:cleanTestDebugUnitTest :modelmgmt:testDebugUnitTest` | 84 passed, 0 failed, 0 skipped |
| Instrumentation APKs | `:app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| API 35 arm64 emulator | `AURI_SKIP_BUILD=1 AURI_DEVICE_OUT=/tmp/auri-ticket17-review-final AURI_AVD_NAME=auri-t17-review-final AURI_GRADLE_ARGS='--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2' execution/acceptance/android-control/run_emulator_acceptance.sh` after sourcing Android env, run from a disposable archive with `PYTHON` and the three baseline APK paths set to the worktree | Platform 12/12, app 2/2; the checker verified every test ran. Measured: no playback, 4 injected media keys, 0 deliveries; E2 only. A second emulator run repeated 12/12 and 2/2 while checking D2/D3. |
| Android control Python | `AURI_GRADLE_ARGS='--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2' .venv/bin/pytest execution/acceptance/android-control --basetemp=/tmp/auri-ticket17-acceptance` after sourcing Android env | 106 passed, 0 failed, 0 skipped in 56.81 s |
| Full repo Python | `.venv/bin/pytest tests/contract tests/integration tests/security --basetemp=/tmp/auri-ticket17-repo` | 492 passed, 0 failed, 0 skipped in 192.24 s |

The coordinator clarified the shared-machine guard: wait when free memory is below 20% or swap grows by more than 500 MiB over 60 seconds, rather than on historical swap use alone. The first readings were 3301.25 then 3293.25 MiB used with 64–65% free. An earlier emulator run increased swap to 4358.50 MiB; after 60 seconds it fell to 4310.50 MiB with 58% free before the next Gradle-bearing suite. After the post-merge emulator run the host reported 3958.44 MiB used and 55% free; before the post-merge acceptance suite it reported 3942.44 MiB used and 60% free.

Ticket checklist coverage (the Chinese ticket keeps its boxes unchecked; these are the observed results):

| Checklist item | Evidence |
|---|---|
| Media keys cannot start capture with Control Mode off | `HomeFlowTest` and `HeadsetControlChainTest`; `HeadsetMediaKeyOnDeviceTest` and `ControlWiringOnDeviceTest`, E2. |
| Normalized events carry source/sequence and one duplicate gesture cannot start and immediately stop | `HeadsetEventNormalizerTest`, `CompositeControlAdapterTest`, `HeadsetControlChainTest`, E2. Distinct platform events from the same physical gesture still require E3 measurement. |
| A cancel reaches routing and a stale toggle during finalizing cannot start the next segment | `HeadsetControlChainTest` covers both and the over-five-second delivery case, E2. |
| Calibration covers idle, recording, restart and lock | `CalibrationFindingsTest` and `ControlViewModelTest` exercise all four steps, E2; real lock-screen behavior is NOT_RUN. |
| Profile validates and untested capabilities stay untested | `ControlContractOutputTest`, `HeadsetProfileContractTest`, `control_contract_check.py`, and `CalibrationFindingsTest`, E2; unattributed-event reason cannot be persisted under the existing schema. |
| Distinct start, stop and refusal feedback; manual controls remain available | `FeedbackHubTest`, `FeedbackPlayerOnDeviceTest`, and `HomeViewModelTest`, E2; subjective haptic feel is NOT_RUN. |
| Complete calibration and record using a real headset | NOT_RUN: no Android phone or FreeClip is attached; the owner procedure is in the acceptance README. |

The post-merge JVM mutation run applied each change separately in a disposable `git archive` of `53d3302`, restored the source after each case, and ran the named test class with fresh JUnit XML. **77/77 reconstructed and new mutants were killed by the intended assertion**; a build failure or an unrelated failure was not counted. The exact runner is `execution/acceptance/android-control/review_mutations.py`, invoked with the Android environment sourced and Python 3.12; it refuses to mutate a worktree. The new cases are F1, F2, F3, F4, F5, F6-source, F6-evidence, F6-lock, F7, F8 and F9, each tied to the corresponding regression test in the runner. All are E2.

The complete post-merge JVM mutation invocation, run at the worktree root, was:

```bash
source ~/.config/auri/android-env.sh && .venv/bin/python - <<'PY'
import pathlib,subprocess,sys,tarfile,tempfile
with tempfile.TemporaryDirectory(prefix='auri-ticket17-mutations-final-',dir='/tmp') as directory:
    print('Disposable archive:',directory,flush=True)
    archive=subprocess.Popen(['git','archive','HEAD'],stdout=subprocess.PIPE)
    with tarfile.open(fileobj=archive.stdout,mode='r|') as source: source.extractall(directory,filter='data')
    if archive.wait()!=0: raise SystemExit('git archive failed')
    script=pathlib.Path(directory)/'execution/acceptance/android-control/review_mutations.py'
    with open('/tmp/auri-ticket17-mutants-final.log','w') as log:
        process=subprocess.Popen([sys.executable,str(script)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        for line in process.stdout:
            print(line,end='',flush=True); log.write(line); log.flush()
        code=process.wait()
    raise SystemExit(code)
PY
```

The three documented device mutants were reconstructed after the merge, each with a separate `./gradlew --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :platform:assembleDebugAndroidTest` in an archive. D1 (do not release the session), D2 (claim PLAYING), and D3 (do not activate it) were each caught by the named `HeadsetMediaKeyOnDeviceTest` assertion on API 35, E2. A temporary wrapper initially classified D2's JUnit `ComparisonFailure` incorrectly even though the assertion failed; a corrected wrapper reran D2 and D3 successfully. Each emulator was stopped and its AVD and lock removed.

For each mutated APK, the wrapper used `adb install -r -t <mutant-platform-test.apk>`, then `adb shell am instrument -w -r -e class app.auri.platform.control.HeadsetMediaKeyOnDeviceTest#<method> app.auri.platform.test/androidx.test.runner.AndroidJUnitRunner`. The method was `sessionExistsInTheSystemOnlyWhileControlModeIsOn` for D1 and D3 and `sessionDeclaresPausedNeverPlayingBecauseNothingPlays` for D2; each run had exactly one failure with the intended test name and an assertion or JUnit comparison failure. The script first ran the unchanged baseline APKs using the normal acceptance script; it then installed the mutant APKs sequentially on the same locked emulator.

The literal requirement to rerun every old mutation cannot be verified from this repository: the earlier scripts were not retained, and the groups described only as 22 `ControlViewModel`, 5 `FeedbackHub`, 8 emulator-detection, 10 emulator-script, 7 media-session-dump and 29 Python-checker mutations have no individual mutation definitions in the record. This report does not count their old results as new runs. The old K4 broad time-window suppression conflicts with F4's requirement to admit an independently issued cancellation; exact duplicates are instead checked by K7, and F4 recreates the harmful broad window. K9's stale broad-window behavior is likewise covered by F4. For S7, F8 now requires a locked stop followed by `FINALIZE_COMPLETE` before a locked restart, so initializing the earlier global finalization flag to true has no effect on that formerly targeted locked-restart verdict; the capture state machine also cannot successfully restart a foreground segment during finalization. The old R10 UI retry loop was replaced by F3's serialized coordinator shutdown. Earlier Q4/Q9/Q11–Q15 correspond to reconstructed N8/N7/N9/N10/N1+N1b/N3/N2 respectively. These replacements are not claims that the unretained original scripts ran unchanged.

Assumptions and gaps: Android 31–35 does not expose a public verified Bluetooth address on `InputDevice`, so system media events are conservatively UNKNOWN_REMOTE and cannot certify headset gestures until reliable selected-device attribution exists. The Headset Profile capability schema has no reason field; the reason is shown during this calibration run but cannot be persisted in the contract profile. Distinct platform events are not merged solely on timing, so a headset producing several different platform events for one gesture needs real-device measurement. Real FreeClip behavior, subjective haptic feel, and real lock-screen behavior are NOT_RUN. The API 35 emulator previously delivered 0/6 media-key cases without playback, and this run delivered 0/4 injected keys; silent keep-alive is forbidden and no workaround is claimed.

Cleanup: created and removed the three throwaway AVDs (`auri-t17-review`, `auri-t17-review-final`, and `auri-t17-review-retry`), each emulator lock and its owner file, the adb server started by the scripts, disposable `/tmp/auri-ticket17-mutations-*` and `/tmp/auri-ticket17-device-*` archives, the mutation log, the three emulator output directories, and both pytest basetemps. No emulator, adb server, or Gradle process from these runs remains. The pre-existing `.serena/` directory, the branch, and the worktree were left in place.
