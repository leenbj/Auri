# Auri Gateway 运维说明

本说明涵盖 Gateway 的日常运维命令、自签 TLS 证书管理（依据 ADR-0018）、设备与凭证管理、丢失设备记录清理、在线备份与恢复，以及数据库安全升级流程。

## 1. 密钥与证书文件

Gateway 首次在 TLS 模式下启动（`serve`）或首次生成配对载荷（`pair`）时，会在持久化数据目录 `<data_dir>/tls/` 下自动生成：

- `gateway.key.pem`：ECDSA P-256 私钥（文件权限 `0600`）。**切勿提交到仓库、打印到日志或暴露给任何客户端。**
- `gateway.cert.pem`：X.509 v3 自签服务端证书（文件权限 `0644`），SAN 包含本机回环与局域网 IP。

此后每次重启都会复用同一把私钥。客户端配对时记住的是证书中公钥（`SubjectPublicKeyInfo` DER 编码）的 SHA-256 指纹（`public_key_sha256`），而不是整张证书的哈希。

## 2. 证书有效期

- 默认有效期：**365 天**（`not_valid_before` 设在签发前 5 分钟以容忍轻微时钟偏差）。
- 可在续签时通过 `--validity-days <天数>` 自定义有效期（例如 `180` 或 `365`）。
- 可通过以下命令查看当前证书的有效期和公钥指纹：

```bash
openssl x509 -in <data_dir>/tls/gateway.cert.pem -noout -dates -serial
openssl x509 -in <data_dir>/tls/gateway.cert.pem -noout -pubkey | openssl pkey -pubin -outform der | sha256sum
```

## 3. 证书续签步骤（无需重新配对）

使用同一把私钥重新签发证书时，公钥与 `public_key_sha256` 指纹**保持不变**，已配对的手机无需重新扫码：

1. 运行续签命令（可选 `--validity-days` 与 `--json`）：

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway renew-cert --data-dir <data_dir> --validity-days 365
```

命令会检查 `<data_dir>/tls/gateway.key.pem` 是否存在，并用同一把私钥原子替换 `<data_dir>/tls/gateway.cert.pem`，输出新的 `not_valid_after` 与保持不变的 `public_key_sha256`。

2. 重启运行中的 Gateway 进程以加载新证书：

```bash
# 先优雅停止正在运行的 serve 进程（SIGTERM / Ctrl-C），再重新启动：
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir <data_dir> --port 8443
```

3. 核对续签前后 `public_key_sha256` 一致后，手机与参考客户端可继续直接连接。

### 何时需要重新配对

仅当 `gateway.key.pem` 丢失、损坏、未随数据迁移或怀疑泄露而删除重生成时，公钥指纹才会变化；此时必须重新运行 `pair` 生成配对二维码并让设备重新配对。

## 4. 局域网监听与明文限制

- 默认 `serve` 会自动发现并绑定本机回环（`127.0.0.1`）及私网局域网 IP（RFC 1918 / 链路本地 / IPv6 ULA），**拒绝**绑定 `0.0.0.0`、`::`、公网 IP 或 VPN/CGNAT 地址（如 `100.64.0.0/10`）。
- 默认强制使用 HTTPS（TLS）。仅本机开发调试时可通过 `--host 127.0.0.1 --plaintext-loopback` 启用回环明文；在任何非回环地址上指定 `--plaintext-loopback` 均会被拒绝启动。
- 启动后 Gateway 会在局域网内广播 mDNS 服务 `_auri-gateway._tcp`（默认启用，可用 `--no-mdns` 关闭）。客户端仅将 mDNS 作为地址线索，连接前仍必须校验配对时绑定的 `public_key_sha256`，指纹不符即直接拒绝。

### 局域网 IP 变化后配对地址与 mDNS 不一致的处理

当运行 Gateway 的主机因 DHCP 重新分配、切换网络等原因导致局域网 IP 变化时，已配对客户端记下的配对载荷 `addresses`（或变化前生成的旧配对载荷）可能仍指向旧 IP，而重启后的 Gateway 通过 mDNS（`_auri-gateway._tcp`）广播的是当前新 IP，两者会出现不一致。依据 ADR-0018，运维人员按以下方式处理：

- **依赖 mDNS 自动回退（无需重新配对，推荐）**：确保在新的网络环境下重启 Gateway `serve`（默认开启 mDNS，不要加 `--no-mdns`），使 Gateway 绑定新局域网 IP 并广播最新地址。已配对客户端在原 `addresses` 不可达时会自动通过 mDNS 查找 Gateway，并在核对不变的 `public_key_sha256` 指纹后继续连接（iOS 需已授予“本地网络”权限）。
- **在 mDNS 不可用时重新配对**：若启动时使用了 `--no-mdns`、当前路由器/局域网拦截了 mDNS 多播、或客户端平台本身不支持 mDNS（依 ADR-0018 退回仅使用已记录地址），则必须先在新网络下重启 Gateway，再重新运行 `pair`（必要时配合 `--host <当前局域网IP>`）生成新的配对二维码并重新配对。
- 说明：客户端校验的是私钥对应的公钥指纹（`public_key_sha256`），只要未更换 `gateway.key.pem`，IP 变化本身不需要重新生成私钥；若希望证书 SAN 列表也同步包含新 IP，可执行一次 `renew-cert` 后重启 Gateway。

## 5. 设备与凭证管理

以下命令行工具直接操作持久化数据目录 `<data_dir>`（可附加 `--json` 输出结构化 JSON），输出只包含标识、名称、状态和时间戳，**绝不输出**设备令牌、Destination 凭证或其哈希、或任何 Record 正文。

### 列出已配对设备与创建的凭证

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway list-devices --data-dir <data_dir>
```

