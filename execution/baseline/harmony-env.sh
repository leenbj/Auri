# Source this file to use the pinned HarmonyOS 6.0.1 toolchain.
if [ -d /Applications/DevEco-Studio.app ]; then
  export DEVECO_APP=/Applications/DevEco-Studio.app
elif [ -d "$HOME/Applications/DevEco-Studio.app" ]; then
  export DEVECO_APP="$HOME/Applications/DevEco-Studio.app"
else
  export DEVECO_APP=/Applications/DevEco-Studio.app
fi
export HARMONY_CLI_HOME="$HOME/.local/bin/auri-harmony-6.0.1.251/command-line-tools"
if [ -d "$HARMONY_CLI_HOME" ]; then
  export DEVECO_SDK_HOME="$HARMONY_CLI_HOME/sdk"
  export NODE_HOME="$HARMONY_CLI_HOME/tool/node"
else
  export DEVECO_SDK_HOME="$DEVECO_APP/Contents/sdk"
  export NODE_HOME="$DEVECO_APP/Contents/tools/node"
fi
for auri_harmony_path in "$DEVECO_APP/Contents/tools/hvigor/bin" "$DEVECO_APP/Contents/tools/ohpm/bin" "$DEVECO_SDK_HOME/default/openharmony/toolchains" "$HARMONY_CLI_HOME/bin" "$NODE_HOME/bin"; do
  if [ -d "$auri_harmony_path" ]; then
    case ":$PATH:" in
      *":$auri_harmony_path:"*) ;;
      *) PATH="$auri_harmony_path:$PATH" ;;
    esac
  fi
done
export PATH
unset auri_harmony_path
