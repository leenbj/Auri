# 本地转录候选模型核实

对应 Ticket 13（GitHub issue #14）。本文只核实事实，给出进入真机评测的名单、淘汰理由和评测语料要求。V1 只做本地转录（ADR-0019 [P3]），所以只看能在手机端侧运行的候选。不选默认模型（那是 Ticket 15），不写 ADR，仓库内不放任何权重。

## 0. 阅读须知

| 项 | 说明 |
|---|---|
| 读取日期 | 2026-11-17（UTC）。本机时区为 UTC−5，对应本机日期 2026-11-16 晚至 2026-11-17 凌晨。第 9 节每条来源都带读取日期，除另注外就是这一天。 |
| 证据等级 | 全文为 E1：官方文档、上游仓库、模型卡、发布元数据和源码（等级定义见 PRD 第 5.2 节 [P5]）。没有任何 E3 真机证据，所以“三端能否加载”只回答到“公共 API 里有没有对应类型”；真机加载、内存、速度一律标 `NOT_RUN`，留给 Ticket 14。 |
| 用语 | “未核实”：查不到一手来源，或只有自述、转述，不推测。“推算”：由已核实的数据算出，算法写在旁边。“厂商自报”：模型作者自己发布的数字或声明，不是我们的实测。 |
| 体积怎么来的 | HF / ModelScope 的文件清单 API、sherpa-onnx 官方文档里的 `ls -lh`、GitHub release 资产 API；没有文档清单的包，用流式 `tar -t` 只列目录和大小。没有任何权重落盘，仓库里没有权重文件。 |
| 固定版本 | 能给修订号、提交号、SHA-256 的都给了完整值。评测下载以 SHA-256 为准，不以文件名为准（见冲突 C8）。 |
| 本文不做的事 | 选默认模型（Ticket 15）、真机测速（Ticket 14）、写新 ADR。 |

## 1. 结论摘要

**进入真机评测（第一轮）**：A 档 6 个（必测），B 档 1 个（可选对照）。理由和风险见第 5 节。

| 档 | 候选 | 一句话理由 |
|---|---|---|
| A | Qwen3-ASR-1.7B int8（第三方导出） | ADR-0017 指名；风险最高：2.24 GiB，没有官方发布包 |
| A | Qwen3-ASR-0.6B int8（k2-fsa 官方包） | ADR-0017 要求的“小尺寸版本”对比对象 |
| A | SenseVoice-Small int8 | ADR-0017 要求的对比对象；权重许可证没有得到最终确认 |
| A | Fun-ASR-Nano int8 | 中、英、日；Apache-2.0；与 Qwen3 同属 LLM 解码类，作对照 |
| A | FireRedASR2-CTC int8 | 中英加 20 多种方言口音；Apache-2.0；非自回归 |
| A | X-ASR zh-en int8（含标点与不含标点两个变体） | 中英；Zipformer 转录器；约 167 MiB，是最轻的候选 |
| B | Paraformer-zh（2024-03-09 大模型 int8） | 成熟的非自回归基线；许可证只有 ModelScope 元数据一处标注 |

**需要新 ADR 才能使用的路线**（都不在入围名单里）：llama.cpp / GGUF、LiteRT-LM、MLX / CoreML。见第 4 节。

**三个最大风险**：

1. SenseVoice 权重的商用与随应用分发许可，没有得到许可方的最终确认（3.3 节，冲突 C1）。
2. Qwen3-ASR-1.7B 只有第三方导出：sherpa-onnx 官方没发布；ModelScope 仓库声明“仅供参考、不保证”；没有许可证文件；官方没有发布或验证过它，在 sherpa-onnx 上跑过的证据只有 #3535 里一条贡献者自述，加上引擎源码里两处引用该 issue 的注释（3.2 节）。
3. 所有候选在鸿蒙 NEXT、iOS、Android 上的真机加载、内存、速度全是 `NOT_RUN`。绑定层面三端都有（Cohere 除外）。0.7–2.2 GiB 的模型在鸿蒙和 iOS 上的内存与包体积限制，没有查到一手数字。

**ADR-0017 要求先核实的三件事**（① 有可用的 ONNX 导出且引擎支持；② 三端能加载；③ 许可证允许随应用分发）：

| 候选 | ① | ② | ③ |
|---|---|---|---|
| Qwen3-ASR-1.7B | 成立，但导出来自第三方 | 三端绑定都有；真机 `NOT_RUN` | 上游 Apache-2.0 允许，附条件；导出层许可证未核实 → 有条件 |
| Qwen3-ASR-0.6B | 成立（k2-fsa 官方包） | 同上 | 同上 |
| SenseVoice-Small | 成立（k2-fsa 官方包） | 同上；鸿蒙官方 Demo 文档就用它做示例 | 未确认（3.3 节） |

“未核实”和 `NOT_RUN` 不等于“不成立”。按 ADR-0017，任一项不成立才在对应平台标不可用。第③项对 SenseVoice 算不算“不成立”，由 Ticket 15 和产品负责人裁定，本文不替他们定。

## 2. 引擎基线

### 2.1 固定的引擎版本

| 项 | 值 | 来源 |
|---|---|---|
| sherpa-onnx | v2.0.1；标签指向提交 `af2d8d81dbfa61c4cea4c568da78f3982edca005`；2026-10-26T15:24:29Z 发布；仓库许可证 Apache-2.0 | [E1] |
| ONNX Runtime | Android 预编译库 1.28.2（`csukuangfj/onnxruntime-libs` 发布 v1.28.2）；iOS 的 `Package.swift` 固定 `onnxruntime-libs` 精确版本 1.28.2；鸿蒙所用 ORT 版本：未核实 | [E1] |
| Android 分发 | v2.0.1 发布附带 `sherpa-onnx-2.0.1.aar` | [E1] |
| 鸿蒙分发 | `sherpa_onnx-v2.0.1.har`，另有 ohos 原生库包（arm64-v8a、armeabi-v7a、x86_64） | [E1] |
| iOS 分发 | SwiftPM，最低 iOS 15；静态 xcframework `sherpa-onnx-v2.0.1-ios-static.xcframework.zip`（来自名为 `xcframework` 的发布，校验和 `88aa569f71b7903e0bbdfbe6e2e114b3b95862b1ef0759eea3024f82926d7374`） | [E1] |

### 2.2 三端绑定覆盖（v2.0.1，源码检索）

在 ArkTS 的 `NonStreamingAsr.ets`、Kotlin 的 `OfflineRecognizer.kt`、Swift 的 `SherpaOnnx.swift` 里检索各模型家族的配置类型：

| 家族 | 鸿蒙 ArkTS | Android Kotlin | iOS Swift |
|---|---|---|---|
| Transducer（含 X-ASR）、Paraformer、ZipformerCtc | 有 | 有 | 有 |
| SenseVoice、FunAsrNano、Qwen3Asr | 有 | 有 | 有 |
| FireRedAsr、FireRedAsrCtc | 有 | 有 | 有 |
| Whisper、Moonshine、Dolphin、TeleSpeech、Omnilingual | 有 | 有 | 有 |
| Cohere Transcribe | **无** | 有 | 有 |

来源：[E3][E4][E5]。这张表只说明公共 API 里有对应类型，不证明某个具体模型包能在真机上加载。

鸿蒙加载方式（[E3]）：ArkTS 的 `OfflineRecognizer(config, mgr?)`，传 `mgr` 时走 rawfile（`SherpaOnnxCreateOfflineRecognizerOHOS`），不传时按文件路径加载（`SherpaOnnxCreateOfflineRecognizer`）。官方 VAD + 非流式 ASR Demo 文档在 VAD 模型和 ASR 模型（`moonshine-tiny-en`、SenseVoice 两个示例）的下载步骤里都写明 `Caution: The model MUST be placed inside the directory rawfile` [E17]。大模型走 rawfile 会放大应用包体积，走沙盒路径要先下载或复制；两条路 API 都支持，应用包体积限制和真机行为：未核实。

## 3. 候选逐项核实

下列每张表的“来源”列是第 9 节的编号。“三端绑定”只指 v2.0.1 的公共 API。