按配对时间列出每台已配对设备（`active` / `revoked`），以及该设备曾经签发过的每条 Destination 凭证（`credential_id`、`destination_id`、名称、状态与时间戳）。

### 吊销设备或单个凭证

- **吊销设备**（手机丢失或淘汰时使用）：

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway revoke-device --data-dir <data_dir> --device-id <device_id>
```

被吊销的设备令牌在下次请求时会被立即拒绝（HTTP 401 `GATEWAY_AUTH_REVOKED`）。依据 ADR-008 与工单约定，**吊销设备不会自动吊销它创建过的 Destination 凭证**，仍在合法运行中的 Agent 不受影响。

- **吊销单个凭证**（仅撤回某一条 Agent 凭证，不影响设备或 Destination 本身）：

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway revoke-credential --data-dir <data_dir> --credential-id <credential_id>
```

该凭证在下次访问 `/mcp` 时会被拒绝（`GATEWAY_AUTH_REVOKED`）；设备仍可为同一 Destination 重新签发新凭证。

撤销凭证的后果（Ticket 22 评审 B3，保守的决定）：撤销的若是该 Destination **最后一条有效凭证**，它就进入“暂停”：

- 撤销的那一刻，它上面还没被领取（`PENDING`）和租约中（`LEASED`）的投递被**取消**（`CANCELLED`）；已确认、已拒收的历史不动。之后签发的新凭证**领不到**这些文字——撤销前、撤销期间攒下的文字不会补发。
- 暂停期间新入库的文字照常入库，但**不再为它建投递**（手机提交时点名它也不报错、不拒收）。
- 在手机上对这个 Destination“生成/轮换凭证”才恢复，且只对恢复**之后**入库的文字生效。手机上它会显示“已暂停”（网关在 Destination 资源里给 `credential_active: false`）。
- **撤销截止点**（再评审 R1）：恢复的那一刻网关给这个 Destination 记一个“撤销截止点”（`destinations.revocation_cutoff`；与库里已有文字的最晚入库时间取较大者，所以网关时钟回拨过也不会漏）。入库时间不晚于它的文字，替换凭证在**每一条** Agent 路径上都看不到：`record_list` 不列、`record_search` 搜不到、`record_get` 回 404（含已取消、已确认的旧投递）、`delivery_claim` 领不到、`delivery_renew_lease` / `delivery_ack` / `delivery_nack` 认不出旧的投递编号与租约（回 404，连已确认那条的重复确认也不再回答）。只轮换（不暂停）不设截止点，排队的文字照常交给新凭证。**延迟到达的追加**（再评审 R2：手机上预览确认在撤销并签发替换凭证之后才点）对截止点之前入库的文字同样不建投递、不报错（回答里没有它，同一请求里别的目标照常建），所以状态页不会出现一条永远领不到的“已入库”。截止点只往后走：再暂停一次、再恢复，它就移到那一次。升级到这一版时，旧库里已经结束的暂停按凭证历史补上截止点（迁移 `0005`：某条凭证签发时，更早的凭证都在它之前就已撤销，而轮换是在签发的同一瞬间撤销旧的）。
- 想让 Agent 接着收原来的文字：不要撤销凭证，用手机“轮换凭证”（旧凭证立即失效，排着的投递保留给新凭证）。
- 撤销一条已经被轮换掉的旧凭证什么也不改变（它早已失效，Destination 另有有效凭证）；从没签发过凭证的 Destination 不是“暂停”。

