# contracts：三端与网关共用的数据合同

DEV-002 的产物，当前合同版本 `1.1`（1.0 冻结于 2026-11-15；1.1 于 2026-11-16 经产品负责人批准，在 `common.schema.json#/$defs/error_code` 新增 `GATEWAY_AUTH_INVALID`、`REQUEST_INVALID`、`PAIRING_CODE_INVALID`）。三端（ArkTS / Swift / Kotlin）和网关（Python）都以这里的文件为准；命名以仓库根目录的 `GLOSSARY.md` 为准。

## 目录

| 路径 | 内容 |
|---|---|
| `schemas/` | JSON Schema（2020-12）。`common.schema.json` 放标识、时间、哈希与全部枚举，其余文件引用它。 |
| `fixtures/messages/valid/` | 必须通过校验的样本。 |
| `fixtures/messages/schema-invalid/` | 必须被 schema 拒绝的样本，写明拒绝原因、出错位置和关键字。 |
| `fixtures/messages/semantic-invalid/` | 能通过 schema、但违反下面某条语义规则的样本。 |
| `fixtures/hash-vectors.json` | `text_sha256` 的跨语言测试向量。 |
| `fixtures/state-machines/` | 四个状态机的转移表（`*.machine.json`）和事件序列样本（`*.scenarios.json`）。 |
| `compatibility/` | 留给耳机 × 手机 × 系统的兼容矩阵，目前为空。 |

样本里的令牌、凭证、配对码都是带 `fixture` 字样的假值。`headset-profile` 样本是测试数据，不是兼容结论。

## 已冻结的合同

| 合同 | 文件 |
|---|---|
| 提交 Revision、入库结果、删除 Record | `revision.schema.json` |
| 领取、续租、确认、拒收 Delivery（MCP 工具输入输出） | `delivery.schema.json` |
| 只读查询 Record（`record_list`、`record_get`、`record_search` MCP 工具输入输出） | `record_query.schema.json` |
| 网关存活、能力、操作与 Record 状态查询（`/health`、`/v1/capabilities`、`/v1/operations/{id}`、`/v1/records/{id}/status`） | `gateway.schema.json` |
| 设备配对 | `pairing.schema.json` |
| Destination 管理与凭证签发 | `destination.schema.json` |
| Headset Profile | `headset-profile.schema.json` |
| 统一错误结构 | `error.schema.json` |
| 八个原生适配器类型（PRD 第 17.1 节）：`NormalizedControlEvent`、`CaptureEligibility`、`PreparedInput`、`CaptureHandle`、`RouteChangeEvent`、`RecordingResult`、`AsrCapabilities`、`AsrTaskHandle` | `adapter.schema.json` |
| 采集、转录、同步、投递四个状态机 | `fixtures/state-machines/` |

适配器类型在进程内传递，不是网络消息，所以不带 `schema_version`。它们描述的是跨平台语义，三端各自用本平台的类型实现，并用同一批样本验证。

没有冻结的部分：

- 云端转录的任务字段和 `/v1/asr/jobs` 接口（ADR-0019）。

## schema 表达不了、必须由实现校验的规则

每个实现都要自己做这些检查，并用 `fixtures/messages/semantic-invalid/` 下的样本验证。参考实现在 `tests/contract/semantic_rules.py`。

| 编号 | 规则 |
|---|---|
| SR-01 | `text_sha256` 等于正文按 UTF-8 编码后的原始字节的 SHA-256。不做 Unicode 规范化，不去空白，不转换换行。 |
| SR-02 | `speech_segments`（`RecordingResult` 里是 `verified_ranges`，下同）每段 `start_ms < end_ms`，各段按时间升序且互不重叠。 |
| SR-03 | 任何语音片段的 `end_ms` 不超过 `capture_duration_ms`。 |
| SR-04 | `speech_duration_ms` 等于各语音片段时长之和。 |
| SR-05 | V1 只接受 `execution_location = on_device`（ADR-0019）；对提交的 Revision 和 `AsrCapabilities` 都适用。 |
| SR-06 | 正文按 UTF-8 编码后不超过 1 MiB。超过时拒绝，不截断后假装成功。 |

另有几条规则依赖网关的状态，样本无法单独表达，由 DEV-015 的网关测试覆盖：

- 请求体里的 `record_id`、`revision` 必须与路径一致。
- 同一 `record_id + revision` 重复提交时只比较 `text_sha256`：相同返回首次入库的结果，不同返回 `RECORD_VERSION_CONFLICT`。
- 只有录下 Record 的那台设备能提交或删除它（ADR-0020）。
- 已有删除墓碑的 Record 不接受任何提交。
- 身份只来自认证结果。请求体里没有 `tenant_id`、`agent_id`，也不接受这些字段。
- `GET /health` 不要求认证，不返回正文、密钥或内部拓扑；`GET /v1/capabilities`、`GET /v1/operations/{operation_id}`、`GET /v1/records/{record_id}/status` 均要求设备令牌（Bearer）认证（未认证或持 Destination 凭证返回 `GATEWAY_AUTH_INVALID`），且 `/v1/operations/{operation_id}` 仅限来源设备查询、`/v1/records/{record_id}/status` 按认证 Tenant 隔离。
- `record_get` 返回完整正文时（`is_partial = false`），`text_sha256` 必须与正文原始 UTF-8 字节一致；超出分页上限时必须通过 `is_partial = true` 与不透明 `next_cursor` 分段返回，不得静默截断后冒充完整正文。

## 状态机文件怎么读

`*.machine.json` 的 `transitions` 列出全部允许的（状态，事件）组合：

- `outcome = transition`：按规则转移，可以转到自身。
- `outcome = ignored`：幂等地忽略，状态不变，不报错。
- 没有列出的组合：被拒绝，状态不变，并返回错误。迟到的旧回调属于这一类。

`reserved_states` 是留在枚举里、但 V1 不允许产生的状态。`*.scenarios.json` 的每个样本给出初始状态和一串事件，每一步写明期望的结果和之后的状态。实现方读这些文件，把事件依次喂给自己的状态机并比对。

## 运行校验

依赖版本固定在 `tests/contract/requirements.txt`。在仓库根目录执行：

```bash
python3 -m venv .venv
.venv/bin/pip install -r tests/contract/requirements.txt
.venv/bin/pytest tests/contract
```

测试检查：schema 本身合法；有效样本通过；无效样本因为它声明的原因被拒绝；每个合同都同时有有效和无效样本；状态机定义自洽，与 `common.schema.json` 的枚举一致，每条规则至少被一个样本覆盖。

## 修改规则

- 改 schema、状态机或语义规则由单一合同负责人合并，其他人不得另建相近但不兼容的字段（PRD 第 24.2 节）。
- 不得通过改样本的期望、删无效样本来让测试通过。
- 新增可选字段是兼容变更；删除字段、改名、新增必填字段、增删枚举值、改状态机规则都是不兼容变更，需要提升 `schema_version` 并在 PRD 版本记录里写明。
