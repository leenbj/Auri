#!/bin/bash
# Check every requested tool even when an earlier tool is unavailable.
status=0
[ ! -f "$HOME/.config/auri/ios-env.sh" ] || . "$HOME/.config/auri/ios-env.sh"
[ ! -f "$HOME/.config/auri/harmony-env.sh" ] || . "$HOME/.config/auri/harmony-env.sh"

report() {
  local detail
  detail=$(printf '%s' "$3" | tr '\r\n' '  ')
  printf '%s: %s %s\n' "$1" "$2" "$detail"
  [ "$2" = OK ] || status=1
}

if version=$(xcodebuild -version 2>/dev/null); then
  report Xcode OK "$(printf '%s' "$version" | tr '\n' ' ')"
else
  report Xcode MISSING 'full Xcode unavailable'
fi
if version=$(xcrun --sdk iphoneos --show-sdk-version 2>/dev/null); then
  report 'iOS SDK' OK "$version"
else
  report 'iOS SDK' BLOCKED 'full Xcode and its first launch are required'
fi
if xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1; then
  report 'Xcode first launch' OK 'completed'
else
  report 'Xcode first launch' BLOCKED 'license or first launch incomplete'
fi
if runtimes=$(xcrun simctl list runtimes 2>/dev/null) && printf '%s\n' "$runtimes" | grep -E 'iOS .*\(' | grep -v -i unavailable >/dev/null; then
  report 'iOS Simulator' OK "$(printf '%s\n' "$runtimes" | grep -E 'iOS .*\(' | grep -v -i unavailable | tr '\n' ' ')"
else
  report 'iOS Simulator' BLOCKED 'no available iOS runtime'
fi
if version=$(xcodegen --version 2>/dev/null) && [ "$version" = 'Version: 2.46.0' ]; then
  report XcodeGen OK "$version"
else
  report XcodeGen MISSING "requires 2.46.0; observed ${version:-none}"
fi

deveco_app=${DEVECO_APP:-/Applications/DevEco-Studio.app}
if [ -d "$deveco_app" ]; then
  version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$deveco_app/Contents/Info.plist" 2>/dev/null)
  binary=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$deveco_app/Contents/Info.plist" 2>/dev/null)
  if [[ "$version" = 6.0.1* ]] && [ -n "$binary" ] && [ -f "$deveco_app/Contents/MacOS/$binary" ] && [ -x "$deveco_app/Contents/MacOS/$binary" ]; then
    report 'DevEco Studio' OK "$version"
  else
    report 'DevEco Studio' BLOCKED "requires an executable 6.0.1 app; observed ${version:-unknown}"
  fi
else
  report 'DevEco Studio' MISSING "$deveco_app"
fi
sdk=${DEVECO_SDK_HOME:-$deveco_app/Contents/sdk}
if [ -s "$sdk/default/hms/ets/uni-package.json" ] &&
   [ -d "$sdk/default/openharmony/native" ] &&
   [ -d "$sdk/default/openharmony/toolchains" ] &&
   python3 - "$sdk/default/openharmony/ets/oh-uni-package.json" 2>/dev/null <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as package:
        metadata = json.load(package)
    assert str(metadata['apiVersion']) == '21' and metadata['version'] == '6.0.1.112'
except (OSError, ValueError, KeyError, AssertionError):
    sys.exit(1)
PY
then
  report 'HarmonyOS SDK' OK '6.0.1.112 (API 21)'
else
  report 'HarmonyOS SDK' BLOCKED 'HarmonyOS API 21 / 6.0.1.112 not verified'
fi
if version=$(hvigorw -v 2>/dev/null) && printf '%s\n' "$version" | grep -Eq '(^|[^0-9.])6\.21\.1([^0-9.]|$)'; then
  report hvigorw OK "$(printf '%s' "$version" | tr '\n' ' ')"
else
  report hvigorw BLOCKED "requires 6.21.1; observed ${version:-none}"
fi
if version=$(ohpm --version 2>/dev/null) && printf '%s\n' "$version" | grep -Eq '(^|[^0-9.])6\.0\.1([^0-9.]|$)'; then
  report ohpm OK "$(printf '%s' "$version" | tr '\n' ' ')"
else
  report ohpm BLOCKED "requires 6.0.1; observed ${version:-none}"
fi
if [ -n "${NODE_HOME:-}" ] && [ -x "$NODE_HOME/bin/node" ] && version=$("$NODE_HOME/bin/node" -v 2>/dev/null); then
  report 'DevEco Node' OK "$version"
else
  report 'DevEco Node' BLOCKED 'bundled Node not verified'
fi
exit "$status"