### Destination 数量上限（Ticket 22 评审 M4）

- 一个 Tenant 最多同时有 **50 个有效**（未撤销）的 Destination。再创建第 51 个，网关回答 `409 REQUEST_INVALID`，`required_action` 说明先撤销用不到的；什么也不会创建。手机上看到的是“网关上有效的目的地已经有 50 个……没有创建新的”。
- 已撤销的 Destination 不占名额：撤销一个，就能再建一个（撤销的那个仍记录在库里，所以库里的总行数可以超过 50）。
- 这条规则只管创建，不动已有数据：升级前就超过 50 个的网关照常运行，既不删也不拒读，只是在撤销到 50 个以下之前不能再建新的。
- 数的动作和创建在同一个事务里，几部手机同时创建也不会超过上限。

### 清理已吊销设备名下的 Record

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway delete-device-records --data-dir <data_dir> --device-id <device_id>
```

- 仅允许对**已吊销**（`revoked`）的设备执行；对活跃设备执行会被拒绝。
- 删除语义与设备自己调用 `DELETE /v1/records/{record_id}` 完全一致（共用同一套墓碑与投递取消逻辑）：为每条尚未删除的 Record 写下永久墓碑、取消所有未完成的投递（`PENDING` / `LEASED` → `CANCELLED`），并**保留**已确认（`ACKNOWLEDGED`）或已永久拒收（`REJECTED`）的历史投递。重复执行是幂等的。

## 6. 备份与恢复

### 在线备份（支持 Gateway 运行中执行）

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway backup --data-dir <data_dir> --output <backup.tar.gz>
```

- 通过 SQLite 在线备份 API（`sqlite3.Connection.backup`）生成一致性快照，即使 `serve` 正在运行且处于 WAL 模式也能安全备份，不使用危险的直接文件拷贝。
- 归档内包含 `manifest.json`（含各文件 SHA-256 校验和）、一致性 `gateway.db`，以及恢复运行所需的密钥文件（若存在）：`tls/gateway.key.pem`、`tls/gateway.cert.pem` 与分页游标密钥 `cursor.key`（不打包瞬态 `runtime.json`）。
- 生成的备份文件包含私密密钥材料与凭证哈希，权限严格限制为 `0600`（仅属主可读写）。

### 恢复到新的空目录

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway restore --backup <backup.tar.gz> --data-dir <new_empty_data_dir>
```

- 目标目录必须为空或尚不存在；若目录非空则拒绝恢复，避免误覆盖现有数据。
- 恢复前会校验归档路径安全性、各文件 SHA-256 校验和及 SQLite `PRAGMA integrity_check`，并恢复安全文件权限（`gateway.db`、`cursor.key`、`tls/gateway.key.pem` 均为 `0600`）。
- 恢复后用 `serve --data-dir <new_empty_data_dir>` 启动即可继续服务：TLS 公钥指纹、已配对设备令牌、Destination 凭证哈希、Record 墓碑与 Delivery 状态均保持完整可用。

## 7. 升级与自动迁移机制

启动 `serve` 或显式执行 `migrate` 时，Gateway 会检查 `<data_dir>/gateway.db` 中 `schema_migrations` 记录的已应用版本，自动应用所有更高编号的 SQL 迁移脚本：

```bash
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway migrate --data-dir <data_dir>
```

### 为何采用“先在副本上迁移验证、成功后再原子替换”的策略

- **设计选择**：当检测到存在待执行的迁移时，Gateway 不会直接改写在线的 `gateway.db`，而是先通过 SQLite 在线备份 API 将原库（连同已提交的 WAL 帧）完整复制到同目录下的临时副本，在副本的事务内依次执行全部待迁移 SQL，并运行 `PRAGMA integrity_check` 校验；**只有全部迁移与完整性校验均通过后**，才会在排空原库 WAL 后以 `os.replace` 原子替换 `gateway.db`。
- **相比纯原地事务的优势**：SQLite 中部分语句（或跨多个迁移文件的组合变更、外键/索引重建以及 WAL 边车文件状态）在原地失败时容易留下中间副作用或 WAL 污染；在副本上试运行并验证通过后再替换，能够保证**只要任何一步迁移失败，原始 `gateway.db` 及其数据字节完全不被触碰**，同时 Gateway 明确拒绝启动并向标准错误输出清晰原因，排查后可安全修复并重试。
