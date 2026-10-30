import { useEffect, useMemo, useState } from "react";
import type { MusicLibrary, TrackDto } from "../api";
import { formatDuration } from "../api";
import type { PlayItem } from "../player";
import { Cover } from "./Cover";
import { PlayIcon, SearchIcon, VideoIcon } from "./Icons";

interface MusicViewProps {
  library: MusicLibrary;
  baseUrl: string;
  rootId: number | null;
  onPlay: (queue: PlayItem[], index: number) => void;
}

type ViewMode = "tree" | "tracks";

const toItem = (track: TrackDto): PlayItem => ({
  key: `track-${track.id}`,
  title: track.title,
  subtitle: [track.artist || "未知艺人", track.album].filter(Boolean).join(" · "),
  url: `/media/music?id=${encodeURIComponent(track.id)}`,
  artwork: track.hasArt ? `/media/songart?id=${encodeURIComponent(track.id)}` : null,
});

export function MusicView({ library, baseUrl, rootId, onPlay }: MusicViewProps) {
  const [mode, setMode] = useState<ViewMode>(() => (localStorage.getItem("aurora.musicView") as ViewMode) || "tree");
  const [query, setQuery] = useState("");
  const [selectedArtistId, setSelectedArtistId] = useState<string | null>(
    () => localStorage.getItem("aurora.selectedArtist"),
  );
  const [collapsedAlbums, setCollapsedAlbums] = useState<Set<string>>(new Set());

  useEffect(() => {
    localStorage.setItem("aurora.musicView", mode);
  }, [mode]);
  useEffect(() => {
    if (selectedArtistId) localStorage.setItem("aurora.selectedArtist", selectedArtistId);
  }, [selectedArtistId]);

  const artists = useMemo(() => {
    return library.artists
      .map((artist) => {
        const albums = artist.albums
          .map((album) => ({
            ...album,
            tracks: album.tracks.filter((track) => rootId === null || track.rootId === rootId),
          }))
          .filter((album) => album.tracks.length > 0);
        return {
          ...artist,
          albums,
          trackCount: albums.reduce((total, album) => total + album.tracks.length, 0),
        };
      })
      .filter((artist) => artist.albums.length > 0);
  }, [library, rootId]);

  const allTracks = useMemo(
    () => artists.flatMap((artist) => artist.albums.flatMap((album) => album.tracks)),
    [artists],
  );

  const activeArtist = useMemo(() => {
    if (artists.length === 0) return null;
    return artists.find((artist) => artist.id === selectedArtistId) ?? artists[0];
  }, [artists, selectedArtistId]);

  useEffect(() => {
    if (activeArtist && activeArtist.id !== selectedArtistId) {
      setSelectedArtistId(activeArtist.id);
    }
  }, [activeArtist, selectedArtistId]);

  const search = query.trim().toLowerCase();
  const matches = useMemo(() => {
    if (!search) return [];
    return allTracks.filter((track) =>
      [track.title, track.artist, track.album, track.fileName]
        .join(" ")
        .toLowerCase()
        .includes(search),
    );
  }, [allTracks, search]);

  if (artists.length === 0) {
    return (
      <div className="empty-state">
        <h3>还没有音乐</h3>
        <p>添加一个音乐文件夹并扫描后，这里会按艺人、专辑展示曲目。</p>
      </div>
    );
  }

  const playAlbum = (tracks: TrackDto[], index = 0) => {
    onPlay(tracks.map(toItem), index);
  };

  const toggleAlbum = (albumId: string) => {
    setCollapsedAlbums((current) => {
      const next = new Set(current);
      if (next.has(albumId)) next.delete(albumId);
      else next.add(albumId);
      return next;
    });
  };

  return (
    <div className="music-view">
      <div className="toolbar">
        <div className="segmented">
          <button className={mode === "tree" ? "active" : ""} onClick={() => setMode("tree")}>艺人 / 专辑</button>
          <button className={mode === "tracks" ? "active" : ""} onClick={() => setMode("tracks")}>全部曲目</button>
        </div>
        <div className="search-box">
          <SearchIcon width={15} height={15} />
          <input
            value={query}
            placeholder="搜索曲目、艺人或专辑"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="toolbar-meta">
          {library.totalTracks} 首
          {library.pathOrder && <span className="pill" title="存在兼容模式目录，专辑内按文件路径排序">路径排序</span>}
        </div>
      </div>

      {search ? (
        <div className="pane">
          <div className="pane-head">
            <span>搜索结果</span>
            <span className="muted">{matches.length} 首</span>
          </div>
          <TrackTable tracks={matches} onPlay={playAlbum} />
        </div>
      ) : mode === "tracks" ? (
        <div className="pane">
          <div className="pane-head">
            <span>全部曲目</span>
            <span className="muted">{allTracks.length} 首</span>
          </div>
          <TrackTable tracks={allTracks} onPlay={playAlbum} />
        </div>
      ) : (
        <div className="split">
          <div className="artist-pane">
            <div className="pane-head small">
              <span>艺人</span>
              <span className="muted">{artists.length}</span>
            </div>
            <div className="artist-list">
              {artists.map((artist) => (
                <button
                  key={artist.id}
                  className={`artist-row ${activeArtist?.id === artist.id ? "active" : ""}`}
                  onClick={() => setSelectedArtistId(artist.id)}
                >
                  <span className="artist-avatar">{artist.name.slice(0, 1).toUpperCase()}</span>
                  <span className="artist-name" title={artist.name}>{artist.name}</span>
                  <span className="artist-count">{artist.trackCount}</span>
                </button>
              ))}
            </div>
          </div>

          <div className="album-pane">
            {activeArtist && (
              <>
                <div className="artist-header">
                  <div className="artist-header-text">
                    <h2>{activeArtist.name}</h2>
                    <p>{activeArtist.albums.length} 张专辑 · {activeArtist.trackCount} 首曲目</p>
                  </div>
                  <button
                    className="btn primary small"
                    onClick={() => {
                      const tracks = activeArtist.albums.flatMap((album) => album.tracks);
                      playAlbum(tracks);
                    }}
                  >
                    <PlayIcon width={14} height={14} /> 播放全部
                  </button>
                </div>

                {activeArtist.albums.map((album) => {
                  const collapsed = collapsedAlbums.has(album.id);
                  const coverUrl = album.coverTrackId
                    ? `${baseUrl}/media/songart?id=${encodeURIComponent(album.coverTrackId)}`
                    : null;
                  return (
                    <section className="album-block" key={album.id}>
                      <header className="album-head">
                        <Cover src={coverUrl} size={52} radius={10} alt={album.title} />
                        <div className="album-title">
                          <button className="link-title" onClick={() => toggleAlbum(album.id)}>
                            {album.title}
                          </button>
                          <span className="muted">
                            {album.tracks.length} 首 · {formatDuration(album.durationSec)}
                          </span>
                        </div>
                        <button
                          className="icon-btn"
                          title="播放这张专辑"
                          onClick={() => playAlbum(album.tracks)}
                        >
                          <PlayIcon width={16} height={16} />
                        </button>
                      </header>
                      {!collapsed && (
                        <TrackTable tracks={album.tracks} onPlay={(tracks, index) => playAlbum(tracks, index)} compact />
                      )}
                    </section>
                  );
                })}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

interface TrackTableProps {
  tracks: TrackDto[];
  onPlay: (tracks: TrackDto[], index: number) => void;
  compact?: boolean;
}

function TrackTable({ tracks, onPlay, compact = false }: TrackTableProps) {
  if (tracks.length === 0) {
    return <div className="empty-hint">没有匹配的曲目</div>;
  }
  return (
    <div className={`track-table ${compact ? "compact" : ""}`}>
      {tracks.map((track, index) => (
        <div className="track-row" key={track.id} onDoubleClick={() => onPlay(tracks, index)}>
          <button className="track-play" title="播放" onClick={() => onPlay(tracks, index)}>
            <PlayIcon width={13} height={13} />
          </button>
          <span className="track-index">{track.trackNumber > 0 ? track.trackNumber : index + 1}</span>
          <span className="track-title" title={track.title}>
            {track.title}
            {track.fromVideo && (
              <span className="video-badge" title="来自视频文件，仅共享音频">
                <VideoIcon width={12} height={12} /> 音频
              </span>
            )}
          </span>
          {!compact && <span className="track-artist">{track.artist || "未知艺人"}</span>}
          {!compact && <span className="track-album">{track.album}</span>}
          <span className="track-duration">{formatDuration(track.durationSec)}</span>
        </div>
      ))}
    </div>
  );
}
