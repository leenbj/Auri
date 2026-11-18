# Apple and HarmonyOS toolchains on the Mac mini

Verified on 2026-11-18 on `ticket/00-verify-apple-harmony`, initially based on `origin/integration/auri-v2` at `8389344a3f6aa0a81e2299bf44a5ba9b49bedcf2`. The earlier survey and installation history remain in this file at that commit. This verification did not accept a licence, run sudo, sign in, edit a shell profile, or change probe source.

**The iOS simulator build succeeded. The HarmonyOS build failed at ArkTS compilation with the installed API 26 Beta1 SDK; the probe's pinned API 21 release toolchain is still missing.** A pre-existing Xcode GUI process did not quit on request and needs owner attention, as described below.

## Survey

The following are actual command outputs; an empty output with exit 0 is noted explicitly.

```text
$ ls /Applications | grep -iE "xcode|deveco"
DevEco-Studio.app
Xcode.app
$ xcode-select -p
/Applications/Xcode.app/Contents/Developer
$ xcodebuild -version
Xcode 27.2
Build version 27B52
$ xcodebuild -license check
<no output; exit 0>
$ xcrun --sdk iphoneos --show-sdk-version
27.2
$ xcrun simctl list runtimes
== Runtimes ==
$ xcrun simctl list devices booted
== Devices ==
$ git --version
git version 2.54.0 (Apple Git-157)
$ /usr/bin/git --version
git version 2.54.0 (Apple Git-157)
$ /usr/bin/python3 --version
Python 3.9.6
$ DEVELOPER_DIR=/Library/Developer/CommandLineTools git --version
git version 2.54.0 (Apple Git-157)
$ DEVELOPER_DIR=/Library/Developer/CommandLineTools /usr/bin/git --version
git version 2.54.0 (Apple Git-157)
$ DEVELOPER_DIR=/Library/Developer/CommandLineTools /usr/bin/python3 --version
Python 3.9.6
$ DEVELOPER_DIR=/Library/Developer/CommandLineTools xcrun --version
xcrun version 72.
$ defaults read /Applications/DevEco-Studio.app/Contents/Info CFBundleShortVersionString
26.0.1
$ xcodegen --version
Version: 2.46.0
$ df -h /
Filesystem        Size    Used   Avail Capacity iused ifree %iused  Mounted on
/dev/disk5s2s1   954Gi    13Gi   697Gi     2%    487k  4.3G    0%   /
```

The default `DEVELOPER_DIR` was unset. System Git and Python work both with the selected Xcode and with Command Line Tools selected; no licence or selection fix is currently needed for other workers. `xcodebuild -checkFirstLaunchStatus` also succeeded through the check script. No iOS Simulator runtime is installed, though the simulator SDK was sufficient for the generic build below. Available disk was 695 GiB on the later check; other work was running concurrently on the host.

DevEco's executable is `/Applications/DevEco-Studio.app/Contents/MacOS/devecostudio`. The tools found inside the app are:

```text
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw
/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node
/Applications/DevEco-Studio.app/Contents/sdk
```

The searched Auri command-line-tools target, `~/.local/bin/auri-harmony-6.0.1.251/command-line-tools`, and `~/Library/Huawei` and `~/Library/HarmonyOS` were absent. In the app SDK, all ten `default/{openharmony,hms}/{ets,js,native,previewer,toolchains}` package metadata files reported `apiVersion=26 platformVersion=26.0.1 releaseType=Beta1 version=26.0.1.120`. For example, both ETS metadata files contain:

```json
"apiVersion": "26",
"platformVersion": "26.0.1",
"releaseType": "Beta1",
"version": "26.0.1.120"
```

This is not the probe's pinned DevEco Studio 6.0.1 Release and HarmonyOS 6.0.1.112/API 21 Release SDK. Reading the app metadata and executable proves they exist, not that the DevEco GUI was opened or tested.

## Environment and checks

