# contracts：三端与网关共用的数据合同

DEV-002 的产物，当前合同版本 `1.2`（1.0 冻结于 2026-11-15；1.1 于 2026-11-16 经产品负责人批准，在 `common.schema.json#/$defs/error_code` 新增 `GATEWAY_AUTH_INVALID`、`REQUEST_INVALID`、`PAIRING_CODE_INVALID`；1.2 于 2026-11-21 随 Ticket 22 再评审 R7 提升，内容见“修改规则”之后的“版本记录”）。三端（ArkTS / Swift / Kotlin）和网关（Python）都以这里的文件为准；命名以仓库根目录的 `GLOSSARY.md` 为准。

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
| 提交 Revision、入库结果、删除 Record、给已入库的 Revision 追加 Delivery | `revision.schema.json` |
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
- 给已入库的 Revision 追加 Delivery：`POST /v1/records/{record_id}/revisions/{revision}/deliveries`，请求 `revision.schema.json#/$defs/delivery_additions`，响应 `#/$defs/deliveries_added`（Ticket 22 评审 B2，关闭缺口 G13；合同 1.2 新增：一个端点和两条消息，没有改任何已有字段、必填项、枚举或状态机；1.1 的网关没有这个端点，所以手机要求 1.2 的网关）。要求设备令牌认证，仅限来源设备（ADR-0020：非来源设备 `403 RECORD_NOT_ORIGIN_DEVICE`），Record 已删除 `410 RECORD_DELETED`，Record 或 Revision 不存在 `404 REQUEST_INVALID`。Revision 本身（正文、哈希、`operation_id`、首次入库返回的 `deliveries` 快照）不变；每个 `(Revision, Destination)` 至多一份 Delivery，重复请求回答现有的那份（不论它现在什么状态），不新建、不改状态；整个请求要么全部成功、要么一份也不建（Destination 不存在、已撤销或不接收这个 `intent` 返回 `422 REQUEST_INVALID`；已经有 Delivery 的 Destination 不再校验）。`intent` 是这批新 Delivery 自己的标签，Agent 领到的就是它。较新的版本取代较旧的规则与首次入库一致：同一 Destination 上更旧、尚未被领取的 Delivery 被取消；该 Destination 已有更高版本的 Delivery 时，新建的这份直接是 `CANCELLED`。
- 凭证被撤销 = Destination 暂停（Ticket 22 评审 B3；合同 1.2 新增：Destination 资源多一个可选字段 `credential_active`——资源 schema 是 `additionalProperties: false`，严格校验的 1.1 客户端会拒收带它的响应，所以随 1.2 提升版本；没有改任何已有字段、必填项、枚举或状态机）。签发过凭证（`credential_issued = true`）、却没有任何有效凭证（`credential_active = false`）、也没被撤销（`revoked_at = null`）的 Destination 是**暂停**的；它只由撤销凭证造成（轮换总会留下一条有效凭证），从凭证的状态现算，不另存。规则：① 撤销最后一条有效凭证的那一刻，该 Destination 上尚未被领取（`PENDING`）和租约中（`LEASED`）的 Delivery 被取消（沿用状态机的 `DESTINATION_REVOKED` 事件，它的含义是“这个 Destination 不能再接收”；不新增状态或事件；已确认、已拒收、已取消的历史不动）；② 暂停期间，提交 Revision 的 `publication.destination_ids` 和追加 Delivery 里点名它**不报错**，只是不为它建 Delivery（响应里没有它）——手机可能还不知道凭证被撤销了，整条被拒会让文字永远进不了网关；③ 签发新凭证（`POST /v1/destinations/{id}/credential`）才恢复，而且只对**之后**入库的文字生效：撤销之前、撤销期间的文字永远不会补给新凭证；④ 从没签发过凭证的 Destination 不是暂停（照旧为它建 Delivery，第一次签发凭证后领得到）；⑤ 被撤销（`revoked_at` 不为 null）的 Destination 的 `credential_active` 一定是 `false`，行为仍按“已撤销”（提交里点名它是 `422 REQUEST_INVALID`）；⑥ **撤销截止点**（再评审 R1，第四轮 F6 改为单调入库顺序）：③ 里“恢复”的那一刻，网关为该 Destination 记一个按持久单调**入库顺序**计算的截止点（不依赖墙上时间，所以网关时钟曾跑快并校正回来后，恢复之后新入库的文字不会被永久截断）；入库顺序不晚于截止点的文字，这个 Destination 的 Agent 在每一条路径上都看不到——`record_list` 不列、`record_search` 搜不到、`record_get` 回 `404 REQUEST_INVALID`、`delivery_claim` 领不到、`delivery_renew_lease` / `delivery_ack` / `delivery_nack` 对这些文字的投递编号一律回 `404 REQUEST_INVALID`（含撤销之前就已确认的）；不暂停的轮换没有截止点；⑦ 对截止点之前入库的文字，**追加 Delivery**（`POST …/deliveries`）里点名这个 Destination 不为它建 Delivery、也不报错（响应里没有它，和暂停一样；同一请求里别的目标照常建）——否则会留下一条永远没有 Agent 能领到、却会在状态页里显示“已入库”的 PENDING；恢复之后新入库的文字其入库顺序总是严格晚于已有截止点，照常建 Delivery。
- 一个 Tenant 至多有 50 个有效（`revoked_at = null`）Destination（Ticket 22 评审 M4；合同 1.2 起的网关规则：没有改任何字段、必填项、枚举、错误码或状态机）。第 51 个 `POST /v1/destinations` 返回 `409 REQUEST_INVALID`（`retryable = false`，`required_action` 说明先撤销用不到的），什么也不创建；网关在创建事务里数，所以几部手机同时创建也不会超过 50 个。已撤销的不占名额，撤销一个就空出一个；这条规则不动网关上已有的数据（早先超过 50 个的不会被删、也不会被拒读）。50 与 `publication.destination_ids`、追加 Delivery 的 `destination_ids` 每次最多 50 个一致，所以“发给全部有效的 Destination”一个请求装得下，不会有目标被悄悄丢掉；手机以这个 409 为准，不用本机缓存拒绝（缓存可能是旧的）。
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
- 例外：资源与响应的 schema 都是 `additionalProperties: false`，往**响应或资源**里新增可选字段，严格校验的旧客户端会拒收，同样要提升 `schema_version`（1.2 就是这么来的）；只往**请求**里加可选字段才算兼容。网关行为变化（撤销、截止点、上限）若会让旧客户端的假设落空，也一并写进版本记录。
- 提升版本的动作清单（以 1.1 → 1.2 为例）：`common.schema.json` 的 `schema_version` 常量、全部 schema 的 `$id`、每个样本的 `schema_version`、`tests/contract/conftest.py` 的 `BASE_URI`、网关与三端代码里的版本常量、本文件与 PRD 版本记录、网关 `OPERATIONS.md`；网关 `GET /v1/capabilities` 只通告它实现的版本，手机据此拒绝太旧的网关。