### 3.1 Qwen3-ASR-0.6B（k2-fsa 官方 sherpa 包，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游与许可证 | 上游 `Qwen/Qwen3-ASR-0.6B`（修订 `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`），HF 卡片许可 apache-2.0；GitHub `QwenLM/Qwen3-ASR`（提交 `7c6daf77a2421100f5fb066495372c00129d39ff`）许可 Apache-2.0，仓库无 NOTICE 文件、无 release | [Q1][Q3] |
| 语言 | 官方 README：52 种语言和方言（30 种语言加 22 种中文方言），含 zh、en、yue；音频类型含语音、歌声、带背景音乐的歌曲 | [Q1] |
| 手机可运行导出 | 有。`asr-models` 发布里的 `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`：878,702,423 B，SHA-256 `393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`，资产更新时间 2026-04-07T09:52:53Z。内容是三个 ONNX 图（conv_frontend、encoder.int8、decoder.int8）加 tokenizer 目录。官方导出页指向的导出代码来自第三方 Wasser1462，仓库 `Wasser1462/Qwen3-ASR-onnx`（提交 `0829a577ee741e408e65763dfe01ff4fe75408e9`，仓库没有 LICENSE 文件） | [E7][E9][Q4] |
| sherpa-onnx 支持 | 核心从 1.12.34（2026-03-26）起（CHANGELOG：`Add Qwen3-ASR support (#3399)`、`Upload Qwen3 ASR 0.6B int8 models (#3409)`）。ArkTS 绑定从 1.12.35（2026-04-03）起：v1.12.34 的 `NonStreamingAsr.ets` 里没有 Qwen3，v1.12.35 起有。1.13.7 修了中心化 STFT 对齐（#3873）和静音输入幻觉（#3907），1.13.8 修了共享随机数发生器的数据竞争（#3912）。v2.0.1 包含这些修复 | [E2][E21][E19] |
| 三端绑定 | ArkTS 有、Kotlin 有、Swift 有（`Qwen3Asr` 配置类型）。真机加载 `NOT_RUN` | [E3][E4][E5] |
| 磁盘体积 | 压缩包 838.0 MiB。解压后：conv_frontend.onnx 42 MiB、decoder.int8.onnx 721 MiB、encoder.int8.onnx 174 MiB、tokenizer 约 4.2 MiB，合计约 941 MiB（官方文档 `ls -lh` 的取整值相加） | [E7][E9] |
| 许可证与随应用分发 | 上游 Apache-2.0。Apache-2.0 第 4 条：再分发须附许可证副本；改过的文件要有显著的修改说明；保留原有版权、专利、商标、署名声明；上游有 NOTICE 的要带其中的署名内容（本模型上游无 NOTICE）。转换包没有单独的许可证文件。“上游许可延伸到转换产物”是我们的工作假设，不是法律意见；转换者另有条款的可能性：未核实 | [Q1][Q3][Q4][L1] |
| 引擎行为（影响评测设计） | ① 总长度固定：ArkTS 配置默认 `maxTotalLen = 512`；C++ 实现每 100 帧（1 s）产生 13 个音频 token，提示词加音频超出 `max_total_len` 时记录 “Truncating audio placeholders …” 并截断音频特征。所以单次可识别的音频不超过 512 ÷ 13 ≈ 39 s（推算，没有扣除提示词和新生成的 token）；已发布导出的实际 `max_total_len`：未核实。② 歌声输入曾出现重复循环（#3535 中贡献者对 1.7B 的分析，归因于特征前端整体偏移 5 ms，#3873 修复）。③ 官方文档只演示“长音频先用 VAD 切段再逐段识别” | [E3][E6][E8][E18][E19] |

### 3.2 Qwen3-ASR-1.7B（第三方导出，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游与许可证 | 上游 `Qwen/Qwen3-ASR-1.7B`（修订 `7278e1e70fe206f11671096ffdd38061171dd6e5`，safetensors 4.380 GiB），HF 卡片许可 apache-2.0；语言同 0.6B | [Q1][Q2] |
| 官方有没有发布 | **没有。** 维护者在 #3535（2026-04-22）答复：`The model is too large. GitHub only allows to upload files of size < 2GB.`；issue 至今未关闭。2026-08-31 维护者邀请提问者自己传到 HF 再在文档里放链接；读取日 HF 上没有 `csukuangfj`、`hongbo-miao` 名下的 Qwen3 仓库。官方导出页只写“1.7B 请到 ModelScope `zengshuishui/Qwen3-ASR-onnx/model_1.7B` 下载” | [E18][E9][O4] |
| 第三方导出详情 | ModelScope 仓库 `zengshuishui/Qwen3-ASR-onnx`，最新提交 `9c182309f7bb075f241424441add9e16c5086dfb`（2026-06-04T02:36Z）；`model_1.7B` 目录提交 `cb045ad80b8970c9d411d463e5b78991a566596c`（2026-04-07T09:44Z）。读取时下载 1475 次、7 星。int8 文件集：`conv_frontend.onnx` 48,080,441 B（SHA-256 `fa894a4ba53da6a4238f2a6ca0b09362e505d39cecbd646051b033e2e8d7e2fb`）、`encoder.int8.onnx` 314,222,162 B（`436fbd910a0c8914851e5ac1354e807be9f283d08a5da728adaa609731c41469`）、`decoder.int8.onnx` 2,037,458,645 B（`c43c853fa6e97d08365cb8a5502b360b595cd43c00dc60e4d8ca7cc18cad460b`）；tokenizer 三文件（merges.txt、tokenizer_config.json、vocab.json）4,461,173 B。仓库另含 fp32 变体（`decoder.onnx.data` 8,126,930,944 B），不适合手机，不入围 | [Q5] |
| ModelScope 仓库的免责声明与许可证 | 仓库 README 原文：`This is a simple demo implementation intended for reference only. Please use it at your own discretion, as no guarantees are provided regarding completeness or production readiness.` 元数据许可字段 apache-2.0，但仓库里没有 LICENSE 文件；元数据的基础模型只写了 `Qwen/Qwen3-ASR-0.6B`，没有标注 1.7B 的来源 | [Q5] |
| sherpa-onnx 支持 | 三端公共 API 里只有一套 `Qwen3Asr` 配置，不按模型大小区分，1.7B 与 0.6B 走同一实现。1.7B 在 sherpa-onnx 上跑过的证据有两条：贡献者 eolivelli 在 #3535 的自述（称用同一导出配方，歌声输入出现重复循环，归因于特征前端偏移，随 #3873 修复）；v2.0.1 引擎源码里两处引用 #3535 并写明 “1.7B” 的注释（`CreateStream` 里的中心化 STFT 对齐、解码循环里的退化重复检测），说明这两处修复针对的是 1.7B 上观察到的问题。官方没有发布或验证过 1.7B 的包，也没有任何 1.7B 的 CER/WER。HF 上还有若干个人上传的 1.7B ONNX 或 sherpa 重新打包（如 `s0me-0ne/Qwen3-ASR-1.7B-ONNX-int8` 下载 19 次，`thieunv-asilla/…`、`solavr/…` 下载 0 次），出处与完整性未核实，不采用 | [E6][E18][E19][O4] |
| 三端绑定 | 同 0.6B：有、有、有。真机加载 `NOT_RUN` | [E3][E4][E5] |
| 磁盘体积 | int8 文件集合计 2,404,222,421 B = 2.239 GiB（三个 ONNX 加三个 tokenizer 文件）。`decoder.int8.onnx` 单文件 1.90 GiB | [Q5] |
| 许可证与随应用分发 | 上游 Apache-2.0，条件同 3.1。导出层（ModelScope 与 GitHub 导出仓库）没有许可证文件，元数据标 apache-2.0：作为工作假设处理，导出者另有条款的可能性未核实 | [Q2][Q4][Q5][L1] |
| ADR-0017 前置项 | ① 有导出且引擎支持，但依赖第三方，官方文档指向它；② 三端绑定存在，真机 `NOT_RUN`；③ 上游允许，导出层未核实。按 ADR-0017，目前没有任何一项被证明“不成立” | [P2] |

