import { useMemo, useState } from "react";
import type { AudiobookDto } from "../api";
import { formatDuration } from "../api";
import type { PlayItem } from "../player";
import { Cover } from "./Cover";
import { PlayIcon, SearchIcon, VideoIcon } from "./Icons";

interface AudiobooksViewProps {
  books: AudiobookDto[];
  baseUrl: string;
  rootId: number | null;
  onPlay: (queue: PlayItem[], index: number) => void;
}

const toItem = (book: AudiobookDto, chapterIndex: number): PlayItem => {
  const chapter = book.chapters[chapterIndex];
  return {
    key: `${book.id}#${chapterIndex}`,
    title: chapter.title,
    subtitle: book.title,
    url: chapter.audioUrl,
    artwork: book.coverUrl,
  };
};

export function AudiobooksView({ books, baseUrl, rootId, onPlay }: AudiobooksViewProps) {
  const [query, setQuery] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);

  const filtered = useMemo(
    () => books.filter((book) => rootId === null || book.rootId === rootId),
    [books, rootId],
  );

  const matches = useMemo(() => {
    const search = query.trim().toLowerCase();
    if (!search) return filtered;
    return filtered.filter((book) =>
      [book.title, book.relPath].join(" ").toLowerCase().includes(search),
    );
  }, [filtered, query]);

  const active = matches.find((book) => book.id === openId) ?? null;

  if (filtered.length === 0) {
    return (
      <div className="empty-state">
        <h3>还没有有声书</h3>
        <p>添加一个有声书文件夹。每个子文件夹会被识别为一本书，其中按顺序排列音频或视频文件。</p>
      </div>
    );
  }

  if (active) {
    const queue = active.chapters.map((_, index) => toItem(active, index));
    return (
      <div className="detail-view">
        <button className="btn ghost small back" onClick={() => setOpenId(null)}>← 返回书目</button>
        <div className="detail-head">
          <Cover
            src={active.coverUrl ? `${baseUrl}${active.coverUrl}` : null}
            size={120}
            radius={14}
            alt={active.title}
          />
          <div className="detail-meta">
            <h2>{active.title}</h2>
            <p className="muted">
              {active.chapterCount} 章 · {formatDuration(active.durationSec)} · {active.relPath || "根目录"}
            </p>
            <div className="detail-actions">
              <button className="btn primary small" onClick={() => onPlay(queue, 0)} disabled={queue.length === 0}>
                <PlayIcon width={14} height={14} /> 从第一章播放
              </button>
            </div>
          </div>
        </div>

        <div className="chapter-list">
          {active.chapters.map((chapter, index) => (
            <div className="chapter-row" key={chapter.path} onDoubleClick={() => onPlay(queue, index)}>
              <button className="track-play" onClick={() => onPlay(queue, index)} title="播放这一章">
                <PlayIcon width={13} height={13} />
              </button>
              <span className="track-index">{chapter.trackNumber > 0 ? chapter.trackNumber : index + 1}</span>
              <span className="chapter-title" title={chapter.path}>
                {chapter.title}
                {chapter.fromVideo && (
                  <span className="video-badge"><VideoIcon width={12} height={12} /> 音频</span>
                )}
              </span>
              <span className="track-duration">{formatDuration(chapter.durationSec)}</span>
            </div>
          ))}
          {active.chapters.length === 0 && <div className="empty-hint">这本书里没有可播放的章节。</div>}
        </div>
      </div>
    );
  }

  return (
    <div className="audiobook-view">
      <div className="toolbar">
        <div className="search-box">
          <SearchIcon width={15} height={15} />
          <input
            value={query}
            placeholder="搜索有声书"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="toolbar-meta">{matches.length} 本</div>
      </div>

      <div className="book-grid">
        {matches.map((book) => (
          <button key={book.id} className="book-card" onClick={() => setOpenId(book.id)}>
            <Cover
              src={book.coverUrl ? `${baseUrl}${book.coverUrl}` : null}
              size={132}
              radius={12}
              alt={book.title}
              className="book-card-cover"
            />
            <span className="book-card-title" title={book.title}>{book.title}</span>
            <span className="book-card-meta">{book.chapterCount} 章 · {formatDuration(book.durationSec)}</span>
          </button>
        ))}
        {matches.length === 0 && <div className="empty-hint">没有匹配的有声书</div>}
      </div>
    </div>
  );
}
