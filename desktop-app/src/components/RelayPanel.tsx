import { useCallback, useEffect, useMemo, useState } from "react";
import {
  api,
  formatBytes,
  type RelayDevice,
  type RelayPairStart,
  type RelayRemotePairStart,
  type RelayStatus,
} from "../api";

interface RelayPanelProps {
  onClose: () => void;
  onToast: (message: string) => void;
}

export function RelayPanel({ onClose, onToast }: RelayPanelProps) {
  const [status, setStatus] = useState<RelayStatus | null>(null);
  const [bootstrap, setBootstrap] = useState("");
  const [pairLabel, setPairLabel] = useState("");
  const [pairing, setPairing] = useState<RelayPairStart | null>(null);
  const [remotePairing, setRemotePairing] = useState<RelayRemotePairStart | null>(null);
  const [remoteTtlMinutes, setRemoteTtlMinutes] = useState(10);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Devices are expanded by default so the real folder list is visible without
  // an extra click; only explicit collapses are remembered.
  const [collapsed, setCollapsed] = useState<Set<string>>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem("relay.collapsedDevices") || "[]");
      return new Set(Array.isArray(saved) ? saved.filter((id) => typeof id === "string") : []);
    } catch {
      return new Set();
    }
  });

  const load = useCallback(async () => {
    try {
      const next = await api.relayStatus();
      setStatus(next);
      setError(null);
    } catch (err) {
      setError(String(err));
    }
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, 2000);
    return () => window.clearInterval(timer);
  }, [load]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const configured = Boolean(status?.settings.hostToken && status?.settings.enabled);
  const agent = status?.agent;
  const connected = Boolean(agent?.connected);
  const remaining = pairing ? Math.max(0, Math.ceil((pairing.expiresAt - now) / 1000)) : 0;
  const remoteRemaining = remotePairing
    ? Math.max(0, Math.ceil((remotePairing.expiresAt - now) / 1000))
    : 0;

  useEffect(() => {
    if (pairing && remaining === 0) {
      setPairing(null);
      api.relayCancelPairing().catch(() => {});
    }
  }, [pairing, remaining]);

  useEffect(() => {
    if (remotePairing && remoteRemaining === 0) setRemotePairing(null);
  }, [remotePairing, remoteRemaining]);

  const connect = async () => {
    setBusy(true);
    setError(null);
    try {
      const next = await api.relayApplyBootstrap(bootstrap.trim());
      setStatus(next);
      setBootstrap("");
      onToast("已连接中继，正在同步资料库…");
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const startPairing = async () => {
    setBusy(true);
    setError(null);
    try {
      const start = await api.relayStartPairing(pairLabel);
      setPairing(start);
      setNow(Date.now());
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const startRemotePairing = async () => {
    setBusy(true);
    setError(null);
    try {
      const start = await api.relayStartRemotePairing(pairLabel, remoteTtlMinutes);
      setRemotePairing(start);
      setNow(Date.now());
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const copyRemoteCode = async () => {
    if (!remotePairing) return;
    try {
      await navigator.clipboard.writeText(remotePairing.code);
      onToast("配对字符串已复制");
    } catch {
      onToast("复制失败，请手动选中复制");
    }
  };

  const cancelPairing = async () => {
    setPairing(null);
    setRemotePairing(null);
    await api.relayCancelPairing().catch(() => {});
  };

  const revoke = async (id: string) => {
    if (!window.confirm("撤销后该设备需要重新扫码配对，已下载的媒体不受影响。继续？")) return;
    try {
      const next = await api.relayRevokeDevice(id);
      setStatus(next);
      onToast("设备已撤销");
    } catch (err) {
      setError(String(err));
    }
  };

  const toggleExpanded = (id: string) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      try {
        localStorage.setItem("relay.collapsedDevices", JSON.stringify([...next]));
      } catch {
        // Storage can be unavailable; the collapsed state just won't persist.
      }
      return next;
    });
  };

  const setRoots = async (device: RelayDevice, rootIds: number[]) => {
    try {
      const next = await api.relaySetDeviceRoots(device.id, rootIds);
      setStatus(next);
    } catch (err) {
      setError(String(err));
    }
  };

  const toggleRoot = (device: RelayDevice, rootId: number) => {
    const current = new Set(device.grants.map((grant) => grant.rootId));
    if (current.has(rootId)) current.delete(rootId);
    else current.add(rootId);
    setRoots(device, [...current]);
  };

  const resetIdentity = async () => {
    if (!window.confirm("将重新生成 NAS 证书，并用当前 host token 重新绑定中继。已配对设备不受影响。继续？")) {
      return;
    }
    try {
      const next = await api.relayResetIdentity();
      setStatus(next);
      onToast("已重置 NAS 证书");
    } catch (err) {
      setError(String(err));
    }
  };

  const resetEncryption = async () => {
    if (
      !window.confirm(
        "重置端到端加密会更换媒体密钥：中继上已缓存的分片、资料库快照、所有已配对设备全部作废，所有手机都要重新扫码配对。确定继续？",
      )
    ) {
      return;
    }
    try {
      const next = await api.relayResetEncryption();
      setStatus(next);
      setPairing(null);
      onToast("媒体密钥已更换，请重新配对设备");
    } catch (err) {
      setError(String(err));
    }
  };

  const deviceCount = status?.devices.length ?? 0;
  const usage = agent && agent.storeCap > 0 ? Math.min(1, agent.storeUsed / agent.storeCap) : 0;
  const bootstrapHint = useMemo(
    () => "在服务器上首次启动 relay 时会打印一行 podrelay://relay?…，整行粘贴到这里",
    [],
  );

  return (
    <div className="relay-overlay" onClick={onClose}>
      <div className="relay-card" onClick={(event) => event.stopPropagation()}>
        <header className="relay-head">
          <div>
            <h2>公网中继</h2>
            <p>PodRelay：公网服务器只保存加密分片；可在局域网扫码配对，也可生成跨网络配对字符串。</p>
          </div>
          <button className="icon-btn" onClick={onClose} title="关闭">
            ×
          </button>
        </header>

        {error && <div className="banner error">{error}</div>}

        {!configured && (
          <section className="relay-section">
            <h3>连接中继服务器</h3>
            <p className="relay-hint">{bootstrapHint}</p>
            <textarea
              className="relay-textarea"
              rows={3}
              placeholder="podrelay://relay?v=2&a=relay.example.com:21443&p=sha256/…&t=…"
              value={bootstrap}
              onChange={(event) => setBootstrap(event.target.value)}
            />
            <div className="relay-actions">
              <button className="btn primary" disabled={busy || !bootstrap.trim()} onClick={connect}>
                {busy ? "连接中…" : "连接"}
              </button>
            </div>
          </section>
        )}

        {configured && (
          <>
            <section className="relay-section">
              <h3>运行状态</h3>
              <div className="relay-status-row">
                <span className={agent?.connected ? "dot ok" : "dot bad"} />
                {agent?.connected ? "已连接中继" : "未连接"}
                <span className="relay-hint">
                  快照版本 {agent?.libVersion ?? 0} · 配对设备 {deviceCount}
                </span>
              </div>
              <div className="relay-meter">
                <div style={{ width: `${Math.round(usage * 100)}%` }} />
              </div>
              <div className="relay-hint">
                缓存 {formatBytes(agent?.storeUsed ?? 0)} / {formatBytes(agent?.storeCap ?? 0)}
                {status?.pairing.lanHost ? ` · 局域网 ${status.pairing.lanHost}:${status.pairing.lanPort}` : ""}
              </div>
              {agent?.lastError && <div className="banner error">{agent.lastError}</div>}
            </section>

            <section className="relay-section">
              <h3>添加设备</h3>
              <p className="relay-hint">
                手机需要与这台电脑连同一个 Wi-Fi。用手机系统相机扫码，确认后自动完成证书与密钥交接。
              </p>
              <div className="relay-pair-row">
                <input
                  className="relay-input"
                  placeholder="设备名，例如 Pixel 9"
                  value={pairLabel}
                  onChange={(event) => setPairLabel(event.target.value)}
                />
                <button className="btn primary" disabled={busy || !connected} onClick={startPairing}>
                  生成配对码
                </button>
              </div>
              {!connected && <div className="relay-hint">中继未连接时无法配对。</div>}
              {pairing && (
                <div className="relay-qr-wrap">
                  <div className="relay-qr" dangerouslySetInnerHTML={{ __html: pairing.svg }} />
                  <div className="relay-qr-meta">
                    <strong>{pairing.label}</strong>
                    <span className={remaining <= 60 ? "relay-countdown warn" : "relay-countdown"}>
                      {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}
                    </span>
                    <button className="btn ghost small" onClick={cancelPairing}>
                      作废
                    </button>
                  </div>
                  <code className="relay-uri">{pairing.uri}</code>
                </div>
              )}

              <details className="relay-advanced">
                <summary>远程配对（不需要同一 Wi-Fi）</summary>
                <p className="relay-hint">
                  生成一段包含中继地址、证书、私钥、媒体密钥的配对字符串，gzip + Base64 编码。
                  发给手机后在「公网中继 → 配对」里粘贴即可；过期后手机端会拒绝导入。
                </p>
                <div className="relay-pair-row">
                  <input
                    className="relay-input"
                    placeholder="设备名，例如 Pixel 9"
                    value={pairLabel}
                    onChange={(event) => setPairLabel(event.target.value)}
                  />
                  <label className="relay-hint">
                    有效期
                    <input
                      className="relay-input relay-ttl"
                      type="number"
                      min={1}
                      max={10080}
                      value={remoteTtlMinutes}
                      onChange={(event) =>
                        setRemoteTtlMinutes(Math.max(1, Number(event.target.value) || 1))
                      }
                    />
                    分钟
                  </label>
                  <button className="btn primary" disabled={busy || !connected} onClick={startRemotePairing}>
                    生成远程配对码
                  </button>
                </div>
                {remotePairing && (
                  <div className="relay-qr-wrap">
                    <div className="relay-qr-meta">
                      <strong>{remotePairing.label}</strong>
                      <span className={remoteRemaining <= 60 ? "relay-countdown warn" : "relay-countdown"}>
                        {Math.floor(remoteRemaining / 60)}:{String(remoteRemaining % 60).padStart(2, "0")}
                      </span>
                      <button className="btn ghost small" onClick={copyRemoteCode}>
                        复制
                      </button>
                      <button className="btn ghost small" onClick={() => setRemotePairing(null)}>
                        隐藏
                      </button>
                    </div>
                    <textarea
                      className="relay-textarea"
                      rows={4}
                      readOnly
                      value={remotePairing.code}
                      onFocus={(event) => event.currentTarget.select()}
                    />
                  </div>
                )}
              </details>
            </section>

            <section className="relay-section">
              <h3>已配对设备</h3>
              <p className="relay-hint">
                这里只限制公网中继的访问，局域网直连不受影响。新设备默认不共享任何文件夹。
              </p>
              {deviceCount === 0 && <p className="relay-hint">还没有设备。</p>}
              <div className="relay-devices">
                {status?.devices.map((device) => {
                  const roots = status.roots;
                  const rootIds = new Set(roots.map((root) => root.id));
                  const granted = new Set(
                    device.grants
                      .map((grant) => grant.rootId)
                      .filter((rootId) => rootIds.has(rootId)),
                  );
                  const open = !collapsed.has(device.id);
                  return (
                    <div className="relay-device" key={device.id}>
                      <div className="relay-device-head">
                        <div>
                          <strong>{device.label}</strong>
                          <span className="relay-hint">{device.fingerprint.slice(0, 22)}…</span>
                        </div>
                        <div className="relay-actions">
                          <button className="btn ghost small" onClick={() => toggleExpanded(device.id)}>
                            {open ? "收起" : "文件夹"}
                          </button>
                          <button className="btn ghost small" onClick={() => revoke(device.id)}>
                            撤销
                          </button>
                        </div>
                      </div>
                      <div className="relay-hint">
                        已授权 {granted.size}/{roots.length} 个文件夹
                        {granted.size === 0 ? " · 当前看不到任何内容" : ""}
                      </div>
                      {open && (
                        <div className="relay-folder-list">
                          <div className="relay-actions">
                            <button
                              className="btn ghost small"
                              onClick={() => setRoots(device, roots.map((root) => root.id))}
                            >
                              全选
                            </button>
                            <button className="btn ghost small" onClick={() => setRoots(device, [])}>
                              清空
                            </button>
                          </div>
                          {roots.length === 0 && <span className="relay-hint">还没有添加任何文件夹。</span>}
                          {roots.map((root) => (
                            <label
                              className="relay-folder"
                              key={root.id}
                              title={root.displayName || root.path}
                            >
                              <input
                                type="checkbox"
                                checked={granted.has(root.id)}
                                onChange={() => toggleRoot(device, root.id)}
                              />
                              <span className="relay-folder-path">{root.path}</span>
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </section>

            <section className="relay-section">
              <h3>媒体密钥</h3>
              <div className="relay-key">
                <code>{status?.mediaKey || "…"}</code>
              </div>
              <div className="relay-hint">
                key_id <code>{(status?.keyId ?? 0).toString(16)}</code> · 已配对设备无需手动填写
              </div>
            </section>

            <section className="relay-section">
              <h3>维护</h3>
              <div className="relay-actions spread">
                <button className="btn ghost small" onClick={resetIdentity}>
                  重置 NAS 证书
                </button>
                <button className="btn ghost small danger" onClick={resetEncryption}>
                  重置端到端加密
                </button>
              </div>
              <p className="relay-hint">
                换服务器或重置 token 时，把新的 <code>podrelay://relay?…</code> 粘贴到上面的连接框即可。
              </p>
              <details className="relay-advanced">
                <summary>重新填写引导串</summary>
                <textarea
                  className="relay-textarea"
                  rows={3}
                  placeholder="podrelay://relay?v=2&a=…&p=…&t=…"
                  value={bootstrap}
                  onChange={(event) => setBootstrap(event.target.value)}
                />
                <div className="relay-actions">
                  <button className="btn ghost small" disabled={busy || !bootstrap.trim()} onClick={connect}>
                    应用
                  </button>
                </div>
              </details>
            </section>
          </>
        )}

        <footer className="relay-actions">
          <button className="btn ghost" onClick={onClose}>
            关闭
          </button>
        </footer>
      </div>
    </div>
  );
}
