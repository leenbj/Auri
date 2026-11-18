#!/bin/bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
cli_name=commandline-tools-mac-arm64-6.0.1.251.zip
cli_target="$HOME/.local/bin/auri-harmony-6.0.1.251"
if [ -w /Applications ]; then apps_dir=/Applications; else apps_dir="$HOME/Applications"; fi
app_target="$apps_dir/DevEco-Studio.app"
env_file="$HOME/.config/auri/harmony-env.sh"

if [ "${1:-}" = --dry-run ]; then
  printf 'Studio archive: %s\n' 'download DevEco Studio 6.0.1.251 for Mac ARM from Huawei'
  if [ -f "$HOME/Downloads/$cli_name" ]; then
    printf 'Command-line archive: present: %s\n' "$HOME/Downloads/$cli_name"
  else
    printf 'Command-line archive: MISSING: %s\n' "$HOME/Downloads/$cli_name"
  fi
  printf 'Targets: %s and %s\n' "$app_target" "$cli_target"
  printf '%s\n' 'Supply both archive paths and the SHA-256 values published by Huawei; no files changed.'
  exit 0
fi

if [ "$#" -ne 4 ]; then
  echo 'Usage: finish-deveco-install.sh STUDIO_ARCHIVE STUDIO_SHA256 CLI_ARCHIVE CLI_SHA256' >&2
  echo 'Copy the SHA-256 values from the Huawei download page, not from the downloaded archives.' >&2
  exit 2
fi
studio_archive=$1
studio_sha=$2
cli_archive=$3
cli_sha=$4

verify_archive() {
  local file=$1 expected=$2 actual
  [ -f "$file" ] || { echo "Missing archive: $file" >&2; exit 1; }
  [[ "$expected" =~ ^[[:xdigit:]]{64}$ ]] || { echo 'Expected a 64-digit SHA-256 from Huawei.' >&2; exit 1; }
  actual=$(shasum -a 256 "$file" | awk '{print $1}')
  if [ "$actual" != "$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')" ]; then
    echo "SHA-256 mismatch for $file: $actual" >&2
    exit 1
  fi
  printf 'Verified SHA-256: %s  %s\n' "$actual" "$file"
}

[ "$(basename "$cli_archive")" = "$cli_name" ] || { echo "Expected $cli_name" >&2; exit 1; }
[ ! -e "$app_target" ] || { echo "Refusing to overwrite $app_target" >&2; exit 1; }
[ ! -e "$cli_target" ] || { echo "Refusing to overwrite $cli_target" >&2; exit 1; }
if [ -e "$env_file" ] && ! cmp -s "$env_file" "$script_dir/harmony-env.sh"; then
  echo "Refusing to overwrite a different $env_file" >&2
  exit 1
fi
available_kib=$(df -Pk / | awk 'NR == 2 {print $4}')
[ "$available_kib" -ge 15728640 ] || { echo "BLOCKED_EXTERNAL: disk: $available_kib KiB available; at least 15 GiB required" >&2; exit 1; }
verify_archive "$studio_archive" "$studio_sha"
verify_archive "$cli_archive" "$cli_sha"

stage=$(mktemp -d "${TMPDIR:-/tmp}/auri-deveco.XXXXXXXX")
mounted=0
cleanup() {
  if [ "$mounted" -eq 1 ] && ! hdiutil detach "$stage/mount" -quiet; then
    echo "Could not detach $stage/mount; detach it before removing $stage" >&2
    return 1
  fi
  rm -rf "$stage"
}
trap cleanup EXIT
mkdir -p "$stage/studio" "$stage/cli" "$stage/mount"
case "$studio_archive" in
  *.zip) ditto -x -k "$studio_archive" "$stage/studio" ;;
  *.dmg) hdiutil attach -readonly -nobrowse -quiet -mountpoint "$stage/mount" "$studio_archive"; mounted=1 ;;
  *) echo 'The Studio archive must be a .zip or .dmg.' >&2; exit 1 ;;
esac
studio_app=$(find "$stage/studio" "$stage/mount" -maxdepth 5 -type d -name 'DevEco-Studio*.app' -print -quit)
if [ -z "$studio_app" ] && [ "$mounted" -eq 0 ]; then
  image=$(find "$stage/studio" -maxdepth 5 -type f -name '*.dmg' -print -quit)
  if [ -n "$image" ]; then
    hdiutil attach -readonly -nobrowse -quiet -mountpoint "$stage/mount" "$image"
    mounted=1
    studio_app=$(find "$stage/mount" -maxdepth 5 -type d -name 'DevEco-Studio*.app' -print -quit)
  fi
fi
[ -n "$studio_app" ] || { echo 'DevEco Studio app not found in archive.' >&2; exit 1; }
version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$studio_app/Contents/Info.plist")
case "$version" in 6.0.1*) ;; *) echo "Expected DevEco Studio 6.0.1; found $version" >&2; exit 1;; esac
binary=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$studio_app/Contents/Info.plist")
lipo -archs "$studio_app/Contents/MacOS/$binary" | grep -qw arm64 || { echo 'DevEco Studio is not an arm64 app.' >&2; exit 1; }
codesign --verify --deep --strict "$studio_app"

ditto -x -k "$cli_archive" "$stage/cli"
cli="$stage/cli/command-line-tools"
for path in bin/hvigorw bin/ohpm tool/node/bin/node sdk/default/openharmony/ets/oh-uni-package.json sdk/default/openharmony/native sdk/default/openharmony/toolchains sdk/default/hms/ets/uni-package.json; do
  [ -e "$cli/$path" ] || { echo "Archive is missing $path" >&2; exit 1; }
done
python3 - "$cli/sdk/default/openharmony/ets/oh-uni-package.json" <<'PY'
import json, sys
with open(sys.argv[1]) as package:
    data = json.load(package)
if str(data.get('apiVersion')) != '21' or data.get('version') != '6.0.1.112':
    sys.exit('Archive does not contain the pinned API 21 / 6.0.1.112 SDK.')
PY

mkdir -p "$apps_dir" "$(dirname "$cli_target")" "$(dirname "$env_file")"
[ -e "$env_file" ] || cp "$script_dir/harmony-env.sh" "$env_file"
ditto "$studio_app" "$app_target"
mv "$stage/cli" "$cli_target"
echo "Installed DevEco Studio $version at $app_target and CLI tools at $cli_target."
echo 'The following check also reports any still-blocked Apple components.'
"$script_dir/check-apple-harmony-toolchain.sh"
