#!/bin/bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/auri-toolchain-test.XXXXXXXX")
trap 'rm -rf "$fixture"' EXIT
export HOME="$fixture/home"
export PATH="$fixture/bin:/usr/bin:/bin:/usr/sbin:/sbin"
sdk="$HOME/sdk/default"
mkdir -p "$fixture/bin" "$HOME/.config/auri" "$HOME/DevEco-Studio.app/Contents/MacOS" \
  "$HOME/node/bin" "$sdk/hms/ets" "$sdk/openharmony/ets" \
  "$sdk/openharmony/native" "$sdk/openharmony/toolchains"
cat > "$HOME/.config/auri/harmony-env.sh" <<'EOF'
export DEVECO_APP="$HOME/DevEco-Studio.app"
export DEVECO_SDK_HOME="$HOME/sdk"
export NODE_HOME="$HOME/node"
EOF
cat > "$HOME/DevEco-Studio.app/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleShortVersionString</key><string>6.0.1.251</string><key>CFBundleExecutable</key><string>deveco-studio</string></dict></plist>
EOF
printf '%s\n' '{}' > "$sdk/hms/ets/uni-package.json"
printf '#!/bin/bash\nexit 0\n' > "$HOME/DevEco-Studio.app/Contents/MacOS/deveco-studio"
printf '%s\n' '{"apiVersion":21,"version":"6.0.1.112"}' > "$sdk/openharmony/ets/oh-uni-package.json"
cat > "$fixture/bin/xcodebuild" <<'EOF'
#!/bin/bash
if [ "$1" = -version ]; then printf 'Xcode 27.2\nBuild version 27B52\n';
elif [ -e "$HOME/block-first-launch" ]; then exit 1; fi
EOF
cat > "$fixture/bin/xcrun" <<'EOF'
#!/bin/bash
if [ "$1" = --sdk ]; then echo 27.2; else echo 'iOS 27.2 (27.2) - com.apple.CoreSimulator.SimRuntime.iOS-27-2'; fi
EOF
printf '#!/bin/bash\necho "Version: 2.46.0"\n' > "$fixture/bin/xcodegen"
printf '#!/bin/bash\necho "hvigor 6.21.1"\n' > "$fixture/bin/hvigorw"
printf '#!/bin/bash\necho 6.0.1\n' > "$fixture/bin/ohpm"
printf '#!/bin/bash\necho v22.0.0\n' > "$HOME/node/bin/node"
chmod +x "$fixture/bin/"* "$HOME/node/bin/node" "$HOME/DevEco-Studio.app/Contents/MacOS/deveco-studio"

output=$("$script_dir/check-apple-harmony-toolchain.sh")
printf '%s\n' "$output" | grep -q '^HarmonyOS SDK: OK '
printf '%s\n' "$output" | grep -q '^iOS Simulator: OK '
[ "$(printf '%s\n' "$output" | wc -l | tr -d ' ')" -eq 10 ]

printf '%s\n' '{"apiVersion":20,"version":"6.0.1.112"}' > "$sdk/openharmony/ets/oh-uni-package.json"
touch "$HOME/block-first-launch"
if output=$("$script_dir/check-apple-harmony-toolchain.sh"); then
  echo 'Expected missing first launch and mismatched SDK to fail.' >&2
  exit 1
fi
printf '%s\n' "$output" | grep -q '^Xcode first launch: BLOCKED '
printf '%s\n' "$output" | grep -q '^HarmonyOS SDK: BLOCKED '
printf '%s\n' "$output" | grep -q '^DevEco Node: OK '
echo 'Toolchain check fixture passed (fake tools; not an installation proof).'
