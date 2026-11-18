# Ticket 20: review-fix evidence

Branch `ticket/20-model-management`; integration base checked at `e92cfa7`. The final report commit is the branch head (`git rev-parse HEAD`); the last code commit before this report is `558cc9a`. All results below are E2 (local JVM, fakes, or Android emulator); no real-device result is claimed.

## Findings: red test, fix, green test

The named tests were first run against the defective behavior and failed for the reason described; they were retained and subsequently passed in the clean module suites. Where an additional case was added after the fix, it is identified as such.

| Finding | Failing test before the fix and observed failure | Fix and passing evidence |
|---|---|---|
| F1 | `SchedulerCancelTest.取消尚未执行的运行任务后释放名额`: the next job remained QUEUED. | `TranscriptionScheduler.kt`: a run's idempotent completion handler releases its slot even if the coroutine body never starts. Passed in `:capture` and killed the F1 mutant, E2 controllable dispatcher. |
| F2 | `ModelManagerTest.指针无法解析时保留所有版本并报告可恢复的存储错误` and `启用后读取指针失败也不能删除刚安装的版本`: a valid generation was deleted. | `ModelManager.kt`: garbage collection requires a valid read; after installation it uses the known active directory. Both passed in `:modelmgmt`; the additional `指针读取发生实际文件权限错误时保留旧版本` exercises a real POSIX read failure and kills the F2 mutant. E2 native filesystem; the initial red used malformed JSON, a different way to reach the same failed-read result. |
| F3 | `SchedulerRestartTest.开始状态无法落盘时不运行引擎，存储恢复后才能开始`: inference started despite a failed RUNNING write; `结果已发布但成功状态未落盘，重启认领本次结果而不是之后的用户修订`: a second Revision was appended. | `TranscriptionScheduler.kt`, `RecordLibrary.kt`, and `SqliteRecordLibrary.kt`: persist attempt before execution and use the job ID as the publication idempotency key, including SQLite migration. Both passed in `:capture`; F3a/F3b mutants were killed, E2 fakes; the SQLite migration/contract is exercised on the emulator. |
| F4 | `ModelManagerTest.安装新版本时仍在使用的旧版本保留到使用者完成`: verification failed after the old directory was removed. | `ModelManager.kt` and `ActiveModelTranscriptionAdapter.kt`: leases retain the model generation through verification and inference and defer collection until the last release. The test now also checks two leases and idempotent release and killed the F4 mutant, E2 native filesystem. The original red used the existing active model as a stand-in because the lease API did not yet exist. |
| F5 | `SchedulerRetryTest.旧模型迟到的拒绝不会让新模型继续等待`: the new generation remained WAITING_MODEL. | `TranscriptionScheduler.kt` and `ActiveModelTranscriptionAdapter.kt`: tag a refusal with the generation actually used and ignore stale refusals for a newer generation. Passed in `:capture` and adapter tests, E2 controlled interleaving. |
| F6 | `LocalTranscriptionStackTest.模型切换时运行中的旧引擎不阻塞邮箱取消任务`: cancellation timed out while engine close blocked the mailbox. | `ActiveModelTranscriptionAdapter.kt`: retain in-use engines and close retired engines asynchronously on the adapter dispatcher after the final use. Passed in `:platform`; the test checks the close thread, E2 JVM fake. |
| F7 | `ModelManagerTest.暂停等待中的第二次传输后再开始，第三次仍须等待第一次关闭`: two requests touched the partial transfer while the first sink was closing. | `ModelManager.kt`: each transfer owns its stop reason and awaits its predecessor's complete cleanup, including cancellation while waiting. Passed in `:modelmgmt` and killed the F7 mutant, E2 controlled sinks/threads. |
| F8 | `SchedulerRobustnessTest.资源变化流抛错时等待并重订阅，异常不逃到未捕获处理器`: an unavailable monitor allowed inference. Additional reds: `资源监听尚未注册完成时不能开始推理` and `ResourceChangesTest.注册未完成就失败时不发送提前到达的温度信号`. | `TranscriptionScheduler.kt`, `RecordingAwareResourceMonitor.kt`, and `ResourceChanges.kt`: wait for successful registration, contain failures as unknown resources, retry, and unregister every registered callback in `finally`. All passed in `:capture`/`:platform`, E2 controlled registration failures; actual exceptional Android registration cannot be injected by those JVM fakes. |
| F9 | `LocalTranscriptionStackTest.关闭转录栈会停止资源监听，而调用方作用域仍然存活`: the resource listener remained active. | `LocalTranscriptionStack.kt`: own and cancel the scheduler's child lifetime when closing the stack. Passed in `:platform`, E2 fake listener. |
| F10 | `UrlConnectionTransportTest.重定向到 HTTPS 回环地址在连接前被拒绝`: a connection was attempted and timed out; `私有网段与本地域名不允许作为 HTTPS 地址` also failed. | `HttpTransport.kt`: HTTPS connections use DNS addresses validated for public routing; reject private/local names and addresses and cap redirects at five. Loopback, 10.x, and normal-host fake redirect cases passed in `:modelmgmt`, E2. The successful normal-host test uses a rewritten fake destination and the resolver is tested independently, so it is not a real public TLS download. |
| F11 | `SchedulerWaitingTest.五次重查之后内存或存储恢复且没有通知，五分钟内自动继续`: the job remained WAITING_RESOURCES. Additional red `配置过短或递减的资源重查间隔也要有下限且退避` caught a tight configured loop. | `TranscriptionScheduler.kt`: retry waiting resources with a 15-second minimum and nondecreasing backoff capped at five minutes, while retaining bounded FAILED retries. Both passed in `:capture`; the revised M9 mutant was killed, E2 virtual clock. |
| F12 | `SchedulerRetranscribeTest.第一次失败且没有 Revision，重转录无语音时 Record 显示无语音`: Record still showed FAILED. | `TranscriptionScheduler.kt`: preserve status during retranscription only when an existing Revision must be preserved. Passed in `:capture`, E2 in-memory library. |
| F13 | `TranscriptionDoesNotDropFramesTest.真实低优先级 CPU 推理并发时，实时生产的录音帧零丢失且延迟有界` failed when inference was deliberately moved onto the mailbox: recording did not begin within 1,000 ms. | `TranscriptionDoesNotDropFramesTest.kt`: real producer thread, real coordinator, and CPU-bound fake inference on the production low-priority dispatcher. Passed in the clean `:capture` run: 150 frames, 0 dropped, 2,980 ms production, maximum delivery latency 6 ms on this Mac; the test limit is 500 ms. E2 JVM contention only, **real phone NOT_RUN**. |

