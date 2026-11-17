# Auri Gateway 局域网传输与证书运维说明

本说明涵盖 Gateway 自签 TLS 证书的有效期、续签步骤、局域网监听规则及 mDNS 发现行为（依据 ADR-0018）。

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
