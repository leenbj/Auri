# Ticket 18 Android silence and limits — verification

Branch: `ticket/18-silence-and-limits`. Host verification is **INCOMPLETE**: all five Gradle JVM suites, the debug build, and the contract/integration/security suite passed, but 22 acceptance cases requiring a Gradle subprocess inside pytest remain NOT_RUN under the limit of one Gradle or pytest process at a time. Device evidence remains NOT_RUN as described below.

## Checklist and evidence

| Requirement | Evidence | Level |
| --- | --- | --- |
| Filter silence, retain padded speech and original ranges | `SilenceFilteringTest` verifies a six-second mixed capture, two ranges, stored samples and each chunk SHA-256; `ContractOutputTest` exports actual results. | E2 JVM |
| Pauses never stop recording | `SilenceFilteringTest` holds a session open through 90 seconds of silence; a deliberate stop-on-silence mutation caused this test to fail. | E2 JVM |
| VAD only after verified route | `SilenceFilteringTest` injects a VAD spy and proves quarantine and wrong-epoch frames never reach it; `RouteBoundaryTest` checks the Android adapter boundary. | E2 JVM |
| No-speech Record without Revision or transcription | `SilenceFilteringTest` connects the real coordinator and Record pipeline; `HomeViewModelTest` and `HomeScreen` map `NO_SPEECH` to `无语音`. | E2 JVM |
| Persisted 30-minute default, maximum 120, warning and auto-stop | `DurationLimitSettingTest`, `CaptureConfigTest`, `HomeViewModelTest`, and `SilenceFilteringTest` cover validation, persistence, visible warning, feedback, automatic stop and no automatic restart. | E2 JVM |
| End reasons | `CaptureResultTest` exercises screen, headset, volume, duration, route, permission and system interruption; `CaptureStalenessTest` covers process restoration. | E2 JVM |
| Contract and semantics | `ContractOutputTest` writes speech, mixed, no-speech and limit results; `test_silence_contract_output.py` uses the existing schema/semantic checker and rejects an SR-04 mutation. | E2 JVM/Python |

The former 29.5-second capture cap was removed as a **requirement change**. Tests that encoded that interim cap now assert the 30-minute default or an explicit independent test limit. The local adapter streams long WAV recordings in windows no longer than the engine's declared limit, without creating a partial Revision when a later window fails.

Real headset, long real recording, lock screen, process death and device-only instrumented checks: **NOT_RUN (reason: deferred, emulator disabled to save memory)**. The exact device command and product-owner procedure are in [README.md](README.md). No emulator, AVD, simulator or instrumented test was started.

## Fresh verification (2026-11-19, UTC)

The branch already contained the fetched `origin/integration/auri-v2` tip. Each Gradle task below was run separately with the shared lock and passed; the successful test runs had zero failures and zero skips. The initial app run had 110 passes and one failure: an existing test still expected three distinct feedback messages after the duration warning added a fourth. Its assertion now requires four distinct messages, preserving the original checks, and the fresh app rerun passed all 111. The combined acceptance collection initially failed because two test files were named `test_contract_output.py`; renaming the ticket's file preserved every test and corrected collection from 297 items plus one error to 300 items with no errors.

| Gradle task | Fresh result |
| --- | --- |
| `:capture:test` | 339 passed |
| `:modelmgmt:testDebugUnitTest` | 84 passed |
| `:platform:testDebugUnitTest` | 183 passed |
| `:app:testDebugUnitTest` | 111 passed after the test fix |
| `:asr:testDebugUnitTest` | 112 passed |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

Exact Gradle commands, each run separately after taking `~/.cache/auri/gradle.lock`, recording memory, and sourcing `~/.config/auri/android-env.sh`:

```bash
cd apps/android
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :capture:test --rerun
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :modelmgmt:testDebugUnitTest --rerun
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :platform:testDebugUnitTest --rerun
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :app:testDebugUnitTest --rerun
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :asr:testDebugUnitTest --rerun
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2 :app:assembleDebug --rerun
```

`.venv/bin/pytest tests/contract tests/integration tests/security` passed **492 tests**. The device-free acceptance selection passed **278 tests** with six deselected and 16 additional cases in three ignored files; these **22 NOT_RUN** cases launch real Gradle processes from pytest, conflicting with the binding limit of one Gradle or pytest process at a time. The same selection passed again after the filename fix. Running the complete acceptance command later requires a decision on that process restriction and per-invocation Gradle locking and memory checks:

```bash
.venv/bin/pytest execution/acceptance/android-silence execution/acceptance/android-capture execution/acceptance/android-control execution/acceptance/android-models execution/acceptance/asr-android
```

The actual acceptance selection command was:

```bash
.venv/bin/pytest execution/acceptance/android-silence execution/acceptance/android-capture execution/acceptance/android-control execution/acceptance/android-models execution/acceptance/asr-android \
  --ignore execution/acceptance/android-capture/test_capture_contract_output.py \
  --ignore execution/acceptance/android-control/test_control_contract_output.py \
  --ignore execution/acceptance/asr-android/test_contract_output.py \
  --deselect execution/acceptance/asr-android/test_engine_pin_guard.py::test_build_refuses_an_aar_with_one_flipped_byte \
  --deselect execution/acceptance/asr-android/test_engine_pin_guard.py::test_build_refuses_a_truncated_aar \
  --deselect execution/acceptance/asr-android/test_engine_pin_guard.py::test_build_accepts_exactly_the_pinned_aar \
  --deselect execution/acceptance/asr-android/test_engine_pin_guard.py::test_missing_aar_is_not_silently_replaced_when_offline \
  --deselect execution/acceptance/asr-android/test_engine_pin_guard.py::test_every_module_build_runs_the_aar_check_first
```

For collection only, the complete command above plus `--collect-only` was run before and after the rename. It never executed the cases. An attempted selection rerun was interrupted by the monitor's history-gap guard; it was not a test failure. The guard was corrected and the selection rerun.

