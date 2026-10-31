import { useState } from "react";
import type { LibraryStats, RootInfo, ScanProgress, ServerInfo } from "../api";
import { kindLabel, modeLabel } from "../api";
import { BookIcon, FolderIcon, HeadphonesIcon, MusicIcon, PlusIcon, RefreshIcon, ServerIcon, TrashIcon } from "./Icons";

interface SidebarProps {
  roots: RootInfo[];
  stats: LibraryStats | null;
  info: ServerInfo | null;
  selectedRootId: number | null;
  progress: Record<number, ScanProgress>;
  scanning: boolean;
  onSelectRoot: (id: number | null) => void;
  onAdd: () => void;
  onScan: (ids: number[]) => void;
  onRemove: (id: number) => void;
  onToggleRandom: (root: RootInfo) => void;
}

const kindIcon = {
  music: MusicIcon,
  audiobook: HeadphonesIcon,
  book: BookIcon,
};

export function Sidebar({
  roots,
  stats,
  info,
  selectedRootId,
  progress,
  scanning,
  onSelectRoot,
  onAdd,
  onScan,
  onRemove,
  onToggleRandom,
}: SidebarProps) {
  const [confirming, setConfirming] = useState<number | null>(null);

  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">A</div>
        <div className="brand-text">
          <strong>Aurora</strong>
          <span>桌面共享端</span>
        </div>
      </div>

      <div className="sidebar-section-head">
        <span>共享目录</span>
        <button className="icon-btn" title="添加共享目录" onClick={onAdd}>
          <PlusIcon />
        </button>
      </div>

      <div className="root-list">
        <button
          className={`root-item all ${selectedRootId === null ? "active" : ""}`}
          onClick={() => onSelectRoot(null)}
        >
          <span className="root-icon"><FolderIcon /></span>
          <span className="root-main">
            <span className="root-name">全部目录</span>
            <span className="root-meta">
              {stats ? `${stats.tracks} 曲 · ${stats.audiobooks} 书 · ${stats.books} 本` : "尚未扫描"}
            </span>
          </span>
        </button>

        {roots.map((root) => {
          const Icon = kindIcon[root.kind] ?? FolderIcon;
          const running = progress[root.id];
          const percent = running && running.total > 0 ? Math.round((running.scanned / running.total) * 100) : null;
          return (
            <div
              key={root.id}
              className={`root-item ${selectedRootId === root.id ? "active" : ""}`}
              role="button"
              tabIndex={0}
              onClick={() => onSelectRoot(root.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") onSelectRoot(root.id);
              }}
            >
              <span className={`root-icon kind-${root.kind}`}><Icon /></span>
              <span className="root-main">
                <span className="root-name" title={root.path}>{root.displayName}</span>
                <span className="root-meta">
                  {kindLabel[root.kind]}
                  {root.kind === "music" && root.musicMode ? ` · ${modeLabel[root.musicMode]}` : ""}
                  {root.kind === "book" && root.randomPick ? " · 随机选书" : ""}
                  {root.itemCount > 0 ? ` · ${root.itemCount}` : ""}
                </span>
                {percent !== null && (
                  <span className="root-progress">
                    <span style={{ width: `${percent}%` }} />
                  </span>
                )}
              </span>
              <span className="root-actions" onClick={(event) => event.stopPropagation()}>
                {root.kind === "book" && (
                  <button
                    className={`icon-btn small random-toggle ${root.randomPick ? "on" : ""}`}
                    title={root.randomPick ? "关闭随机选书" : "开启随机选书"}
                    onClick={() => onToggleRandom(root)}
                  >
                    随机
                  </button>
                )}
                <button
                  className="icon-btn small"
                  title="重新扫描"
                  disabled={scanning}
                  onClick={() => onScan([root.id])}
                >
                  <RefreshIcon />
                </button>
                {confirming === root.id ? (
                  <button
                    className="icon-btn small danger"
                    title="再次点击确认删除"
                    onClick={() => {
                      setConfirming(null);
                      onRemove(root.id);
                    }}
                  >
                    <TrashIcon />
                  </button>
                ) : (
                  <button
                    className="icon-btn small"
                    title="移除目录"
                    onClick={() => {
                      setConfirming(root.id);
                      window.setTimeout(() => setConfirming((current) => (current === root.id ? null : current)), 3000);
                    }}
                  >
                    <TrashIcon />
                  </button>
                )}
              </span>
            </div>
          );
        })}

        {roots.length === 0 && (
          <div className="empty-hint">
            还没有共享目录。点击右上角 <PlusIcon width={14} height={14} /> 添加音乐、有声书或书籍文件夹。
          </div>
        )}
      </div>

      <div className="sidebar-footer">
        <div className="server-line">
          <ServerIcon width={15} height={15} />
          <span>局域网服务端口</span>
          <strong>{info ? info.port : "…"}</strong>
        </div>
        <div className="server-line muted">
          <span>设备</span>
          <span className="mono">{info ? info.deviceName : "…"}</span>
        </div>
        <div className="server-line muted">
          <span>ID</span>
          <span className="mono">{info ? info.deviceId.slice(0, 8) : "…"}</span>
        </div>
        {stats && (
          <div className="server-line muted">
            <span>资料库版本</span>
            <span className="mono">{stats.libraryVersion}</span>
          </div>
        )}
      </div>
    </aside>
  );
}
