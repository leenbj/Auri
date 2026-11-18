# Apple and HarmonyOS toolchains on the Mac mini

Surveyed 2026-11-18 on branch `ticket/00-apple-harmony-toolchain`, based on `origin/integration/auri-v2` at `93b902450027d5c4073a8cd55a3bd72dcddd8a59`. The full toolchains are **not installed**: their external blockers and the owner steps are below. No credentials or license acceptances were supplied by the worker.

## Machine and initial survey

macOS 27.2 (26B123), arm64, Apple M4, 16 GiB RAM. `df -h /` showed 954 GiB total and **735 GiB available before, 734 GiB on the final survey**; the requested Xcode (~35 GB plus ~8 GB Simulator) and DevEco/SDK (~15 GB) budgets fit. Other work was running on the host, so the change is not attributable solely to these installs. Only one of this task's downloads, installs or builds ran at a time.

At the start, `/Applications` and `~/Applications` had no Xcode or DevEco Studio app; `xcode-select -p` returned `/Library/Developer/CommandLineTools`. `xcodebuild -version` said that full Xcode is required, and `xcrun simctl list runtimes` could not find `simctl`. `ohpm`, `hvigorw`, `mas`, `xcodes` and `xcodegen` were absent; `node -v` was `v24.22.2`. Passwordless sudo was unavailable: `sudo -n true` returned `sudo: a password is required`.

Initial `brew list --versions`, before this task installed three formulae:

```text
brotli 1.2.1; bun 1.4.4_1; c-ares 1.34.9; ca-certificates 2026-11-05; cairo 1.18.6; cmake 4.5.0; fontconfig 2.18.3; freetype 2.15.0; gettext 1.0; gh 2.103.3; giflib 6.1.3; glib 2.90.1; graphite2 1.3.15; harfbuzz 15.1.0; hdrhistogram_c 0.12.1; icu4c@78 78.3_1; icu4c@79 79.1; jpeg-turbo 3.2.1; json-c 0.19; krb5 1.22.2_2; libnghttp2 1.71.0; libnghttp3 1.19.0; libpng 1.6.59; libpq 18.6_2; libtiff 4.7.2; libunistring 1.5; libuv 1.53.0; libx11 1.8.14; libxau 1.0.12; libxcb 1.17.0; libxdmcp 1.1.5; libxext 1.3.7; libxrender 0.9.13; little-cms2 2.20; lz4 1.11.0; lzo 2.10; ninja 1.13.2; node@22 22.23.4_2; node@24 24.22.2_2; openjdk@17 17.0.21.0.3; openssl@3 3.5.9; openssl@4 4.1.0; pcre2 10.49; pixman 0.46.4; readline 8.3.1; redis 8.10.3; simdjson 5.1.1; sqlite 3.54.1; supabase 3.0.0; uv 0.13.7; uvwasi 0.0.23; webp 1.6.0; xorgproto 2026.1; xz 5.8.4; zstd 1.5.7_1; android-commandlinetools 15859902
```

The shell's system Node is available, but its compatibility with DevEco 6.0.1 is **not verified**; the Harmony environment selects the vendor-bundled Node when installed.

## Current results

| Component | Status | Evidence |
| --- | --- | --- |
| Xcode | `BLOCKED_EXTERNAL: Apple ID sign-in` | No full Xcode; `xcodes install --latest --no-superuser --no-aria2` selected 27.2 and stopped at `Apple ID: Missing username or a password`. No username or password was entered. `mas account` cannot establish App Store state: mas 7.0.0 returns `Unexpected argument 'account'`. |
| Xcode selection, first launch and license | `BLOCKED_EXTERNAL: sudo password / owner license` | `sudo -n true` requires a password. The worker did not run privileged setup or accept the license. |
| iOS SDK / Simulator | `BLOCKED_EXTERNAL` | `xcrun --sdk iphoneos --show-sdk-version` reports SDK not found; `xcrun simctl list runtimes` reports `simctl` not found. No iOS Simulator runtime was downloaded. |
| XcodeGen | **OK**, 2.46.0 | `xcodegen --version` returned `Version: 2.46.0`; it generated the probe project in a temporary copy. |
| DevEco Studio | `BLOCKED_EXTERNAL: Huawei download access` | Not installed; version command **NOT_RUN**. Huawei download pages returned HTTP 403 to this machine, so an official 6.0.1.251 Mac ARM file URL and Studio archive filename could not be verified. Whether sign-in alone resolves the 403 is unknown. |
| HarmonyOS SDK | `BLOCKED_EXTERNAL` | Not installed; API 21 / `Ohos_sdk_public 6.0.1.112` is the probe's pinned target, not an observed installed version. |
| `hvigorw` / `ohpm` | `BLOCKED_EXTERNAL` | Neither is installed, so their version commands are **NOT_RUN**. Huawei documents 6.21.1 and 6.0.1 respectively for the 6.0.1.251 command-line bundle. |
| DevEco-bundled Node | `BLOCKED_EXTERNAL` | Not installed; version command **NOT_RUN**. |

