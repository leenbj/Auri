---
status: accepted
---
# ADR-0015：Handy 只作为处理流程的参考，不 fork 其代码

产品负责人要求“以 Handy（cjpais/Handy，MIT）为基础”开发手机端语音转文字。核对后确认 Handy 是 Tauri（Rust + React）桌面应用，只支持 Windows、macOS、Linux，核心交互是全局键盘快捷键和自动粘贴，这两样在手机上都不存在。因此决定：Auri 借用 Handy 的处理流程设计，即“一次录音内用 Silero VAD 过滤静音 → 本地模型转录 → 保存历史记录”，三端仍按 PRD 第 15.1 节各自原生实现（ArkTS / Swift / Kotlin），不 fork、不移植 Handy 的代码。

## Considered Options

- **fork Handy，用 Tauri 2 出手机包**：否决。Tauri 2 能出 iOS 和 Android 包，但未查到对鸿蒙 NEXT 的支持，等于放弃一个目标平台；耳机按键、蓝牙输入路由、后台生命周期本来就必须写原生代码，能复用的只剩界面和少量 Rust 管线。
- **只把 Handy 的 Rust 音频管线抽成共享库**：否决。PRD 已选 C/C++ 的 sherpa-onnx 作为共享本地引擎候选，再引入一套 Rust 管线会出现两套共享核心。

## Consequences

- Handy 列入 PRD 第 26.2 节开源候选表（OSS-15），定位与其他参考仓库相同：只借思路。
- Handy 的 README 把“macOS 上用蓝牙耳机麦克风会降低播放音质，建议改用内置麦克风”列为已知问题。这个建议与 Auri 的严格蓝牙输入相反，不得照搬。
- Handy 的“会话内 VAD 过滤静音”如何落到 Auri，见 ADR-0016。
- 若日后要采用跨平台 UI，仍按 PRD 第 15.1 节的要求另写 ADR 论证。