Every real-device, simulator, and instrumented check is **NOT_RUN (reason: deferred, emulator disabled to save memory)**. The exact command for the instrumented suites, to use later on a suitable device after taking the shared lock, is:

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :app:connectedDebugAndroidTest :platform:connectedDebugAndroidTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```

The headset and timing procedure and its metadata command are in [README.md](README.md). The Python launcher-script tests used fake shell tools, not a real emulator, AVD, device, or instrumented test.

### Fresh suite memory readings

Readings are machine-wide; swap values are `total / used / free` in MiB. Every row records the requested `memory_pressure | grep -i 'free percentage'` and `sysctl vm.swapusage` before and after the suite. Between the initial failed app run and its rerun, swap use increased by 698.69 MiB in less than 60 seconds; the runner waited 120 seconds while continuing to monitor memory before starting the rerun.

| Suite and result | Before: UTC, free, swap | After: UTC, free, swap |
| --- | --- | --- |
| :capture:test — 339 passed | 12:03:46Z; 57%; 4096.00 / 2971.38 / 1124.62 | 12:04:31Z; 51%; 4096.00 / 2971.38 / 1124.62 |
| :modelmgmt:testDebugUnitTest — 84 passed | 12:04:31Z; 51%; 4096.00 / 2971.38 / 1124.62 | 12:05:37Z; 42%; 4096.00 / 3008.06 / 1087.94 |
| :platform:testDebugUnitTest — 183 passed | 12:05:37Z; 42%; 4096.00 / 3008.06 / 1087.94 | 12:06:07Z; 42%; 4096.00 / 3008.06 / 1087.94 |
| :app:testDebugUnitTest — 110 passed, 1 failed (before fix) | 12:06:07Z; 42%; 4096.00 / 3008.06 / 1087.94 | 12:06:22Z; 43%; 4096.00 / 3008.06 / 1087.94 |
| :app:testDebugUnitTest — 111 passed (rerun) | 12:09:45Z; 55%; 4096.00 / 3690.75 / 405.25 | 12:10:00Z; 47%; 4096.00 / 3690.75 / 405.25 |
| :asr:testDebugUnitTest — 112 passed | 12:10:00Z; 47%; 4096.00 / 3690.75 / 405.25 | 12:10:15Z; 50%; 4096.00 / 3690.75 / 405.25 |
| :app:assembleDebug — passed | 12:10:15Z; 50%; 4096.00 / 3690.75 / 405.25 | 12:10:35Z; 50%; 4096.00 / 3690.75 / 405.25 |
| Acceptance selection — 278 passed, 6 deselected, 16 ignored | 12:10:35Z; 51%; 4096.00 / 3690.75 / 405.25 | 12:12:26Z; 59%; 4096.00 / 3682.75 / 413.25 |
| Contract, integration, security — 492 passed | 12:12:26Z; 59%; 4096.00 / 3682.75 / 413.25 | 12:16:28Z; 52%; 4096.00 / 3650.75 / 445.25 |
| Full acceptance collection — 297 collected, 1 error (before rename) | 12:16:33Z; 51%; 4096.00 / 3650.75 / 445.25 | 12:16:38Z; 52%; 4096.00 / 3650.75 / 445.25 |
| Full acceptance collection — 300 collected (after rename) | 12:16:55Z; 51%; 4096.00 / 3650.75 / 445.25 | 12:17:00Z; 52%; 4096.00 / 3650.75 / 445.25 |
| Acceptance selection — interrupted by monitor history guard | 12:17:00Z; 52%; 4096.00 / 3650.75 / 445.25 | 12:17:45Z; 42%; 4096.00 / 3650.75 / 445.25 |
| Acceptance selection — 278 passed, 6 deselected, 16 ignored (post-rename rerun) | 12:19:42Z; 52%; 4096.00 / 3634.75 / 461.25 | 12:21:38Z; 62%; 4096.00 / 3634.75 / 461.25 |
| GitNexus index refresh — passed | 12:22:55Z; 63%; 4096.00 / 3634.75 / 461.25 | 12:23:20Z; 60%; 4096.00 / 3634.75 / 461.25 |

The verification removed its `/tmp/auri-t18-verify-task_a0875a4da8f8` helpers and logs and the generated `.serena` directory. The shared Gradle lock was released, and no verification Gradle, Kotlin, pytest, or fake device-tool process remained. The newly created Python 3.12 `.venv` and generated build outputs were retained for the deferred rerun.

## Previous worker's historical results

- Direct JVM capture suite: **339 passed, 0 failed**, with the updated state-machine replay overlaid on the classes compiled by Gradle. A preceding run had 338 passed, 1 failed because the new warning was counted as a state-machine transition; the replay now separately asserts the warning and all original transitions.
- `SilenceFilteringTest`: **8 passed, 0 failed** after the segment-hash assertions were added. Deliberately stopping on silence produced **2 failures of 4 tests**, including the long-pause test, and was reverted.
- `CaptureResultTest`: **10 passed, 0 failed**; `CaptureScenarioReplayTest`: **1 passed, 0 failed**; `RouteBoundaryTest`: **1 passed, 0 failed**.
- `LocalTranscriptionAdapterTest`: **15 passed, 0 failed** in direct JVM execution, including a synthetic 31-second recording with a 30-second engine limit. The deliberate pre-implementation Gradle run had **12 tests, 2 failed**, demonstrating the long-audio requirement was absent.
- `DurationLimitSettingTest`: **2 passed, 0 failed**; `CaptureConfigTest` plus `HomeViewModelTest`: **17 passed, 0 failed**.
- `ContractOutputTest`: **4 passed, 0 failed**; the three schema/semantic/timeline/mutation functions in the then-named `test_contract_output.py` passed when called directly. The fresh pytest results are recorded above.
- Full Android Gradle run was **interrupted (exit 130)** after a second Gradle process started despite this branch holding the shared lock. At that point no complete five-module result or complete debug-build result was claimed; fresh results are recorded above.
- On the Mac JVM, the capture contention test reported 150 synthetic frames, 2,981 ms producer duration, and 59 ms maximum delivery latency. These are E2 test measurements, not phone or headset performance.

The interrupted full run's last output was:

```text
> Task :modelmgmt:javaPreCompileDebugUnitTest
> Task :modelmgmt:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :modelmgmt:processDebugJavaRes
> Task :modelmgmt:processDebugUnitTestJavaRes
> Task :capture:compileTestKotlin
Full Android Gradle exit=130 after
```

The expected red ASR run ended with `12 tests completed, 2 failed`: long audio was rejected, and the old missing-file case still returned the old length-limit error. The updated direct JVM suite passed all 15 tests; this does not substitute for the required final Gradle run.

## Assumptions and limits

- The lower setting bound is one whole minute; the spec specified the default and maximum but not a minimum. A changed value applies to the next session, not one already recording.
- The energy threshold is 300 mean absolute PCM units over 10 ms, with 200 ms pre-roll and 300 ms post-roll. Speech duration includes retained padding, consistent with SR-04. The threshold is uncalibrated and may miss quiet/distant speech or retain noise; E3 audio evaluation is required.
- Segment positions use the delivered verified PCM sample timeline. Elapsed capture duration uses the larger of monotonic time and received-sample time; alignment after dropped device buffers requires real-device verification.
- Long speech can be split at a 30-second engine window boundary, potentially cutting a word at that boundary. Text is joined in order with spaces; no real-model or real-headset quality result is claimed.
- The contract has no storage-exhaustion `end_reason`; the existing `system_interruption` partial-record mapping is retained. No contract fields or enums were changed and no dependency was added.
- The read-only main-checkout GitNexus/aoci index did not contain `CaptureCoordinator` or `AuriRuntime`; lookup and impact reported not found, so callers and sources were confirmed with `rg` in this worktree.

## Previous cleanup

Created for this task and removed after copying the evidence here: `/tmp/auri-t18-*` helper scripts and logs, temporary JVM class directories, this worktree's `.venv` and `.serena` directories, and the generated `apps/android` Gradle/build directories. The shared Gradle lock was released when held. No other worker's processes, caches, branch, or worktree were removed.

## Previous commands and memory readings

The previous worker's interrupted full Gradle command was:

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :capture:test :modelmgmt:testDebugUnitTest :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```