The reviewable files `ios-env.sh` and `harmony-env.sh` beside this record were copied to `~/.config/auri/`. Their existing selection logic already resolves the actual app paths on this host; the Harmony comment was corrected because the current installation is not the pinned release. No other environment logic or check expectations changed. The optional older CLI target remains supported if the owner installs it later. Both files were sourced twice in fresh `bash -lc` and `zsh -lc` sessions; each printed `PATH idempotency: OK` and selected:

```text
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
DEVECO_APP=/Applications/DevEco-Studio.app
DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
NODE_HOME=/Applications/DevEco-Studio.app/Contents/tools/node
$ xcodebuild -version
Xcode 27.2
Build version 27B52
$ hvigorw --version
6.26.20
$ ohpm -v
26.0.1.220
$ node -v
v24.14.1
```

Each version command exited 0 in both shells. The executable commands work, but this does not establish compatibility of these newer Harmony tools with the pinned release.

`execution/baseline/check-apple-harmony-toolchain.sh` exited 1 and reported:

```text
Xcode: OK Xcode 27.2 Build version 27B52
iOS SDK: OK 27.2
Xcode first launch: OK completed
iOS Simulator: BLOCKED no available iOS runtime
XcodeGen: OK Version: 2.46.0
DevEco Studio: BLOCKED requires an executable 6.0.1 app; observed 26.0.1
HarmonyOS SDK: BLOCKED HarmonyOS API 21 / 6.0.1.112 not verified
hvigorw: BLOCKED requires 6.21.1; observed 6.26.20
ohpm: BLOCKED requires 6.0.1; observed 26.0.1.220
DevEco Node: OK v24.14.1
```

`execution/baseline/test-apple-harmony-toolchain.sh` exited 0: `Toolchain check fixture passed (fake tools; not an installation proof).` The requirements were left unchanged: the installed Harmony versions differ from the probe's pin and the attempted build failed, so compatibility has not been established.

## Real builds

Only one of these builds ran at a time. Both used temporary copies and no signing credentials.

### iOS simulator: OK

The following commands ran from a temporary copy, as in the earlier record:

```sh
source ~/.config/auri/ios-env.sh
probe_tmp=$(mktemp -d "${TMPDIR:-/tmp}/auri-ios-build.XXXXXXXX")
ditto execution/p0-spikes/ios-probe "$probe_tmp/ios-probe"
cd "$probe_tmp/ios-probe"
xcodegen generate --spec project.yml --project .
xcodebuild -project AuriProbeIOS.xcodeproj -scheme AuriProbeIOS \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$probe_tmp/derived" \
  CODE_SIGNING_ALLOWED=NO build
```

`xcodegen` exited 0 and `xcodebuild` exited 0 with `** BUILD SUCCEEDED **`, producing `derived/Build/Products/Debug-iphonesimulator/AuriProbeIOS.app`. This is a compile/build result, not a simulator run or a device test. The probe README pins Xcode 27.1 (27A9275) and iOS SDK 27.1; this build used Xcode 27.2 (27B52) and the 27.2 simulator SDK.

### HarmonyOS: FAILED

A separate temporary copy ran:

```sh
source ~/.config/auri/harmony-env.sh
probe_tmp=$(mktemp -d "${TMPDIR:-/tmp}/auri-harmony-build.XXXXXXXX")
ditto execution/p0-spikes/harmonyos-probe "$probe_tmp/harmonyos-probe"
cd "$probe_tmp/harmonyos-probe"
ohpm install
hvigorw assembleHap -p buildMode=debug --no-daemon
```