`xcodes list` offered stable Xcode 27.2 (27B52); Apple's [27.2 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-27_2-release-notes) require macOS 26.6 or later, so this host meets the OS requirement. The existing probe README pins Xcode 27.1, so a build on 27.2 remains unverified. Huawei's [6.0.1 compatibility table](https://developer.huawei.com/consumer/en/doc/harmonyos-releases/deveco-studio-new-features-601/) identifies the matching 6.0.1.251 tools, and its [6.0.1 release notes](https://developer.huawei.com/consumer/cn/doc/harmonyos-releases/overview-601) identify API 21 / `6.0.1.112`. The probe's minimum compatible system is API 12; the application specification requires the SDK version to be pinned.

As an additional direct-download probe, `curl -I` of the official `contentcenter-vali-drcn.dbankcdn.cn` Mac ARM DevEco 4.0.0.600 archive linked from the [OpenHarmony 4.0 release notes](https://api.gitee.com/openharmony/docs/blob/master/zh-cn/release-notes/OpenHarmony-v4.0-release.md) also returned `HTTP/1.1 403 Forbidden`. That legacy archive cannot satisfy the required 6.0.1 toolchain; no official 6.0.1 direct asset URL was discoverable from this host.

## Installed files, sources and SHA-256

Homebrew installed these bottles, with automatic cleanup disabled. Sizes below are measured installed directories; the SHA-256 values are measured on the corresponding cached bottle archives.

| Tool | Version / installed size | Bottle SHA-256 |
| --- | --- | --- |
| [XcodeGen](https://formulae.brew.sh/formula/xcodegen) | 2.46.0, 7.3 MiB, `/opt/homebrew/Cellar/xcodegen/2.46.0` | `cc5980a1ffd679edf00e4204473b1cfa8c3abcce69c22c084d4d079b277e248b` |
| [mas](https://formulae.brew.sh/formula/mas) | 7.0.0, 11 MiB, `/opt/homebrew/Cellar/mas/7.0.0` | `641455eac9e2dfeaa0cff4238d4d644f6c2b91d25492e95c1ebc4461967eab97` |
| [xcodes](https://formulae.brew.sh/formula/xcodes) | 2.2.0, 10 MiB, `/opt/homebrew/Cellar/xcodes/2.2.0` | `81c13eb2a30b3eaaa45888af463df13ca68dca033d665dfc92be4544ad65b199` |

Homebrew provided the exact pinned XcodeGen version. The upstream [2.46.0 GitHub release](https://github.com/yonaskolb/XcodeGen/releases/tag/2.46.0) reports `xcodegen.zip` SHA-256 `4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806`; that zip was **not** downloaded, so this is its published hash, not the installed bottle hash. No Xcode, Simulator, DevEco or Harmony archive was downloaded, so no installed archive size or hash is available for them.

## Owner completion: Xcode and Simulator

1. In **App Store → Sign In**, sign in with your Apple ID. Search for **Xcode**, choose **Get/Install**, and wait for the app to finish installing at `/Applications/Xcode.app`. Alternatively, after signing in and obtaining Xcode in the App Store, `sudo mas install 497799835` can install it; mas 7 reports that installation needs root. Do not use `mas signin`, which this mas version does not provide.
2. In Terminal, run the following. The license commands are for the owner to review and, only if they agree, accept; no agent has accepted it.

```sh
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -license
# If you have reviewed and agree to the license and it is still pending:
sudo xcodebuild -license accept
sudo xcodebuild -runFirstLaunch
xcodebuild -downloadPlatform iOS
source ~/.config/auri/ios-env.sh
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version
xcrun simctl list runtimes
```

To prove the probe builds without changing its tracked directory, from the repository root run:

```sh
probe_tmp=$(mktemp -d "${TMPDIR:-/tmp}/auri-ios-build.XXXXXXXX")
ditto execution/p0-spikes/ios-probe "$probe_tmp/ios-probe"
cd "$probe_tmp/ios-probe"
xcodegen generate --spec project.yml --project .
xcodebuild -project AuriProbeIOS.xcodeproj -scheme AuriProbeIOS \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$probe_tmp/derived" \
  CODE_SIGNING_ALLOWED=NO build
```

No simulator build or device signing was attempted here.

## Owner completion: HarmonyOS

1. Visit the official [Huawei download center](https://developer.huawei.com/consumer/cn/download/), sign in if required, and locate **DevEco Studio 6.0.1.251 for Mac ARM** and the matching **Command Line Tools 6.0.1.251 for Mac ARM**. Download both into `~/Downloads`, and copy each archive's SHA-256 from Huawei's page. The expected CLI filename is `commandline-tools-mac-arm64-6.0.1.251.zip`; this name was observed in public package metadata, but the inaccessible Huawei page did not independently confirm it. The Studio archive's exact filename could not be observed: use the actual name displayed by Huawei. If that older version or its official hashes are unavailable, keep this item `BLOCKED_EXTERNAL` rather than substituting an unverified download.
2. From the repository root, substitute the actual Studio filename and both **Huawei-published** SHA-256 values in the one command below. Never paste credentials or tokens into the command.

```sh
execution/baseline/finish-deveco-install.sh \
  "$HOME/Downloads/<actual Studio archive name>" '<Studio SHA-256>' \
  "$HOME/Downloads/commandline-tools-mac-arm64-6.0.1.251.zip" '<CLI SHA-256>'
```

The script verifies both hashes, checks the Studio signature and architecture, stages the archives, validates the bundled API 21 SDK metadata, installs the app and CLI tools without overwriting existing targets, installs the Harmony environment file, and runs the complete check. It expects the SDK to be bundled in the CLI archive, as described by Huawei; it does **not** automatically accept any license or sign into Huawei. Its archive-layout, signature and actual installation paths are **NOT_RUN** with real Huawei archives; if the vendor package differs, or an SDK/license click is required, it stops and needs owner attention. `--dry-run` works without downloads and makes no changes.

The CLI installation target is `~/.local/bin/auri-harmony-6.0.1.251/command-line-tools`; the Studio target is `/Applications/DevEco-Studio.app`, falling back to `~/Applications` if `/Applications` is not writable. If the archive lacks SDK components, use DevEco Studio's SDK manager to install the HarmonyOS 6.0.1 API 21 Release SDK, including ArkTS/ETS, native and toolchains, then rerun the check; do not assume an OpenHarmony-only SDK is sufficient. Further GUI or license steps have not been verified on this machine.

## Verification and removal

The environment files are `~/.config/auri/ios-env.sh` and `~/.config/auri/harmony-env.sh`, with reviewable copies beside this record. Source each as needed; sourcing twice was checked in bash and zsh. No shell profile was edited. Run:

```sh
source ~/.config/auri/ios-env.sh
source ~/.config/auri/harmony-env.sh
execution/baseline/check-apple-harmony-toolchain.sh
execution/baseline/test-apple-harmony-toolchain.sh
```

The check runs every probe, reports one `OK`, `MISSING` or `BLOCKED` line per tool, and exits 0 only if all pass. Its first run before any installation exited 1 with XcodeGen missing; the final real-machine run exits 1 with XcodeGen OK and the other requested tools blocked or missing. The fixture test uses fake tools and SDK metadata solely to verify status and exit behavior; it is not evidence that either actual platform builds. The Harmony installer dry-run and a checksum-mismatch rejection were tested, but installation was not.

To remove only this task's Homebrew tools, run `brew uninstall xcodegen xcodes mas` after checking whether anything else now depends on them. The owner may remove the two Auri environment files and, if subsequently installed with this procedure, move the DevEco or Xcode app to the Trash and remove `~/.local/bin/auri-harmony-6.0.1.251`; retain any SDK, runtime, caches or projects that should not be removed. No other project files were changed.

Both requested skills were found and read at `~/.claude/plugins/cache/claude-plugins-official/mattpocock-skills/1.3.1/skills/engineering/{implement-spec,tdd}/SKILL.md`; the task's explicit prohibition on helpers, PRs and questions overrides the generic skill workflow.