The red ASR Gradle command was:

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :platform:testDebugUnitTest --tests 'app.auri.platform.asr.LocalTranscriptionAdapterTest' --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```

The formerly pending Python command, now completed, was:

```bash
source ~/.config/auri/android-env.sh
uv venv --python 3.12 --seed .venv
.venv/bin/pip install -r tests/contract/requirements.txt -r services/gateway/requirements.txt -r tests/integration/requirements.txt
.venv/bin/pytest tests/contract tests/integration tests/security
```

Direct JUnit and Python commands, their helper bodies, and the before/after memory log are appended below so the temporary helpers can be removed without losing the exact invocation details.

### Direct JVM and Python invocations

The temporary helper bodies below record the earlier runs; they were created only in `/tmp` and removed during cleanup. The historical direct Python command used the former filename `test_contract_output.py`, since renamed to `test_silence_contract_output.py`. The latest focused runs used:

```bash
/tmp/auri-t18-capture-suite.sh
/tmp/auri-t18-jvm-check.sh 'Speech chunk hash verification'
/tmp/auri-t18-jvm-check.sh 'Stop reasons with VAD and setting' CaptureResultTest
/tmp/auri-t18-jvm-check.sh 'State-machine replay with warning retry' CaptureScenarioReplayTest
/tmp/auri-t18-jvm-check.sh 'Contract JSON generation' ContractOutputTest contract
/tmp/auri-t18-asr-check.sh 'Thirty-one second ASR verification'
/tmp/auri-t18-setting-check.sh 'Duration setting validation'
/tmp/auri-t18-app-check.sh 'Home UI and capture settings checks'
/tmp/auri-t18-route-check.sh
.venv/bin/python -c 'import runpy; tests=runpy.run_path("execution/acceptance/android-silence/test_contract_output.py"); tests["test_real_recording_results_satisfy_schema_and_semantics"](); tests["test_result_scenarios_preserve_timeline_and_end_reason"](); tests["test_mutated_speech_duration_is_rejected"](); print("3 contract-output checks passed")'
```

The previous retry was deferred before invoking Gradle when another build was already present. Its intended command was:

```bash
cd apps/android
./gradlew :capture:cleanTest :modelmgmt:cleanTestDebugUnitTest :platform:cleanTestDebugUnitTest :app:cleanTestDebugUnitTest :asr:cleanTestDebugUnitTest :capture:test :modelmgmt:testDebugUnitTest :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest :app:assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```

<details><summary>auri-t18-capture-suite.sh</summary>

```bash
#!/bin/bash
source ~/.config/auri/android-env.sh
printf '%s\n' 'Capture direct JVM suite before' | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
cache="$HOME/.gradle/caches/modules-2/files-2.1"
test_cp=$(rg --files "$cache" | rg '(kotlin-stdlib/2.0.21|kotlin-reflect/1.6.10|kotlinx-coroutines-(core|test)-jvm/1.9.0|kotlinx-serialization-(core|json)-jvm/1.7.3|junit/4.13.2|hamcrest-core/1.3|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
root=apps/android/capture/build/classes/kotlin
compiler_cp=$(rg --files "$cache" | rg '(kotlin-compiler-embeddable/2.0.21|kotlin-stdlib/2.0.21|kotlin-script-runtime/2.0.21|kotlin-reflect/1.6.10|kotlin-daemon-embeddable/2.0.21|trove4j/1.0.20200330|kotlinx-coroutines-core-jvm/1.9.0|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
override=$(mktemp -d /tmp/auri-t18-replay-XXXXXX)
trap '.venv/bin/python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$override"' EXIT
java -Xmx384m -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath "${root}/test:${root}/testFixtures:${root}/main:${test_cp}" -d "$override" apps/android/capture/src/test/kotlin/app/auri/capture/coordinator/CaptureScenarioReplayTest.kt || exit $?
classes=()
while IFS= read -r path; do
  name=${path#"${root}/test/"}
  name=${name%.class}
  name=${name//\//.}
  classes+=("$name")
done < <(find "$root/test" -name '*Test.class' | sort)
java -Xmx256m -Dauri.contract.out="$PWD/apps/android/capture/build/contract-out/jvm" -Dauri.repo.root="$PWD" -cp "${override}:${root}/test:${root}/testFixtures:${root}/main:${test_cp}" org.junit.runner.JUnitCore "${classes[@]}"
result=$?
printf '%s\n' "Capture direct JVM suite exit=$result after" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
exit "$result"
```

</details>

<details><summary>auri-t18-jvm-check.sh</summary>

```bash
#!/bin/bash
source ~/.config/auri/android-env.sh
label="$1"
test_class="${2:-SilenceFilteringTest}"
test_package="${3:-coordinator}"
printf '%s\n' "${label} before" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
cache="$HOME/.gradle/caches/modules-2/files-2.1"
compiler_cp=$(rg --files "$cache" | rg '(kotlin-compiler-embeddable/2.0.21|kotlin-stdlib/2.0.21|kotlin-script-runtime/2.0.21|kotlin-reflect/1.6.10|kotlin-daemon-embeddable/2.0.21|trove4j/1.0.20200330|kotlinx-coroutines-core-jvm/1.9.0|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
test_cp=$(rg --files "$cache" | rg '(kotlin-stdlib/2.0.21|kotlinx-coroutines-(core|test)-jvm/1.9.0|kotlinx-serialization-(core|json)-jvm/1.7.3|junit/4.13.2|hamcrest-core/1.3|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
main_classes=apps/android/capture/build/classes/kotlin/main
test_cp="${main_classes}:apps/android/capture/build/classes/kotlin/testFixtures:${test_cp}"
test_dir=$(mktemp -d /tmp/auri-t18-jvm-XXXXXX)
trap '.venv/bin/python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$test_dir"' EXIT
sources=(apps/android/capture/src/main/kotlin/app/auri/capture/coordinator/CoordinatorTypes.kt apps/android/capture/src/main/kotlin/app/auri/capture/feedback/Feedback.kt apps/android/capture/src/testFixtures/kotlin/app/auri/capture/testing/Fakes.kt apps/android/capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt apps/android/capture/src/main/kotlin/app/auri/capture/coordinator/VoiceActivityDetector.kt "apps/android/capture/src/test/kotlin/app/auri/capture/${test_package}/${test_class}.kt")
if [[ -f apps/android/capture/src/main/kotlin/app/auri/capture/coordinator/SpeechFilter.kt ]]; then sources+=(apps/android/capture/src/main/kotlin/app/auri/capture/coordinator/SpeechFilter.kt); fi
java -Xmx384m -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -Xfriend-paths="$main_classes" -classpath "$test_cp" -d "$test_dir" "${sources[@]}"
result=$?
if [[ "$result" -eq 0 ]]; then java -Xmx256m -Dauri.contract.out="$PWD/apps/android/capture/build/contract-out/jvm" -Dauri.repo.root="$PWD" -cp "${test_dir}:${test_cp}" org.junit.runner.JUnitCore "app.auri.capture.${test_package}.${test_class}"; result=$?; fi
printf '%s\n' "${label} exit=${result} after" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
exit "$result"
```

</details>

<details><summary>auri-t18-asr-check.sh</summary>

```bash
#!/bin/bash
source ~/.config/auri/android-env.sh
label="$1"
printf '%s\n' "${label} before" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
cache="$HOME/.gradle/caches/modules-2/files-2.1"
compiler_cp=$(rg --files "$cache" | rg '(kotlin-compiler-embeddable/2.0.21|kotlin-stdlib/2.0.21|kotlin-script-runtime/2.0.21|kotlin-reflect/1.6.10|kotlin-daemon-embeddable/2.0.21|trove4j/1.0.20200330|kotlinx-coroutines-core-jvm/1.9.0|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
test_cp=$(rg --files "$cache" | rg '(kotlin-stdlib/2.0.21|kotlinx-coroutines-(core|test)-jvm/1.9.0|kotlinx-serialization-(core|json)-jvm/1.7.3|junit/4.13.2|hamcrest-core/1.3|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
platform=apps/android/platform/build/tmp/kotlin-classes/debug
test_cp="${platform}:apps/android/asr/build/tmp/kotlin-classes/debug:apps/android/capture/build/classes/kotlin/main:${test_cp}"
test_dir=$(mktemp -d /tmp/auri-t18-asr-XXXXXX)
trap '.venv/bin/python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$test_dir"' EXIT
java -Xmx384m -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -Xfriend-paths="$platform" -classpath "$test_cp" -d "$test_dir" apps/android/platform/src/main/kotlin/app/auri/platform/asr/LocalTranscriptionAdapter.kt apps/android/platform/src/test/kotlin/app/auri/platform/asr/LocalTranscriptionAdapterTest.kt
result=$?
if [[ "$result" -eq 0 ]]; then java -Xmx256m -cp "${test_dir}:${test_cp}" org.junit.runner.JUnitCore app.auri.platform.asr.LocalTranscriptionAdapterTest; result=$?; fi
printf '%s\n' "${label} exit=${result} after" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
exit "$result"
```

</details>

<details><summary>auri-t18-setting-check.sh</summary>

```bash
#!/bin/bash
source ~/.config/auri/android-env.sh
label="$1"
printf '%s\n' "${label} before" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
cache="$HOME/.gradle/caches/modules-2/files-2.1"
compiler_cp=$(rg --files "$cache" | rg '(kotlin-compiler-embeddable/2.0.21|kotlin-stdlib/2.0.21|kotlin-script-runtime/2.0.21|kotlin-reflect/1.6.10|kotlin-daemon-embeddable/2.0.21|trove4j/1.0.20200330|kotlinx-coroutines-core-jvm/1.9.0|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
test_cp=$(rg --files "$cache" | rg '(kotlin-stdlib/2.0.21|kotlinx-coroutines-core-jvm/1.9.0|junit/4.13.2|hamcrest-core/1.3|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
test_dir=$(mktemp -d /tmp/auri-t18-setting-XXXXXX)
trap '.venv/bin/python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$test_dir"' EXIT
sources=(apps/android/platform/src/test/kotlin/app/auri/platform/storage/DurationLimitSettingTest.kt)
if [[ -f apps/android/platform/src/main/kotlin/app/auri/platform/storage/DurationLimitSetting.kt ]]; then sources+=(apps/android/platform/src/main/kotlin/app/auri/platform/storage/DurationLimitSetting.kt); fi
java -Xmx384m -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath "$test_cp" -d "$test_dir" "${sources[@]}"
result=$?
if [[ "$result" -eq 0 ]]; then java -Xmx256m -cp "${test_dir}:${test_cp}" org.junit.runner.JUnitCore app.auri.platform.storage.DurationLimitSettingTest; result=$?; fi
printf '%s\n' "${label} exit=${result} after" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
exit "$result"
```

</details>

<details><summary>auri-t18-app-check.sh</summary>

```bash
#!/bin/bash
source ~/.config/auri/android-env.sh
label="$1"
printf '%s\n' "${label} before" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
cache="$HOME/.gradle/caches/modules-2/files-2.1"
compiler_cp=$(rg --files "$cache" | rg '(kotlin-compiler-embeddable/2.0.21|kotlin-stdlib/2.0.21|kotlin-script-runtime/2.0.21|kotlin-reflect/1.6.10|kotlin-daemon-embeddable/2.0.21|trove4j/1.0.20200330|kotlinx-coroutines-core-jvm/1.9.0|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
test_cp=$(rg --files "$cache" | rg '(kotlin-stdlib/2.0.21|kotlinx-coroutines-(core|test)-jvm/1.9.0|kotlinx-serialization-(core|json)-jvm/1.7.3|lifecycle-common-jvm/2.8.7|annotation-jvm/1.8.1|junit/4.13.2|hamcrest-core/1.3|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
lifecycle=$(rg --files "$HOME/.gradle/caches/8.10.2/transforms" | rg 'lifecycle-viewmodel-release/jars/classes.jar$' | head -1)
app=apps/android/app/build/tmp/kotlin-classes/debug
test_cp="${app}:apps/android/platform/build/tmp/kotlin-classes/debug:apps/android/capture/build/classes/kotlin/main:apps/android/capture/build/classes/kotlin/testFixtures:${lifecycle}:${test_cp}"
test_dir=$(mktemp -d /tmp/auri-t18-app-XXXXXX)
trap '.venv/bin/python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$test_dir"' EXIT
java -Xmx384m -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -Xfriend-paths="$app" -classpath "$test_cp" -d "$test_dir" apps/android/app/src/test/kotlin/app/auri/android/CaptureConfigTest.kt apps/android/app/src/test/kotlin/app/auri/android/home/HomeViewModelTest.kt
result=$?
if [[ "$result" -eq 0 ]]; then java -Xmx256m -cp "${test_dir}:${test_cp}" org.junit.runner.JUnitCore app.auri.android.CaptureConfigTest app.auri.android.home.HomeViewModelTest; result=$?; fi
printf '%s\n' "${label} exit=${result} after" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
exit "$result"
```

</details>

<details><summary>auri-t18-route-check.sh</summary>

```bash
#!/bin/bash
source ~/.config/auri/android-env.sh
printf '%s\n' 'Route boundary JVM before' | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
cache="$HOME/.gradle/caches/modules-2/files-2.1"
compiler_cp=$(rg --files "$cache" | rg '(kotlin-compiler-embeddable/2.0.21|kotlin-stdlib/2.0.21|kotlin-script-runtime/2.0.21|kotlin-reflect/1.6.10|kotlin-daemon-embeddable/2.0.21|trove4j/1.0.20200330|kotlinx-coroutines-core-jvm/1.9.0|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
test_cp=$(rg --files "$cache" | rg '(kotlin-stdlib/2.0.21|kotlinx-coroutines-(core|test)-jvm/1.9.0|kotlinx-serialization-(core|json)-jvm/1.7.3|junit/4.13.2|hamcrest-core/1.3|org.jetbrains/annotations/13.0)/.*\.jar$' | paste -sd: -)
platform=apps/android/platform/build/tmp/kotlin-classes
test_cp="${platform}/debugUnitTest:${platform}/debug:apps/android/capture/build/classes/kotlin/main:apps/android/capture/build/classes/kotlin/testFixtures:${test_cp}"
test_dir=$(mktemp -d /tmp/auri-t18-route-XXXXXX)
trap '.venv/bin/python -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$test_dir"' EXIT
java -Xmx384m -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -Xfriend-paths="${platform}/debugUnitTest" -classpath "$test_cp" -d "$test_dir" apps/android/platform/src/test/kotlin/app/auri/platform/audio/RouteBoundaryTest.kt
result=$?
if [[ "$result" -eq 0 ]]; then java -Xmx256m -cp "${test_dir}:${test_cp}" org.junit.runner.JUnitCore app.auri.platform.audio.RouteBoundaryTest; result=$?; fi
printf '%s\n' "Route boundary JVM exit=${result} after" | tee -a /tmp/auri-t18-memory.log
date -u '+%Y-%m-%dT%H:%M:%SZ' | tee -a /tmp/auri-t18-memory.log
memory_pressure | grep -i 'free percentage' | tee -a /tmp/auri-t18-memory.log
sysctl vm.swapusage | tee -a /tmp/auri-t18-memory.log
exit "$result"
```

</details>

### Suite memory log

The first Gradle attempt was stopped when free memory fell below 25%; later interruptions are marked with exit 130. A deferred attempt with exit 2 did not invoke Gradle. Values are machine-wide, in UTC.

```text
RED :capture:test SilenceFilteringTest before
2026-11-19T04:21:49Z
System-wide memory free percentage: 53%
vm.swapusage: total = 4096.00M  used = 2778.88M  free = 1317.12M  (encrypted)
RED exit=130 after
2026-11-19T04:24:06Z
System-wide memory free percentage: 20%
vm.swapusage: total = 4096.00M  used = 3654.75M  free = 441.25M  (encrypted)
RED retry :capture:test SilenceFilteringTest before
2026-11-19T04:32:54Z
System-wide memory free percentage: 52%
vm.swapusage: total = 5120.00M  used = 3156.81M  free = 1963.19M  (encrypted)
RED retry interrupted exit=130 after
2026-11-19T04:35:09Z
System-wide memory free percentage: 50%
vm.swapusage: total = 5120.00M  used = 2727.56M  free = 2392.44M  (encrypted)
RED third attempt exit=2 after
2026-11-19T04:36:13Z
System-wide memory free percentage: 55%
vm.swapusage: total = 4096.00M  used = 2586.25M  free = 1509.75M  (encrypted)
Standalone JVM red test before
2026-11-19T04:38:58Z
System-wide memory free percentage: 33%
vm.swapusage: total = 4096.00M  used = 3671.88M  free = 424.12M  (encrypted)
Standalone JVM red test exit=1 after
2026-11-19T04:39:49Z
System-wide memory free percentage: 37%
vm.swapusage: total = 5120.00M  used = 4112.19M  free = 1007.81M  (encrypted)
Standalone JVM green test before
2026-11-19T04:41:17Z
System-wide memory free percentage: 52%
vm.swapusage: total = 5120.00M  used = 4452.12M  free = 667.88M  (encrypted)
Standalone JVM green test exit=1 after
2026-11-19T04:41:30Z
System-wide memory free percentage: 53%
vm.swapusage: total = 5120.00M  used = 4436.12M  free = 683.88M  (encrypted)
Standalone JVM green retry before
2026-11-19T04:44:39Z
System-wide memory free percentage: 52%
vm.swapusage: total = 5120.00M  used = 4617.19M  free = 502.81M  (encrypted)
Standalone JVM green retry exit=0 after
2026-11-19T04:44:48Z
System-wide memory free percentage: 50%
vm.swapusage: total = 5120.00M  used = 4609.19M  free = 510.81M  (encrypted)
Mixed audio red test before
2026-11-19T04:45:43Z
System-wide memory free percentage: 43%
vm.swapusage: total = 5120.00M  used = 4545.19M  free = 574.81M  (encrypted)
Mixed audio red test exit=1 after
2026-11-19T04:45:55Z
System-wide memory free percentage: 44%
vm.swapusage: total = 5120.00M  used = 4545.19M  free = 574.81M  (encrypted)
Mixed audio green test before
2026-11-19T04:47:31Z
System-wide memory free percentage: 31%
vm.swapusage: total = 6144.00M  used = 4942.88M  free = 1201.12M  (encrypted)
Mixed audio green test exit=130 after
2026-11-19T04:48:21Z
System-wide memory free percentage: 39%
vm.swapusage: total = 6144.00M  used = 5277.56M  free = 866.44M  (encrypted)
Mixed audio green retry before
2026-11-19T04:51:00Z
System-wide memory free percentage: 59%
vm.swapusage: total = 6144.00M  used = 4789.44M  free = 1354.56M  (encrypted)
Mixed audio green retry exit=0 after
2026-11-19T04:51:06Z
System-wide memory free percentage: 61%
vm.swapusage: total = 6144.00M  used = 4789.44M  free = 1354.56M  (encrypted)
Mutation silence stops recording before
2026-11-19T04:52:34Z
System-wide memory free percentage: 54%
vm.swapusage: total = 6144.00M  used = 4717.44M  free = 1426.56M  (encrypted)
Mutation silence stops recording exit=1 after
2026-11-19T04:52:49Z
System-wide memory free percentage: 58%
vm.swapusage: total = 6144.00M  used = 4709.44M  free = 1434.56M  (encrypted)
Silence and quarantine green before
2026-11-19T04:52:59Z
System-wide memory free percentage: 60%
vm.swapusage: total = 6144.00M  used = 4709.44M  free = 1434.56M  (encrypted)
Silence and quarantine green exit=0 after
2026-11-19T04:53:14Z
System-wide memory free percentage: 61%
vm.swapusage: total = 6144.00M  used = 4701.44M  free = 1442.56M  (encrypted)
Short frame red test before
2026-11-19T04:53:30Z
System-wide memory free percentage: 61%
vm.swapusage: total = 6144.00M  used = 4693.44M  free = 1450.56M  (encrypted)
Short frame red test exit=1 after
2026-11-19T04:53:36Z
System-wide memory free percentage: 62%
vm.swapusage: total = 6144.00M  used = 4685.44M  free = 1458.56M  (encrypted)
Short frame green test before
2026-11-19T04:53:50Z
System-wide memory free percentage: 62%
vm.swapusage: total = 6144.00M  used = 4637.44M  free = 1506.56M  (encrypted)
Short frame green test exit=0 after
2026-11-19T04:53:57Z
System-wide memory free percentage: 63%
vm.swapusage: total = 6144.00M  used = 4597.44M  free = 1546.56M  (encrypted)
Existing capture result compatibility red before
2026-11-19T04:54:21Z
System-wide memory free percentage: 64%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Existing capture result compatibility red exit=1 after
2026-11-19T04:54:24Z
System-wide memory free percentage: 64%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Existing capture result compatibility red retry before
2026-11-19T04:54:35Z
System-wide memory free percentage: 64%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Existing capture result compatibility red retry exit=1 after
2026-11-19T04:54:39Z
System-wide memory free percentage: 63%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Existing capture result compatibility green before
2026-11-19T04:55:16Z
System-wide memory free percentage: 65%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Existing capture result compatibility green exit=0 after
2026-11-19T04:55:21Z
System-wide memory free percentage: 64%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Silence checks with speech fixtures before
2026-11-19T04:55:25Z
System-wide memory free percentage: 62%
vm.swapusage: total = 6144.00M  used = 4517.44M  free = 1626.56M  (encrypted)
Silence checks with speech fixtures exit=0 after
2026-11-19T04:55:29Z
System-wide memory free percentage: 62%
vm.swapusage: total = 6144.00M  used = 4509.44M  free = 1634.56M  (encrypted)
Duration warning red test before
2026-11-19T04:56:26Z
System-wide memory free percentage: 63%
vm.swapusage: total = 6144.00M  used = 4485.44M  free = 1658.56M  (encrypted)
Duration warning red test exit=1 after
2026-11-19T04:56:30Z
System-wide memory free percentage: 64%
vm.swapusage: total = 6144.00M  used = 4445.44M  free = 1698.56M  (encrypted)
Duration warning green test before
2026-11-19T04:57:18Z
System-wide memory free percentage: 64%
vm.swapusage: total = 6144.00M  used = 4421.44M  free = 1722.56M  (encrypted)
Duration warning green test exit=0 after
2026-11-19T04:57:22Z
System-wide memory free percentage: 65%
vm.swapusage: total = 6144.00M  used = 4421.44M  free = 1722.56M  (encrypted)
Duration setting red test before
2026-11-19T04:58:24Z
System-wide memory free percentage: 65%
vm.swapusage: total = 6144.00M  used = 4413.44M  free = 1730.56M  (encrypted)
Duration setting red test exit=1 after
2026-11-19T04:58:26Z
System-wide memory free percentage: 65%
vm.swapusage: total = 6144.00M  used = 4413.44M  free = 1730.56M  (encrypted)
Duration setting green test before
2026-11-19T04:58:45Z
System-wide memory free percentage: 63%
vm.swapusage: total = 6144.00M  used = 4405.44M  free = 1738.56M  (encrypted)
Duration setting green test exit=0 after
2026-11-19T04:58:48Z
System-wide memory free percentage: 60%
vm.swapusage: total = 6144.00M  used = 4405.44M  free = 1738.56M  (encrypted)
Session duration setting red test before
2026-11-19T04:59:13Z
System-wide memory free percentage: 61%
vm.swapusage: total = 6144.00M  used = 4405.44M  free = 1738.56M  (encrypted)
Session duration setting red test exit=1 after
2026-11-19T04:59:16Z
System-wide memory free percentage: 62%
vm.swapusage: total = 6144.00M  used = 4405.44M  free = 1738.56M  (encrypted)
Session duration setting green test before
2026-11-19T04:59:34Z
System-wide memory free percentage: 62%
vm.swapusage: total = 6144.00M  used = 4405.44M  free = 1738.56M  (encrypted)
Session duration setting green test exit=0 after
2026-11-19T04:59:39Z
System-wide memory free percentage: 60%
vm.swapusage: total = 6144.00M  used = 4405.44M  free = 1738.56M  (encrypted)
ASR split red Gradle before
2026-11-19T05:03:01Z
System-wide memory free percentage: 60%
vm.swapusage: total = 5120.00M  used = 3484.81M  free = 1635.19M  (encrypted)
ASR split red Gradle exit=1 after
2026-11-19T05:04:14Z
System-wide memory free percentage: 51%
vm.swapusage: total = 5120.00M  used = 3460.81M  free = 1659.19M  (encrypted)
ASR split green test before
2026-11-19T05:06:26Z
System-wide memory free percentage: 48%
vm.swapusage: total = 5120.00M  used = 3452.81M  free = 1667.19M  (encrypted)
ASR split green test exit=0 after
2026-11-19T05:06:42Z
System-wide memory free percentage: 49%
vm.swapusage: total = 5120.00M  used = 3452.81M  free = 1667.19M  (encrypted)
ASR split failure handling before
2026-11-19T05:07:07Z
System-wide memory free percentage: 46%
vm.swapusage: total = 5120.00M  used = 3452.81M  free = 1667.19M  (encrypted)
ASR split failure handling exit=0 after
2026-11-19T05:07:27Z
System-wide memory free percentage: 46%
vm.swapusage: total = 5120.00M  used = 3452.81M  free = 1667.19M  (encrypted)
VAD route isolation red test before
2026-11-19T05:09:42Z
System-wide memory free percentage: 54%
vm.swapusage: total = 5120.00M  used = 3436.81M  free = 1683.19M  (encrypted)
VAD route isolation red test exit=1 after
2026-11-19T05:09:49Z
System-wide memory free percentage: 55%
vm.swapusage: total = 5120.00M  used = 3436.81M  free = 1683.19M  (encrypted)
VAD route isolation green test before
2026-11-19T05:10:02Z
System-wide memory free percentage: 57%
vm.swapusage: total = 5120.00M  used = 3436.81M  free = 1683.19M  (encrypted)
VAD route isolation green test exit=0 after
2026-11-19T05:10:11Z
System-wide memory free percentage: 56%
vm.swapusage: total = 5120.00M  used = 3436.81M  free = 1683.19M  (encrypted)
Contract output direct red before
2026-11-19T05:10:33Z
System-wide memory free percentage: 51%
vm.swapusage: total = 5120.00M  used = 3361.25M  free = 1758.75M  (encrypted)
Contract output direct red exit=1 after
2026-11-19T05:10:34Z
System-wide memory free percentage: 53%
vm.swapusage: total = 5120.00M  used = 3361.19M  free = 1758.81M  (encrypted)
Contract JSON generation before
2026-11-19T05:10:54Z
System-wide memory free percentage: 47%
vm.swapusage: total = 4096.00M  used = 3328.50M  free = 767.50M  (encrypted)
Contract JSON generation exit=0 after
2026-11-19T05:11:08Z
System-wide memory free percentage: 48%
vm.swapusage: total = 4096.00M  used = 3320.50M  free = 775.50M  (encrypted)
Contract output direct green before
2026-11-19T05:11:20Z
System-wide memory free percentage: 43%
vm.swapusage: total = 4096.00M  used = 3320.50M  free = 775.50M  (encrypted)
Contract output direct green exit=0 after
2026-11-19T05:11:20Z
System-wide memory free percentage: 45%
vm.swapusage: total = 4096.00M  used = 3320.50M  free = 775.50M  (encrypted)
Contract mutation checks before
2026-11-19T05:11:46Z
System-wide memory free percentage: 50%
vm.swapusage: total = 4096.00M  used = 3320.50M  free = 775.50M  (encrypted)
Contract mutation checks exit=0 after
2026-11-19T05:11:46Z
System-wide memory free percentage: 50%
vm.swapusage: total = 4096.00M  used = 3320.50M  free = 775.50M  (encrypted)
Stop reasons with VAD and setting before
2026-11-19T05:11:51Z
System-wide memory free percentage: 51%
vm.swapusage: total = 4096.00M  used = 3320.50M  free = 775.50M  (encrypted)
Stop reasons with VAD and setting exit=0 after
2026-11-19T05:12:08Z
System-wide memory free percentage: 52%
vm.swapusage: total = 4096.00M  used = 3304.50M  free = 791.50M  (encrypted)
Duration setting validation before
2026-11-19T05:12:22Z
System-wide memory free percentage: 51%
vm.swapusage: total = 4096.00M  used = 3304.50M  free = 791.50M  (encrypted)
Duration setting validation exit=0 after
2026-11-19T05:12:28Z
System-wide memory free percentage: 49%
vm.swapusage: total = 4096.00M  used = 3304.50M  free = 791.50M  (encrypted)
Home UI and capture settings checks before
2026-11-19T05:15:16Z
System-wide memory free percentage: 55%
vm.swapusage: total = 4096.00M  used = 3264.50M  free = 831.50M  (encrypted)
Home UI and capture settings checks exit=0 after
2026-11-19T05:15:20Z
System-wide memory free percentage: 55%
vm.swapusage: total = 4096.00M  used = 3264.50M  free = 831.50M  (encrypted)
Full Android Gradle before
2026-11-19T05:16:00Z
System-wide memory free percentage: 53%
vm.swapusage: total = 4096.00M  used = 3264.50M  free = 831.50M  (encrypted)
Full Android Gradle exit=130 after
2026-11-19T05:17:19Z
System-wide memory free percentage: 44%
vm.swapusage: total = 4096.00M  used = 3248.50M  free = 847.50M  (encrypted)
Capture direct JVM suite before
2026-11-19T05:18:29Z
System-wide memory free percentage: 37%
vm.swapusage: total = 4096.00M  used = 3240.50M  free = 855.50M  (encrypted)
Capture direct JVM suite exit=1 after
2026-11-19T05:18:36Z
System-wide memory free percentage: 39%
vm.swapusage: total = 4096.00M  used = 3240.50M  free = 855.50M  (encrypted)
State-machine replay with warning before
2026-11-19T05:19:06Z
System-wide memory free percentage: 40%
vm.swapusage: total = 5120.00M  used = 4148.38M  free = 971.62M  (encrypted)
State-machine replay with warning exit=130 after
2026-11-19T05:19:13Z
System-wide memory free percentage: 33%
vm.swapusage: total = 5120.00M  used = 4151.38M  free = 968.62M  (encrypted)
State-machine replay with warning retry before
2026-11-19T05:21:39Z
System-wide memory free percentage: 34%
vm.swapusage: total = 5120.00M  used = 4805.62M  free = 314.38M  (encrypted)
State-machine replay with warning retry exit=0 after
2026-11-19T05:22:20Z
System-wide memory free percentage: 57%
vm.swapusage: total = 6144.00M  used = 4578.38M  free = 1565.62M  (encrypted)
Capture direct JVM suite before
2026-11-19T05:27:35Z
System-wide memory free percentage: 51%
vm.swapusage: total = 5120.00M  used = 3584.62M  free = 1535.38M  (encrypted)
Capture direct JVM suite exit=0 after
2026-11-19T05:27:59Z
System-wide memory free percentage: 46%
vm.swapusage: total = 5120.00M  used = 3584.62M  free = 1535.38M  (encrypted)
Route boundary JVM before
2026-11-19T05:29:14Z
System-wide memory free percentage: 37%
vm.swapusage: total = 5120.00M  used = 3536.62M  free = 1583.38M  (encrypted)
Route boundary JVM exit=0 after
2026-11-19T05:29:36Z
System-wide memory free percentage: 36%
vm.swapusage: total = 5120.00M  used = 3554.94M  free = 1565.06M  (encrypted)
Speech chunk hash verification before
2026-11-19T05:30:18Z
System-wide memory free percentage: 36%
vm.swapusage: total = 5120.00M  used = 3546.94M  free = 1573.06M  (encrypted)
Speech chunk hash verification exit=0 after
2026-11-19T05:31:29Z
System-wide memory free percentage: 37%
vm.swapusage: total = 5120.00M  used = 4329.12M  free = 790.88M  (encrypted)
Full Android Gradle retry exit=2 after
2026-11-19T05:34:51Z
System-wide memory free percentage: 54%
vm.swapusage: total = 5120.00M  used = 4073.06M  free = 1046.94M  (encrypted)
Thirty-one second ASR verification before
2026-11-19T05:35:33Z
System-wide memory free percentage: 54%
vm.swapusage: total = 5120.00M  used = 4057.06M  free = 1062.94M  (encrypted)
Thirty-one second ASR verification exit=0 after
2026-11-19T05:35:50Z
System-wide memory free percentage: 55%
vm.swapusage: total = 5120.00M  used = 4057.06M  free = 1062.94M  (encrypted)
```