### 3.3 SenseVoice-Small（k2-fsa 官方包，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游 | 代码 `QwenAudio/SenseVoice`（原 `FunAudioLLM/SenseVoice`，GitHub 重定向到此），提交 `586bbdb2b052f4fe19322581825228b6810a9fb3`，代码许可 MIT。权重 `FunAudioLLM/SenseVoiceSmall`（HF 修订 `3847d57b6bdf2dd8875cb1508d2af43d80a16bf7`，2026-06-20 更新） | [S1][S5] |
| 语言与能力 | 官方卡片：训练数据超过 40 万小时、“支持超过 50 种语言”，识别任务面向中文、粤语、英语、日语、韩语；输出带情感与事件标签。sherpa 包标注 5 种语言（zh、en、ja、ko、yue）。是否有专门的“中英混说”声明：未核实。2024-07-17 版：`use_itn` 设为 1 时输出标点 | [S6][E11] |
| 手机可运行导出 | 有。`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2`：163,002,883 B，SHA-256 `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e`，资产更新时间 2025-09-01T08:32:59Z（与名称里的日期不一致，见 C8）。官方文档写明转换自 ModelScope `iic/SenseVoiceSmall`。HF 转换仓库 `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`（修订 `2365baeacb507f821a0c8120fcee3d484dba7a07`）没有许可证字段 | [E7][E11][S7] |
| sherpa-onnx 支持 | 从 1.10.17（2024-07-23）起；两遍识别 Android APK 从 1.10.24 起；Swift API 的语种、情感、事件结果从 1.10.26 起。ArkTS、Kotlin 首次出现的版本：未核实 | [E2] |
| 三端绑定 | 有、有、有。官方鸿蒙非流式 Demo 文档用 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8` 做示例之一（E1 级，不是我们的真机结果）。真机加载 `NOT_RUN` | [E3][E4][E5][E17] |
| 磁盘体积 | `model.int8.onnx` 228 MiB，`tokens.txt` 308 KiB（官方文档 `ls -lh`）；压缩包 155.5 MiB。fp32 的 `model.onnx` 894 MiB，不入围 | [E11][E7] |
| 引擎行为 | 官方示例用 VAD 把长音频切成最长 30 s 的片段再送入模型（`max_single_segment_time`: 30000 ms）；是否有硬性长度上限：未核实 | [S6] |
| **许可证与随应用分发** | **未确认。** 各处说法互相不一致，按从严处理，见下文 | [S1][S2][S3][S4][S5][S6] |

**SenseVoice 权重许可证，逐条：**

1. HF 卡片 `license: other`，`license_link` 指向 FunASR 的 `MODEL_LICENSE`。FunASR README 写明：预训练权重单独授权，以各模型卡为准；模型卡链接 `MODEL_LICENSE` 时适用其条款。所以 SenseVoice-Small 权重适用 FunASR Model Open Source License v1.1（固定于提交 `58830eca4012644aac0c3218c3ccc7d98f003fda`，文件 SHA-256 `7dba975a2069691db4992b0592d70828b330d2f8a30a71450f4e152a554e84f8`；与 main 的 `114292dbeff0f8f377bb165e22121cfcfb60cf65` 内容相同）。
2. 该协议：第 2.1 条授予使用、复制、修改、分享；第 2.2 条要求署名并保留模型名称；第 3 条写明“仅供参考和学习目的”；第 4.2 条规定贬损行为自动丧失授权；第 6 条允许单方修订；第 7 条适用法律为占位符。
3. SenseVoice README 引用维护者的“官方澄清”：商用允许；第 3 条只是责任免责声明，不是额外的非商用限制；第 2.2 条仍适用；澄清只覆盖官方权重，第三方转换产物要另查。
4. 但该澄清所在的 issue #334 评论（2026-07-26 发出）带有 2026-09-22 的状态更新，原文：`This is an earlier response, not final license-owner confirmation.` issue 重新打开，读取时仍是 open（最近更新 2026-11-16）。
5. ModelScope `iic/SenseVoiceSmall` 的元数据标 “Apache License 2.0”，与 HF 卡片不一致。
6. sherpa-onnx 的 ONNX int8 包是转换产物，按 SenseVoice README 自己的话属于“需另查”的第三方转换；HF 转换仓库没有许可证字段。

结论：现在不能判定“允许随应用分发”，也不能判定“禁止”。评测可以做（评测不分发）；能否成为随包默认，取决于许可方的最终确认。

### 3.4 Fun-ASR-Nano-2512（k2-fsa 官方包，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游与许可证 | HF `FunAudioLLM/Fun-ASR-Nano-2512`（修订 `272c57b82523ada6fd87095e955f8e29100979ab`），卡片许可 apache-2.0；GitHub `QwenAudio/Fun-ASR`（原 `FunAudioLLM/Fun-ASR`，提交 `4219e118c1339d65fa3503eb9ebad05ac2f65c9f`）许可 Apache-2.0 | [F1][F2] |
| 语言与能力 | 官方 README：支持中文、英文、日文；中文含 7 种方言和 26 种地区口音；含歌词与说唱识别；800M 参数；README 称针对远场和高噪声场景做了优化（厂商自报）。sherpa 文档标注 “Chinese, English, Japanese” | [F1][E10] |
| 手机可运行导出 | 有。`sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2`：841,730,611 B，SHA-256 `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b`，资产更新时间 2026-04-12T05:45:11Z（int8 在 1.12.37 刷新过，#3493）。内容是三个 ONNX（encoder_adaptor.int8、llm.int8、embedding.int8）加 `Qwen3-0.6B/` 分词目录。导出代码 `Wasser1462/FunASR-nano-onnx`（提交 `6823a8ed9f4a0393750d54d750051cf5a51a7fa9`）没有 LICENSE 文件；HF 转换仓库 `csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30`（修订 `6f16bd378457e13f36ccf3910df9017f96c346fb`）没有许可证字段 | [E7][E10][F3][F4] |
| sherpa-onnx 支持 | 从 1.12.20（2025-12-17）起；热词 1.12.24；静音输入幻觉修复 1.13.8（#3921）；按流设置提示词选项 2.0.0（#4012）。ArkTS：v1.12.34 的 `NonStreamingAsr.ets` 里已有 `FunAsrNano` | [E2][E21] |
| 三端绑定 | 有、有、有。真机加载 `NOT_RUN` | [E3][E4][E5] |
| 磁盘体积 | embedding.int8 149 MiB、encoder_adaptor.int8 227 MiB、llm.int8 573 MiB，三者约 949 MiB；`Qwen3-0.6B/` 分词目录 16 MiB（merges.txt、tokenizer.json、vocab.json）；合计约 965 MiB（官方文档 `ls -lh` 取整值）；压缩包 802.7 MiB | [E10][E7] |
| 许可证与随应用分发 | 上游 Apache-2.0，条件同 3.1。转换产物许可证未单独声明，工作假设同 3.1 | [F1][F2][L1] |

### 3.5 FireRedASR2-CTC（k2-fsa 官方包，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游与许可证 | GitHub `FireRedTeam/FireRedASR2S`（提交 `4e7d9aaf4482a47cec1724807026b9b151926eb5`，2026-06-02）许可 Apache-2.0；HF `FireRedTeam/FireRedASR2-AED`（修订 `2304afed56eacfee6256dee5937ed22ffa0b64ec`）卡片许可 apache-2.0。sherpa 实际转换自 ModelScope 的 `FireRedTeam/FireRedASR2-AED`，那里的许可字段：未核实 | [R1][R2][E12] |
| 语言与能力 | sherpa 文档：中文加英文，以及 20 多种方言和口音（粤语、四川话、上海话、吴语、闽南话等）。文档称 v2 的 CTC 模型“在 CPU 上非常快”（文档自述，非实测） | [E12] |
| 手机可运行导出 | 有。`sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2`：520,516,278 B，SHA-256 `1da8b737ecc5e29f36759a4460c754863e7c919a4ba325aea187331fbfc83274`，资产更新时间 2026-02-26T05:44:33Z；只转换了 CTC 分支，注意力解码分支被排除。文档给出了 Android APK 示例（`…fire_red_asr2_ctc_int8_2026_02_25.apk`） | [E7][E12] |
| sherpa-onnx 支持 | CTC 模型与 FireRedASR CTC 支持、语言绑定从 1.12.27（2026-02-26）起；FireRedASR AED 从 1.10.45 起 | [E2] |
| 三端绑定 | `FireRedAsrCtc` 有、有、有。真机加载 `NOT_RUN` | [E3][E4][E5] |
| 磁盘体积 | `model.int8.onnx` 740 MiB，`tokens.txt` 77 KiB（官方文档 `ls -lh`）；压缩包 496.4 MiB | [E12][E7] |
| 许可证与随应用分发 | 上游 Apache-2.0，条件同 3.1；转换产物的工作假设同 3.1 | [R1][R2][L1] |

### 3.6 X-ASR zh-en（k2-fsa 官方包，Zipformer 转录器，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游与许可证 | GitHub `Gilgamesh-J/X-ASR`（提交 `838297cd47fed858e6cf72eaf5a52f948a3edd73`，2026-07-29）许可 Apache-2.0；HF `GilgameshWind/X-ASR-zh-en`（修订 `689ff18c584d29910da37b6fe904db0c1489c9d1`）卡片许可 apache-2.0。训练数据为“约 100 万小时开源与收集的语音数据”（README 原文 `open-source and collected speech data`），各数据集的许可证：未核实 | [X1][X2] |
| 语言与能力 | README：中文、英文；offline-streaming 统一的 Zipformer 转录器；模型输出支持标点和大小写；其示例应用称可“中英自由混说”。以上都是作者声明，基准表是作者自报 | [X1] |
| 手机可运行导出 | 有。`…-x-asr-zipformer-transducer-zh-en-int8-2026-06-03.tar.bz2`：136,902,463 B，SHA-256 `7592d1a1e9bc1e034c39fbaab9b0db8af46429d95d64c6e2a69593934af10e9c`，资产更新时间 2026-06-29T08:33:24Z。另有 `…-punct-int8-2026-06-03.tar.bz2`：136,396,739 B，SHA-256 `5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224`。两个变体输出标点的具体差别：未核实 | [E7] |
| sherpa-onnx 支持 | Transducer 是 sherpa-onnx 的老路径；X-ASR 的包随 1.13.3（2026-06-15）首次上传（#3656，该 PR 同时给识别结果加了去除中日韩字符间多余空格的规整）。官方文档站里没有 X-ASR 专页（2026-11-17 检索链接索引无命中） | [E2][E20] |
| 三端绑定 | Transducer 有、有、有。真机加载 `NOT_RUN` | [E3][E4][E5] |
| 磁盘体积 | 不含标点的 int8 包：encoder.int8 161,015,628 B、decoder 11,309,084 B、joiner.int8 2,581,422 B、bpe.model 359,499 B、tokens.txt 59,240 B，合计 175,324,873 B = 167.2 MiB；含标点的变体合计 175,084,290 B = 167.0 MiB（流式 `tar -t` 列目录） | [E7] |
| 许可证与随应用分发 | 上游 Apache-2.0，条件同 3.1；包内 `README.md` 只有 96 B；转换产物的工作假设同 3.1 | [X1][X2][L1] |

### 3.7 Paraformer-zh（k2-fsa 官方包，int8）

| 项 | 结论 | 来源 |
|---|---|---|
| 上游与许可证 | 转换自 ModelScope `iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1`（创建 2022-08-04，元数据更新 2024-01-23）。元数据许可字段 “Apache License 2.0”；模型卡正文里没有许可条款（检索无命中）。FunASR README 规定“模型卡链接 MODEL_LICENSE 时适用其条款”，这张卡没有链接，所以未适用 MODEL_LICENSE。正文条款：未核实 | [M1][S3] |
| 语言与能力 | sherpa 文档：中英双语（普通话及河南、天津、四川等方言）；不支持时间戳 | [E13] |
| 手机可运行导出 | 有。`sherpa-onnx-paraformer-zh-2024-03-09.tar.bz2`：996,591,364 B（含 fp32 与 int8）；发布 API 没有给出 digest，SHA-256：未核实（评测下载时计算并固定）。HF 转换仓库无许可证字段 | [E7][E13] |
| sherpa-onnx 支持 | Paraformer 是老路径；首次支持的版本：未核实（CHANGELOG 里没有找到起点） | [E2] |
| 三端绑定 | 有、有、有。真机加载 `NOT_RUN` | [E3][E4][E5] |
| 磁盘体积 | `model.int8.onnx` 217 MiB，fp32 的 `model.onnx` 785 MiB（官方文档 `ls -lh`） | [E13] |
| 许可证与随应用分发 | 依据只有 ModelScope 元数据一处，正文无条款。工作假设：Apache-2.0 允许，条件同 3.1；该依据偏弱 | [M1][L1] |

## 4. 需要其他推理运行时的路线（需要新 ADR）

ADR-0017 与 PRD 第 15.1 节：引入共享 sherpa-onnx 之外的推理运行时会改变“共享本地 ASR”的选型，必须单独写 ADR。本文不写那份 ADR，也不挑路线。下面几条都不在入围名单里。

| 路线 | 已核实的事实 | 来源 |
|---|---|---|
| llama.cpp / GGUF | `ggml-org/Qwen3-ASR-1.7B-GGUF`（修订 `36a678687ba7d07a74ca70ccb0e36902e005fb80`）：Q8_0 主体 2,064.7 MiB 加 mmproj 339.2 MiB，合计约 2.35 GiB；`ggml-org/Qwen3-ASR-0.6B-GGUF`（修订 `928ab958557df9aa2ef1c93e0e83c7ad0933fae2`）：Q8_0 767.5 MiB 加 mmproj 204.5 MiB，合计约 972 MiB。两张卡都没有声明许可证，基础模型标注为 `Qwen/Qwen3-ASR-*`。Fun-ASR 与 SenseVoice 的卡片横幅也提到各自的 GGUF 路线。三端能否加载、许可证：未核实 | [O1][O2][F1][S6] |
| LiteRT-LM | `litert-community/Qwen3-ASR-0.6B`（修订 `5028edc410addd0f9cf0b35a58a38aeb8bea4604`）：卡片许可 apache-2.0；`qwen3_asr_0.6b_5s_i8.litertlm` 915.2 MiB（卡片写 960 MB）；卡片称编码器窗口固定 5 秒，更长的音频用重叠的 5 秒窗口（步长 2.5 至 3.0 s）处理，并建议有 3–4 GB 可用内存。卡片面向 Android、iOS、macOS、Linux、C++，没有提到 HarmonyOS（鸿蒙能否运行：未核实） | [O3] |
| MLX / CoreML | HF 上有第三方的 `appautomaton/qwen3-asr-1.7b-int8-mlx`（标 apache-2.0）和 `UniMocha/Qwen3-ASR-1.7B-CoreML-INT8`（无许可标注），均为 Apple 专用运行时的产物，覆盖不了鸿蒙和 Android；出处与许可证未逐项核实 | [O4] |
| 厂商 NPU 路径（QNN、RKNN、Ascend 等） | sherpa-onnx 里有，但依赖特定硬件（例如 QNN 路径绑定高通），不是三端通用路径；不作为候选。第 1.13.3 版 CHANGELOG 记录了 X-ASR 到 QNN 的导出和 Android QNN 示例 | [E2] |

## 5. 入围名单与淘汰理由

### 5.1 入围标准（本文约定）

- S1：有文档化的中文支持，且有中英双语或中英混说的一手声明。
- S2：sherpa-onnx v2.0.1 在鸿蒙、Android、iOS 的公共 API 里都有对应类型。
- S3：权重许可证没有被一手来源明确排除“随应用分发”。“未确认”的标注后仍可入围，因为评测不分发。
- S4：磁盘体积不超过约 2.5 GiB。这是本文约定的评测筛选线，不是来源；ADR-0017 要求 1.7B 必测，2.24 GiB 在线内。
- S5：有 k2-fsa 官方包，或者是 ADR-0017 指名、官方文档指向的包。

淘汰分两类：**阻断**是一手来源已经表明不满足硬要求；**暂缓**不是失败，只是第一轮不测，写明重启条件。

### 5.2 入围名单

| 档 | 候选 | 入围依据 | 评测时必须盯的风险 |
|---|---|---|---|
| A | Qwen3-ASR-1.7B int8 | ADR-0017 指名必测；上游 Apache-2.0；官方文档指向其导出 | 2.24 GiB 内存与冷启动；第三方导出的正确性（官方没有验证，没有 CER/WER）；单次容量与截断；歌声与静音输入的重复和幻觉；导出层许可证 |
| A | Qwen3-ASR-0.6B int8 | ADR-0017 要求的小尺寸对比对象；k2-fsa 官方包 | 同上，规模较小；`max_total_len` 实际值 |
| A | SenseVoice-Small int8 | ADR-0017 要求的对比对象；鸿蒙官方 Demo 文档用它做示例 | 许可证未确认；长音频必须切段；中英混说无专门声明 |
| A | Fun-ASR-Nano int8 | 中英日；Apache-2.0；k2-fsa 官方包；与 Qwen3 同属 LLM 解码类 | 约 965 MiB；静音幻觉（1.13.8 才修）；转换者导出仓库无许可证文件 |
| A | FireRedASR2-CTC int8 | 中英加方言口音；Apache-2.0；CTC 非自回归 | 740 MiB 单文件；官方称快但无实测；转换来源许可字段未核 |
| A | X-ASR zh-en int8（含标点与不含标点各一份） | 中英；Apache-2.0；167 MiB，最轻；作者声称 100 万小时训练数据 | 新模型（2026-06 上传）；文档站无专页；训练数据许可未核；标点变体差别未核 |
| B | Paraformer-zh 2024-03-09 int8 | 中英双语；217 MiB；成熟的非自回归基线 | 许可证依据偏弱；包无 digest；不输出时间戳 |

各平台默认模型可以不同（ADR-0017），本节不预判。

### 5.3 淘汰名单

| 候选 | 类别 | 理由（带来源） | 重启条件 |
|---|---|---|---|
| Zipformer-CTC 中文（`zipformer-ctc-zh-int8-2025-07-03`，350 MiB，HF 标 apache-2.0） | 阻断 | 官方文档写明 `supports only Chinese`，覆盖不了规格第 45 条的“中文夹英文” [E14][M8][P4] | 需求放宽为只要中文 |
| Moonshine base-zh（压缩包 95.3 MiB，`.ort`） | 阻断 | 文档：`supports only Chinese` [E15]。包内 LICENSE（文件 SHA-256 `6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd`）：英语以外的模型适用 Moonshine Community License，开头自称 `a non-commercial license`；商业用途须注册，年收入超过 100 万美元时授权终止，须署名 “Powered by Moonshine AI” [M3] | 无 |
| Dolphin base / small（int8 99 / 239 MiB） | 阻断 | 许可证 Apache-2.0（GitHub 与 HF 卡片一致）；但官方语言列表（`languages.md`，提交 `78ea615194777f91a3970ff010b5d678d0bbf5f0`）里没有英语条目，“English” 只出现在表头，覆盖不了中英混说 [M4][E22] | 无 |
| TeleSpeech-ASR（int8 压缩包 174.7 MiB） | 阻断 | 多方言模型；GitHub README 声明使用须遵循“TeleSpeech 模型社区许可协议”，商用要向中国电信邮件申请，审核通过才授予商用许可，与 HF 的 apache-2.0 标签冲突（C2）；README 未声明英文能力：未核实 [M5][E7] | 无 |
| Cohere Transcribe 14 语种 int8 | 阻断 | v2.0.1 的 ArkTS 绑定里没有 Cohere [E3]；编码器外部数据 2.5 GiB，整包约 2.65 GiB，超出 S4 的筛选线 [E16]；HF 仓库为自动审批的 gated 仓库，许可证 apache-2.0 [M7] | ArkTS 补绑定且体积可接受 |
| NeMo 系（Canary 180m flash、fast-conformer、Citrinet 等）、MedASR、GigaAM | 阻断 | 发布资产名称就标明语种：`en`、`en-es-de-fr`、欧洲语种列表、俄语（MedASR 的资产名标 `en`），没有中文 [E7]。只凭资产名判断，各自的卡片未逐项读 | 无 |
| SenseVoice 2025-09-09 粤语微调版 | 暂缓 | 官方文档：转换自 `ASLP-lab/WSYue-ASR`，在 2024-07-17 版上用 21.8k 小时粤语数据微调，不支持标点；不是普通话与中英混说的候选。许可证未核实 [E11] | 需要粤语 |
| `sense-voice-funasr-nano`（CTC，int8 179 MiB） | 暂缓 | 官方文档和 CHANGELOG 里都找不到该包的出处与用途：未核实 [E7][E2] | 找到出处 |
| Paraformer-zh-small（压缩包 74.3 MiB） | 暂缓 | 转换自个人在 ModelScope 上的仓库（`crazyant/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-onnx`），不是 FunASR 官方；许可证未核实 [E13] | 无 |
| Paraformer 四川话、重庆话版（2025-10-07） | 暂缓 | 在 2023-03-28 的中英模型上用 10k 小时方言数据微调，方言专用 [E13] | 需要方言 |
| FireRedASR2-AED int8、FireRedASR v1 AED-L | 暂缓 | v2 AED：encoder.int8 779 MiB 加 decoder.int8 398 MiB，约 1.15 GiB；v1 AED-L：encoder.int8 1.3 GiB 加 decoder.int8 425 MiB。与入围的 CTC 同系，体积更大且自回归解码；v1 已被 v2 取代 [E12] | CTC 质量不达标 |
| Whisper tiny / base / small / turbo | 暂缓 | MIT（GitHub README；HF 卡片标签不一致，见 C3）[M2]。官方 README：用 30 s 滑窗处理 [M2]。Fun-ASR 的自报对比表里，Whisper-large-v3 在 AIShell1、WenetSpeech 上的错误率高于 FireRed-ASR 与 Fun-ASR-nano（自报；且是 large-v3，不是能上手机的 small / turbo）[F1]。中英混说的一手声明：未找到。手机可用的 int8：small 357.3 MiB，turbo 987.9 MiB（HF 文件清单）[M2] | 需要通用对照基线时追加 small int8 |
| Omnilingual-CTC 300M / 1B | 暂缓 | 代码与模型 Apache-2.0（README 原文）；定位是 1600 多种语言覆盖；没有找到面向普通话或中英混说的质量声明：未核实 [M6]。300M int8 压缩包 279.0 MiB，1B int8 750 MiB [E7] | 找到中文质量依据 |
| Qwen3-ASR 的 fp32 / bf16 导出 | 暂缓 | 体积不适合手机（1.7B 的 fp32 外部数据 7.57 GiB）[Q5] | 无 |

### 5.4 入围包的固定清单（评测起点）

评测时下载以 SHA-256 为准。下表来自 GitHub release 资产 API 与 ModelScope 文件清单，不是我们下载后计算的值。

| 候选 | 文件 | 字节数 | SHA-256 | 来源 |
|---|---|---|---|---|
| Qwen3-ASR-0.6B | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2` | 878,702,423 | `393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96` | [E7] |
| Qwen3-ASR-1.7B | `model_1.7B/conv_frontend.onnx` | 48,080,441 | `fa894a4ba53da6a4238f2a6ca0b09362e505d39cecbd646051b033e2e8d7e2fb` | [Q5] |
| Qwen3-ASR-1.7B | `model_1.7B/encoder.int8.onnx` | 314,222,162 | `436fbd910a0c8914851e5ac1354e807be9f283d08a5da728adaa609731c41469` | [Q5] |
| Qwen3-ASR-1.7B | `model_1.7B/decoder.int8.onnx` | 2,037,458,645 | `c43c853fa6e97d08365cb8a5502b360b595cd43c00dc60e4d8ca7cc18cad460b` | [Q5] |
| Qwen3-ASR-1.7B | `tokenizer/merges.txt` | 1,671,853 | `8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5` | [Q5] |
| Qwen3-ASR-1.7B | `tokenizer/tokenizer_config.json` | 12,487 | `4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c` | [Q5] |
| Qwen3-ASR-1.7B | `tokenizer/vocab.json` | 2,776,833 | `ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910` | [Q5] |
| SenseVoice-Small | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` | 163,002,883 | `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e` | [E7] |
| Fun-ASR-Nano | `sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2` | 841,730,611 | `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b` | [E7] |
| FireRedASR2-CTC | `sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2` | 520,516,278 | `1da8b737ecc5e29f36759a4460c754863e7c919a4ba325aea187331fbfc83274` | [E7] |
| X-ASR zh-en | `sherpa-onnx-x-asr-zipformer-transducer-zh-en-int8-2026-06-03.tar.bz2` | 136,902,463 | `7592d1a1e9bc1e034c39fbaab9b0db8af46429d95d64c6e2a69593934af10e9c` | [E7] |
| X-ASR zh-en（含标点） | `sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2` | 136,396,739 | `5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224` | [E7] |
| Paraformer-zh（B 档） | `sherpa-onnx-paraformer-zh-2024-03-09.tar.bz2` | 996,591,364 | 未核实（资产无 digest，评测时计算） | [E7] |

## 6. 评测语料要求

依据：ticket 清单（普通话、中文夹英文、短句、长音频、轻声、远讲、噪声）[P7]；规格第 44 条（没有识别出语音时要明确说明，不能编造文字）和第 45 条（普通话与中文夹英文都要较准确）[P4]；ADR-0017（三台目标手机、同一批耳机录制的中文音频、比较质量、内存峰值、冷启动、耗时、发热）[P2]；PRD FR-ASR-003 的检查项（中文质量、中文夹英文、短句、长音频、耳机录音采样条件、内存峰值、冷启动、持续推理发热、许可证、下载体积、目标 ABI、运行库兼容性）[P5]。下面“建议值”是本文的提议，不是来源，Ticket 14 可以调整。

### 6.1 全局要求

1. **同一批耳机录制。** 用产品负责人的三台目标手机配对应的耳机，走严格蓝牙输入（`bluetooth_only`）录制。不得用手机麦克风录，也不得用高质量录音重采样来模拟耳机音质（ADR-002、CLAUDE.md）[P1][P2]。
2. **记录实际采样条件。** 仓库里现有的 iOS 探针只证明录音输入是 `BluetoothHFP`，没有记录采样率与声道 [P6]。耳机录音的实际采样率和编码路径：未核实。语料清单必须为每条音频写明采样率、声道、手机、耳机、路由证据。
3. **音频不入库。** 真实个人口述音频、密钥不得提交仓库 [P1]。仓库里只放语料清单（内容哈希、时长、类别、录制条件）和脱敏后的结果。音频存放位置另行约定。
4. **参考文本与评分。** 每条音频有人工校对的参考文本，并事先规定写法：标点、数字、英文大小写、中英混说的写法。各候选的输出格式不同（有无标点、大小写、逆文本规整），评分前统一规整。中文按字错误率，英文按词错误率，混说另记边界错误。标点是否计分要事先定。
5. **同条件比较。** 同一语料、同一引擎版本（v2.0.1）、同样的线程数与预处理；每个候选记录模型文件的 SHA-256（FR-ASR-004）[P5]；性能类指标每项至少重复 3 次取分布（建议值）。
6. **说话人。** 至少产品负责人本人；有条件时再加一位不同性别或口音的说话人（建议值）。

### 6.2 分类要求

| 类别 | 内容要求 | 为什么 | 记录与判据 |
|---|---|---|---|
| 普通话 | 日常口述，含数字、人名、地名、专有名词，有句内停顿和口头语。建议至少 100 条、约 6000 字 | 规格第 45 条；ADR-0017 | 参考文本、字错误率 |
| 中文夹英文 | 英文单词、产品名、缩写嵌入中文句；整句英文插入；英文术语加数字；Agent 与开发场景词（如 API、MCP）。建议至少 100 条、300 个英文词 | 规格第 45 条；候选里的混说声明都是厂商自报 | 中文字错误率、英文词错误率、中英边界错误 |
| 短句 | 3 s 以内、1 到 5 个字的口令式短话（“好的”“继续”“取消”），起止静音很短。建议至少 100 条 | 固定开销占比最大；LLM 类解码器可能多输出或少输出 | 首字延迟、总耗时、多输出与少输出 |
| 长音频 | 60 s、5 min、30 min（单次录音默认上限）、120 min（硬上限，可由真实录音首尾拼接，须标注拼接）各至少 1 条；每条分别测“整段直输”和“VAD 切段后逐段识别再拼接” | 规格第 201 行：默认上限 30 分钟、硬上限 120 分钟 [P4]；Qwen3 单次容量不超过约 39 s（推算，3.1 节）；SenseVoice 官方示例切段上限 30 s（3.3 节） | 截断、漏字、重复、内存随时长增长、持续发热、总耗时 |
| 轻声 | 低音量、气声、耳语式；记录相对电平 | ticket 要求 | 字错误率、整句漏识别 |
| 远讲 | 说话人离耳机约 1 m 和 2 m；他人说话作干扰；记录距离 | ticket 要求；Fun-ASR 称针对远场优化，只是自报，待验证 | 距离、字错误率 |
| 噪声 | 街道、地铁、风、办公室、厨房、车内；真实录制；FreeClip 开放式佩戴下的风噪。可另设合成噪声作对照，但不能替代真实录制 | ticket 要求 | 环境、估计信噪比、字错误率 |
| 无语音与非语音 | 纯静音（0.5 s、5 s、30 s）、仅环境噪声、仅呼吸或咳嗽、哼唱与歌声、背景音乐加人声 | 规格第 44 条；sherpa-onnx 曾修过 Qwen3（#3907）、Fun-ASR-Nano（#3921）、Cohere（#3924）在静音上编造文字；Qwen3 在歌声上出现过重复循环 [E2][E18] | 是否输出文字、输出长度与时长之比、重复 n-gram |

### 6.3 自动化检查（建议）

- 重复循环：检测输出里同一 n-gram 连续重复，和输出字数与音频时长之比的异常值。
- 截断：对 30 s 以上的整段直输，比较参考文本末尾是否出现在输出里。
- 幻觉：无语音类的输出必须为空或明确的“无语音”标记，任何实质文字都记为失败。

### 6.4 本轮不测

方言、热词、说话人分离。它们不在 ticket 清单里，规格第 45 条也只要求普通话与中文夹英文。有人提出需求时再扩。

## 7. 未核实清单

| 编号 | 内容 |
|---|---|
| U1 | 全部候选在三台目标手机（鸿蒙 NEXT、iOS、Android）上的真机加载、内存峰值、冷启动、速度、发热：`NOT_RUN`，待 Ticket 14 |
| U2 | 鸿蒙 NEXT 上大模型（单文件 0.7 GiB 以上）经 rawfile 或沙盒路径加载的实际行为，以及应用包体积限制（文档只给 rawfile 示例，没有查到限制数字） |
| U3 | iOS 单进程内存上限对 1.7B、Fun-ASR-Nano 等的影响 |
| U4 | Qwen3 已发布导出（0.6B、1.7B）的实际 `max_total_len`，以及由它决定的单次可识别音频上限（“约 39 s”是推算） |
| U5 | Qwen3-ASR-1.7B int8 第三方导出在 sherpa-onnx v2.0.1 上的识别正确性：只有 #3535 里贡献者的自述和引擎源码里两处引用该 issue 的注释，官方没有发布或验证，没有 CER/WER |
| U6 | SenseVoice 权重的商用与随应用分发许可：许可方最终确认缺失（issue #334 仍开放） |
| U7 | 第三方转换产物的许可证：Qwen3 导出（导出仓库无 LICENSE，ModelScope 元数据标 apache-2.0）、Fun-ASR-Nano 导出（仓库无 LICENSE）、sherpa 的 HF 转换仓库（SenseVoice、Fun-ASR-Nano、Paraformer 无许可证字段） |
| U8 | Paraformer-large 的 ModelScope 模型卡正文是否另有许可条款；Paraformer、Paraformer-small、Whisper 压缩包的 SHA-256（资产无 digest） |
| U9 | X-ASR、FireRedASR2、Fun-ASR-Nano、Qwen3 的训练数据许可证（不在权重许可证范围内） |
| U10 | 鸿蒙所用 ONNX Runtime 版本；SenseVoice、Paraformer 等在 Kotlin、Swift 绑定里首次出现的版本；Paraformer 的 sherpa-onnx 首次支持版本 |
| U11 | X-ASR 含标点与不含标点两个变体在输出上的具体差别；X-ASR 的中英混说能力除作者声明外无验证 |
| U12 | `sense-voice-funasr-nano` CTC 包的出处与用途 |
| U13 | 各候选的实际识别质量（字错误率、词错误率）：只有厂商自报（E1），我们没有任何实测 |
| U14 | 耳机录音的实际采样率与编码：iOS 探针只证明输入为 `BluetoothHFP` |
| U15 | 候选在静音与噪声输入上的幻觉行为：只有 sherpa-onnx 的修复记录（#3907、#3921、#3924），没有实测 |
| U16 | 被淘汰项只核实到足以淘汰的程度，其余字段未逐项核实（Moonshine、Dolphin、TeleSpeech、Omnilingual、Cohere、NeMo 系、MLX/CoreML 第三方仓库） |
| U17 | FireRedASR2 转换来源（ModelScope `FireRedTeam/FireRedASR2-AED`）上的许可字段 |

## 8. 冲突与偏差

| 编号 | 冲突 | 本文的处理 |
|---|---|---|
| C1 | SenseVoice：README 引用的“官方澄清”称商用允许，但该评论自带“非最终确认”的状态更新，issue 仍开放；ModelScope 元数据标 Apache License 2.0，HF 卡片标 `other` 并链接 MODEL_LICENSE | 按 HF 卡片加 FunASR README 的规则，适用 MODEL_LICENSE v1.1；因最终确认缺失，不判定可分发。ModelScope 的 Apache 标注不采信，待清理 |
| C2 | TeleSpeech：HF 标签 apache-2.0，GitHub README 却要求遵循社区许可协议并申请商用 | 取更严的 GitHub 声明；该模型已被淘汰，不影响结论 |
| C3 | Whisper：`openai/whisper-small` 的 HF 卡片标 apache-2.0，`whisper-large-v3-turbo` 标 mit，GitHub README 写 MIT | 都是宽松许可；以 GitHub README 的 MIT 为准；该模型暂缓，不影响结论 |
| C4 | Moonshine：HF `UsefulSensors/moonshine-base` 标 mit，但包内 LICENSE 说英语以外的模型适用非商业为主的 Community License | 中文模型取包内 LICENSE |
| C5 | Qwen3 第三方导出：ModelScope 元数据标 apache-2.0，但仓库没有 LICENSE 文件，导出代码仓库也没有 | 以上游 Apache-2.0 作为权重许可依据；导出层许可证列入未核实 |
| C6 | `docs/agent/product-and-constraints.md` “依赖锁定”一条（含“模型含哈希与许可证必须固定版本”）末尾标注的是“第 26.4 节”；PRD 第 26.4 节实际是“必须纠正的参考项目用法”，依赖与版本锁定在第 15.4 节 | 本文引用第 15.4 节与 FR-ASR-003 / 004；建议文档负责人把 `docs/agent/product-and-constraints.md` 的这处引用拆开标注 |
| C7 | Qwen3 的 sherpa-onnx 核心支持从 1.12.34 起，但 ArkTS 绑定从 1.12.35 起 | 鸿蒙上最早可用版本按 1.12.35 算；固定的 v2.0.1 不受影响 |
| C8 | 资产名称里的日期与资产实际更新时间不一致：SenseVoice 名称 2024-07-17、更新 2025-09-01；Fun-ASR-Nano 名称 2025-12-30、更新 2026-04-12；Qwen3-0.6B 名称 2026-03-25、更新 2026-04-07 | 名称不能标识内容（PRD FR-ASR-003：“模型名称相同不等于转换产物相同”）；固定清单以 SHA-256 为准 |

## 9. 来源

读取日期除另注外均为 2026-11-17（UTC）。项目内文件读取于工作树 `ticket/t13-asr-candidates`（基于 `integration/auri-v1`，HEAD `5921970`）。

### 9.1 项目内文件

| 编号 | 内容 |
|---|---|
| P1 | `CLAUDE.md` |
| P2 | `docs/adr/0017-qwen3-asr-as-candidate-model.md` |
| P3 | `docs/adr/0019-v1-local-transcription-only.md` |
| P4 | `docs/spec/auri-v1-spec.md`（用户故事 44、45；第 201 行默认值） |
| P5 | `VoiceBridge_PRD_Agent_Ready_v1.0.md`（第 5.2 节证据等级；FR-ASR-003、FR-ASR-004；第 15.4 节） |
| P6 | `execution/p0-spikes/ios-probe/results/2026-10-20-freeclip2-iphone-air.md` |
| P7 | `.scratch/auri-v1/issues/13-asr-candidate-verification.md` |

### 9.2 sherpa-onnx 引擎

| 编号 | 内容与地址 | 版本或哈希 |
|---|---|---|
| E1 | 发布 v2.0.1：<https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1>；同标签下的 `Package.swift`、`cmake/onnxruntime-android-aarch64.cmake` | 提交 `af2d8d81dbfa61c4cea4c568da78f3982edca005` |
| E2 | `CHANGELOG.md`：<https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/CHANGELOG.md> | v2.0.1 |
| E3 | ArkTS 绑定：<https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/harmony-os/SherpaOnnxHar/sherpa_onnx/src/main/ets/components/NonStreamingAsr.ets>，原生层 `…/sherpa_onnx/src/main/cpp/non-streaming-asr.cc` | v2.0.1 |
| E4 | Kotlin 绑定：<https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflineRecognizer.kt> | v2.0.1 |
| E5 | Swift 绑定：<https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/swift-api-examples/SherpaOnnx.swift> | v2.0.1 |
| E6 | Qwen3 实现：<https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc> | v2.0.1 |
| E7 | 模型发布 `asr-models`（资产名、字节数、digest、更新时间）：<https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models>，API <https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/130628817/assets>；X-ASR 包的文件清单与 Moonshine 的 LICENSE 文本用流式 `tar` 读取（只保留清单和 LICENSE 文本，不保存权重） | 读取时 501 个资产 |
| E8 | 文档 Qwen3-ASR 预训练模型：<https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html> | — |
| E9 | 文档 Qwen3-ASR 导出：<https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/export.html> | — |
| E10 | 文档 Fun-ASR-Nano：<https://k2-fsa.github.io/sherpa/onnx/funasr-nano/pretrained.html> | — |
| E11 | 文档 SenseVoice：<https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html> | — |
| E12 | 文档 FireRedASR：<https://k2-fsa.github.io/sherpa/onnx/FireRedAsr/pretrained.html> | — |
| E13 | 文档 Paraformer：<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html> | — |
| E14 | 文档 Zipformer-CTC：<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/icefall/zipformer.html> | — |
| E15 | 文档 Moonshine：<https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html> | — |
| E16 | 文档 Cohere Transcribe：<https://k2-fsa.github.io/sherpa/onnx/cohere_transcribe/pretrained.html> | — |
| E17 | 文档 鸿蒙非流式 ASR：<https://k2-fsa.github.io/sherpa/onnx/harmony-os/vad-asr.html>，索引 <https://k2-fsa.github.io/sherpa/onnx/harmony-os/index.html> | — |
| E18 | issue #3535：<https://github.com/k2-fsa/sherpa-onnx/issues/3535> | 2026-04-21 创建，读取时 open |
| E19 | PR #3873：<https://github.com/k2-fsa/sherpa-onnx/pull/3873> | 合并提交 `bbd946415d7791a878792790fc94bfcccf2a5ce3`（2026-08-31） |
| E20 | PR #3656（X-ASR 非流式转录器上传）：<https://github.com/k2-fsa/sherpa-onnx/pull/3656> | 2026-06-03 合并 |
| E21 | 版本证明：标签 v1.12.34、v1.12.35 下的 `NonStreamingAsr.ets` 内容（Qwen3 出现与否）；v1.12.35 标签提交 `21473b6bd2f3ed317426b05f27bab09fd160d0a0`，2026-04-03T03:58:45Z 发布；各版本发布时间取自 GitHub release API（v1.10.17 2024-07-23，v1.12.20 2025-12-17，v1.12.27 2026-02-26，v1.12.34 2026-03-26，v1.13.3 2026-06-15，v1.13.8 2026-09-10） | — |
| E22 | 文档 Dolphin：<https://k2-fsa.github.io/sherpa/onnx/Dolphin/pretrained.html> | — |

### 9.3 Qwen3-ASR

| 编号 | 内容与地址 | 版本或哈希 |
|---|---|---|
| Q1 | `QwenLM/Qwen3-ASR`：<https://github.com/QwenLM/Qwen3-ASR> | 提交 `7c6daf77a2421100f5fb066495372c00129d39ff` |
| Q2 | HF `Qwen/Qwen3-ASR-1.7B`：<https://huggingface.co/Qwen/Qwen3-ASR-1.7B> | 修订 `7278e1e70fe206f11671096ffdd38061171dd6e5` |
| Q3 | HF `Qwen/Qwen3-ASR-0.6B`：<https://huggingface.co/Qwen/Qwen3-ASR-0.6B> | 修订 `5eb144179a02acc5e5ba31e748d22b0cf3e303b0` |
| Q4 | `Wasser1462/Qwen3-ASR-onnx`：<https://github.com/Wasser1462/Qwen3-ASR-onnx> | 提交 `0829a577ee741e408e65763dfe01ff4fe75408e9` |
| Q5 | ModelScope `zengshuishui/Qwen3-ASR-onnx`：<https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx>；文件清单 API `…/api/v1/models/zengshuishui/Qwen3-ASR-onnx/repo/files?Revision=master&Recursive=true` | 提交 `9c182309f7bb075f241424441add9e16c5086dfb` |

### 9.4 SenseVoice

| 编号 | 内容与地址 | 版本或哈希 |
|---|---|---|
| S1 | `QwenAudio/SenseVoice`：<https://github.com/QwenAudio/SenseVoice>（README 许可证一节） | 提交 `586bbdb2b052f4fe19322581825228b6810a9fb3` |
| S2 | FunASR `MODEL_LICENSE`：<https://github.com/modelscope/FunASR/blob/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE> | SHA-256 `7dba975a2069691db4992b0592d70828b330d2f8a30a71450f4e152a554e84f8` |
| S3 | FunASR README（许可证一节）：<https://github.com/modelscope/FunASR/blob/114292dbeff0f8f377bb165e22121cfcfb60cf65/README.md> | 提交 `114292dbeff0f8f377bb165e22121cfcfb60cf65` |
| S4 | issue #334 与评论 5083546605：<https://github.com/QwenAudio/SenseVoice/issues/334> | 读取时 open，最近更新 2026-11-16 |
| S5 | HF `FunAudioLLM/SenseVoiceSmall`：<https://huggingface.co/FunAudioLLM/SenseVoiceSmall> | 修订 `3847d57b6bdf2dd8875cb1508d2af43d80a16bf7` |
| S6 | ModelScope `iic/SenseVoiceSmall`（元数据与卡片正文）：<https://modelscope.cn/models/iic/SenseVoiceSmall> | 元数据更新 2026-06-20T11:40Z |
| S7 | HF `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`：<https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17> | 修订 `2365baeacb507f821a0c8120fcee3d484dba7a07` |

### 9.5 Fun-ASR-Nano、FireRedASR2、X-ASR

| 编号 | 内容与地址 | 版本或哈希 |
|---|---|---|
| F1 | HF `FunAudioLLM/Fun-ASR-Nano-2512`（含对比表）：<https://huggingface.co/FunAudioLLM/Fun-ASR-Nano-2512> | 修订 `272c57b82523ada6fd87095e955f8e29100979ab` |
| F2 | `QwenAudio/Fun-ASR`：<https://github.com/QwenAudio/Fun-ASR> | 提交 `4219e118c1339d65fa3503eb9ebad05ac2f65c9f` |
| F3 | `Wasser1462/FunASR-nano-onnx`：<https://github.com/Wasser1462/FunASR-nano-onnx> | 提交 `6823a8ed9f4a0393750d54d750051cf5a51a7fa9` |
| F4 | HF `csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30`：<https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30> | 修订 `6f16bd378457e13f36ccf3910df9017f96c346fb` |
| R1 | `FireRedTeam/FireRedASR2S`：<https://github.com/FireRedTeam/FireRedASR2S> | 提交 `4e7d9aaf4482a47cec1724807026b9b151926eb5` |
| R2 | HF `FireRedTeam/FireRedASR2-AED`：<https://huggingface.co/FireRedTeam/FireRedASR2-AED> | 修订 `2304afed56eacfee6256dee5937ed22ffa0b64ec` |
| X1 | `Gilgamesh-J/X-ASR`：<https://github.com/Gilgamesh-J/X-ASR> | 提交 `838297cd47fed858e6cf72eaf5a52f948a3edd73` |
| X2 | HF `GilgameshWind/X-ASR-zh-en`：<https://huggingface.co/GilgameshWind/X-ASR-zh-en> | 修订 `689ff18c584d29910da37b6fe904db0c1489c9d1` |

### 9.6 其他候选与许可证文本

| 编号 | 内容与地址 | 版本或哈希 |
|---|---|---|
| M1 | ModelScope `iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1`（元数据）：<https://modelscope.cn/models/iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1> | 元数据更新 2024-01-23T14:35Z |
| M2 | `openai/whisper`：<https://github.com/openai/whisper>；HF `openai/whisper-small`、`openai/whisper-large-v3-turbo`；HF `csukuangfj/sherpa-onnx-whisper-small`、`csukuangfj/sherpa-onnx-whisper-turbo` | GitHub 提交 `86098128c0b4f24f0e2aa2994de830614b474227`；HF 修订依次 `973afd24965f72e36ca33b3055d56a652f456b4d`、`41f01f3fe87f28c78e2fbf8b568835947dd65ed9`、`8f3c18b358db4d1f2fc1eae49d75cd20989e4309`、`2ca6ff69fc878651b770880507669577ac41c2ff` |
| M3 | Moonshine 包内 `LICENSE`，取自 <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-zh-quantized-2026-02-27.tar.bz2> | 压缩包 SHA-256 `6495d4240f66bcdd2bbbbfefaa687c463150d467854001270934e88940b733c1`；LICENSE SHA-256 `6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd` |
| M4 | `DataoceanAI/Dolphin`（`languages.md`）：<https://github.com/DataoceanAI/Dolphin>；HF `DataoceanAI/dolphin-base`、`dolphin-small` | GitHub 提交 `78ea615194777f91a3970ff010b5d678d0bbf5f0`；HF 修订 `4f498c42abc03065b6a8b088800d08eb342b6e35`、`1df1f4f848fa1ad4bfe1fbdd1603ae45b5afb2ae` |
| M5 | `Tele-AI/TeleSpeech-ASR`：<https://github.com/Tele-AI/TeleSpeech-ASR>；HF `Tele-AI/TeleSpeech-ASR1.0` | GitHub 提交 `bc93f1b5835a593df48e4a4485d5b8d1cfe2afbb`；HF 修订 `31e0429ef454190b2e348c0ecc080ecc9cf31f22` |
| M6 | `facebookresearch/omnilingual-asr`：<https://github.com/facebookresearch/omnilingual-asr>；HF `facebook/omniASR-CTC-300M` | GitHub 提交 `81f51e224ce9e74b02cc2a3eaf21b2d91d743455`；HF 修订 `8e35f0cc28fa6099e0c14d56db85ce0423baa691` |
| M7 | HF `CohereLabs/cohere-transcribe-03-2026`（gated: auto）：<https://huggingface.co/CohereLabs/cohere-transcribe-03-2026> | 修订 `d552dadc2922ba6f6c1f994f8db904d52dcaf942` |
| M8 | HF `csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03`：<https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03> | 修订 `824c678e9b80f301110e3ea8cee9819a7994c049` |
| L1 | Apache License 2.0 全文：<https://www.apache.org/licenses/LICENSE-2.0.txt> | SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |

### 9.7 其他运行时

| 编号 | 内容与地址 | 版本或哈希 |
|---|---|---|
| O1 | HF `ggml-org/Qwen3-ASR-1.7B-GGUF`：<https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF> | 修订 `36a678687ba7d07a74ca70ccb0e36902e005fb80` |
| O2 | HF `ggml-org/Qwen3-ASR-0.6B-GGUF`：<https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF> | 修订 `928ab958557df9aa2ef1c93e0e83c7ad0933fae2` |
| O3 | HF `litert-community/Qwen3-ASR-0.6B`：<https://huggingface.co/litert-community/Qwen3-ASR-0.6B> | 修订 `5028edc410addd0f9cf0b35a58a38aeb8bea4604` |
| O4 | HF 模型搜索（`qwen3-asr-1.7b-int8`、`sherpa-onnx-qwen3-asr`，按下载量排序）与按作者检索 `csukuangfj`、`hongbo-miao` 的结果：<https://huggingface.co/api/models?search=qwen3-asr-1.7b-int8> | 读取日的搜索结果，未逐项核实 |