## Checklist coverage

| Ticket criterion | Evidence and level |
|---|---|
| Model size, required space, language and license | `ModelCatalogTest`, `ModelsViewModelTest`, `test_model_manifest.py`; E2. |
| Pause/resume, verify then atomically activate, preserve old version on failure | `ResumableDownloaderTest`, `ModelManagerTest`; E2 local fake HTTP/filesystem; Android download on a phone NOT_RUN. |
| Transcription state-machine sample sequences | `TranscriptionScenarioReplayTest`, `TranscriptionMachineTableTest`; E2 with unchanged contract fixtures. |
| Model/resource waits explain reason and recover; failure retries bounded | `SchedulerWaitingTest`, `SchedulerRetryTest`, `SchedulerRobustnessTest`; E2 virtual clock; physical thresholds NOT_RUN. |
| Recording while transcribing does not drop frames | `TranscriptionDoesNotDropFramesTest`; E2 Mac threads only; real device NOT_RUN. |
| Retranscription appends a Revision and preserves previous Revision | `SchedulerRetranscribeTest`, `SchedulerRestartTest`, `SqliteRecordLibraryContractTest`; E2 JVM and emulator. |
| No speech yields no text | `SchedulerRunTest`, `SchedulerRetranscribeTest`, `ModelsViewModelTest`; E2. |

## Final clean validation

All Gradle commands used `source ~/.config/auri/android-env.sh`, `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`, and the isolated `-Dorg.gradle.daemon.registry.base=/tmp/auri-ticket20-gradle-registry`. Each invocation was preceded by `sysctl vm.swapusage` and `memory_pressure | grep -i "free percentage"`; the five test suites ran one at a time and each test output was cleared first.

