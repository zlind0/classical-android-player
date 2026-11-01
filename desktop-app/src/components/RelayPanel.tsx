import { useCallback, useEffect, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { api, formatBytes, type RelaySettings, type RelayStatus } from "../api";

interface RelayPanelProps {
  onClose: () => void;
  onToast: (message: string) => void;
}

const emptySettings: RelaySettings = {
  enabled: false,
  addr: "relay.example.com:21443",
  serverFingerprint: "",
  certPath: "",
  keyPath: "",
  readaheadFragments: 8,
  maxInflightReads: 4,
  snapshotPollSec: 60,
};

export function RelayPanel({ onClose, onToast }: RelayPanelProps) {
  const [settings, setSettings] = useState<RelaySettings>(emptySettings);
  const [status, setStatus] = useState<RelayStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const next = await api.relayStatus();
      setStatus(next);
      setSettings(next.settings);
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

  const patch = (value: Partial<RelaySettings>) => setSettings((current) => ({ ...current, ...value }));

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const next = await api.relayApplySettings(settings);
      setStatus(next);
      setSettings(next.settings);
      onToast("中继配置已保存");
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const importCert = async () => {
    setError(null);
    try {
      const cert = await open({
        multiple: false,
        title: "选择 NAS 客户端证书 (nas.crt)",
        filters: [{ name: "Certificate", extensions: ["crt", "pem"] }],
      });
      if (typeof cert !== "string") return;
      const key = await open({
        multiple: false,
        title: "选择 NAS 客户端私钥 (nas.key)",
        filters: [{ name: "Private key", extensions: ["key", "pem"] }],
      });
      if (typeof key !== "string") return;
      const next = await api.relayImportCert(cert, key);
      setStatus(next);
      setSettings(next.settings);
      onToast("证书已导入");
    } catch (err) {
      setError(String(err));
    }
  };

  const regenerate = async () => {
    if (!window.confirm("重新生成媒体密钥会让中继已缓存的分片失效，手机端需要重新填写新密钥。继续？")) {
      return;
    }
    setError(null);
    try {
      const next = await api.relayRegenerateKey();
      setStatus(next);
      setSettings(next.settings);
      onToast("已生成新的媒体密钥");
    } catch (err) {
      setError(String(err));
    }
  };

  const copyKey = async () => {
    if (!status?.mediaKey) return;
    try {
      await navigator.clipboard.writeText(status.mediaKey);
      onToast("媒体密钥已复制");
    } catch {
      onToast("复制失败，请手动选择文本");
    }
  };

  const agent = status?.agent;
  const usage = agent && agent.storeCap > 0 ? Math.min(1, agent.storeUsed / agent.storeCap) : 0;

  return (
    <div className="relay-overlay" onClick={onClose}>
      <div className="relay-card" onClick={(event) => event.stopPropagation()}>
        <header className="relay-head">
          <div>
            <h2>公网中继</h2>
            <p>PodRelay：把资料库通过公网服务器共享给手机，服务器只保存加密分片。</p>
          </div>
          <button className="icon-btn" onClick={onClose} title="关闭">
            ×
          </button>
        </header>

        {error && <div className="banner error">{error}</div>}

        <label className="relay-switch">
          <input
            type="checkbox"
            checked={settings.enabled}
            onChange={(event) => patch({ enabled: event.target.checked })}
          />
          <span>启用中继</span>
        </label>

        <div className="relay-grid">
          <label>
            中继地址
            <input
              value={settings.addr}
              placeholder="relay.example.com:21443"
              onChange={(event) => patch({ addr: event.target.value })}
            />
          </label>
          <label>
            服务器指纹
            <input
              value={settings.serverFingerprint}
              placeholder="sha256/…"
              onChange={(event) => patch({ serverFingerprint: event.target.value })}
            />
          </label>
          <label className="span-2">
            客户端证书
            <div className="relay-file">
              <input value={settings.certPath} readOnly placeholder="尚未导入" />
              <input value={settings.keyPath} readOnly placeholder="尚未导入私钥" />
              <button className="btn ghost small" onClick={importCert}>
                导入证书与私钥
              </button>
            </div>
          </label>
        </div>

        <section className="relay-section">
          <h3>媒体密钥</h3>
          <p className="relay-hint">
            这把钥匙只存在于 NAS 与手机端；中继服务器没有它。请把下面的内容填到手机「公网中继」设置里。
          </p>
          <div className="relay-key">
            <code>{status?.mediaKey || "…"}</code>
            <button className="btn ghost small" onClick={copyKey}>
              复制
            </button>
          </div>
          <div className="relay-hint">
            key_id：<code>{(status?.keyId ?? 0).toString(16)}</code>
            <button className="btn ghost small" onClick={regenerate}>
              重新生成
            </button>
          </div>
        </section>

        <section className="relay-section">
          <h3>运行状态</h3>
          {!settings.enabled && <p className="relay-hint">中继未启用。</p>}
          {settings.enabled && !agent && <p className="relay-hint">正在启动…</p>}
          {agent && (
            <>
              <div className="relay-status-row">
                <span className={agent.connected ? "dot ok" : "dot bad"} />
                {agent.connected ? "已连接中继" : "未连接"}
                <span className="relay-hint">
                  {agent.hostOnline ? "源设备在线" : "源设备离线"} · 快照版本 {agent.libVersion}
                </span>
              </div>
              <div className="relay-meter">
                <div style={{ width: `${Math.round(usage * 100)}%` }} />
              </div>
              <div className="relay-hint">
                缓存占用 {formatBytes(agent.storeUsed)} / {formatBytes(agent.storeCap)}
              </div>
              {agent.lastError && <div className="banner error">{agent.lastError}</div>}
            </>
          )}
        </section>

        <footer className="relay-actions">
          <button className="btn ghost" onClick={onClose}>
            关闭
          </button>
          <button className="btn primary" disabled={busy} onClick={save}>
            {busy ? "保存中…" : "保存并应用"}
          </button>
        </footer>
      </div>
    </div>
  );
}
