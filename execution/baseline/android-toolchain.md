# Android toolchain on the Mac mini

Installed 2026-11-18 on macOS arm64, without sudo. Activate it in each shell:

```sh
source ~/.config/auri/android-env.sh
```

The file exports `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME`, and the required executable paths. The SDK is at `~/Library/Android/sdk`; its licenses are accepted.

## Installed versions and locations

| Package | Version | Location |
| --- | --- | --- |
| Homebrew OpenJDK 17 | 17.0.21.0.3 | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Homebrew CMake | 4.5.0 | `/opt/homebrew/Cellar/cmake/4.5.0` |
| Homebrew Ninja | 1.13.2 | `/opt/homebrew/Cellar/ninja/1.13.2` |
| Homebrew Android command-line tools cask | 15859902; tools revision 22.0 | `/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest`; a copy lives at `$ANDROID_HOME/cmdline-tools/latest` |
| Android platform-tools | 37.0.1 | `$ANDROID_HOME/platform-tools` |
| Android platform 35 | revision 2 | `$ANDROID_HOME/platforms/android-35` |
| Android build-tools | 35.0.0 | `$ANDROID_HOME/build-tools/35.0.0` |
| NDK r27d | 27.3.13750724 | `$ANDROID_HOME/ndk/27.3.13750724` |
| Android Emulator | 37.2.13 | `$ANDROID_HOME/emulator` |
| Google APIs ARM64 Android 35 system image | revision 9 | `$ANDROID_HOME/system-images/android-35/google_apis/arm64-v8a` |

Homebrew installed these additional dependencies in `/opt/homebrew/Cellar/<name>/<version>`: cairo 1.18.6, fontconfig 2.18.3, freetype 2.15.0, gettext 1.0, giflib 6.1.3, glib 2.90.1, graphite2 1.3.15, harfbuzz 15.1.0, jpeg-turbo 3.2.1, json-c 0.19, libpng 1.6.59, libtiff 4.7.2, libunistring 1.5, libx11 1.8.14, libxau 1.0.12, libxcb 1.17.0, libxdmcp 1.1.5, libxext 1.3.7, libxrender 0.9.13, little-cms2 2.20, lzo 2.10, pcre2 10.49, pixman 0.46.4, webp 1.6.0, and xorgproto 2026.1. Existing Homebrew formulae were unchanged; in particular lz4 stayed at 1.11.0.

Measured directory sizes: SDK 7.9 GiB, Homebrew command-line tools 173 MiB, and the new Homebrew formulae together about 558 MiB. APFS clones can share storage between the two command-line tools directories; Homebrew download caches are additional.

## Verification and notes

- `java -version`: OpenJDK 17.0.21.0.3; `sdkmanager --list_installed` reported all six Android SDK packages in the table.
- `adb version`: 1.0.41 / platform-tools 37.0.1-15733141; `cmake --version`: 4.5.0; `ninja --version`: 1.13.2.
- NDK Clang 18.0.4 compiled a C file for `aarch64-linux-android35` into an ARM64 ELF object. Homebrew CMake and Ninja also built an ARM64 shared library with the NDK toolchain. Temporary test files were removed.
- The Android probe was copied to a temporary directory and built with its Gradle 8.10.2 wrapper: `:app:assembleDebug :app:testDebugUnitTest` succeeded; 25 unit tests passed. Android Gradle plugin 8.8.0 warned that it understands SDK XML only through version 3 while version 4 was encountered.
- The NDK toolchain emits CMake deprecation warnings, but the native build succeeded, so an SDK CMake package was not needed. Command-line tools 22.0 mark `sdkmanager` deprecated in favor of `android sdk`; the requested `sdkmanager` commands worked.
- A download connection failed once; the segment was retried and the Google archives were checksum-verified. Installation used `sdkmanager` through a temporary loopback mirror of the verified Google packages; no mirror configuration remains.
- The SDK command-line tools are a copy of the cask files so `sdkmanager` selects the home-directory SDK by default; updating the cask does not update that copy automatically.
- No emulator was created or started, and no phone was contacted. TDD does not apply: this was machine setup with no product-code change.