`ohpm install` exited 0 (`install completed in 0s 26ms`). `hvigorw` exited 255 after `Execution failed for task 'default@CompileArkTS'`, `COMPILE RESULT:FAIL {ERROR:70 WARN:17}` and `BUILD FAILED`; no HAP was produced. Its generated build log identified `apiVersion: 26` and SDK path `/Applications/DevEco-Studio.app/Contents/sdk`, even though the project targets `6.0.1(21)`. The compiler printed 69 numbered diagnostics: 67 reject untyped object literals, one says `Property 'pressedTime' does not exist on type 'KeyEvent'` in `AVSessionProbe.ets:120:43`, and one rejects an `ARMED`/`BLOCKED` versus `ROUTING` comparison in `CaptureProbe.ets:224:11`. The complete captured output, with terminal colours and trailing whitespace removed and the temporary path normalized, is in [harmonyos-probe-build-2026-11-18.log](harmonyos-probe-build-2026-11-18.log). The build stopped before signing, so signing or account requirements were not established. It also printed `Installing pnpm@10.28.2...` followed by `1 high severity vulnerability`; no audit fix was run.

## Owner actions and remaining work

1. **`BLOCKED_EXTERNAL`: install an iOS Simulator runtime** for simulator execution/tests. The owner can run `xcodebuild -downloadPlatform iOS`, then `xcrun simctl list runtimes`. A runtime was not needed for the successful generic build.
2. **`BLOCKED_EXTERNAL`: provide the pinned Harmony toolchain.** Obtain Huawei's DevEco Studio 6.0.1.251 for Mac ARM and matching Command Line Tools 6.0.1.251 archive and their Huawei-published SHA-256 values. The earlier survey could not verify the official download page or archive hashes from this host. If sign-in is required, the owner must sign in on Huawei's download page. The existing 26.0.1 app occupies the installer's target; preserve it first rather than overwrite it. At this survey `/Applications` was writable and `~/Applications/DevEco-Studio-26.0.1.app` did not exist. After obtaining the archives and verifying that destination is still free, the owner can run:

   ```sh
   mkdir -p "$HOME/Applications"
   mv /Applications/DevEco-Studio.app "$HOME/Applications/DevEco-Studio-26.0.1.app"
   execution/baseline/finish-deveco-install.sh \
     "$HOME/Downloads/<actual Studio archive name>" '<Huawei Studio SHA-256>' \
     "$HOME/Downloads/commandline-tools-mac-arm64-6.0.1.251.zip" '<Huawei CLI SHA-256>'
   ```

   Replace the placeholders with the actual downloaded filename and Huawei-published hashes. The installer refuses to overwrite an existing target and its real Huawei archive layout remains unverified. If the needed API 21 SDK is absent, install HarmonyOS 6.0.1 Release/API 21 including ETS, native and toolchains via DevEco Studio's SDK Manager. Re-run the check and the temporary Harmony build above. The present compiler errors may also require a separate probe-code fix; installing the pinned version has not been shown to fix them.
3. **`BLOCKED_EXTERNAL`: quit the pre-existing Xcode GUI.** PID 80211 started at 11:53:41, before this survey; a graceful `osascript` request to quit timed out with `AppleEvent已超时 (-1712)`, and the process remained. In Xcode choose **Xcode → Quit Xcode** and resolve any save dialog; if it is unresponsive, use **Apple menu → Force Quit → Xcode** after preserving needed work. No Simulator GUI, DevEco GUI, hvigor or emulator process was found in the final process check, and no booted simulator was listed. CoreSimulator services and the Xcode developer policy service were present and had started before this survey. This verification did not force-terminate a pre-existing app or another worker's processes.

4. **`BLOCKED_EXTERNAL`: coordinate Gradle shutdown with the Android worker.** A Gradle 8.10.2 daemon, PID 35342, started at 12:04:28 after the earlier process check. This task ran no Gradle build and the Android worker is active, so it was left alone to avoid interrupting that work. Once the Android worker has finished, the owner can run:

   ```sh
   "$HOME/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle" --stop
   ps -p 35342 -o pid,command
   ```

Device signing, simulator execution, hardware behavior, and a successful HarmonyOS HAP remain unverified.