## 版本记录

- **1.2（2026-11-21，Ticket 22 再评审 R7；2026-11-24 第四轮 F6 补充）**：① `destination` 资源新增可选字段 `credential_active`（B3）；② 新端点 `POST /v1/records/{record_id}/revisions/{revision}/deliveries` 与消息 `delivery_additions`、`deliveries_added`（B2）；③ 网关规则：撤销最后一条有效凭证 = 暂停 Destination 并取消排着的投递（B3），签发新凭证时记撤销截止点、截止点之前的文字对新凭证在所有读取路径上不可见（再评审 R1、R2；第四轮 F6 改为按网关内部持久单调的入库顺序比较，不再按墙上时间，仅改网关行为、不动任何网络消息与 schema，因此不提升 `schema_version`），每个 Tenant 至多 50 个有效 Destination、第 51 个 `409 REQUEST_INVALID`（M4）；④ 版本协商：`schema_version` 常量改为 `1.2`，`GET /v1/capabilities` 的 `supported_schema_versions` 只有 `["1.2"]`，网关不再接受 `schema_version` 为 `1.1` 的请求（`422 REQUEST_INVALID`）；手机要求 1.2 的网关，遇到更旧的网关显示“网关版本太旧，请先更新网关”并且什么都不发。没有新增错误码，没有改状态机、已有字段或枚举；所有样本只改了 `schema_version`（和 `$id`），没有改任何期望。
- 1.1（2026-11-16）：新增三个网关错误码。
- 1.0（2026-11-15）：DEV-002 冻结。