| Exact command after those flags, or shell command | Result |
|---|---|
| `:capture:cleanTest :capture:test` | 172 passed, 0 failed, 0 skipped |
| `:modelmgmt:cleanTestDebugUnitTest :modelmgmt:testDebugUnitTest` | 82 passed, 0 failed, 0 skipped |
| `:platform:cleanTestDebugUnitTest :platform:testDebugUnitTest` | 115 passed, 0 failed, 0 skipped |
| `:app:cleanTestDebugUnitTest :app:testDebugUnitTest` | 66 passed, 0 failed, 0 skipped |
| `:asr:cleanTestDebugUnitTest :asr:testDebugUnitTest` | 112 passed, 0 failed, 0 skipped |
| `:app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `AURI_DEVICE_OUT=/tmp/auri-ticket20-device-out AURI_AVD_NAME=auri-t20-review-20 AURI_SKIP_BUILD=1 execution/acceptance/android-capture/.auri-ticket20-emulator.sh` | E2 API 35 arm64 emulator: platform 32 passed; app 4 passed; checker 0 issues. Two sequential device mutants caught; original APK rebuilt before the complete suites. |
| `.venv/bin/pytest -q execution/acceptance/android-models` | 2 passed |
| `.venv/bin/pytest tests/contract tests/integration tests/security` | 492 passed, 0 failed, in 211.24 s |

The first preflight capture run exposed a setup race in the new frame test (the fake sink was not yet registered); waiting for the sink fixed the test setup and the clean run above passed. An earlier platform preflight was interrupted by a separate process issuing Gradle's machine-wide stop; the subsequent isolated-registry clean platform suite passed. The AVD manager printed a `devices.xml` warning but booted the specified API 35 image successfully and all instrumented tests completed.

## Mutation evidence

The 24 JVM checks M1–M19, M17b, M-T and M-R1a/b/c all failed for an assertion under their respective mutations. M9 had to be revised: the historical mutation meant “resource waits never become needsUser,” which is now explicitly the desired F11 behavior, so that historical assertion cannot both be killed and meet the new rule. The new M9 instead stops automatic resource polling after the fifth check and is killed by `SchedulerWaitingTest.五次重查之后内存或存储恢复且没有通知，五分钟内自动继续`; it was rerun against the final code. M-T now covers the test-only loopback HTTP timeout path; it is not proof of the production HTTPS client's timeout.

The five requested new areas yielded six JVM mutants, all killed: F1 (never releases the run slot), F2 (collects after invalid pointer; additionally rerun using an actual file permission read error), F3a (runs without durable RUNNING), F3b (does not recognize already-published Revision), F4 (deletes the leased generation), and F7 (drops the transfer predecessor). Device mutants M-D1 and M-D2 were both caught on the emulator by their respective battery and thermal signal tests (one expected assertion failure each). Mutants were applied one at a time, their targeted test failure was checked in fresh JUnit XML, and the original source was restored after each. A compile error was not counted as a killed mutant.

## Assumptions, limitations, cleanup

- A pre-existing RUNNING job from the old SQLite format whose ASR Revision cannot be attributed to a job is failed conservatively, retaining existing data instead of risking a duplicate. A previously stored resource wait marked `needsUser` is resumed automatically under the new F11 rule.
- The F10 production client disables proxies so validation is applied to the addresses actually connected to. Its public-host success test is a fake-server seam, not a live TLS test. The 163 MB real download recorded in the historical README predates the new HTTPS client and does not validate it.
- Original tests requiring resource waits to stop after five checks, or allowing inference after a failed RUNNING write, were updated to assert the new, stronger required behavior; unrelated coverage was retained.
- Thermal, memory, battery/power saver, storage exhaustion, download and recording during transcription on an actual phone are **NOT_RUN**. Emulator instrumentation is E2, not E3, and does not calibrate phone thresholds or reproduce every registration failure.
- The F2 permission test uses POSIX filesystem permissions and an unprivileged user, as on this Mac. Model and network race tests use controlled fakes and do not substitute for Android hardware acceptance.
- Temporary artifacts created and removed: `/tmp/auri-ticket20-*` mutation, build, test, emulator and daemon-registry files; `execution/acceptance/android-capture/.auri-ticket20-emulator.sh`; the throwaway AVD and disk image, emulator process and shared lock. The pre-existing `.serena/` directory was left untouched. The existing virtual environment and caches from earlier work were retained.
