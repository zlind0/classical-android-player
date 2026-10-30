import { useCallback, useEffect, useMemo, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import {
  api,
  kindLabel,
  onScanDone,
  onScanProgress,
  type AudiobookDto,
  type BookDto,
  type LibraryStats,
  type MusicLibrary,
  type MusicMode,
  type RootInfo,
  type RootKind,
  type ScanProgress,
  type ScanSummary,
  type ServerInfo,
  type Tab,
} from "./api";
import { AddRootDialog } from "./components/AddRootDialog";
import { AudiobooksView } from "./components/AudiobooksView";
import { BooksView } from "./components/BooksView";
import { HeadphonesIcon, MusicIcon, BookIcon, RefreshIcon } from "./components/Icons";
import { MusicView } from "./components/MusicView";
import { PlayerBar } from "./components/Player";
import { Sidebar } from "./components/Sidebar";
import { currentItem, type PlayItem, type PlayerState } from "./player";

const loadTab = (): Tab => {
  const stored = localStorage.getItem("aurora.tab");
  return stored === "audiobooks" || stored === "books" ? stored : "music";
};

const loadRoot = (): number | null => {
  const stored = localStorage.getItem("aurora.rootFilter");
  if (!stored) return null;
  const value = Number(stored);
  return Number.isFinite(value) ? value : null;
};

export default function App() {
  const [info, setInfo] = useState<ServerInfo | null>(null);
  const [roots, setRoots] = useState<RootInfo[]>([]);
  const [stats, setStats] = useState<LibraryStats | null>(null);
  const [library, setLibrary] = useState<MusicLibrary | null>(null);
  const [audiobooks, setAudiobooks] = useState<AudiobookDto[]>([]);
  const [books, setBooks] = useState<BookDto[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [tab, setTab] = useState<Tab>(loadTab);
  const [rootFilter, setRootFilter] = useState<number | null>(loadRoot);

  const [scanning, setScanning] = useState(false);
  const [progress, setProgress] = useState<Record<number, ScanProgress>>({});
  const [toast, setToast] = useState<string | null>(null);

  const [pendingPath, setPendingPath] = useState<string | null>(null);
  const [addBusy, setAddBusy] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);

  const [player, setPlayer] = useState<PlayerState | null>(null);
  const [playToken, setPlayToken] = useState(0);

  const showToast = useCallback((message: string) => setToast(message), []);

  const refresh = useCallback(async () => {
    try {
      const [nextRoots, nextStats, nextLibrary, nextAudiobooks, nextBooks] = await Promise.all([
        api.listRoots(),
        api.libraryStats(),
        api.musicLibrary(),
        api.listAudiobooks(),
        api.listBooks(),
      ]);
      setRoots(nextRoots);
      setStats(nextStats);
      setLibrary(nextLibrary);
      setAudiobooks(nextAudiobooks);
      setBooks(nextBooks);
      setError(null);
    } catch (err) {
      setError(String(err));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    api.getServerInfo().then(setInfo).catch((err) => setError(String(err)));
    refresh();
  }, [refresh]);

  useEffect(() => {
    localStorage.setItem("aurora.tab", tab);
  }, [tab]);
  useEffect(() => {
    localStorage.setItem("aurora.rootFilter", rootFilter === null ? "" : String(rootFilter));
  }, [rootFilter]);

  useEffect(() => {
    if (rootFilter !== null && loaded && !roots.some((root) => root.id === rootFilter)) {
      setRootFilter(null);
    }
  }, [roots, rootFilter, loaded]);

  useEffect(() => {
    let alive = true;
    const unlisteners: Array<() => void> = [];
    onScanProgress((value) => {
      if (!alive) return;
      setProgress((current) => ({ ...current, [value.rootId]: value }));
    }).then((unlisten) => {
      if (alive) unlisteners.push(unlisten);
      else unlisten();
    });
    onScanDone((summary) => {
      if (!alive) return;
      const parts = [`${summary.rootName}：新增 ${summary.added}`];
      if (summary.updated) parts.push(`更新 ${summary.updated}`);
      if (summary.removed) parts.push(`移除 ${summary.removed}`);
      if (summary.errors.length) parts.push(`错误 ${summary.errors.length}`);
      showToast(`${kindLabel[summary.kind]}扫描完成 · ${parts.join(" · ")}`);
    }).then((unlisten) => {
      if (alive) unlisteners.push(unlisten);
      else unlisten();
    });
    return () => {
      alive = false;
      unlisteners.forEach((unlisten) => unlisten());
    };
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 5000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const startScan = useCallback(
    async (ids: number[]) => {
      if (ids.length === 0 || scanning) return;
      setScanning(true);
      setError(null);
      try {
        const summaries: ScanSummary[] = await api.scanRoots(ids);
        const totalErrors = summaries.reduce((sum, summary) => sum + summary.errors.length, 0);
        if (totalErrors > 0) {
          setError(summaries.flatMap((summary) => summary.errors).slice(0, 5).join("\n"));
        }
        await refresh();
      } catch (err) {
        setError(String(err));
      } finally {
        setScanning(false);
        setProgress({});
      }
    },
    [refresh, scanning],
  );

  const pickFolder = async () => {
    setAddError(null);
    try {
      const selected = await open({
        directory: true,
        multiple: false,
        title: "选择要共享的文件夹",
      });
      if (typeof selected === "string") {
        setPendingPath(selected);
      }
    } catch (err) {
      setError(String(err));
    }
  };

  const submitRoot = async (kind: RootKind, mode: MusicMode | null, name: string) => {
    if (!pendingPath) return;
    setAddBusy(true);
    setAddError(null);
    try {
      const root = await api.addRoot(pendingPath, kind, mode, name);
      setPendingPath(null);
      await refresh();
      showToast(`已添加「${root.displayName}」，开始扫描…`);
      await startScan([root.id]);
    } catch (err) {
      setAddError(String(err));
    } finally {
      setAddBusy(false);
    }
  };

  const removeRoot = async (id: number) => {
    try {
      await api.removeRoot(id);
      if (rootFilter === id) setRootFilter(null);
      await refresh();
    } catch (err) {
      setError(String(err));
    }
  };

  const play = (queue: PlayItem[], index: number) => {
    setPlayer({ queue, index });
    setPlayToken((token) => token + 1);
  };

  const item = currentItem(player);

  const total = useMemo(() => {
    const values = Object.values(progress);
    return values.reduce(
      (acc, value) => ({ scanned: acc.scanned + value.scanned, total: acc.total + value.total }),
      { scanned: 0, total: 0 },
    );
  }, [progress]);

  const tabCounts = {
    music: stats?.tracks ?? 0,
    audiobooks: stats?.audiobooks ?? 0,
    books: stats?.books ?? 0,
  };

  return (
    <div className={`app ${item ? "with-player" : ""}`}>
      <Sidebar
        roots={roots}
        stats={stats}
        info={info}
        selectedRootId={rootFilter}
        progress={progress}
        scanning={scanning}
        onSelectRoot={setRootFilter}
        onAdd={pickFolder}
        onScan={(ids) => startScan(ids)}
        onRemove={removeRoot}
      />

      <main className="main">
        <header className="topbar">
          <nav className="tabs">
            <button className={tab === "music" ? "active" : ""} onClick={() => setTab("music")}>
              <MusicIcon width={16} height={16} />
              音乐
              <span className="tab-count">{tabCounts.music}</span>
            </button>
            <button className={tab === "audiobooks" ? "active" : ""} onClick={() => setTab("audiobooks")}>
              <HeadphonesIcon width={16} height={16} />
              有声书
              <span className="tab-count">{tabCounts.audiobooks}</span>
            </button>
            <button className={tab === "books" ? "active" : ""} onClick={() => setTab("books")}>
              <BookIcon width={16} height={16} />
              书籍
              <span className="tab-count">{tabCounts.books}</span>
            </button>
          </nav>

          <div className="topbar-right">
            {scanning && (
              <span className="scan-status">
                <span className="spinner" />
                扫描中 {total.total > 0 ? `${Math.min(total.scanned, total.total)} / ${total.total}` : "…"}
              </span>
            )}
            <button
              className="btn ghost small"
              disabled={scanning || roots.length === 0}
              onClick={() => startScan(roots.map((root) => root.id))}
            >
              <RefreshIcon width={15} height={15} /> 扫描全部
            </button>
          </div>
        </header>

        {error && (
          <div className="banner error" onClick={() => setError(null)}>
            <span>{error}</span>
            <span className="banner-close">×</span>
          </div>
        )}

        <div className="content">
          {!loaded ? (
            <div className="empty-state"><h3>正在加载资料库…</h3></div>
          ) : tab === "music" ? (
            library ? (
              <MusicView library={library} baseUrl={info?.baseUrl ?? ""} rootId={rootFilter} onPlay={play} />
            ) : null
          ) : tab === "audiobooks" ? (
            <AudiobooksView
              books={audiobooks}
              baseUrl={info?.baseUrl ?? ""}
              rootId={rootFilter}
              onPlay={play}
            />
          ) : (
            <BooksView books={books} baseUrl={info?.baseUrl ?? ""} rootId={rootFilter} />
          )}
        </div>

        {item && player && (
          <PlayerBar
            key={`${item.key}-${playToken}`}
            item={item}
            baseUrl={info?.baseUrl ?? ""}
            hasPrev={player.index > 0}
            hasNext={player.index < player.queue.length - 1}
            onPrev={() => setPlayer({ ...player, index: Math.max(0, player.index - 1) })}
            onNext={() =>
              setPlayer({ ...player, index: Math.min(player.queue.length - 1, player.index + 1) })
            }
          />
        )}
      </main>

      {pendingPath && (
        <AddRootDialog
          path={pendingPath}
          busy={addBusy}
          error={addError}
          onCancel={() => {
            if (!addBusy) {
              setPendingPath(null);
              setAddError(null);
            }
          }}
          onSubmit={submitRoot}
        />
      )}

      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}
