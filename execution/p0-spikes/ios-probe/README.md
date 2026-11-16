# DEV-004 iOS 控制/路由/后台激活探针

一次性探针，不是产品代码。目的是用真机回答 PRD 第 4、6、7 章的几个问题，并留下可复现的证据。探针只用公开 API：不播放任何音频保活，不伪造通话，不使用私有 API，蓝牙路由证据不满足时拒绝录音，不回退手机麦克风。

## 构建与安装

前置：Xcode 27.1、已登录的 Apple ID、已开启开发者模式的 iPhone。

```bash
# 工程由 XcodeGen 生成（版本固定 2.46.0，zip 的 sha256 见下），.xcodeproj 与 Info.plist 不入库
xcodegen generate --spec project.yml --project .
xcodebuild -project AuriProbeIOS.xcodeproj -scheme AuriProbeIOS \
  -destination 'id=<设备 UDID>' -derivedDataPath <构建目录> \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=<你的团队 ID> build
xcrun devicectl device install app --device <UDID> <构建目录>/Build/Products/Debug-iphoneos/AuriProbeIOS.app
xcrun devicectl device process launch --device <UDID> app.auri.probe.ios
```

- XcodeGen 2.46.0：`xcodegen.zip` 的 sha256 为 `4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806`。
- 免费账号首次安装后，要在手机上信任开发者描述文件：设置 → 通用 → VPN 与设备管理。
- 团队 ID 不写进仓库，构建时用命令行传入。

## 取日志

探针把事件追加写入 App 沙盒的 `Documents/probe-log.jsonl`（JSON Lines，含单调时间 `mono_ns`、生命周期状态 `app_state`），音频写入 `Documents/rec-<session>.caf`。

```bash
xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer \
  --domain-identifier app.auri.probe.ios --source Documents/probe-log.jsonl \
  --destination execution/p0-spikes/ios-probe/results/raw/
```

`results/raw/` 已被 `.gitignore` 忽略：原始日志含设备名和真实口述音频，不入库。入库的只有脱敏后的结论。

## 场景矩阵

每个场景单独给出状态：`NOT_TESTED / SUPPORTED_FOREGROUND / SUPPORTED_RECORDING_IN_BACKGROUND / SUPPORTED_BACKGROUND_RESTART / PARTIAL / UNSUPPORTED`。未跑的保持 `NOT_TESTED`，不得当通过。耳机只在 iPhone 上连接（先从其他设备断开）。

| 场景 | 操作 | 看哪些日志事件 | 对应 PRD |
|---|---|---|---|
| S1 前台待命收到什么 | 打开 Armed，映射选 `logOnly`，双击耳机 | `remote_command` 的 `platform_command`、`event_timestamp` | FR-HS-001 |
| S2 前台开始录音 | 映射选 `semantic`，双击 | `capture_start_requested` → `available_inputs` → `capture_started`，`route.inputs[].type` 为蓝牙类型 | FR-REC-001/002 |
| S3 录音中再次双击能否停止（必测） | S2 之后再双击 | 是否出现 `remote_command` 与 `capture_stopped(reason=command_*)` | FR-HS-003 第 5 步 |
| S4 录音中锁屏 | 录音中锁屏，说几秒，再双击 | `protected_data_unavailable`、帧数是否继续增加、锁屏下 `remote_command` 是否到达 | 第 4 章 |
| S5 停止后后台再次启动 | 停止后锁屏或回桌面，再双击 | `remote_command` 是否到达；`capture_blocked` 的 `error_code` | iOS 最大风险 |
| S6 进程终止后 | 上滑关掉 App，再双击 | 是否有任何新事件（预期没有） | 第 12 章 |
| S7 路由丢失 | 录音中把耳机断开或关机 | `route_change` → `gate_closed_route_not_bluetooth` → `capture_stopped(reason=route_lost)`，没有手机麦克风帧 | FR-REC-004 |
| S8 对照变量 | 重复 S1、S3、S5，只切换“发布 Now Playing 元数据” | 两种设置下 `remote_command` 到达情况的差异 | 开放问题 |

S8 仅发布元数据，不播放任何音频。如果结果显示必须依赖它才能收到命令，要先提交评审，确认这不属于 PRD 禁止的保活手段，才能写进产品。

## 证据等级

探针在真机上跑出的日志才算 E3。模拟器、单元测试、手动编造的日志都不能替代。失败也要保留日志，并附合法对照（例如同一操作在前台成功）。
