import { useMemo, useState } from "react";
import type { MusicMode, RootKind } from "../api";
import { BookIcon, CloseIcon, HeadphonesIcon, MusicIcon, VideoIcon } from "./Icons";

interface AddRootDialogProps {
  path: string;
  busy: boolean;
  error: string | null;
  onCancel: () => void;
  onSubmit: (kind: RootKind, musicMode: MusicMode | null, displayName: string) => void;
}

const baseName = (path: string) => {
  const parts = path.split(/[\\/]/).filter(Boolean);
  return parts[parts.length - 1] ?? path;
};

export function AddRootDialog({ path, busy, error, onCancel, onSubmit }: AddRootDialogProps) {
  const [kind, setKind] = useState<RootKind>("music");
  const [mode, setMode] = useState<MusicMode>("tag");
  const [name, setName] = useState(() => baseName(path));
  const folderName = useMemo(() => baseName(path), [path]);

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onCancel()}>
      <div className="modal">
        <div className="modal-head">
          <h2>添加共享目录</h2>
          <button className="icon-btn" onClick={onCancel} title="关闭"><CloseIcon /></button>
        </div>

        <div className="modal-path" title={path}>{path}</div>

        <div className="field">
          <label>目录类型</label>
          <div className="type-grid">
            <button className={`type-card ${kind === "music" ? "active" : ""}`} onClick={() => setKind("music")}>
              <MusicIcon width={22} height={22} />
              <strong>音乐</strong>
              <span>按曲目浏览，支持 ID3 或艺人/专辑目录</span>
            </button>
            <button className={`type-card ${kind === "audiobook" ? "active" : ""}`} onClick={() => setKind("audiobook")}>
              <HeadphonesIcon width={22} height={22} />
              <strong>有声书</strong>
              <span>每个子文件夹作为一本有声书</span>
            </button>
            <button className={`type-card ${kind === "book" ? "active" : ""}`} onClick={() => setKind("book")}>
              <BookIcon width={22} height={22} />
              <strong>书籍</strong>
              <span>EPUB / MOBI / AZW3 / PDF / TXT</span>
            </button>
          </div>
        </div>

        {kind === "music" && (
          <div className="field">
            <label>音乐归类方式</label>
            <div className="mode-list">
              <button className={`mode-card ${mode === "tag" ? "active" : ""}`} onClick={() => setMode("tag")}>
                <strong>ID3 标签</strong>
                <span>艺人、专辑、曲号取自内嵌标签。适合整理过的音乐库。</span>
              </button>
              <button className={`mode-card ${mode === "folder" ? "active" : ""}`} onClick={() => setMode("folder")}>
                <strong>兼容模式（艺人 / 专辑）</strong>
                <span>外层文件夹作为艺人，内层文件夹作为专辑。适合按目录整理的旧库。</span>
              </button>
            </div>
          </div>
        )}

        <div className="field">
          <label>显示名称</label>
          <input
            className="text-input"
            value={name}
            placeholder={folderName}
            onChange={(event) => setName(event.target.value)}
            maxLength={64}
          />
        </div>

        {kind !== "book" && (
          <div className="notice">
            <VideoIcon width={15} height={15} />
            <span>视频文件只共享其中的音频：扫描时会识别视频容器，播放时由 ffmpeg 提取音轨（需要系统已安装 ffmpeg）。</span>
          </div>
        )}

        {error && <div className="form-error">{error}</div>}

        <div className="modal-actions">
          <button className="btn ghost" onClick={onCancel} disabled={busy}>取消</button>
          <button
            className="btn primary"
            disabled={busy || !name.trim()}
            onClick={() => onSubmit(kind, kind === "music" ? mode : null, name.trim() || folderName)}
          >
            {busy ? "正在添加…" : "添加并扫描"}
          </button>
        </div>
      </div>
    </div>
  );
}
