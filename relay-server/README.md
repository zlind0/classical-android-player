# PodRelay

公网中继（PodRelay）的 Rust 实现。协议与部署说明见仓库根目录的
[`remote_database_relay.md`](../remote_database_relay.md)。

## 组成

| crate | 作用 |
|---|---|
| `protocol` | 帧协议、消息编解码、AES-256-GCM、资料库快照格式、`file_id` |
| `server` | 公网服务器核心：mTLS、会话、密文分片存储、快照版本、WANT 调度 |
| `agent` | NAS 侧库：连接中继、重建索引、推送快照与分片、响应 STAT / 直通请求 |
| `cli` | 唯一二进制 `relay-server`：`server` / `gencert` / `keygen` / `fingerprint` / `status` / `fetch` / `selftest` |

中继服务器只保存密文；`media_key` 只存在于 NAS 与手机端。

## 构建与测试

```bash
cargo test
cargo run -p pod-relay-cli -- selftest
cargo build --release -p pod-relay-cli   # target/release/relay-server
```

## 部署

```bash
# 1. 生成证书（在任意机器上执行，把产物拷到对应端点）
relay-server gencert --role relay  --cn relay.example.com \\
    --san dns:relay.example.com --san ip:203.0.113.10 --out certs/relay
relay-server gencert --role host   --cn aurora-nas --out certs/nas
relay-server gencert --role client --cn pixel-9   --out certs/pixel9

# 2. 记录指纹
relay-server fingerprint certs/nas.crt
relay-server fingerprint certs/pixel9.crt
relay-server fingerprint certs/relay.crt

# 3. 写配置并启动（配置样例见 remote_database_relay.md §13.1）
relay-server server --config /etc/pod-relay/relay.json
```

防火墙只放行配置里的 TCP 端口（默认 `21443`）；80/443 保持关闭，本服务不使用 HTTP/HTTPS。

## 运维

```bash
relay-server status --config /etc/pod-relay/agent.json   # 服务器/缓存/连接状态
relay-server fetch --config /etc/pod-relay/agent.json \\
    --kind music --key 42 --start 0 --end 65535 --out out.bin
```

`selftest` 会在 `relay-server/tmp/selftest-<time>/` 生成临时证书、存储与完整链路日志，
该目录已加入 `.gitignore`，由人工清理。
